package dylan.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

data class DylanTokens(
    val primary: Color,
    val onPrimary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val error: Color,
    val gradientStop: Color,
)

// Nothing OS — dot-matrix brutalism: pure black, NDot mono, 1px #2E2E2E, red #FF3030 signal only
val LightTokens =
    DylanTokens(
        primary = Color(0xFFFF3030),
        onPrimary = Color(0xFFFFFFFF),
        background = Color(0xFF000000),
        surface = Color(0xFF111111),
        surfaceVariant = Color(0xFF1A1A1A),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFF8A8A8A),
        divider = Color(0xFF2E2E2E),
        error = Color(0xFFFF3030),
        gradientStop = Color(0xFF000000),
    )

val DarkTokens =
    DylanTokens(
        primary = Color(0xFFFF3030),
        onPrimary = Color(0xFFFFFFFF),
        background = Color(0xFF000000),
        surface = Color(0xFF111111),
        surfaceVariant = Color(0xFF1A1A1A),
        textPrimary = Color(0xFFFFFFFF),
        textSecondary = Color(0xFF8A8A8A),
        divider = Color(0xFF2E2E2E),
        error = Color(0xFFFF3030),
        gradientStop = Color(0xFF000000),
    )

val LocalDylanTokens = androidx.compose.runtime.staticCompositionLocalOf { LightTokens }

private fun toScheme(t: DylanTokens) =
    if (t == DarkTokens) {
        darkColorScheme(
            primary = t.primary,
            onPrimary = t.onPrimary,
            primaryContainer = t.primary.copy(alpha = 0.18f),
            onPrimaryContainer = t.textPrimary,
            secondary = t.textSecondary,
            onSecondary = t.surface,
            secondaryContainer = t.surfaceVariant,
            onSecondaryContainer = t.textPrimary,
            background = t.background,
            onBackground = t.textPrimary,
            surface = t.surface,
            onSurface = t.textPrimary,
            surfaceVariant = t.surfaceVariant,
            onSurfaceVariant = t.textSecondary,
            error = t.error,
            onError = t.onPrimary,
            outline = t.divider,
            outlineVariant = t.divider,
            scrim = Color.Black.copy(alpha = 0.4f),
        )
    } else {
        lightColorScheme(
            primary = t.primary,
            onPrimary = t.onPrimary,
            primaryContainer = t.primary.copy(alpha = 0.18f),
            onPrimaryContainer = t.textPrimary,
            secondary = t.textSecondary,
            onSecondary = t.surface,
            secondaryContainer = t.surfaceVariant,
            onSecondaryContainer = t.textPrimary,
            background = t.background,
            onBackground = t.textPrimary,
            surface = t.surface,
            onSurface = t.textPrimary,
            surfaceVariant = t.surfaceVariant,
            onSurfaceVariant = t.textSecondary,
            error = t.error,
            onError = t.onPrimary,
            outline = t.divider,
            outlineVariant = t.divider,
            scrim = Color.Black.copy(alpha = 0.4f),
        )
    }

private val DylTypography =
    Typography(
        // NDot substitute: Space Mono / JetBrains Mono via letterSpacing, uppercase where Nothing demands it
        displayLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.2.sp),
        displayMedium = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp),
        titleLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp),
        titleMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.1.sp),
        bodyLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.2.sp),
        bodyMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.15.sp),
        labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 1.4.sp),
    )

// Nothing geometry: 0px cards/surfaces, 6px buttons — no rounded artwork, no pill sheets
private val DylShapes =
    Shapes(
        extraSmall = RoundedCornerShape(0.dp),
        small = RoundedCornerShape(0.dp),
        medium = RoundedCornerShape(0.dp),
        large = RoundedCornerShape(6.dp),
        extraLarge = RoundedCornerShape(0.dp),
    )

@Composable
fun DylanTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val tokens = if (darkTheme) DarkTokens else LightTokens
    androidx.compose.runtime.CompositionLocalProvider(LocalDylanTokens provides tokens) {
        MaterialTheme(colorScheme = toScheme(tokens), typography = DylTypography, shapes = DylShapes, content = content)
    }
}
