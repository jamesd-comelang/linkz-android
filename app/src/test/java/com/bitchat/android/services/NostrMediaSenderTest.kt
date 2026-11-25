package com.bitchat.android.services

import com.bitchat.android.model.BitchatFilePacket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric tests for the Nostr media sender (voice notes / images /
 * files to a mutual favorite when no mesh route exists): the gate, the
 * chunk encoding, and the paced chunk send. The real Nostr transport is
 * never touched — packets are recorded by the injected seam.
 */
@RunWith(RobolectricTestRunner::class)
class NostrMediaSenderTest {

    private val peerID = "8877665544332211"
    private val senderPeerID = "0011223344556677"

    private fun resolution(
        isMutualFavorite: Boolean,
        nostrPubkey: String?
    ) = ContactDirectory.ContactResolution(
        conversationID = "contact_" + "a".repeat(64),
        meshPeerID = null,
        noisePublicKey = ByteArray(32) { 1 },
        nostrPubkey = nostrPubkey,
        displayName = "Alice",
        isMutualFavorite = isMutualFavorite
    )

    private fun filePacketBytes(size: Int): ByteArray {
        val packet = BitchatFilePacket(
            fileName = "voice.m4a",
            fileSize = size.toLong(),
            mimeType = "audio/mp4",
            content = ByteArray(size) { (it % 251).toByte() }
        )
        return packet.encode()!!
    }

    private fun recordingSender(): Pair<NostrMediaSender, MutableList<Pair<String, String>>> {
        val recorded = mutableListOf<Pair<String, String>>()
        val sender = NostrMediaSender(
            sendPacket = { embedded, to -> recorded.add(embedded to to) },
            isEligible = { true }
        )
        return sender to recorded
    }

    // MARK: - Gate

    @Test
    fun `mutual favorite with npub is eligible`() {
        assertTrue(
            NostrMediaSender.isEligibleResolution(resolution(true, "npub1test"))
        )
    }

    @Test
    fun `mutual favorite without npub is not eligible`() {
        assertFalse(NostrMediaSender.isEligibleResolution(resolution(true, null)))
    }

    @Test
    fun `non-favorite with npub is not eligible`() {
        assertFalse(NostrMediaSender.isEligibleResolution(resolution(false, "npub1test")))
    }

    // MARK: - Encoding

    @Test
    fun `small payload encodes to one bitchat1 chunk`() {
        val (sender, _) = recordingSender()
        val payload = filePacketBytes(128)
        val chunks = sender.encodeChunks(peerID, senderPeerID, payload)!!
        assertEquals(1, chunks.size)
        assertTrue(chunks.single().startsWith("bitchat1:"))
    }

    @Test
    fun `payload spanning chunks encodes to the right count`() {
        val (sender, _) = recordingSender()
        // A 30,000-byte file packet encodes to a little over 30,000 bytes,
        // so it needs 3 chunks at 12,288 bytes each.
        val payload = filePacketBytes(30_000)
        val chunks = sender.encodeChunks(peerID, senderPeerID, payload)!!
        val expected = (payload.size + 12_287) / 12_288
        assertEquals(expected, chunks.size)
        assertTrue(chunks.all { it.startsWith("bitchat1:") })
    }

    @Test
    fun `oversized and empty payloads encode to null`() {
        val (sender, _) = recordingSender()
        assertNull(sender.encodeChunks(peerID, senderPeerID, ByteArray(NostrMediaSender.MAX_NOSTR_MEDIA_BYTES + 1)))
        assertNull(sender.encodeChunks(peerID, senderPeerID, ByteArray(0)))
    }

    @Test
    fun `blank peer ids encode to null`() {
        val (sender, _) = recordingSender()
        val payload = filePacketBytes(128)
        assertNull(sender.encodeChunks("", senderPeerID, payload))
        assertNull(sender.encodeChunks(peerID, "", payload))
    }

    // MARK: - Sending

    @Test
    fun `sendChunks delivers every chunk to the peer`() = runTest {
        val (sender, recorded) = recordingSender()
        val payload = filePacketBytes(30_000)
        val chunks = sender.encodeChunks(peerID, senderPeerID, payload)!!
        assertTrue(chunks.size > 1)

        sender.sendChunks(chunks, peerID)

        assertEquals(chunks.size, recorded.size)
        assertTrue(recorded.all { it.second == peerID })
        assertEquals(chunks, recorded.map { it.first })
    }
}
