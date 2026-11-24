package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.PrivateChatManager
import com.bitchat.android.ui.PrivateMessageOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class NostrDirectMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val privateChatManager: PrivateChatManager,
    private val updateDeliveryStatus: (String, DeliveryStatus) -> Unit,
    private val scope: CoroutineScope,
    private val repo: GeohashRepository,
    private val dataManager: com.bitchat.android.ui.DataManager,
    private val seenStoreProvider: () -> SeenMessageStore = {
        SeenMessageStore.getInstance(application)
    }
) {
    companion object { private const val TAG = "NostrDirectMessageHandler" }

    private val seenStore by lazy(seenStoreProvider)

    // Simple event deduplication
    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

    private fun dedupe(id: String): Boolean {
        if (seen.contains(id)) return true
        seen.add(id)
        processedIds.addLast(id)
        if (processedIds.size > max) {
            val old = processedIds.removeFirst()
            seen.remove(old)
        }
        return false
    }

    fun onGiftWrap(giftWrap: NostrEvent, geohash: String, identity: NostrIdentity) {
        scope.launch {
            try {
                if (dedupe(giftWrap.id)) return@launch

                val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
                if (messageAge > 173700) return@launch // 48 hours + 15 mins

                val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
                if (decryptResult == null) {
                    Log.w(TAG, "Failed to decrypt Nostr message")
                    return@launch
                }

                val (content, rawSenderPubkey, rumorTimestamp) = decryptResult
                val senderPubkey = rawSenderPubkey.lowercase()

                // If sender is blocked for geohash contexts, drop any events from this pubkey
                // Applies to both geohash DMs (geohash != "") and account DMs (geohash == "")
                if (dataManager.isGeohashUserBlocked(senderPubkey)) return@launch
                // Friend-request acceptance: a plain rumor, not a bitchat1: packet.
                // It arrives on the main identity (the request was sent to our real npub).
                if (content.startsWith(FriendRequestProtocol.ACCEPT_PREFIX)) {
                    handleFriendAccept(content, senderPubkey)
                    return@launch
                }
                // Friend request sent to our main npub instead of the one-time
                // invite code: the sender pasted our QR code, not the invite
                // code. Take it into New Friends the same way — but it did not
                // use the invite, so it must not consume it.
                if (content.startsWith(FriendRequestProtocol.REQUEST_PREFIX)) {
                    handleFriendRequestAtMainIdentity(content, senderPubkey)
                    return@launch
                }
                if (!content.startsWith("bitchat1:")) return@launch

                val base64Content = content.removePrefix("bitchat1:")
                val packetData = base64URLDecode(base64Content) ?: return@launch
                val packet = BitchatPacket.fromBinaryData(packetData) ?: return@launch

                if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) return@launch

                val noisePayload = NoisePayload.decode(packet.payload) ?: return@launch
                val messageTimestamp = Date(rumorTimestamp * 1000L)
                val convKey = "nostr_${senderPubkey.take(16)}"
                repo.putNostrKeyMapping(convKey, senderPubkey)
                com.bitchat.android.nostr.GeohashAliasRegistry.put(convKey, senderPubkey)
                if (geohash.isNotEmpty()) {
                    // Remember which geohash this conversation belongs to so we can subscribe on-demand
                    repo.setConversationGeohash(convKey, geohash)
                    GeohashConversationRegistry.set(convKey, geohash)
                }

                // Ensure sender appears in geohash people list even if they haven't posted publicly yet
                if (geohash.isNotEmpty()) {
                    // Cache a best-effort nickname and mark as participant
                    val cached = repo.getCachedNickname(senderPubkey)
                    if (cached == null) {
                        val base = repo.displayNameForNostrPubkeyUI(senderPubkey).substringBefore("#")
                        repo.cacheNickname(senderPubkey, base)
                    }
                    repo.updateParticipant(geohash, senderPubkey, messageTimestamp)
                }

                val senderNickname = repo.displayNameForNostrPubkeyUI(senderPubkey)
                val conversationID = ContactDirectory.canonicalConversationId(convKey)

                processNoisePayload(noisePayload, conversationID, senderNickname, messageTimestamp, senderPubkey, identity)

            } catch (e: Exception) {
                Log.e(TAG, "onGiftWrap error: ${e.message}")
            }
        }
    }

    /**
     * Gift wrap addressed to a live one-time invite code, decrypted with the
     * invite identity. Only a friend request is ever valid here: anything
     * else (including a plain DM) is dropped, so the invite npub can never
     * become a DM channel.
     */
    fun onInviteGiftWrap(giftWrap: NostrEvent, identity: NostrIdentity) {
        scope.launch {
            try {
                if (dedupe(giftWrap.id)) return@launch

                val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
                if (messageAge > 173700) return@launch // 48 hours + 15 mins, same as DMs

                val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
                if (decryptResult == null) return@launch

                val (content, rawSenderPubkey, _) = decryptResult
                val senderPubkey = rawSenderPubkey.lowercase()

                if (dataManager.isGeohashUserBlocked(senderPubkey)) return@launch

                val payload = FriendRequestProtocol.decodeRequest(content) ?: return@launch
                // The rumor's sender must match the request's claimed identity.
                if (payload.fromPubkeyHex.lowercase() != senderPubkey) return@launch
                if (payload.fromNoiseKeyHex.isBlank()) return@launch

                recordIncomingRequest(payload)
            } catch (e: Exception) {
                Log.e(TAG, "onInviteGiftWrap error: ${e.message}")
            }
        }
    }

    private fun recordIncomingRequest(
        payload: FriendRequestPayload,
        consumeInvite: Boolean = true
    ) {
        val current = AppStateStore.friendRequests.value
        // One pending row per person: a re-sent request replaces the old one.
        val withoutSameSender = current.filterNot {
            it.direction == FriendRequestDirection.INCOMING &&
                it.remotePubkeyHex.equals(payload.fromPubkeyHex, ignoreCase = true) &&
                it.status == FriendRequestStatus.PENDING
        }
        val request = FriendRequest(
            requestId = payload.requestId,
            remotePubkeyHex = payload.fromPubkeyHex.lowercase(),
            remoteNickname = payload.fromNickname,
            remoteNoiseKeyHex = payload.fromNoiseKeyHex.lowercase(),
            greeting = payload.greeting,
            createdAtMs = payload.createdAtMs,
            direction = FriendRequestDirection.INCOMING,
            status = FriendRequestStatus.PENDING
        )
        val updated = withoutSameSender + request
        AppStateStore.setFriendRequests(updated)
        dataManager.saveFriendRequests(updated)
        // Single-use: the invite dies the moment its first valid request lands.
        // A request that arrived at the main npub did not use the invite,
        // so it must not kill it.
        if (consumeInvite) {
            InviteCodeManager.consumeInvite(application.applicationContext)
            NostrBackgroundRuntime.refreshInviteSubscription()
        }
        Log.d(TAG, "Recorded friend request from ${payload.fromNickname} (${payload.fromPubkeyHex.take(16)}...)")
    }

    /**
     * A friend request that arrived at the main identity, i.e. the sender
     * used our QR code instead of the one-time invite code. Same checks as
     * the invite path ([onInviteGiftWrap]) — rumor sender must match the
     * request's claimed identity, Noise key must be present — then the same
     * New Friends row, without consuming the live invite.
     */
    private fun handleFriendRequestAtMainIdentity(content: String, senderPubkey: String) {
        val payload = FriendRequestProtocol.decodeRequest(content) ?: return
        if (payload.fromPubkeyHex.lowercase() != senderPubkey) return
        if (payload.fromNoiseKeyHex.isBlank()) return
        recordIncomingRequest(payload, consumeInvite = false)
    }

    private suspend fun handleFriendAccept(content: String, senderPubkey: String) {
        val payload = FriendRequestProtocol.decodeAccept(content) ?: return
        if (payload.fromPubkeyHex.lowercase() != senderPubkey) return
        val noiseKeyBytes = ContactIdentityResolver.bytesFromHex(payload.fromNoiseKeyHex) ?: return

        val current = AppStateStore.friendRequests.value
        // Match by requestId only: the outgoing row stores the invite npub,
        // the accept carries the accepter's real npub, so they differ.
        val match = current.firstOrNull {
            it.direction == FriendRequestDirection.OUTGOING &&
                it.requestId == payload.requestId &&
                it.status == FriendRequestStatus.PENDING
        } ?: return

        val updated = current.map {
            if (it.requestId == match.requestId) it.copy(
                status = FriendRequestStatus.ACCEPTED,
                remotePubkeyHex = payload.fromPubkeyHex.lowercase(),
                remoteNickname = payload.fromNickname,
                remoteNoiseKeyHex = payload.fromNoiseKeyHex.lowercase()
            ) else it
        }
        AppStateStore.setFriendRequests(updated)
        dataManager.saveFriendRequests(updated)

        // The two sides are now mutual favorites, so the ordinary Nostr DM
        // path (which requires mutual-favorite status) works from here on.
        FavoritesPersistenceService.shared.updateFavoriteStatus(noiseKeyBytes, payload.fromNickname, true)
        FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKeyBytes, true)
        FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKeyBytes, payload.fromPubkeyHex.lowercase())

        val conversationID = ContactDirectory.canonicalConversationId(
            ContactIdentityResolver.contactConversationIdForNoiseKey(noiseKeyBytes)
        )
        val displayName = payload.fromNickname.takeIf { it.isNotBlank() } ?: "Unknown"
        val systemMessage = BitchatMessage(
            sender = "system",
            content = "$displayName accepted. You can chat now.",
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            senderPeerID = conversationID
        )
        withContext(Dispatchers.Main) {
            privateChatManager.handleIncomingPrivateMessageDurably(
                message = systemMessage,
                suppressUnread = true,
                origin = PrivateMessageOrigin.NOSTR
            )
        }
    }

    private suspend fun processNoisePayload(
        payload: NoisePayload,
        conversationID: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String,
        recipientIdentity: NostrIdentity
    ) {
        when (payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessagePacket.decode(payload.data) ?: return
                val existingMessages = state.getPrivateChatsValue()[conversationID] ?: emptyList()
                if (existingMessages.any { it.id == pm.messageID }) return

                val favoriteControl = FavoriteControlMessage.parse(pm.content)
                if (favoriteControl != null) {
                    val admitted = handleFavoriteControl(
                        favoriteControl,
                        conversationID,
                        senderNickname,
                        timestamp,
                        senderPubkey
                    )
                    if (!admitted) return
                    if (!seenStore.hasDelivered(pm.messageID)) {
                        val nostrTransport = NostrTransport.getInstance(application)
                        nostrTransport.sendDeliveryAckGeohash(pm.messageID, senderPubkey, recipientIdentity)
                        seenStore.markDelivered(pm.messageID)
                    }
                    return
                }

                val message = BitchatMessage(
                    id = pm.messageID,
                    sender = senderNickname,
                    content = pm.content,
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = state.getNicknameValue(),
                    senderPeerID = conversationID,
                    senderNostrPubkey = senderPubkey,
                    deliveryStatus = DeliveryStatus.Delivered(to = state.getNicknameValue() ?: "Unknown", at = Date())
                )

                val isViewing = state.getSelectedPrivateChatPeerValue() == conversationID
                val suppressUnread = seenStore.hasBeenReadLocally(pm.messageID)

                val admitted = withContext(Dispatchers.Main) {
                    privateChatManager.handleIncomingPrivateMessageDurably(
                        message = message,
                        suppressUnread = suppressUnread,
                        origin = PrivateMessageOrigin.NOSTR
                    )
                }
                if (!admitted) return

                if (!seenStore.hasDelivered(pm.messageID)) {
                    val nostrTransport = NostrTransport.getInstance(application)
                    nostrTransport.sendDeliveryAckGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    seenStore.markDelivered(pm.messageID)
                }

                if (isViewing && !suppressUnread) {
                    val nostrTransport = NostrTransport.getInstance(application)
                    nostrTransport.sendReadReceiptGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    seenStore.markReadLocally(pm.messageID)
                    seenStore.markReadReceiptSent(pm.messageID)
                }
            }
            NoisePayloadType.DELIVERED -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    updateDeliveryStatus(
                        messageId,
                        DeliveryStatus.Delivered(conversationID, Date())
                    )
                }
            }
            NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    updateDeliveryStatus(
                        messageId,
                        DeliveryStatus.Read(conversationID, Date())
                    )
                }
            }
            NoisePayloadType.FILE_TRANSFER -> {
                // Properly handle encrypted file transfer
                val file = BitchatFilePacket.decode(payload.data)
                if (file != null) {
                    val uniqueMsgId = java.util.UUID.randomUUID().toString().uppercase()
                    val savedPath = com.bitchat.android.features.file.FileUtils.saveIncomingFile(application, file)
                    val message = BitchatMessage(
                        id = uniqueMsgId,
                        sender = senderNickname,
                        content = savedPath,
                        type = com.bitchat.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                        timestamp = timestamp,
                        isRelay = false,
                        isPrivate = true,
                        recipientNickname = state.getNicknameValue(),
                        senderPeerID = conversationID,
                        senderNostrPubkey = senderPubkey
                    )
                    Log.d(TAG, "📄 Saved Nostr encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                    val admitted = withContext(Dispatchers.Main) {
                        privateChatManager.handleIncomingPrivateMessageDurably(
                            message = message,
                            suppressUnread = false,
                            origin = PrivateMessageOrigin.NOSTR
                        )
                    }
                    if (!admitted) {
                        com.bitchat.android.features.file.FileUtils.deleteStoredMediaPaths(
                            application,
                            listOf(savedPath)
                        )
                    }
                } else {
                    Log.w(TAG, "Failed to decode Nostr file transfer from $conversationID")
                }
            }
            // Profile sync (lazy avatar sync): only for peers we know. The
            // sender's Nostr pubkey must resolve to a mesh peer ID (a
            // favorite) — conversationID here is a `nostr_<hex>` key, not the
            // peer ID, and strangers resolve to null and are ignored.
            // Background, low priority; malformed payloads are dropped.
            NoisePayloadType.PROFILE_REQUEST -> {
                val peerID = com.bitchat.android.favorites.FavoritesPersistenceService.shared
                    .findPeerIDForNostrPubkey(senderPubkey) ?: return
                val cachedVersion =
                    com.bitchat.android.model.ProfileSync.decodeRequest(payload.data)
                        ?: return
                val profileSync = com.bitchat.android.services.ProfileSyncManager.getInstance(application)
                profileSync.replyOverNostr(peerID, cachedVersion)
            }

            NoisePayloadType.PROFILE_ANNOUNCE -> {
                val peerID = com.bitchat.android.favorites.FavoritesPersistenceService.shared
                    .findPeerIDForNostrPubkey(senderPubkey) ?: return
                val announce =
                    com.bitchat.android.model.ProfileSync.decodeAnnounce(payload.data)
                        ?: return
                val profileSync = com.bitchat.android.services.ProfileSyncManager.getInstance(application)
                profileSync.onProfileAnnounce(peerID, announce)
            }

            NoisePayloadType.PROFILE_RESPONSE -> {
                val peerID = com.bitchat.android.favorites.FavoritesPersistenceService.shared
                    .findPeerIDForNostrPubkey(senderPubkey) ?: return
                val profileSync = com.bitchat.android.services.ProfileSyncManager.getInstance(application)
                profileSync.onProfileResponse(peerID, payload.data)
            }

            NoisePayloadType.PROFILE_RESPONSE_CHUNK -> {
                val peerID = com.bitchat.android.favorites.FavoritesPersistenceService.shared
                    .findPeerIDForNostrPubkey(senderPubkey) ?: return
                val profileSync = com.bitchat.android.services.ProfileSyncManager.getInstance(application)
                profileSync.onProfileChunk(peerID, payload.data)
            }

            NoisePayloadType.VERIFY_CHALLENGE,
            NoisePayloadType.VERIFY_RESPONSE,
            NoisePayloadType.VOICE_FRAME,
            NoisePayloadType.PEER_STATE -> Unit // Peer state is bound to a live mesh Noise generation.
        }
    }

    private suspend fun handleFavoriteControl(
        control: FavoriteControlMessage,
        conversationID: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String
    ): Boolean {
        return try {
            val senderNpub = control.npub ?: ContactIdentityResolver.npubFromHex(senderPubkey)
            val noiseKey = senderNpub?.let { FavoritesPersistenceService.shared.findNoiseKey(it) }
                ?: FavoritesPersistenceService.shared.findNoiseKey(senderPubkey)

            if (noiseKey == null) {
                Log.w(TAG, "Favorite notification from Nostr sender without known Noise key: ${senderPubkey.take(16)}...")
                return false
            }

            FavoritesPersistenceService.shared.updatePeerFavoritedUs(noiseKey, control.isFavorite)
            senderNpub?.let { FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKey, it) }
            val targetConversationID = ContactDirectory.canonicalConversationId(conversationID)

            val relationship = FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
            val displayName = relationship
                ?.peerNickname
                ?.takeUnless { it.equals("Unknown", ignoreCase = true) }
                ?: senderNickname
            val guidance = if (control.isFavorite) {
                if (relationship?.isFavorite == true) {
                    " - mutual! You can continue DMs via Nostr when out of mesh."
                } else {
                    " - favorite back to continue DMs later."
                }
            } else {
                ". DMs over Nostr will pause unless you both favorite again."
            }
            val action = if (control.isFavorite) "favorited" else "unfavorited"
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "$displayName $action you$guidance",
                timestamp = timestamp,
                isRelay = false,
                isPrivate = true,
                senderPeerID = targetConversationID
            )

            withContext(Dispatchers.Main) {
                privateChatManager.handleIncomingPrivateMessageDurably(
                    message = systemMessage,
                    suppressUnread = true,
                    origin = PrivateMessageOrigin.NOSTR
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle Nostr favorite notification: ${e.message}")
            false
        }
    }

    private fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+")
                .replace("_", "/")
                .let { str ->
                    val padding = (4 - str.length % 4) % 4
                    str + "=".repeat(padding)
                }
            android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
    }
}
