package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Display-name precedence for the profile-sync display fix: a nickname
 * that arrived over Nostr is saved to the profile cache, and the Chats
 * list must show it. The favorite record is frozen at the time the friend
 * was added, so the cached profile name has to beat it — while a live
 * Bluetooth nickname (the same value over the live transport) still wins.
 */
@RunWith(RobolectricTestRunner::class)
class ConversationSummaryTest {

    private fun resolve(
        fallbackName: String = "fallback",
        connectedPeerID: String? = null,
        peerNicknames: Map<String, String> = emptyMap(),
        resolvedContactName: String? = null,
        persistedDisplayName: String? = null,
        cachedProfileName: String? = null
    ) = resolveConversationDisplayName(
        fallbackName, connectedPeerID, peerNicknames,
        resolvedContactName, persistedDisplayName, cachedProfileName
    )

    @Test
    fun `cached profile name beats favorite and persisted names`() {
        assertEquals(
            "NewName",
            resolve(
                resolvedContactName = "OldFavorite",
                persistedDisplayName = "OldMessage",
                cachedProfileName = "NewName"
            )
        )
    }

    @Test
    fun `live Bluetooth nickname beats cached profile name`() {
        assertEquals(
            "LiveName",
            resolve(
                connectedPeerID = "0123456789abcdef",
                peerNicknames = mapOf("0123456789abcdef" to "LiveName"),
                cachedProfileName = "CachedName"
            )
        )
    }

    @Test
    fun `blank or Unknown cached name falls through`() {
        assertEquals(
            "Favorite",
            resolve(resolvedContactName = "Favorite", cachedProfileName = "Unknown")
        )
        assertEquals(
            "Favorite",
            resolve(resolvedContactName = "Favorite", cachedProfileName = "  ")
        )
    }

    @Test
    fun `no names at all falls back to latest message sender`() {
        assertEquals("fallback", resolve())
    }

    @Test
    fun `profile cache keys cover bare fingerprint and contact conversation id`() {
        assertEquals(
            setOf("abcdef0123456789", "contact_abcdef0123456789"),
            ChatViewModel.profileCacheKeys("abcdef0123456789")
        )
    }
}
