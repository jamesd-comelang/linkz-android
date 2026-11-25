package com.bitchat.android.features.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioDurationCacheTest {

    @Test
    fun `seconds round to whole seconds with a half-second rule`() {
        assertEquals(1, AudioDurationCache.secondsFromMillis(0))
        assertEquals(1, AudioDurationCache.secondsFromMillis(900))
        assertEquals(3, AudioDurationCache.secondsFromMillis(2500))
        assertEquals(60, AudioDurationCache.secondsFromMillis(59999))
    }
}
