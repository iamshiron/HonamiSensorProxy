package io.shiron.honamisensorproxy

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The "Amethyst" dark theme from shiron-ui (https://iamshiron.github.io/shiron-ui/),
 * mapped onto Material 3 color roles. Deep near-black backgrounds with a lavender primary
 * and soft-pink accent.
 */
private val AmethystDark = darkColorScheme(
    primary = Color(0xFFB79BEE),
    onPrimary = Color(0xFF1A1326),
    primaryContainer = Color(0xFF2A2140),
    onPrimaryContainer = Color(0xFFE4DCEF),
    secondary = Color(0xFF1C1726),
    onSecondary = Color(0xFFE4DCEF),
    secondaryContainer = Color(0xFF221B30),
    onSecondaryContainer = Color(0xFFE4DCEF),
    tertiary = Color(0xFFD28FC4),
    onTertiary = Color(0xFF1A1326),
    background = Color(0xFF0C0912),
    onBackground = Color(0xFFE4DCEF),
    surface = Color(0xFF14101B),
    onSurface = Color(0xFFE4DCEF),
    surfaceVariant = Color(0xFF171220),
    onSurfaceVariant = Color(0xFF9E93B4),
    outline = Color(0xFF3A3350),
    outlineVariant = Color(0xFF221B30),
    error = Color(0xFFE06E93),
    onError = Color(0xFF1A1326),
    scrim = Color(0xFF000000),
)

/** Non–Material 3 accent tokens from the Amethyst palette, for status text etc. */
object HspColors {
    val Success = Color(0xFF4FCFA4)
    val Warning = Color(0xFFE0B06B)
    val Info = Color(0xFF69B8D8)
    val AccentPink = Color(0xFFD28FC4)
    val InkDim = Color(0xFF9E93B4)
}

/** Wraps content in the Amethyst dark Material theme. */
@Composable
fun HspTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = AmethystDark) {
        // Surface sets the default content color (LocalContentColor) so unstyled Text is legible.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground,
            content = content,
        )
    }
}
