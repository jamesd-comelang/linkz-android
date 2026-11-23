package com.bitchat.android.model

import java.security.MessageDigest
import java.util.UUID

/**
 * Profile sync (WeChat-style lazy avatar sync, adapted for a serverless mesh).
 *
 * WeChat stores the avatar on its server; a friend's client fetches it lazily
 * (when opening the chat/profile) and caches it locally — no real-time push,
 * and the cached copy is shown until a re-fetch happens. Linkz has no server,
 * so the peer itself is the source of truth: it advertises a cheap
 * (version, photo hash) pair, and peers fetch the full profile in the
 * background only when their cached copy is older. Nothing here ever blocks
 * or delays a chat message; a failed fetch just retries later.
 *
 * Wire format (all integers big-endian, all parsing fail-closed):
 *
 * - PROFILE_ANNOUNCE data: version(8) + hashLen(1) + hash + nicknameLen(1) + nickname
 * - PROFILE_REQUEST data: cachedVersion(8)
 * - PROFILE_RESPONSE data: version(8) + hashLen(1) + hash + nicknameLen(1) + nickname
 *   + photoLen(4) + photo JPEG bytes
 * - PROFILE_RESPONSE_CHUNK data: transferId(16) + index(2) + total(2) + totalBytes(4) + chunk
 *
 * The same RESPONSE bytes travel whole over BLE (the transport fragments
 * packets up to 256 fragments) and split into CHUNKs over Nostr (one gift-wrapped
 * DM per chunk, so no single relay event gets large). Both paths share the
 * [ProfileReassembly] + [decodeResponse] code below.
 *
 * Caps are deliberate: a 512px JPEG is far below [MAX_PHOTO_BYTES], and anything
 * larger or malformed is dropped, never stored.
 */
object ProfileSync {

    /** Largest photo accepted, in bytes. A 512px JPEG at quality 85 is well under this. */
    const val MAX_PHOTO_BYTES: Int = 262_144

    /** Largest single chunk sent over Nostr (binary bytes; base64 adds ~33% on the wire). */
    const val MAX_CHUNK_BYTES: Int = 12_288

    /** Largest reassembled RESPONSE payload accepted, in bytes. */
    const val MAX_RESPONSE_BYTES: Int = 300_000

    /** Largest nickname accepted in a profile payload, in UTF-8 bytes. */
    const val MAX_NICKNAME_BYTES: Int = 255

    const val PHOTO_HASH_BYTES: Int = 32

    data class ProfileAnnounce(
        val version: Long,
        val photoHashHex: String?,
        val nickname: String
    )

