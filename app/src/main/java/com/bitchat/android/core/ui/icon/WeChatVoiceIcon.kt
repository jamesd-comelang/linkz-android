package com.bitchat.android.core.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * WeChat-style voice-input glyph: a small filled wedge with two sound arcs
 * radiating right, drawn from the voice toggle in the user's WeChat chat
 * screenshot (2026-11-23). The toggle draws its own circle outline around
 * this glyph (see MessageInput), so the vector holds the glyph only — no
 * outer circle — and the Icon tint drives the colour.
 */
val WeChatVoiceGlyph: ImageVector
    get() {
        _WeChatVoiceGlyph?.let { return it }

        return ImageVector.Builder(
            name = "WeChatVoiceGlyph",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            // Wedge (play-like triangle), filled.
            path(fill = SolidColor(Color.Black)) {
                moveTo(7.8f, 9.0f)
                lineTo(7.8f, 15.0f)
                lineTo(11.6f, 12.0f)
                close()
            }
            // Inner arc. A quadratic arc, degree-elevated to the cubic
            // curveTo the vector path DSL actually has (PathBuilder has no
            // quadratic method): C1 = P0 + 2/3(Q - P0), C2 = P3 + 2/3(Q - P3).
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(13.8f, 9.0f)
                curveTo(15.67f, 11.0f, 15.67f, 13.0f, 13.8f, 15.0f)
            }
            // Outer arc, same conversion.
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
            ) {
                moveTo(16.2f, 6.6f)
                curveTo(18.73f, 10.2f, 18.73f, 13.8f, 16.2f, 17.4f)
            }
        }.build().also { _WeChatVoiceGlyph = it }
    }

private var _WeChatVoiceGlyph: ImageVector? = null
