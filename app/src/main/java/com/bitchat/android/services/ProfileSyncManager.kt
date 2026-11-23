package com.bitchat.android.services

import android.content.Context
import android.util.Log
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.ProfileSync
import com.bitchat.android.nostr.NostrEmbeddedBitChat
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Callback surface the mesh layer ([com.bitchat.android.mesh.MessageHandler])
 * uses to talk to profile sync. The manager owns the decisions (is this
 * advertisement newer? what do we reply?); the mesh layer owns the BLE send.
 */
interface ProfileSyncHandler {
    /** Request bytes to send when the advertised profile is newer than cache, else null. */
    fun requestBytesForAdvertised(peerID: String, version: Long, photoHashHex: String?): ByteArray?

    /**
     * Reply to an incoming PROFILE_REQUEST: (PROFILE_RESPONSE, bytes) when our
     * profile is newer than [cachedVersion], (PROFILE_ANNOUNCE, bytes) otherwise.
     * Null when no reply can be built.
     */
    fun replyForRequest(peerID: String, cachedVersion: Long): Pair<NoisePayloadType, ByteArray>?

    /** A PROFILE_ANNOUNCE payload arrived (BLE or Nostr). */
    fun onProfileAnnounce(peerID: String, announce: ProfileSync.ProfileAnnounce)

    /** A whole PROFILE_RESPONSE payload arrived (BLE path). */
    fun onProfileResponse(peerID: String, responseBytes: ByteArray)

    /** One PROFILE_RESPONSE_CHUNK arrived (Nostr path). */
    fun onProfileChunk(peerID: String, chunkBytes: ByteArray)

    /**
     * Ask a peer for its profile over Nostr (used when no BLE Noise session
     * exists with the peer). No-op when the cache is current or a fetch for
     * this version is already in flight.
     */
    fun fetchOverNostr(peerID: String, version: Long, photoHashHex: String?)
}

/**
 * Background profile sync (WeChat-style lazy avatar sync, serverless).
 *
 * WeChat stores the avatar on its server; a friend's client fetches it lazily
 * and caches it locally, showing the cached copy until a re-fetch happens.
 * Linkz has no server, so the peer itself is the source of truth:
 *
 * - Every profile change (nickname or photo) bumps a local version counter
 *   and recomputes the photo hash ([DataManager]).
 * - The (version, photo hash) pair rides the existing announcement paths:
 *   BLE ANNOUNCE TLVs (0x06/0x07) and a PROFILE_ANNOUNCE push to favorite
 *   peers over Nostr when the profile changes.
 * - When B sees a newer version than its cache, it fetches the full profile
 *   in the BACKGROUND at low priority — over BLE as one Noise-encrypted
 *   packet (the transport fragments it), over Nostr as chunked DMs — then
 *   verifies the SHA-256, caches it per peer, and notifies the UI.
 * - Nothing here ever blocks or delays a chat message. A failed fetch just
 *   waits for the next advertisement (announces are periodic) or the next
 *   time the chat is opened.
 *
 * Only nickname + photo sync. The other profile fields (Gender/Region/
 * Mobile/What's Up) stay local-only and are never transmitted.
 */
