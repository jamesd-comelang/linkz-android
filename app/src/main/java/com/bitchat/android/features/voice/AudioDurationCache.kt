package com.bitchat.android.features.voice

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Voice-clip lengths for the Chats preview ("[Audio] 3\""). The clip file
 * itself carries the duration in its header, so a sent clip and a received
 * clip resolve the same way: one header-only read per path, cached forever.
 * The preview only ever needs the latest clip per conversation, so the
 * probe runs once per new voice message, never per row render.
 */
object AudioDurationCache {
    private val cache = ConcurrentHashMap<String, Int>()

    fun cachedSeconds(path: String): Int? = cache[path]

    suspend fun probeSeconds(path: String): Int? {
        cache[path]?.let { return it }
        val seconds = withContext(Dispatchers.IO) { readDurationSeconds(path) }
        if (seconds != null) cache[path] = seconds
        return seconds
    }

    /** Whole seconds, rounded the way the bubble label rounds: 2.5s -> 3". */
    internal fun secondsFromMillis(durationMs: Long): Int =
        ((durationMs + 500) / 1000).coerceAtLeast(1).toInt()

    internal fun readDurationSeconds(path: String): Int? {
        if (path.isBlank()) return null
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: return null
            secondsFromMillis(ms)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    internal fun putForTest(path: String, seconds: Int) {
        cache[path] = seconds
    }

    internal fun clearForTest() {
        cache.clear()
    }
}
