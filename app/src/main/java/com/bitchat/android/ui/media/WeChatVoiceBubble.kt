package com.bitchat.android.ui.media

import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * WeChat-style voice bubble, replacing the old play-button-plus-waveform player.
 *
 * WeChat shows a voice clip as a plain bar, not a media player: a speaker icon and the
 * duration in seconds, nothing else. The bar's width grows with the clip length, and the
 * order mirrors by side — incoming is [icon][seconds], outgoing is [seconds][icon] — with
 * the speaker wedge at the bubble's outer edge in both cases. Tapping the bar plays or
 * pauses; while playing, the speaker's arcs animate, the way WeChat's do.
 *
 * Recording is capped at 60 seconds (WeChat's limit), so a 60-second clip is the widest
 * this bar ever gets.
 */

/** Bubble width in dp for a clip of [seconds]: 90dp floor, +3dp per second, 210dp cap. */
internal fun voiceBubbleWidthDp(seconds: Int): Int =
    (90 + seconds * 3).coerceAtMost(210).coerceAtLeast(90)

/** Duration label for a clip of [durationMs], e.g. `3"`. Empty until the file is prepared. */
internal fun voiceDurationText(durationMs: Int): String {
    if (durationMs <= 0) return ""
    val seconds = ((durationMs + 500) / 1000).coerceAtLeast(1)
    return "$seconds\""
}

/**
 * WeChat's speaker glyph: a filled wedge with two sound arcs, drawn in a 24x24 space
 * (same geometry as [WeChatVoiceGlyph], the input-bar toggle). [flipped] mirrors it for
 * outgoing bubbles, where the wedge sits at the right edge. While [playing], the arcs
 * cycle 1-2-1-2 so the icon "speaks".
 */
@Composable
fun VoiceSpeakerIcon(
    playing: Boolean,
    flipped: Boolean,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    var phase by remember { mutableStateOf(0) }
    LaunchedEffect(playing) {
        if (!playing) return@LaunchedEffect
        while (true) {
            delay(300)
            phase = (phase + 1) % 2
        }
    }
    val arcs = if (playing) phase + 1 else 2
    val flip = if (flipped) Modifier.graphicsLayer(scaleX = -1f) else Modifier
    Canvas(modifier = modifier.then(flip).size(20.dp)) {
        val s = size.minDimension / 24f
        scale(s) {
            val wedge = Path().apply {
                moveTo(7.8f, 9.0f)
                lineTo(7.8f, 15.0f)
                lineTo(11.6f, 12.0f)
                close()
            }
            drawPath(wedge, tint)
            val stroke = Stroke(width = 1.8f, cap = StrokeCap.Round)
            if (arcs >= 1) {
                val arc1 = Path().apply {
                    moveTo(13.8f, 9.0f)
                    cubicTo(15.67f, 11.0f, 15.67f, 13.0f, 13.8f, 15.0f)
                }
                drawPath(arc1, tint, style = stroke)
            }
            if (arcs >= 2) {
                val arc2 = Path().apply {
                    moveTo(16.2f, 6.6f)
                    cubicTo(18.73f, 10.2f, 18.73f, 13.8f, 16.2f, 17.4f)
                }
                drawPath(arc2, tint, style = stroke)
            }
        }
    }
}

@Composable
fun WeChatVoiceBubble(
    path: String,
    isSelf: Boolean,
    progressOverride: Float? = null,
    progressColor: Color? = null,
    modifier: Modifier = Modifier,
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var durationMs by remember { mutableStateOf(0) }
    val player = remember { MediaPlayer() }

    LaunchedEffect(path) {
        isPrepared = false
        isError = false
        isPlaying = false
        durationMs = 0
        try {
            player.reset()
            player.setOnPreparedListener {
                isPrepared = true
                durationMs = try { player.duration } catch (_: Exception) { 0 }
            }
            player.setOnCompletionListener { isPlaying = false }
            player.setOnErrorListener { _, _, _ ->
                isError = true
                isPlaying = false
                true
            }
            player.setDataSource(path)
            player.prepareAsync()
        } catch (_: Exception) {
            isError = true
        }
    }

    LaunchedEffect(isPlaying, isPrepared) {
        try {
            if (isPlaying && isPrepared) player.start() else if (isPrepared && player.isPlaying) player.pause()
        } catch (_: Exception) {}
    }
    DisposableEffect(Unit) { onDispose { try { player.release() } catch (_: Exception) {} } }

    val seconds = ((durationMs + 500) / 1000).coerceAtLeast(0)
    val controlsEnabled = isPrepared && !isError && progressOverride == null
    // Blue while the clip is still sending, like the old player; theme text colour otherwise.
    val tint = progressColor ?: MaterialTheme.colorScheme.onSurface
    Row(
        modifier = modifier
            .width(voiceBubbleWidthDp(seconds).dp)
            .height(30.dp)
            .clickable(enabled = controlsEnabled) { isPlaying = !isPlaying },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(
            8.dp,
            if (isSelf) Alignment.End else Alignment.Start,
        ),
    ) {
        if (isSelf) {
            Text(
                text = voiceDurationText(durationMs),
                fontFamily = FontFamily.Default,
                fontSize = 15.sp,
                color = tint,
            )
            VoiceSpeakerIcon(
                playing = isPlaying,
                flipped = true,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
        } else {
            VoiceSpeakerIcon(
                playing = isPlaying,
                flipped = false,
                tint = tint,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = voiceDurationText(durationMs),
                fontFamily = FontFamily.Default,
                fontSize = 15.sp,
                color = tint,
            )
        }
    }
}