    data class ProfileResponse(
        val version: Long,
        val photoHashHex: String?,
        val nickname: String,
        val photoBytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProfileResponse) return false
            return version == other.version &&
                photoHashHex == other.photoHashHex &&
                nickname == other.nickname &&
                photoBytes.contentEquals(other.photoBytes)
        }

        override fun hashCode(): Int {
            var result = version.hashCode()
            result = 31 * result + (photoHashHex?.hashCode() ?: 0)
            result = 31 * result + nickname.hashCode()
            result = 31 * result + photoBytes.contentHashCode()
            return result
        }
    }

    data class ProfileChunk(
        val transferId: UUID,
        val index: Int,
        val total: Int,
        val totalBytes: Int,
        val bytes: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ProfileChunk) return false
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

    // MARK: - Hashing

    /** Lowercase hex SHA-256 of [bytes], or null on failure. JVM-safe (no Android APIs). */
    fun sha256Hex(bytes: ByteArray): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            digest.toHexString()
        } catch (_: Exception) {
            null
        }
    }

    fun ByteArray.toHexString(): String {
        val hex = CharArray(size * 2)
        val digits = "0123456789abcdef"
        for (i in indices) {
            val v = this[i].toInt() and 0xFF
            hex[i * 2] = digits[v ushr 4]
            hex[i * 2 + 1] = digits[v and 0x0F]
        }
        return String(hex)
    }

    fun String.hexToBytesOrNull(): ByteArray? {
        if (length % 2 != 0) return null
        if (any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return try {
            val out = ByteArray(length / 2)
            for (i in out.indices) {
                out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
            out
        } catch (_: Exception) {
            null
        }
    }

    // MARK: - Staleness

    /**
     * True when the advertised profile is newer than the cached copy.
     * A missing cache is always stale (fetch once). Equal versions with a
     * different hash also count as stale, so a same-version content change
     * still heals instead of sticking forever.
     */
    fun isNewerThan(
        cachedVersion: Long?,
        cachedHashHex: String?,
        version: Long,
        photoHashHex: String?
    ): Boolean {
        if (cachedVersion == null) return true
        if (version > cachedVersion) return true
        if (version == cachedVersion && photoHashHex != null && photoHashHex != cachedHashHex) return true
        return false
    }

    // MARK: - Announcement TLV values (BLE ANNOUNCE TLV 0x06 / 0x07)

    /** 8-byte big-endian version, the value of the PROFILE_VERSION announcement TLV. */
    fun encodeVersionTlvValue(version: Long): ByteArray {
        val out = ByteArray(8)
        for (i in 0 until 8) out[i] = (version ushr (56 - 8 * i)).toByte()
        return out
    }

    fun decodeVersionTlvValue(bytes: ByteArray): Long? {
        if (bytes.size != 8) return null
        var version = 0L
        for (b in bytes) version = (version shl 8) or (b.toLong() and 0xFF)
        return version
    }

    /** 32 raw SHA-256 bytes, the value of the PROFILE_PHOTO_HASH announcement TLV. */
    fun encodeHashTlvValue(photoHashHex: String): ByteArray? {
        val bytes = photoHashHex.hexToBytesOrNull() ?: return null
        if (bytes.size != PHOTO_HASH_BYTES) return null
        return bytes
    }

    fun decodeHashTlvValue(bytes: ByteArray): String? {
        if (bytes.size != PHOTO_HASH_BYTES) return null
        return bytes.toHexString()
    }

    // MARK: - ANNOUNCE / REQUEST / RESPONSE payloads

    fun encodeAnnounce(version: Long, photoHashHex: String?, nickname: String): ByteArray? {
        val nicknameBytes = nickname.toByteArray(Charsets.UTF_8)
        if (nicknameBytes.size > MAX_NICKNAME_BYTES) return null
        val hashBytes = photoHashHex?.let { encodeHashTlvValue(it) } ?: ByteArray(0)
        if (photoHashHex != null && hashBytes.isEmpty()) return null
        val out = ByteArray(8 + 1 + hashBytes.size + 1 + nicknameBytes.size)
        var o = 0
        encodeVersionTlvValue(version).copyInto(out, o); o += 8
        out[o++] = hashBytes.size.toByte()
        hashBytes.copyInto(out, o); o += hashBytes.size
        out[o++] = nicknameBytes.size.toByte()
        nicknameBytes.copyInto(out, o)
        return out
    }

    fun decodeAnnounce(data: ByteArray): ProfileAnnounce? {
        val reader = ByteReader(data)
        val version = reader.readLong() ?: return null
        val hashLen = reader.readByteAsInt() ?: return null
        if (hashLen != 0 && hashLen != PHOTO_HASH_BYTES) return null
        val hashBytes = reader.readBytes(hashLen) ?: return null
        val nicknameLen = reader.readByteAsInt() ?: return null
        val nicknameBytes = reader.readBytes(nicknameLen) ?: return null
        if (!reader.atEnd()) return null
        val nickname = try {
            String(nicknameBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        if (nickname.isEmpty()) return null
        return ProfileAnnounce(version, hashBytes.toHexString().takeIf { hashLen > 0 }, nickname)
    }

    fun encodeRequest(cachedVersion: Long): ByteArray = encodeVersionTlvValue(cachedVersion)

    fun decodeRequest(data: ByteArray): Long? = decodeVersionTlvValue(data)

    fun encodeResponse(
        version: Long,
        photoHashHex: String?,
        nickname: String,
        photoBytes: ByteArray
    ): ByteArray? {
        if (photoBytes.size > MAX_PHOTO_BYTES) return null
        val nicknameBytes = nickname.toByteArray(Charsets.UTF_8)
        if (nicknameBytes.size > MAX_NICKNAME_BYTES) return null
        val hashBytes = photoHashHex?.let { encodeHashTlvValue(it) } ?: ByteArray(0)
        if (photoHashHex != null && hashBytes.isEmpty()) return null
        // The hash must match the photo bytes, or the receiver cannot verify the payload.
        if (photoHashHex != null && sha256Hex(photoBytes) != photoHashHex) return null
        val out = ByteArray(8 + 1 + hashBytes.size + 1 + nicknameBytes.size + 4 + photoBytes.size)
        var o = 0
        encodeVersionTlvValue(version).copyInto(out, o); o += 8
        out[o++] = hashBytes.size.toByte()
        hashBytes.copyInto(out, o); o += hashBytes.size
        out[o++] = nicknameBytes.size.toByte()
        nicknameBytes.copyInto(out, o); o += nicknameBytes.size
        out[o++] = (photoBytes.size ushr 24).toByte()
        out[o++] = (photoBytes.size ushr 16).toByte()
        out[o++] = (photoBytes.size ushr 8).toByte()
        out[o++] = photoBytes.size.toByte()
        photoBytes.copyInto(out, o)
        return out
    }

    fun decodeResponse(data: ByteArray): ProfileResponse? {
        if (data.size > MAX_RESPONSE_BYTES) return null
        val reader = ByteReader(data)
        val version = reader.readLong() ?: return null
        val hashLen = reader.readByteAsInt() ?: return null
        if (hashLen != 0 && hashLen != PHOTO_HASH_BYTES) return null
        val hashBytes = reader.readBytes(hashLen) ?: return null
        val nicknameLen = reader.readByteAsInt() ?: return null
        val nicknameBytes = reader.readBytes(nicknameLen) ?: return null
        val photoLen = reader.readInt() ?: return null
        if (photoLen < 0 || photoLen > MAX_PHOTO_BYTES) return null
        val photoBytes = reader.readBytes(photoLen) ?: return null
        if (!reader.atEnd()) return null
        val nickname = try {
            String(nicknameBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            return null
        }
        if (nickname.isEmpty()) return null
        val photoHashHex = hashBytes.toHexString().takeIf { hashLen > 0 }
        // The photo must hash to the advertised hash, or the payload is corrupt/forged.
        if (photoHashHex != null && sha256Hex(photoBytes) != photoHashHex) return null
        return ProfileResponse(version, photoHashHex, nickname, photoBytes)
    }

    // MARK: - Chunking (Nostr path)

    fun encodeChunk(
        transferId: UUID,
        index: Int,
        total: Int,
        totalBytes: Int,
        chunk: ByteArray
    ): ByteArray? {
        if (index < 0 || total <= 0 || index >= total) return null
        if (totalBytes < 0 || totalBytes > MAX_RESPONSE_BYTES) return null
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

    fun decodeChunk(data: ByteArray): ProfileChunk? {
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
        if (totalBytes < 0 || totalBytes > MAX_RESPONSE_BYTES) return null
        val chunk = reader.readRemaining() ?: return null
        if (chunk.isEmpty() || chunk.size > MAX_CHUNK_BYTES) return null
        return ProfileChunk(UUID(msb, lsb), index, total, totalBytes, chunk)
    }

    /** Split RESPONSE bytes into chunk payloads for the Nostr path. Empty input yields no chunks. */
    fun splitIntoChunks(transferId: UUID, responseBytes: ByteArray): List<ByteArray> {
        if (responseBytes.isEmpty() || responseBytes.size > MAX_RESPONSE_BYTES) return emptyList()
        val total = (responseBytes.size + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES
        val out = ArrayList<ByteArray>(total)
        for (index in 0 until total) {
            val start = index * MAX_CHUNK_BYTES
            val end = minOf(start + MAX_CHUNK_BYTES, responseBytes.size)
            val chunk = encodeChunk(
                transferId, index, total, responseBytes.size,
                responseBytes.copyOfRange(start, end)
            ) ?: return emptyList()
            out.add(chunk)
        }
        return out
    }

    /**
     * Reassembles chunked RESPONSE bytes. Returns the complete RESPONSE payload
     * once every chunk has arrived, or null while still incomplete. Any
     * inconsistency (mismatched totals, oversized data, duplicate index with
     * different bytes, unknown transfer) is rejected by dropping the buffer.
     */
    class ProfileReassembly {
        private data class Buffer(
            val total: Int,
            val totalBytes: Int,
            val chunks: MutableMap<Int, ByteArray>
        )

        private val buffers = mutableMapOf<UUID, Buffer>()

        /** Feed one decoded chunk; returns the full RESPONSE bytes when complete, else null. */
        fun add(chunk: ProfileChunk): ByteArray? {
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

        fun atEnd(): Boolean = offset == data.size

        fun readByteAsInt(): Int? {
            if (offset + 1 > data.size) return null
            return data[offset++].toInt() and 0xFF
        }

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

        fun readLong(): Long? {
            if (offset + 8 > data.size) return null
            var value = 0L
            for (i in 0 until 8) value = (value shl 8) or (data[offset++].toLong() and 0xFF)
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
