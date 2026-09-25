/*
 * This file is part of TufaraTV, a fork of OpenTV.
 * Copyright (C) 2026 The OpenTV Contributors
 * Licensed under the GNU General Public License v3.0 or later.
 */
package app.tufaratv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * A deliberately dark, Netflix-style black-and-red palette.
 *
 * This is a living-room app: it is looked at in a dark room, from three metres away, often
 * for hours. Bright surfaces and saturated accents that read well on a phone in daylight are
 * actively unpleasant on a 55" panel at night, so everything here is anchored near-black with
 * a single restrained accent — red, not the previous amber — used only for focus and selection.
 */
private val Accent = Color(0xFFE50914)
private val AccentDim = Color(0xFF7A0710)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFFFE5E7),
    secondary = Color(0xFFB3B3B3),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE5E5E5),
    surface = Color(0xFF141414),
    onSurface = Color(0xFFE5E5E5),
    surfaceVariant = Color(0xFF262626),
    onSurfaceVariant = Color(0xFFB3B3B3),
    outline = Color(0xFF3A3A3A),
    error = Color(0xFFFFA726),
    onError = Color(0xFF1A0F00),
)

/**
 * Light mode for anyone who wants it. By default TV boxes stay dark (a white living-room
 * screen at night is nobody's friend) — that default lives at the call site, so a user who
 * explicitly picks Light in settings gets it on any device.
 */
private val LightScheme = lightColorScheme(
    // A darker red keeps white focused text above AA contrast on a television. The old pale
    // primary container combined with mixed hard-coded artwork overlays and became unreadable.
    primary = Color(0xFFB00020),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB00020),
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF434751),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDDE1E8),
    onSecondaryContainer = Color(0xFF111318),
    background = Color(0xFFF3F4F6),
    onBackground = Color(0xFF101114),
    surface = Color.White,
    onSurface = Color(0xFF101114),
    surfaceVariant = Color(0xFFE1E4E9),
    onSurfaceVariant = Color(0xFF282C34),
    outline = Color(0xFF6B7280),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

/** Scaled up from the Material defaults: ten-foot viewing needs bigger text than a phone. */
private val OpenTvTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun TufaraTvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = OpenTvTypography,
        content = content,
    )
}
