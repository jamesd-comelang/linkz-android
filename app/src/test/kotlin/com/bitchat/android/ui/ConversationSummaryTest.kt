package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.PrivateMessageArrivalOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Date

class ConversationSummaryTest {
    @After
    fun tearDown() {
        PrivateMessageArrivalOrder.clear()
    }

    @Test
    fun `read offline conversation remains present`() {
        val message = incoming("message", "alice", 100L)
        PrivateMessageArrivalOrder.record(message.id)

        val conversations = buildConversationSummaries(
            unreadConversationIDs = emptySet(),
            privateChats = mapOf("alice-peer" to listOf(message)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { true }
        )

        assertEquals(1, conversations.size)
        assertEquals("alice-peer", conversations.single().conversationID)
        assertEquals(0, conversations.single().unreadCount)
        assertFalse(conversations.single().isConnected)
    }

    @Test
    fun `canonical aliases yield one conversation with unique messages`() {
        val first = incoming("first", "alice", 300L)
        val second = incoming("second", "alice", 100L)
        PrivateMessageArrivalOrder.record(first.id)
        PrivateMessageArrivalOrder.record(second.id)

        val conversations = buildConversationSummaries(
            unreadConversationIDs = setOf("mesh-alias"),
            privateChats = mapOf(
                "mesh-alias" to listOf(first),
                "nostr_alias" to listOf(first, second)
            ),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { "contact_alice" },
            isMessageRead = { false }
        )

        assertEquals(1, conversations.size)
        assertEquals(2, conversations.single().unreadCount)
        assertEquals(
            setOf("mesh-alias", "nostr_alias", "contact_alice"),
            conversations.single().identityAliases
        )
        assertEquals("alice", conversations.single().displayName)
        assertEquals(DirectMessageTransport.NOSTR, conversations.single().transport)
    }

    @Test
    fun `online conversations sort before newer offline conversations`() {
        val offline = summary(
            id = "offline",
            isConnected = false,
            unreadCount = 3,
            activity = 300L
        )
        val onlineRead = summary(
            id = "online-read",
            isConnected = true,
            unreadCount = 0,
            activity = 100L
        )
        val onlineUnread = summary(
            id = "online-unread",
            isConnected = true,
            unreadCount = 1,
            activity = 50L
        )

        assertEquals(
            listOf("online-unread", "online-read", "offline"),
            sortConversationSummaries(listOf(offline, onlineRead, onlineUnread))
                .map { it.conversationID }
        )
    }

    @Test
    fun `preview follows local arrival order instead of remote timestamp`() {
        val first = incoming(
            id = "first",
            sender = "alice",
            timestamp = 900L,
            content = "older arrival"
        )
        val second = incoming(
            id = "second",
            sender = "alice",
            timestamp = 100L,
            content = "  latest\nmessage  "
        )
        PrivateMessageArrivalOrder.record(first.id)
        PrivateMessageArrivalOrder.record(second.id)

        val conversation = buildConversationSummaries(
            unreadConversationIDs = emptySet(),
            privateChats = mapOf("alice-peer" to listOf(first, second)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { true }
        ).single()

        assertEquals("latest message", conversation.latestMessagePreview)
        assertEquals(BitchatMessageType.Message, conversation.latestMessageType)
    }

    @Test
    fun `file preview contains filename without local path`() {
        val message = incoming(
            id = "file",
            sender = "alice",
            timestamp = 100L,
            content = "/private/conversations/quarterly report.pdf",
            type = BitchatMessageType.File
        )

        val conversation = buildConversationSummaries(
            unreadConversationIDs = emptySet(),
            privateChats = mapOf("alice-peer" to listOf(message)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { true }
        ).single()

        assertEquals("quarterly report.pdf", conversation.latestMessagePreview)
        assertEquals(BitchatMessageType.File, conversation.latestMessageType)
    }

    @Test
    fun `case variants cannot create duplicate conversations or misclassify outgoing messages`() {
        val outgoing = incoming(
            id = "outgoing",
            sender = "Me",
            timestamp = 200L,
            content = "sent by me"
        )

        val conversations = buildConversationSummaries(
            unreadConversationIDs = emptySet(),
            privateChats = mapOf(
                "CONTACT_ALICE" to listOf(outgoing),
                "contact_alice" to listOf(outgoing)
            ),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { true }
        )

        assertEquals(1, conversations.size)
        assertEquals(setOf("contact_alice"), conversations.single().identityAliases)
        assertEquals(true, conversations.single().latestMessageIsOutgoing)
    }

    @Test
    fun `outgoing messages remain outgoing after local nickname changes`() {
        val outgoing = incoming(
            id = "outgoing-before-rename",
            sender = "old nickname",
            timestamp = 200L,
            content = "sent before rename"
        ).copy(senderPeerID = "my-stable-peer-id")

        val conversation = buildConversationSummaries(
            unreadConversationIDs = setOf("contact_alice"),
            privateChats = mapOf("contact_alice" to listOf(outgoing)),
            currentUserIdentifiers = setOf("new nickname", "my-stable-peer-id"),
            canonicalize = { it },
            isMessageRead = { false },
            persistedUnreadCounts = emptyMap()
        ).single()

        assertTrue(conversation.latestMessageIsOutgoing)
        assertEquals(0, conversation.unreadCount)
    }

    @Test
    fun `connected nickname overrides cached conversation names`() {
        assertEquals(
            "Alice Renamed",
            resolveConversationDisplayName(
                fallbackName = "Alice From Message",
                connectedPeerID = "peer-a",
                peerNicknames = mapOf("peer-a" to "Alice Renamed"),
                resolvedContactName = "Alice Favorite Snapshot",
                persistedDisplayName = "Alice Persisted"
            )
        )
    }

    @Test
    fun `persisted nickname is used when no live or contact name remains`() {
        assertEquals(
            "Alice Persisted",
            resolveConversationDisplayName(
                fallbackName = "Alice From Message",
                connectedPeerID = null,
                peerNicknames = emptyMap(),
                resolvedContactName = null,
                persistedDisplayName = "Alice Persisted"
            )
        )
    }

    @Test
    fun `summary-only startup uses persisted unread count`() {
        val latest = incoming("latest", "alice", 300L)

        val conversation = buildConversationSummaries(
            unreadConversationIDs = setOf("contact_alice"),
            privateChats = mapOf("contact_alice" to listOf(latest)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { false },
            persistedUnreadCounts = mapOf("contact_alice" to 47)
        ).single()

        assertEquals(47, conversation.unreadCount)
    }

    // Cached-profile display names (background profile sync): a nickname
    // that arrived over Nostr is saved to the profile cache, and the Chats
    // list must show it. The favorite record is frozen at the time the
    // friend was added, so the cached profile name has to beat it — while a
    // live Bluetooth nickname (the same value over the live transport)
    // still wins.

    @Test
    fun `cached profile name beats favorite and persisted names`() {
        assertEquals(
            "Alice Synced",
            resolveConversationDisplayName(
                fallbackName = "Alice From Message",
                connectedPeerID = null,
                peerNicknames = emptyMap(),
                resolvedContactName = "Alice Favorite Snapshot",
                persistedDisplayName = "Alice Persisted",
                cachedProfileName = "Alice Synced"
            )
        )
    }

    @Test
    fun `live Bluetooth nickname beats cached profile name`() {
        assertEquals(
            "Alice Renamed",
            resolveConversationDisplayName(
                fallbackName = "Alice From Message",
                connectedPeerID = "peer-a",
                peerNicknames = mapOf("peer-a" to "Alice Renamed"),
                resolvedContactName = "Alice Favorite Snapshot",
                persistedDisplayName = "Alice Persisted",
                cachedProfileName = "Alice Synced"
            )
        )
    }

    @Test
    fun `blank or Unknown cached name falls through`() {
        assertEquals(
            "Alice Favorite Snapshot",
            resolveConversationDisplayName(
                fallbackName = "Alice From Message",
                connectedPeerID = null,
                peerNicknames = emptyMap(),
                resolvedContactName = "Alice Favorite Snapshot",
                persistedDisplayName = "Alice Persisted",
                cachedProfileName = "Unknown"
            )
        )
        assertEquals(
            "Alice Favorite Snapshot",
            resolveConversationDisplayName(
                fallbackName = "Alice From Message",
                connectedPeerID = null,
                peerNicknames = emptyMap(),
                resolvedContactName = "Alice Favorite Snapshot",
                persistedDisplayName = "Alice Persisted",
                cachedProfileName = "  "
            )
        )
    }

    @Test
    fun `no names at all falls back to latest message sender`() {
        assertEquals(
            "Alice From Message",
            resolveConversationDisplayName(
                fallbackName = "Alice From Message",
                connectedPeerID = null,
                peerNicknames = emptyMap(),
                resolvedContactName = null,
                persistedDisplayName = null,
                cachedProfileName = null
            )
        )
    }

    @Test
    fun `profile cache keys cover bare id and canonical conversation id`() {
        // A 64-hex Noise key resolves deterministically to its contact_
        // conversation id (SHA-256 of the key bytes), with no favorites
        // record needed. The expected fingerprint is computed here with
        // MessageDigest, not with the production helper.
        val noiseKeyHex = "ab".repeat(32)
        val keyBytes = ContactIdentityResolver.bytesFromHex(noiseKeyHex)!!
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(keyBytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(
            setOf(noiseKeyHex, "contact_$fingerprint"),
            ChatViewModel.profileCacheKeys(noiseKeyHex)
        )
        // A bare 16-hex mesh peer id with no favorites record has no
        // other canonical form, so only itself is stored. (A 16-hex id
        // is the first 16 chars of the 64-hex fingerprint — never pass
        // it to contactConversationIdForFingerprint, which needs all 64.)
        assertEquals(
            setOf("0123456789abcdef"),
            ChatViewModel.profileCacheKeys("0123456789abcdef")
        )
    }

    @Test
    fun `voice preview shows audio label with seconds when duration known`() {
        val clip = incoming("clip", "alice", 100L, content = "/x/voice.m4a", type = BitchatMessageType.Audio)
        PrivateMessageArrivalOrder.record(clip.id)

        val conversations = buildConversationSummaries(
            unreadConversationIDs = emptySet(),
            privateChats = mapOf("alice-peer" to listOf(clip)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { true },
            audioDurations = mapOf("clip" to 3)
        )

        assertEquals("[Audio] 3\"", conversations.single().latestMessagePreview)
    }

    @Test
    fun `voice preview shows audio label without seconds when duration unknown`() {
        val clip = incoming("clip", "alice", 100L, content = "/x/voice.m4a", type = BitchatMessageType.Audio)
        PrivateMessageArrivalOrder.record(clip.id)

        val conversations = buildConversationSummaries(
            unreadConversationIDs = emptySet(),
            privateChats = mapOf("alice-peer" to listOf(clip)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { true }
        )

        assertEquals("[Audio]", conversations.single().latestMessagePreview)
    }

    private fun incoming(
        id: String,
        sender: String,
        timestamp: Long,
        content: String = "hello",
        type: BitchatMessageType = BitchatMessageType.Message
    ) = BitchatMessage(
        id = id,
        sender = sender,
        content = content,
        type = type,
        timestamp = Date(timestamp),
        isPrivate = true
    )

    private fun summary(
        id: String,
        isConnected: Boolean,
        unreadCount: Int,
        activity: Long
    ) = ConversationSummary(
        conversationID = id,
        displayName = id,
        unreadCount = unreadCount,
        latestMessageAt = activity,
        latestActivityOrder = activity,
        latestMessageType = BitchatMessageType.Message,
        latestMessagePreview = "hello",
        transport = DirectMessageTransport.MESH,
        nostrPubkey = null,
        identityAliases = setOf(id),
        isConnected = isConnected
    )
}
