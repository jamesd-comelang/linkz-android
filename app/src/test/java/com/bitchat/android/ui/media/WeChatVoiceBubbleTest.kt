package com.bitchat.android.ui.media

import org.junit.Assert.assertEquals
import org.junit.Test

class WeChatVoiceBubbleTest {

    @Test
    fun `bubble width has a floor for short clips`() {
        assertEquals(90, voiceBubbleWidthDp(0))
        assertEquals(93, voiceBubbleWidthDp(1))
        assertEquals(99, voiceBubbleWidthDp(3))
    }

    @Test
    fun `bubble width grows with duration and caps at 60 seconds`() {
        assertEquals(153, voiceBubbleWidthDp(21))
        assertEquals(210, voiceBubbleWidthDp(40))
        assertEquals(210, voiceBubbleWidthDp(60))
        assertEquals(210, voiceBubbleWidthDp(120))
    }

    @Test
    fun `duration text rounds to whole seconds with a quote`() {
        assertEquals("", voiceDurationText(0))
        assertEquals("1\"", voiceDurationText(900))
        assertEquals("3\"", voiceDurationText(2500))
        assertEquals("31\"", voiceDurationText(30500))
        assertEquals("60\"", voiceDurationText(59999))
    }
}
