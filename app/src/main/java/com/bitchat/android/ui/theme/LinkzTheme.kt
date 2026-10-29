package com.bitchat.android.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowInsetsController
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView

/**
 * Linkz design tokens, WeChat-style.
 *
 * These are original values inspired by the familiar WeChat look (green
 * accent, light-grey chat background, white rows), not copies of WeChat
 * artwork, icons, or trademarks. Only the Material color scheme changes
 * here; mesh, protocol, and encryption code is untouched.
 */
object LinkzColors {
    /** The Linkz accent, used for the selected tab, own bubbles, and actions. */
    val AccentGreen = Color(0xFF07C160)

    /** Chat-list background in light mode. */
    val ChatBackgroundLight = Color(0xFFEDEDED)

    /** Chat-list background in dark mode. */
    val ChatBackgroundDark = Color(0xFF111111)

    /** Hairline between list rows. */
    val RowDividerLight = Color(0xFFE5E5E5)
    val RowDividerDark = Color(0xFF2A2A2A)

    /** Secondary text on both themes. */
    val SecondaryTextLight = Color(0xFF888888)
    val SecondaryTextDark = Color(0xFF999999)
}

internal val LightLinkzColorScheme = lightColorScheme(
    primary = LinkzColors.AccentGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2F0DD),
    onPrimaryContainer = Color(0xFF0A3212),
    secondary = Color(0xFF576B95),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8F0),
    onSecondaryContainer = Color(0xFF1E293B),
    background = LinkzColors.ChatBackgroundLight,
    onBackground = Color(0xFF000000),
    surface = Color.White,
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF7F7F7),
    onSurfaceVariant = LinkzColors.SecondaryTextLight,
    outline = Color(0xFFD9D9D9),
    outlineVariant = LinkzColors.RowDividerLight,
    error = Color(0xFFFA5151),
    onError = Color.White
)

internal val DarkLinkzColorScheme = darkColorScheme(
    primary = LinkzColors.AccentGreen,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF163D1D),
    onPrimaryContainer = Color(0xFFB8F5C1),
    secondary = Color(0xFF8EA6D8),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1E293B),
    onSecondaryContainer = Color(0xFFD6E9FF),
    background = LinkzColors.ChatBackgroundDark,
    onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF1E1E1E),
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2A2A2A),
    onSurfaceVariant = LinkzColors.SecondaryTextDark,
    outline = Color(0xFF3A3A3A),
    outlineVariant = LinkzColors.RowDividerDark,
    error = Color(0xFFFF453A),
    onError = Color.Black
)

/** Fill and border for one chat bubble, WeChat-style. */
data class LinkzBubbleStyle(val fill: Color, val border: Color)

/**
 * WeChat-style bubble colours, shared by text and media bubbles so the
 * transcript stays consistent:
 * - Own messages: light green in light mode, darker green in dark mode.
 * - Incoming messages: the theme surface (white / dark grey).
 * Body text stays `onSurface` in both cases, so links (secondary) and
 * sender names (peer colours) stay legible. Dark mode is detected from
 * the actual background luminance, not the system setting, because the
 * theme preference can override the system.
 */
fun linkzBubbleStyle(isSelf: Boolean, colorScheme: ColorScheme): LinkzBubbleStyle {
    val isDark = colorScheme.background.luminance() < 0.5f
    return if (isSelf) {
        val fill = if (isDark) Color(0xFF2E7D32) else Color(0xFF95EC69)
        LinkzBubbleStyle(fill = fill, border = fill)
    } else {
        LinkzBubbleStyle(fill = colorScheme.surface, border = colorScheme.outlineVariant)
    }
}

/**
 * The Linkz app theme. Same structure as [BitchatTheme] (system / light /
 * dark preference from [ThemePreferenceManager], status-bar handling, and
 * the shared [LocalBitchatPalette] for app-specific tokens), with the
 * WeChat-style Linkz color scheme instead of the Bitchat one.
 */
@Composable
fun LinkzTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val themePref by ThemePreferenceManager.themeFlow.collectAsState(initial = ThemePreference.System)
    val shouldUseDark = when (darkTheme) {
        true -> true
        false -> false
        null -> when (themePref) {
            ThemePreference.Dark -> true
            ThemePreference.Light -> false
            ThemePreference.System -> isSystemInDarkTheme()
        }
    }

    val colorScheme = if (shouldUseDark) DarkLinkzColorScheme else LightLinkzColorScheme
    val palette = if (shouldUseDark) DarkBitchatPalette else LightBitchatPalette

    val view = LocalView.current
    SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.setSystemBarsAppearance(
                    if (!shouldUseDark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0,
                    WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                )
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = if (!shouldUseDark) {
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                } else 0
            }
            window.navigationBarColor = colorScheme.background.toArgb()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
            }
        }
    }

    CompositionLocalProvider(LocalBitchatPalette provides palette) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
