package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.services.NostrMediaSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID

/**
 * Robolectric tests for the Nostr fallback in MediaSendingManager: when
 * no BLE mesh route exists, private voice notes go over Nostr to a mutual
 * favorite instead of failing with "no active mesh route". The real
 * Nostr transport is never touched — packets are recorded by the
 * injected NostrMediaSender seam, and the gate is controlled per test.
 */
@RunWith(RobolectricTestRunner::class)
class MediaSendingManagerNostrFallbackTest {
    // A 16-hex mesh peer ID: PrivateMediaRecipientResolver accepts it
    // directly, so this peer always has a "route" in the test.
    private val meshPeerID = "8877665544332211"

    // A contact conversation ID with no favorites record and no live
    // mesh peer: the resolver finds no route for it.
    private val contactID = "contact_" + "a".repeat(64)

    private lateinit var state: ChatState
    private lateinit var mesh: MeshService
    private lateinit var manager: MediaSendingManager
    private lateinit var file: File
    private lateinit var bigFile: File
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationDatabaseName: String

    private val recorded = mutableListOf<Pair<String, String>>()
    private var eligible = false

    @Before
    fun setup() {
        state = ChatState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        state.setNickname("me")
        conversationDatabaseName = "media-nostr-${UUID.randomUUID()}.db"
        conversationRepository = ConversationRepository(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            dispatcher = Dispatchers.Unconfined,
            databaseName = conversationDatabaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(conversationRepository)
        mesh = mock()
        whenever(mesh.myPeerID).thenReturn("0011223344556677")
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(meshPeerID to "old peer"))
        recorded.clear()
        eligible = false
        val nostrSender = NostrMediaSender(
            sendPacket = { embedded, to -> recorded.add(embedded to to) },
            isEligible = { eligible }
        )
        manager = MediaSendingManager(
            state,
            MessageManager(state),
            mock(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mediaWorkDispatcher = Dispatchers.Unconfined,
            nostrMediaSender = nostrSender,
            getMeshService = { mesh }
        )
        file = kotlin.io.path.createTempFile("voice-note", ".m4a").toFile().apply {
            writeBytes(ByteArray(128) { it.toByte() })
        }
        bigFile = kotlin.io.path.createTempFile("big-media", ".bin").toFile().apply {
            writeBytes(ByteArray(1_100_000) { (it % 251).toByte() })
        }
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        conversationRepository.closeForTest()
        org.robolectric.RuntimeEnvironment.getApplication()
            .deleteDatabase(conversationDatabaseName)
        file.delete()
        bigFile.delete()
    }

    @Test
    fun `mesh route present sends over mesh and never touches Nostr`() {
        whenever(mesh.prepareFilePrivate(eq(meshPeerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.Rejected("too many fragments"))

        manager.sendImageNote(meshPeerID, null, file.absolutePath)

        verify(mesh, never()).sendFilePrivate(any(), any())
        assertTrue(recorded.isEmpty())
        val messages = state.privateChats.value[meshPeerID].orEmpty()
        assertEquals(1, messages.size)
        assertTrue(messages.single().content.contains("too many fragments"))
    }

    @Test
    fun `no route and not eligible posts the mesh-route failure and sends nothing`() {
        eligible = false

        manager.sendVoiceNote(contactID, null, file.absolutePath)

        assertTrue(recorded.isEmpty())
        val messages = state.privateChats.value[contactID].orEmpty()
        assertEquals(1, messages.size)
        assertTrue(messages.single().content.contains("no active mesh route"))
        assertTrue(messages.none { it.type == BitchatMessageType.Audio })
    }

    @Test
    fun `no route and eligible sends the voice note over Nostr with a Sent echo`() {
        eligible = true

        manager.sendVoiceNote(contactID, null, file.absolutePath)

        assertEquals(1, recorded.size)
        assertEquals(contactID, recorded.single().second)
        assertTrue(recorded.single().first.startsWith("bitchat1:"))
        val messages = state.privateChats.value[contactID].orEmpty()
        val audio = messages.singleOrNull { it.type == BitchatMessageType.Audio }
        assertTrue("expected one Audio echo, got $messages", audio != null)
        assertEquals(file.absolutePath, audio!!.content)
        assertEquals(DeliveryStatus.Sent, audio.deliveryStatus)
        assertTrue(messages.none { it.content.contains("no active mesh route") })
    }

    @Test
    fun `oversize file over the Nostr cap posts the mesh-route failure and sends nothing`() {
        eligible = true

        manager.sendVoiceNote(contactID, null, bigFile.absolutePath)

        assertTrue(recorded.isEmpty())
        val messages = state.privateChats.value[contactID].orEmpty()
        assertEquals(1, messages.size)
        assertTrue(messages.single().content.contains("no active mesh route"))
        assertTrue(messages.none { it.type == BitchatMessageType.Audio })
    }
}
