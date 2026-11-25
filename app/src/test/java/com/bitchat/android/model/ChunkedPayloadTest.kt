package com.bitchat.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * JVM tests for the generic Nostr chunking codec (ChunkedPayload): the
 * same transferId/index/total/totalBytes/chunk wire format profile sync
 * uses, with a caller-chosen cap on the reassembled payload.
 * No Android APIs are used, so these run on the JVM in CI.
 */
class ChunkedPayloadTest {

    private val transferId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000")
    private val maxTotal = 1_048_576

    private fun samplePayload(size: Int = 1024): ByteArray =
        ByteArray(size) { (it % 251).toByte() }

    // MARK: - Encode / decode round-trip

    @Test
    fun `single chunk round-trips`() {
        val payload = samplePayload(100)
        val encoded = ChunkedPayload.encodeChunk(transferId, 0, 1, payload.size, payload, maxTotal)!!
        val decoded = ChunkedPayload.decodeChunk(encoded, maxTotal)!!
        assertEquals(transferId, decoded.transferId)
        assertEquals(0, decoded.index)
        assertEquals(1, decoded.total)
        assertEquals(payload.size, decoded.totalBytes)
        assertArrayEquals(payload, decoded.bytes)
    }

    @Test
    fun `split and reassemble a multi-chunk payload`() {
        val payload = samplePayload(40_000)
        val chunks = ChunkedPayload.splitIntoChunks(transferId, payload, maxTotal)
        assertEquals(4, chunks.size) // 12288 * 3 = 36864, so 40000 needs 4
        val reassembly = ChunkedPayload.Reassembly()
        var completed: ByteArray? = null
        for (encoded in chunks) {
            completed = reassembly.add(ChunkedPayload.decodeChunk(encoded, maxTotal)!!)
        }
        assertArrayEquals(payload, completed)
    }

    @Test
    fun `out-of-order chunks reassemble`() {
        val payload = samplePayload(30_000)
        val chunks = ChunkedPayload.splitIntoChunks(transferId, payload, maxTotal)
        assertEquals(3, chunks.size)
        val decoded = chunks.map { ChunkedPayload.decodeChunk(it, maxTotal)!! }
        val reassembly = ChunkedPayload.Reassembly()
        assertNull(reassembly.add(decoded[2]))
        assertNull(reassembly.add(decoded[0]))
        assertArrayEquals(payload, reassembly.add(decoded[1]))
    }

    @Test
    fun `duplicate identical chunk is tolerated`() {
        val payload = samplePayload(30_000)
        val chunks = ChunkedPayload.splitIntoChunks(transferId, payload, maxTotal)
        val decoded = chunks.map { ChunkedPayload.decodeChunk(it, maxTotal)!! }
        val reassembly = ChunkedPayload.Reassembly()
        assertNull(reassembly.add(decoded[0]))
        // Same chunk again: no progress, no failure.
        assertNull(reassembly.add(decoded[0]))
        assertNull(reassembly.add(decoded[1]))
        assertArrayEquals(payload, reassembly.add(decoded[2]))
    }

    @Test
    fun `duplicate index with different bytes drops the buffer`() {
        val payload = samplePayload(30_000)
        val chunks = ChunkedPayload.splitIntoChunks(transferId, payload, maxTotal)
        val decoded = chunks.map { ChunkedPayload.decodeChunk(it, maxTotal)!! }
        val reassembly = ChunkedPayload.Reassembly()
        assertNull(reassembly.add(decoded[0]))
        val conflicting = decoded[0].copy(bytes = ByteArray(12_288) { 7 })
        assertNull(reassembly.add(conflicting))
        // The buffer was dropped, so the remaining chunks start a fresh,
        // incomplete buffer and never complete without chunk 0.
        assertNull(reassembly.add(decoded[1]))
        assertNull(reassembly.add(decoded[2]))
        // A fresh complete set still reassembles.
        val fresh = ChunkedPayload.Reassembly()
        var completed: ByteArray? = null
        for (chunk in decoded) completed = fresh.add(chunk)
        assertArrayEquals(payload, completed)
    }

    // MARK: - Fail-closed validation

    @Test
    fun `empty and oversized payloads split to nothing`() {
        assertTrue(ChunkedPayload.splitIntoChunks(transferId, ByteArray(0), maxTotal).isEmpty())
        assertTrue(
            ChunkedPayload.splitIntoChunks(transferId, ByteArray(maxTotal + 1), maxTotal).isEmpty()
        )
    }

    @Test
    fun `malformed chunks decode to null`() {
        // Truncated header.
        assertNull(ChunkedPayload.decodeChunk(ByteArray(10), maxTotal))
        // Index out of range.
        val badIndex = ChunkedPayload.encodeChunk(transferId, 0, 1, 10, ByteArray(10), maxTotal)!!
        badIndex[16] = 0
        badIndex[17] = 1 // index 1 of total 1
        assertNull(ChunkedPayload.decodeChunk(badIndex, maxTotal))
        // totalBytes over the cap: encodable under a larger cap, but the
        // decode cap rejects it.
        val overCap = ChunkedPayload.encodeChunk(
            transferId, 0, 1, maxTotal + 1, ByteArray(10), maxTotal * 2
        )!!
        assertNull(ChunkedPayload.decodeChunk(overCap, maxTotal))
        // Chunk body over the per-chunk cap.
        assertNull(
            ChunkedPayload.encodeChunk(
                transferId, 0, 1, ChunkedPayload.MAX_CHUNK_BYTES + 1,
                ByteArray(ChunkedPayload.MAX_CHUNK_BYTES + 1), maxTotal
            )
        )
    }

    @Test
    fun `mismatched totals across chunks drop the buffer`() {
        val payload = samplePayload(30_000)
        val chunks = ChunkedPayload.splitIntoChunks(transferId, payload, maxTotal)
        val decoded = chunks.map { ChunkedPayload.decodeChunk(it, maxTotal)!! }
        val reassembly = ChunkedPayload.Reassembly()
        assertNull(reassembly.add(decoded[0]))
        // Same transfer, different total: inconsistent, buffer dropped.
        val wrongTotal = decoded[1].copy(total = 99)
        assertNull(reassembly.add(wrongTotal))
        assertNull(reassembly.add(decoded[2]))
    }
}
