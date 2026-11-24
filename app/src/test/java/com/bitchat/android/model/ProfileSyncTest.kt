package com.bitchat.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * JVM tests for the profile-sync wire format (ProfileSync): version /
 * staleness comparison, photo hashing, and chunked-response reassembly.
 * No Android APIs are used, so these run on the JVM in CI.
 */
class ProfileSyncTest {

    private fun samplePhoto(size: Int = 1024): ByteArray =
        ByteArray(size) { (it % 251).toByte() }

    // MARK: - Version / staleness

    @Test
    fun `missing cache is always stale`() {
        assertTrue(ProfileSync.isNewerThan(null, null, 1L, "aa"))
        assertTrue(ProfileSync.isNewerThan(null, null, 0L, null))
    }

    @Test
    fun `higher version is stale`() {
        assertTrue(ProfileSync.isNewerThan(1L, "aa", 2L, "bb"))
    }

    @Test
    fun `equal version with different photo hash is stale`() {
        assertTrue(ProfileSync.isNewerThan(3L, "aa", 3L, "bb"))
    }

    @Test
    fun `equal version with same photo hash is current`() {
        assertFalse(ProfileSync.isNewerThan(3L, "aa", 3L, "aa"))
        assertFalse(ProfileSync.isNewerThan(3L, null, 3L, null))
    }

    @Test
    fun `older version is never stale`() {
        assertFalse(ProfileSync.isNewerThan(5L, "aa", 4L, "bb"))
    }

    // MARK: - Hash

