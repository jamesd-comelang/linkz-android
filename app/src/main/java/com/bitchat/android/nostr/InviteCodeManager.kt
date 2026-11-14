package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.ContactIdentityResolver

/**
 * One-time invite code (一次性邀请码) storage.
 *
 * The invite is a fresh ephemeral Nostr keypair, unrelated to the user's
 * real Nostr identity. Its npub is what the user shares; anyone holding it
 * can send exactly one friend request to it, and only a friend request.
 *
 * The private key lives in the same encrypted store as the main Nostr key
 * (SecureIdentityStateManager) so it survives app restarts while the invite
 * is live, and dies with it:
 * - consumed when the first valid request arrives (single-use),
 * - expired 3 days after creation (like WeChat's friend requests),
 * - invalidated immediately when the user regenerates the code
 *   (WeChat's "reset my QR code" semantics).
 */
object InviteCodeManager {
    private const val TAG = "InviteCode"
    private const val INVITE_PRIVATE_KEY = "invite_nostr_private_key"
    private const val INVITE_CREATED_AT_MS = "invite_created_at_ms"
    private const val INVITE_USED = "invite_used"

    /** 3 days, matching WeChat's friend-request validity. */
    const val VALIDITY_MS = 3L * 24 * 60 * 60 * 1000

    sealed interface InviteStatus {
        data class Valid(val identity: NostrIdentity, val expiresAtMs: Long) : InviteStatus
        data object Used : InviteStatus
        data object Expired : InviteStatus
        data object None : InviteStatus
    }

    /** Current live invite, or null when there is none, it was used, or it expired. */
    fun getLiveInvite(context: Context): NostrIdentity? {
        val stateManager = SecureIdentityStateManager(context)
        val privateKeyHex = stateManager.getSecureValue(INVITE_PRIVATE_KEY) ?: return null
        if (stateManager.getSecureValue(INVITE_USED) != null) return null
        val createdAtMs = stateManager.getSecureValue(INVITE_CREATED_AT_MS)?.toLongOrNull() ?: return null
        if (System.currentTimeMillis() - createdAtMs > VALIDITY_MS) return null
        return try {
            NostrIdentity.fromPrivateKey(privateKeyHex)
        } catch (e: Exception) {
            Log.w(TAG, "Stored invite key is invalid, dropping it: ${e.message}")
            clearInvite(context)
            null
        }
    }

    fun status(context: Context): InviteStatus {
        val stateManager = SecureIdentityStateManager(context)
        if (stateManager.getSecureValue(INVITE_PRIVATE_KEY) == null) return InviteStatus.None
        if (stateManager.getSecureValue(INVITE_USED) != null) return InviteStatus.Used
        val createdAtMs = stateManager.getSecureValue(INVITE_CREATED_AT_MS)?.toLongOrNull()
            ?: return InviteStatus.None
        val expiresAtMs = createdAtMs + VALIDITY_MS
        if (System.currentTimeMillis() > expiresAtMs) return InviteStatus.Expired
        return try {
            InviteStatus.Valid(NostrIdentity.fromPrivateKey(stateManager.getSecureValue(INVITE_PRIVATE_KEY)!!), expiresAtMs)
        } catch (e: Exception) {
            Log.w(TAG, "Stored invite key is invalid, dropping it: ${e.message}")
            clearInvite(context)
            InviteStatus.None
        }
    }

    /** Live invite, generating a fresh one when there is none, it was used, or it expired. */
    fun getOrCreateInvite(context: Context): NostrIdentity {
        getLiveInvite(context)?.let { return it }
        return regenerateInvite(context)
    }

    /** New keypair; the previous code stops working immediately. */
    fun regenerateInvite(context: Context): NostrIdentity {
        val stateManager = SecureIdentityStateManager(context)
        val identity = NostrIdentity.generate()
        stateManager.storeSecureValue(INVITE_PRIVATE_KEY, identity.privateKeyHex)
        stateManager.storeSecureValue(INVITE_CREATED_AT_MS, System.currentTimeMillis().toString())
        stateManager.clearSecureValues(INVITE_USED)
        Log.d(TAG, "Generated new invite code: ${identity.getShortNpub()}")
        return identity
    }

    /** Single-use: mark the invite consumed and delete its private key. */
    fun consumeInvite(context: Context) {
        val stateManager = SecureIdentityStateManager(context)
        stateManager.storeSecureValue(INVITE_USED, "1")
        stateManager.clearSecureValues(INVITE_PRIVATE_KEY)
        Log.d(TAG, "Invite code consumed")
    }

    /** Remove every trace of the invite (panic wipe). Returns whether the wipe committed. */
    fun clearInvite(context: Context): Boolean {
        val stateManager = SecureIdentityStateManager(context)
        return stateManager.clearSecureValuesSynchronously(INVITE_PRIVATE_KEY, INVITE_CREATED_AT_MS, INVITE_USED)
    }

    /**
     * Parse a shared invite code into a lowercase hex pubkey.
     * Accepts a raw npub, a "nostr:npub1…" URI, or a 64-hex pubkey.
     * Returns null for anything else (including the user's own npub — callers
     * check that separately, since this function has no identity context).
     */
    fun parseInviteCode(text: String): String? {
        val clean = text.trim().removePrefix("nostr:").trim()
        if (clean.isEmpty()) return null
        return ContactIdentityResolver.nostrPubkeyHex(clean)
    }
}
