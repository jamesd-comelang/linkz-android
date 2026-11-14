package com.bitchat.android.nostr

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * One-time invite code (一次性邀请码) friend requests.
 *
 * A request travels as a NIP-17 gift wrap whose rumor content is a plain text
 * line: "linkz-friend-request:{json}" or "linkz-friend-accept:{json}".
 *
 * No Noise session is involved: the two sides have never met over BLE, so
 * there is no shared Noise state and no peerID mapping yet. Confidentiality
 * and sender authentication come from the gift wrap itself (NIP-44 seal +
 * signature), and the rumor's pubkey is the sender's real Nostr identity.
 *
 * The request/accept JSONs carry each side's Noise public key. On accept,
 * both sides store the other as a mutual favorite keyed by that Noise key,
 * so the ordinary mutual-favorite Nostr DM path works afterwards with no
 * parallel transport.
 *
 * Requests live 3 days, like WeChat's friend requests, then expire.
 */

enum class FriendRequestDirection { INCOMING, OUTGOING }

enum class FriendRequestStatus { PENDING, ACCEPTED, DECLINED }

data class FriendRequest(
    val requestId: String,
    /** Remote party's real Nostr pubkey, lowercase hex. */
    val remotePubkeyHex: String,
    val remoteNickname: String,
    /** Remote party's Noise public key, lowercase hex. Exchanged so the two sides can DM after accept. */
    val remoteNoiseKeyHex: String,
    /** Verification greeting attached to the request. Empty for outgoing rows we did not write. */
    val greeting: String,
    val createdAtMs: Long,
    val direction: FriendRequestDirection,
    val status: FriendRequestStatus = FriendRequestStatus.PENDING
) {
    /** Pending requests expire 3 days after they were sent. Expired is derived, never stored. */
    fun isExpired(nowMs: Long = System.currentTimeMillis()): Boolean =
        status == FriendRequestStatus.PENDING &&
            nowMs - createdAtMs > FriendRequestProtocol.REQUEST_VALIDITY_MS
}

/** Wire payload of a friend request (gift-wrapped to a one-time invite npub). */
data class FriendRequestPayload(
    @SerializedName("v") val version: Int = 1,
    @SerializedName("requestId") val requestId: String,
    /** Sender's real Nostr pubkey, lowercase hex. */
    @SerializedName("fromNpub") val fromPubkeyHex: String,
    @SerializedName("fromNickname") val fromNickname: String,
    /** Sender's Noise public key, lowercase hex. */
    @SerializedName("fromNoiseKey") val fromNoiseKeyHex: String,
    @SerializedName("greeting") val greeting: String = "",
    @SerializedName("createdAt") val createdAtMs: Long
)

/** Wire payload of a friend-request acceptance (gift-wrapped to the requester's real npub). */
data class FriendAcceptPayload(
    @SerializedName("v") val version: Int = 1,
    @SerializedName("requestId") val requestId: String,
    /** Accepter's real Nostr pubkey, lowercase hex. */
    @SerializedName("fromNpub") val fromPubkeyHex: String,
    @SerializedName("fromNickname") val fromNickname: String,
    /** Accepter's Noise public key, lowercase hex. */
    @SerializedName("fromNoiseKey") val fromNoiseKeyHex: String,
    @SerializedName("createdAt") val createdAtMs: Long
)

object FriendRequestProtocol {
    const val REQUEST_PREFIX = "linkz-friend-request:"
    const val ACCEPT_PREFIX = "linkz-friend-accept:"

    /** 3 days, matching WeChat's friend-request validity. */
    const val REQUEST_VALIDITY_MS = 3L * 24 * 60 * 60 * 1000

    private val gson = Gson()

    fun encodeRequest(payload: FriendRequestPayload): String =
        REQUEST_PREFIX + gson.toJson(payload)

    fun decodeRequest(content: String): FriendRequestPayload? {
        if (!content.startsWith(REQUEST_PREFIX)) return null
        return try {
            gson.fromJson(content.removePrefix(REQUEST_PREFIX), FriendRequestPayload::class.java)
                ?.takeIf { it.version == 1 && it.requestId.isNotBlank() && it.fromPubkeyHex.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun encodeAccept(payload: FriendAcceptPayload): String =
        ACCEPT_PREFIX + gson.toJson(payload)

    fun decodeAccept(content: String): FriendAcceptPayload? {
        if (!content.startsWith(ACCEPT_PREFIX)) return null
        return try {
            gson.fromJson(content.removePrefix(ACCEPT_PREFIX), FriendAcceptPayload::class.java)
                ?.takeIf { it.version == 1 && it.requestId.isNotBlank() && it.fromPubkeyHex.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }
}
