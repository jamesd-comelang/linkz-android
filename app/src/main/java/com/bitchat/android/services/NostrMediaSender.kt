package com.bitchat.android.services

import android.util.Log
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.ChunkedPayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.nostr.NostrEmbeddedBitChat
import java.util.UUID
import kotlinx.coroutines.delay

/**
 * Private media over Nostr (voice notes, images, files) for peers with no
 * BLE mesh route.
 *
 * Private media is mesh-only today: with no mesh route the send fails and
 * the user sees "no active mesh route" — which is every send between two
 * copies on one phone, since they can never BLE-discover each other. Text
 * DMs already solve the same problem over Nostr (NIP-17 gift wraps to a
 * mutual favorite), and the receiver for file packets over Nostr already
 * exists ([com.bitchat.android.nostr.NostrDirectMessageHandler] handles
 * FILE_TRANSFER). What was missing was the sender: chunk the encoded
 * file packet the way profile photos are chunked, gift-wrap each chunk,
 * and let the receiver reassemble it into the existing FILE_TRANSFER
 * handling.
 *
 * Rules, mirroring the text-DM path:
 * - Mesh stays first: this sender is only used when
 *   [com.bitchat.android.ui.PrivateMediaRecipientResolver] finds no route.
 * - Private DMs only: the caller (MediaSendingManager) only falls back
 *   for peer sends, never for channels.
 * - Only to mutual favorites with a known Nostr pubkey — the same gate
 *   as [MessageRouter.canSendViaNostr] (duplicated here because that
 *   function is private to the router and this sender is constructed
 *   without it; keep the two in sync).
 * - Files over [MAX_NOSTR_MEDIA_BYTES] stay mesh-only: a 10 MB file
 *   would be ~850 gift wraps.
 * - Fire-and-forget, like a text DM: the local echo is marked Sent,
 *   never Delivered.
 *
 * The [sendPacket] seam (one embedded `bitchat1:` string + recipient)
 * and the [isEligible] seam exist so unit tests can record packets and
 * control the gate without the Nostr transport or the encrypted
 * favorites store. Production wires the real transport in ChatViewModel.
 */
class NostrMediaSender(
    private val sendPacket: (embedded: String, toPeerID: String) -> Unit,
    private val isEligible: (String) -> Boolean = { peerID -> canSendMediaViaNostr(peerID) }
) {
    companion object {
        private const val TAG = "NostrMediaSender"

        /** Largest encoded file packet sent over Nostr, in bytes. Voice clips (<= 10 s) fit easily. */
        const val MAX_NOSTR_MEDIA_BYTES: Int = 1_048_576

        /** Pacing between chunk sends, matching the profile-sync Nostr path. */
        const val NOSTR_CHUNK_DELAY_MS: Long = 100L

        /**
         * Pure half of the Nostr gate: a peer can receive Nostr DMs when
         * the directory resolves them as a mutual favorite with a known
         * npub. Unit-testable without the favorites store.
         */
        internal fun isEligibleResolution(resolution: ContactDirectory.ContactResolution): Boolean =
            resolution.isMutualFavorite && resolution.nostrPubkey != null

        /**
         * Whether a peer can receive private media over Nostr: a mutual
         * favorite with a known Nostr pubkey. Mirrors
         * [MessageRouter.canSendViaNostr] including its
         * FavoritesPersistenceService fallbacks for noise-key / mesh peer
         * IDs; keep the two in sync.
         */
        internal fun canSendMediaViaNostr(peerID: String): Boolean {
            return try {
                val resolution = ContactDirectory.resolve(peerID)
                if (isEligibleResolution(resolution)) return true
                val target = resolution.noiseKeyHex ?: peerID
                if (ContactIdentityResolver.isNoiseKeyHex(target)) {
                    val noiseKey = ContactIdentityResolver.bytesFromHex(target) ?: return false
                    val fav = FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                    fav?.isMutual == true && fav.peerNostrPublicKey != null
                } else if (ContactIdentityResolver.isMeshPeerId(target)) {
                    val fav = FavoritesPersistenceService.shared.getFavoriteStatus(target)
                    fav?.isMutual == true && fav.peerNostrPublicKey != null
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    /** The Nostr gate for this peer (default: [canSendMediaViaNostr]). */
    fun canSendViaNostr(peerID: String): Boolean = isEligible(peerID)

    /**
     * Encode an already-encoded file packet into one `bitchat1:` string
     * per [ChunkedPayload] chunk. Returns null when the payload is empty
     * or over [MAX_NOSTR_MEDIA_BYTES], or when any chunk fails to encode —
     * the caller must then not add a local echo, since nothing would go
     * out. Encoding up front (instead of per-chunk during the send loop)
     * keeps a systematic encode failure from producing a false Sent echo.
     */
    fun encodeChunks(peerID: String, senderPeerID: String, payload: ByteArray): List<String>? {
        if (payload.isEmpty() || payload.size > MAX_NOSTR_MEDIA_BYTES) return null
        val chunks = ChunkedPayload.splitIntoChunks(UUID.randomUUID(), payload, MAX_NOSTR_MEDIA_BYTES)
        if (chunks.isEmpty()) return null
        val embedded = ArrayList<String>(chunks.size)
        for (chunkBytes in chunks) {
            val encoded = NostrEmbeddedBitChat.encodePacketForNostr(
                NoisePayloadType.FILE_TRANSFER_CHUNK, chunkBytes, peerID, senderPeerID
            ) ?: return null
            embedded.add(encoded)
        }
        Log.d(TAG, "Encoded ${payload.size}-byte media payload for ${peerID.take(8)} as ${embedded.size} Nostr chunks")
        return embedded
    }

    /** Send pre-encoded chunks to a peer, paced so relays are not flooded. */
    suspend fun sendChunks(chunks: List<String>, toPeerID: String) {
        for ((index, embedded) in chunks.withIndex()) {
            try {
                sendPacket(embedded, toPeerID)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send media chunk ${index + 1}/${chunks.size} to ${toPeerID.take(8)}: ${e.message}")
            }
            if (index < chunks.lastIndex) delay(NOSTR_CHUNK_DELAY_MS)
        }
        Log.d(TAG, "Sent ${chunks.size} media chunks to ${toPeerID.take(8)} over Nostr")
    }
}
