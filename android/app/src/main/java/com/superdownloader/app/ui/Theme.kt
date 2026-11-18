package com.superdownloader.app.ui

import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext

val SdRed = Color(0xFFE53935)
val SdRedBright = Color(0xFFFF4B4B)
val SdRedDeep = Color(0xFFB71C1C)

/** Colours for individual connections in the piece map and segmented bar. */
val ConnColors = listOf(
    Color(0xFFFF4B4B), Color(0xFFFF8A65), Color(0xFFFFB74D), Color(0xFFFF6E91),
    Color(0xFFE57373), Color(0xFFFFD54F), Color(0xFFF06292), Color(0xFFFF7043),
)

val LocalReduceMotion = compositionLocalOf { false }

fun <T> motionSpec(reduce: Boolean, ms: Int = 300): AnimationSpec<T> = if (reduce) snap() else tween(ms)

private val DarkColors = darkColorScheme(
    primary = SdRedBright,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A0D0D),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFFF8A80),
    background = Color.Black,
    onBackground = Color(0xFFEDEDED),
    surface = Color.Black,
    onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF161616),
    onSurfaceVariant = Color(0xFFB5B5B5),
    surfaceContainer = Color(0xFF111111),
    surfaceContainerHigh = Color(0xFF181818),
    surfaceContainerHighest = Color(0xFF202020),
    surfaceContainerLow = Color(0xFF0B0B0B),
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF262626),
)

private val LightColors = lightColorScheme(
    primary = SdRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD6),
    onPrimaryContainer = Color(0xFF410002),
    secondary = SdRedDeep,
    background = Color(0xFFF7F7F8),
    onBackground = Color(0xFF111111),
    surface = Color(0xFFF7F7F8),
    onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFEDEDEF),
    onSurfaceVariant = Color(0xFF555555),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFF0F0F2),
    surfaceContainerLow = Color.White,
    outline = Color(0xFFCFCFD4),
    outlineVariant = Color(0xFFE3E3E7),
)

@Composable
fun SuperTheme(themeMode: Int, content: @Composable () -> Unit) {
    val dark = when (themeMode) { 1 -> false; 2 -> true; else -> isSystemInDarkTheme() }
    val ctx = LocalContext.current
    val reduce = remember {
        runCatching { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    CompositionLocalProvider(LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Typography(), content = content)
    }
}

fun categoryIcon(category: String): ImageVector = when (category) {
    "Video" -> Icons.Filled.Movie
    "Music" -> Icons.Filled.AudioFile
    "Documents" -> Icons.Filled.Description
    "Archives" -> Icons.Filled.Archive
    "Programs" -> Icons.Filled.Android
    "Images" -> Icons.Filled.Image
    else -> Icons.Filled.InsertDriveFile
}