class ProfileSyncManager private constructor(
    private val appContext: Context
) : ProfileSyncHandler {

    companion object {
        private const val TAG = "ProfileSync"

        @Volatile
        private var INSTANCE: ProfileSyncManager? = null

        fun getInstance(context: Context): ProfileSyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ProfileSyncManager(context.applicationContext).also { INSTANCE = it }
            }
        }

        /** Same version re-requests are suppressed for this long (bounds any retry loop). */
        private const val REQUEST_COOLDOWN_MS = 10 * 60 * 1000L

        /** Pause between Nostr response chunks, to avoid relay rate limits. */
        private const val NOSTR_CHUNK_DELAY_MS = 100L
    }

    private val dataManager = DataManager(appContext)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val reassembly = ProfileSync.ProfileReassembly()

    /** peerID -> (version, requestedAtMs) for in-flight / recently requested fetches. */
    private val inFlight = ConcurrentHashMap<String, Pair<Long, Long>>()

    /** Tick bumped whenever a peer's cached profile changes, so the UI reloads avatars. */
    private val _profileUpdated = MutableStateFlow(0L)
    val profileUpdated: StateFlow<Long> = _profileUpdated.asStateFlow()

    // MARK: - Own profile

    fun ownVersion(): Long = dataManager.getProfileVersion()

    fun ownPhotoHashHex(): String? = dataManager.getProfilePhotoHashHex()

    fun ownNickname(): String = dataManager.loadNickname()

    fun ownAnnounceBytes(): ByteArray? =
        ProfileSync.encodeAnnounce(ownVersion(), ownPhotoHashHex(), ownNickname())

    /** Full RESPONSE bytes for our current profile, or null when it cannot be encoded. */
    fun ownResponseBytes(): ByteArray? {
        val photoBytes = dataManager.getProfilePhotoBytes() ?: ByteArray(0)
        return ProfileSync.encodeResponse(ownVersion(), ownPhotoHashHex(), ownNickname(), photoBytes)
    }

    // MARK: - Staleness

    private fun cachedRecord(peerID: String) = dataManager.loadPeerProfile(peerID)

    private fun isStale(peerID: String, version: Long, photoHashHex: String?): Boolean {
        val cached = cachedRecord(peerID)
        return ProfileSync.isNewerThan(cached?.version, cached?.photoHashHex, version, photoHashHex)
    }

    /** True when this (peer, version) was requested recently — suppresses duplicate fetches. */
    private fun recentlyRequested(peerID: String, version: Long): Boolean {
        val (requestedVersion, atMs) = inFlight[peerID] ?: return false
        if (requestedVersion != version) return false
        return System.currentTimeMillis() - atMs < REQUEST_COOLDOWN_MS
    }

    private fun markRequested(peerID: String, version: Long) {
        inFlight[peerID] = version to System.currentTimeMillis()
    }

    private fun clearRequested(peerID: String) {
        inFlight.remove(peerID)
    }

    // MARK: - ProfileSyncHandler

    override fun requestBytesForAdvertised(
        peerID: String,
        version: Long,
        photoHashHex: String?
    ): ByteArray? {
        if (peerID.isBlank()) return null
        if (!isStale(peerID, version, photoHashHex)) return null
        if (recentlyRequested(peerID, version)) return null
        markRequested(peerID, version)
        val cachedVersion = cachedRecord(peerID)?.version ?: 0L
        return ProfileSync.encodeRequest(cachedVersion)
    }

    override fun replyForRequest(
        peerID: String,
        cachedVersion: Long
    ): Pair<NoisePayloadType, ByteArray>? {
        if (ownVersion() > cachedVersion) {
            val response = ownResponseBytes() ?: return null
            return NoisePayloadType.PROFILE_RESPONSE to response
        }
        val announce = ownAnnounceBytes() ?: return null
        return NoisePayloadType.PROFILE_ANNOUNCE to announce
    }

    override fun onProfileAnnounce(peerID: String, announce: ProfileSync.ProfileAnnounce) {
        if (peerID.isBlank()) return
        if (!isStale(peerID, announce.version, announce.photoHashHex)) return
        // The announce itself carries the nickname: cache the nickname now so
        // the name updates even if the photo fetch fails or is still running.
        // Keep the OLD photo hash in the record until the photo actually
        // arrives — otherwise the version+hash would look current and a
        // failed fetch would never be retried.
        val cached = cachedRecord(peerID)
        if (cached == null || cached.nickname != announce.nickname) {
            val existingPhoto = dataManager.getProfilePhotoBytesForPeer(peerID)
            dataManager.savePeerProfile(
                peerID, announce.nickname, announce.version,
                cached?.photoHashHex, existingPhoto
            )
            _profileUpdated.value += 1
        }
        fetchOverNostr(peerID, announce.version, announce.photoHashHex)
    }

    override fun onProfileResponse(peerID: String, responseBytes: ByteArray) {
        val response = ProfileSync.decodeResponse(responseBytes) ?: run {
            Log.w(TAG, "Dropping malformed profile response from ${peerID.take(8)}")
            return
        }
        if (!isStale(peerID, response.version, response.photoHashHex)) {
            clearRequested(peerID)
            return
        }
        dataManager.savePeerProfile(
            peerID, response.nickname, response.version,
            response.photoHashHex, response.photoBytes
        )
        clearRequested(peerID)
        _profileUpdated.value += 1
        Log.d(TAG, "Cached profile v${response.version} for ${peerID.take(8)}")
    }

    override fun onProfileChunk(peerID: String, chunkBytes: ByteArray) {
        val chunk = ProfileSync.decodeChunk(chunkBytes) ?: run {
            Log.w(TAG, "Dropping malformed profile chunk from ${peerID.take(8)}")
            return
        }
        val completed = reassembly.add(chunk) ?: return
        onProfileResponse(peerID, completed)
    }

    // MARK: - Fetch over Nostr

    /**
     * Ask a peer for its profile over Nostr. No-op when the cached copy is
     * already current, when a fetch for this version is already in flight,
     * or when no Nostr route to the peer exists.
     */
    override fun fetchOverNostr(peerID: String, version: Long, photoHashHex: String?) {
        if (peerID.isBlank()) return
        if (!isStale(peerID, version, photoHashHex)) return
        if (recentlyRequested(peerID, version)) return
        val transport = NostrTransport.getInstance(appContext)
        val senderPeerID = transport.myPeerID
        if (senderPeerID.isBlank()) return
        val request = ProfileSync.encodeRequest(cachedRecord(peerID)?.version ?: 0L)
        val embedded = NostrEmbeddedBitChat.encodeProfilePacketForNostr(
            NoisePayloadType.PROFILE_REQUEST, request, peerID, senderPeerID
        ) ?: return
        markRequested(peerID, version)
        transport.sendProfilePacket(embedded, peerID)
    }

    /**
     * Opportunistic fetch for a peer with no cached profile at all (e.g. when
     * opening the chat). Only the Nostr route is tried here; BLE peers are
     * covered by the announcement path, which sends the request itself.
     */
    fun fetchIfUnknown(peerID: String) {
        if (peerID.isBlank()) return
        if (cachedRecord(peerID) != null) return
        fetchOverNostr(peerID, Long.MAX_VALUE, null)
    }

    /** Reply to a PROFILE_REQUEST that arrived over Nostr (chunked when it carries a photo). */
    fun replyOverNostr(peerID: String, cachedVersion: Long) {
        val (type, bytes) = replyForRequest(peerID, cachedVersion) ?: return
        scope.launch {
            try {
                val transport = NostrTransport.getInstance(appContext)
                val senderPeerID = transport.myPeerID
                if (senderPeerID.isBlank()) return@launch
                if (type == NoisePayloadType.PROFILE_ANNOUNCE) {
                    val embedded = NostrEmbeddedBitChat.encodeProfilePacketForNostr(
                        type, bytes, peerID, senderPeerID
                    ) ?: return@launch
                    transport.sendProfilePacket(embedded, peerID)
                    return@launch
                }
                // RESPONSE: split into one DM per chunk so no relay event gets large.
                val chunks = ProfileSync.splitIntoChunks(UUID.randomUUID(), bytes)
                for (chunkBytes in chunks) {
                    val embedded = NostrEmbeddedBitChat.encodeProfilePacketForNostr(
                        NoisePayloadType.PROFILE_RESPONSE_CHUNK, chunkBytes, peerID, senderPeerID
                    ) ?: continue
                    transport.sendProfilePacket(embedded, peerID)
                    delay(NOSTR_CHUNK_DELAY_MS)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to reply to profile request from ${peerID.take(8)}: ${e.message}")
            }
        }
    }

    /**
     * Push our (version, photo hash) to every favorite peer over Nostr after
     * a profile change. Peers whose cache is older fetch the full profile in
     * the background; peers that are current ignore it. BLE peers learn the
     * new version from the next periodic announcement instead.
     */
    fun announceOwnProfileChange() {
        scope.launch {
            try {
                val announce = ownAnnounceBytes() ?: return@launch
                val transport = NostrTransport.getInstance(appContext)
                val senderPeerID = transport.myPeerID
                if (senderPeerID.isBlank()) return@launch
                for (peerID in dataManager.favoritePeers) {
                    val embedded = NostrEmbeddedBitChat.encodeProfilePacketForNostr(
                        NoisePayloadType.PROFILE_ANNOUNCE, announce, peerID, senderPeerID
                    ) ?: continue
                    transport.sendProfilePacket(embedded, peerID)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to announce profile change: ${e.message}")
            }
        }
    }
}