    @Test
    fun `sha256Hex matches the known empty-input vector`() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ProfileSync.sha256Hex(ByteArray(0))
        )
    }

    @Test
    fun `sha256Hex is stable and differs for different bytes`() {
        val a = ProfileSync.sha256Hex(byteArrayOf(1, 2, 3))
        assertEquals(a, ProfileSync.sha256Hex(byteArrayOf(1, 2, 3)))
        assertFalse(a == ProfileSync.sha256Hex(byteArrayOf(1, 2, 4)))
    }

    // MARK: - REQUEST

    @Test
    fun `request round trip preserves cached version`() {
        val encoded = ProfileSync.encodeRequest(7L)
        assertEquals(7L, ProfileSync.decodeRequest(encoded))
    }

    @Test
    fun `request decode rejects garbage and short input`() {
        assertNull(ProfileSync.decodeRequest(byteArrayOf(1, 2, 3)))
        assertNull(ProfileSync.decodeRequest(ByteArray(0)))
    }

    // MARK: - ANNOUNCE

    @Test
    fun `announce round trip preserves version hash and nickname`() {
        val hash = ProfileSync.sha256Hex(samplePhoto())
        val encoded = ProfileSync.encodeAnnounce(4L, hash, "Alice")!!
        val decoded = ProfileSync.decodeAnnounce(encoded)!!
        assertEquals(4L, decoded.version)
        assertEquals(hash, decoded.photoHashHex)
        assertEquals("Alice", decoded.nickname)
    }

    @Test
    fun `announce without photo round trips with null hash`() {
        val encoded = ProfileSync.encodeAnnounce(1L, null, "Bob")!!
        val decoded = ProfileSync.decodeAnnounce(encoded)!!
        assertEquals(1L, decoded.version)
        assertNull(decoded.photoHashHex)
        assertEquals("Bob", decoded.nickname)
    }

    @Test
    fun `announce decode rejects garbage`() {
        assertNull(ProfileSync.decodeAnnounce(byteArrayOf(9, 9, 9)))
        assertNull(ProfileSync.decodeAnnounce(ByteArray(0)))
    }

    // MARK: - RESPONSE

    @Test
    fun `response round trip preserves fields and photo bytes`() {
        val photo = samplePhoto()
        val hash = ProfileSync.sha256Hex(photo)
        val encoded = ProfileSync.encodeResponse(9L, hash, "Carol", photo)!!
        val decoded = ProfileSync.decodeResponse(encoded)!!
        assertEquals(9L, decoded.version)
        assertEquals(hash, decoded.photoHashHex)
        assertEquals("Carol", decoded.nickname)
        assertArrayEquals(photo, decoded.photoBytes)
    }

    @Test
    fun `response decode rejects a tampered photo hash`() {
        val photo = samplePhoto()
        val encoded = ProfileSync.encodeResponse(9L, ProfileSync.sha256Hex(photo), "Carol", photo)!!
        // Flip one photo byte: the stored hash no longer matches.
        encoded[encoded.size - 1] = (encoded[encoded.size - 1].toInt() xor 0xFF).toByte()
        assertNull(ProfileSync.decodeResponse(encoded))
    }

    @Test
    fun `response encode rejects an oversized photo`() {
        val huge = ByteArray(ProfileSync.MAX_PHOTO_BYTES + 1)
        assertNull(ProfileSync.encodeResponse(1L, ProfileSync.sha256Hex(huge), "X", huge))
    }

    @Test
    fun `response decode rejects garbage`() {
        assertNull(ProfileSync.decodeResponse(byteArrayOf(1, 2, 3)))
        assertNull(ProfileSync.decodeResponse(ByteArray(0)))
    }

    // MARK: - Chunking / reassembly

    @Test
    fun `chunk round trip reassembles in order`() {
        val photo = samplePhoto(40_000)
        val response = ProfileSync.encodeResponse(
            2L, ProfileSync.sha256Hex(photo), "Dave", photo
        )!!
        val transferId = UUID.randomUUID()
        val chunks = ProfileSync.splitIntoChunks(transferId, response)
        assertTrue(chunks.size > 1)

        val reassembly = ProfileSync.ProfileReassembly()
        var completed: ByteArray? = null
        for (chunkBytes in chunks) {
            val chunk = ProfileSync.decodeChunk(chunkBytes)!!
            completed = reassembly.add(chunk) ?: completed
        }
        assertArrayEquals(response, completed)
    }

    @Test
    fun `chunk round trip reassembles out of order`() {
        val photo = samplePhoto(40_000)
        val response = ProfileSync.encodeResponse(
            2L, ProfileSync.sha256Hex(photo), "Dave", photo
        )!!
        val transferId = UUID.randomUUID()
        val chunks = ProfileSync.splitIntoChunks(transferId, response).reversed()

        val reassembly = ProfileSync.ProfileReassembly()
        var completed: ByteArray? = null
        for (chunkBytes in chunks) {
            val chunk = ProfileSync.decodeChunk(chunkBytes)!!
            completed = reassembly.add(chunk) ?: completed
        }
        assertArrayEquals(response, completed)
    }

    @Test
    fun `reassembly ignores duplicate chunks and completes once`() {
        val photo = samplePhoto(30_000)
        val response = ProfileSync.encodeResponse(
            2L, ProfileSync.sha256Hex(photo), "Eve", photo
        )!!
        val transferId = UUID.randomUUID()
        val chunks = ProfileSync.splitIntoChunks(transferId, response)

        val reassembly = ProfileSync.ProfileReassembly()
        var completions = 0
        // Send the first chunk twice, then the rest once.
        for (chunkBytes in listOf(chunks[0]) + chunks) {
            val chunk = ProfileSync.decodeChunk(chunkBytes)!!
            if (reassembly.add(chunk) != null) completions++
        }
        assertEquals(1, completions)
    }

    @Test
    fun `reassembly rejects a chunk whose total disagrees`() {
        val photo = samplePhoto(30_000)
        val response = ProfileSync.encodeResponse(
            2L, ProfileSync.sha256Hex(photo), "Eve", photo
        )!!
        val transferId = UUID.randomUUID()
        val chunks = ProfileSync.splitIntoChunks(transferId, response)
        // Corrupt the totalChunks field of the second chunk.
        val tampered = chunks[1].copyOf()
        tampered[18] = (tampered[18].toInt() + 1).toByte()

        val reassembly = ProfileSync.ProfileReassembly()
        assertNull(reassembly.add(ProfileSync.decodeChunk(chunks[0])!!))
        assertNull(reassembly.add(ProfileSync.decodeChunk(tampered)!!))
    }

    @Test
    fun `reassembly rejects a second transfer while one is in progress`() {
        val photo = samplePhoto(30_000)
        val response = ProfileSync.encodeResponse(
            2L, ProfileSync.sha256Hex(photo), "Eve", photo
        )!!
        val chunksA = ProfileSync.splitIntoChunks(UUID.randomUUID(), response)
        val chunksB = ProfileSync.splitIntoChunks(UUID.randomUUID(), response)

        val reassembly = ProfileSync.ProfileReassembly()
        assertNull(reassembly.add(ProfileSync.decodeChunk(chunksA[0])!!))
        assertNull(reassembly.add(ProfileSync.decodeChunk(chunksB[0])!!))
    }

    @Test
    fun `chunk decode rejects garbage`() {
        assertNull(ProfileSync.decodeChunk(byteArrayOf(1, 2, 3)))
        assertNull(ProfileSync.decodeChunk(ByteArray(0)))
    }
}
