package com.bitchat.android.model

import java.util.UUID

/**
 * Generic chunked transfer over Nostr gift wraps.
 *
 * One gift-wrapped DM per chunk, so no single relay event gets large —
 * the same pattern profile sync uses for PROFILE_RESPONSE_CHUNK
 * ([ProfileSync]), but with a caller-chosen cap on the reassembled
 * payload instead of the profile photo cap. The wire format is identical
 * (transferId(16) + index(2) + total(2) + totalBytes(4) + chunk, all
 * integers big-endian), so the two chunk streams stay distinguishable
 * only by their [NoisePayloadType].
 *
 * Pure JVM: no Android APIs, so this runs in unit tests and is compiled
 * into the wear module with the rest of `model/`.
 */
object ChunkedPayload {

    /** Largest single chunk sent over Nostr (binary bytes; base64 adds ~33% on the wire). */
    const val MAX_CHUNK_BYTES: Int = 12_288

    data class Chunk(
        val transferId: UUID,
        val index: Int,
        val total: Int,
        val totalBytes: Int,
        val bytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Chunk) return false
            return transferId == other.transferId && index == other.index &&
                total == other.total && totalBytes == other.totalBytes &&
                bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int {
            var result = transferId.hashCode()
            result = 31 * result + index
            result = 31 * result + total
            result = 31 * result + totalBytes
            result = 31 * result + bytes.contentHashCode()
            return result
        }
    }

    fun encodeChunk(
        transferId: UUID,
        index: Int,
        total: Int,
        totalBytes: Int,
        chunk: ByteArray,
        maxTotalBytes: Int
    ): ByteArray? {
        if (index < 0 || total <= 0 || index >= total) return null
        if (totalBytes < 0 || totalBytes > maxTotalBytes) return null
        if (chunk.isEmpty() || chunk.size > MAX_CHUNK_BYTES) return null
        val out = ByteArray(16 + 2 + 2 + 4 + chunk.size)
        var o = 0
        val msb = transferId.mostSignificantBits
        val lsb = transferId.leastSignificantBits
        for (i in 0 until 8) out[o++] = (msb ushr (56 - 8 * i)).toByte()
        for (i in 0 until 8) out[o++] = (lsb ushr (56 - 8 * i)).toByte()
        out[o++] = (index ushr 8).toByte(); out[o++] = index.toByte()
        out[o++] = (total ushr 8).toByte(); out[o++] = total.toByte()
        out[o++] = (totalBytes ushr 24).toByte()
        out[o++] = (totalBytes ushr 16).toByte()
        out[o++] = (totalBytes ushr 8).toByte()
        out[o++] = totalBytes.toByte()
        chunk.copyInto(out, o)
        return out
    }

    fun decodeChunk(data: ByteArray, maxTotalBytes: Int): Chunk? {
        val reader = ByteReader(data)
        val idBytes = reader.readBytes(16) ?: return null
        var msb = 0L
        var lsb = 0L
        for (i in 0 until 8) msb = (msb shl 8) or (idBytes[i].toLong() and 0xFF)
        for (i in 8 until 16) lsb = (lsb shl 8) or (idBytes[i].toLong() and 0xFF)
        val index = reader.readShortAsInt() ?: return null
        val total = reader.readShortAsInt() ?: return null
        val totalBytes = reader.readInt() ?: return null
        if (index < 0 || total <= 0 || index >= total) return null
        if (totalBytes < 0 || totalBytes > maxTotalBytes) return null
        val chunk = reader.readRemaining() ?: return null
        if (chunk.isEmpty() || chunk.size > MAX_CHUNK_BYTES) return null
        return Chunk(UUID(msb, lsb), index, total, totalBytes, chunk)
    }

    /** Split payload bytes into chunk payloads for the Nostr path. Empty or oversized input yields no chunks. */
    fun splitIntoChunks(transferId: UUID, payload: ByteArray, maxTotalBytes: Int): List<ByteArray> {
        if (payload.isEmpty() || payload.size > maxTotalBytes) return emptyList()
        val total = (payload.size + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES
        val out = ArrayList<ByteArray>(total)
        for (index in 0 until total) {
            val start = index * MAX_CHUNK_BYTES
            val end = minOf(start + MAX_CHUNK_BYTES, payload.size)
            val chunk = encodeChunk(
                transferId, index, total, payload.size,
                payload.copyOfRange(start, end), maxTotalBytes
            ) ?: return emptyList()
            out.add(chunk)
        }
        return out
    }

    /**
     * Reassembles chunked payload bytes. Returns the complete payload
     * once every chunk has arrived, or null while still incomplete. Any
     * inconsistency (mismatched totals, oversized data, duplicate index
     * with different bytes, unknown transfer) is rejected by dropping the
     * buffer. Out-of-order and duplicate (identical) chunks are tolerated.
     */
    class Reassembly {
        private data class Buffer(
            val total: Int,
            val totalBytes: Int,
            val chunks: MutableMap<Int, ByteArray>
        )

        private val buffers = mutableMapOf<UUID, Buffer>()

        /** Feed one decoded chunk; returns the full payload bytes when complete, else null. */
        fun add(chunk: Chunk): ByteArray? {
            val buffer = buffers.getOrPut(chunk.transferId) {
                Buffer(chunk.total, chunk.totalBytes, mutableMapOf())
            }
            if (buffer.total != chunk.total || buffer.totalBytes != chunk.totalBytes) {
                buffers.remove(chunk.transferId)
                return null
            }
            val existing = buffer.chunks[chunk.index]
            if (existing != null && !existing.contentEquals(chunk.bytes)) {
                buffers.remove(chunk.transferId)
                return null
            }
            buffer.chunks[chunk.index] = chunk.bytes
            if (buffer.chunks.size != buffer.total) return null
            var size = 0
            for (i in 0 until buffer.total) {
                val bytes = buffer.chunks[i] ?: run { buffers.remove(chunk.transferId); return null }
                size += bytes.size
            }
            if (size != buffer.totalBytes) {
                buffers.remove(chunk.transferId)
                return null
            }
            val out = ByteArray(size)
            var o = 0
            for (i in 0 until buffer.total) {
                val bytes = buffer.chunks[i]!!
                bytes.copyInto(out, o)
                o += bytes.size
            }
            buffers.remove(chunk.transferId)
            return out
        }

        fun clear() = buffers.clear()
    }

    /** Minimal fail-closed big-endian reader. Any overrun returns null. */
    private class ByteReader(private val data: ByteArray) {
        private var offset = 0

        fun readShortAsInt(): Int? {
            if (offset + 2 > data.size) return null
            val value = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
            offset += 2
            return value
        }

        fun readInt(): Int? {
            if (offset + 4 > data.size) return null
            var value = 0
            for (i in 0 until 4) value = (value shl 8) or (data[offset++].toInt() and 0xFF)
            return value
        }

        fun readBytes(length: Int): ByteArray? {
            if (length < 0 || offset + length > data.size) return null
            val out = data.copyOfRange(offset, offset + length)
            offset += length
            return out
        }

        fun readRemaining(): ByteArray? {
            if (offset > data.size) return null
            val out = data.copyOfRange(offset, data.size)
            offset = data.size
            return out
        }
    }
}
