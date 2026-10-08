package dylan.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
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
//
// The palette: each ARGB word is named for the role it plays in the tokens below. The words are
// `const` and `Color(...)` is applied at each use rather than being folded into a
// `private val SignalRed = Color(0xFFFF3030)` (Material's spelling) because detekt's MagicNumber
// ships `ignorePropertyDeclaration: false`, so a literal in a property initializer is still a magic
// number — that spelling trades twenty findings for seven.
private const val SIGNAL_RED = 0xFFFF3030L
private const val PRIMARY_INK = 0xFFFFFFFFL
private const val VOID_BLACK = 0xFF000000L
private const val SURFACE_BLACK = 0xFF111111L
private const val SURFACE_VARIANT_BLACK = 0xFF1A1A1AL
private const val DIMMED_TEXT = 0xFF8A8A8AL
private const val HAIRLINE_DIVIDER = 0xFF2E2E2EL

val DarkTokens =
    DylanTokens(
        primary = Color(SIGNAL_RED),
        onPrimary = Color(PRIMARY_INK),
        background = Color(VOID_BLACK),
        surface = Color(SURFACE_BLACK),
        surfaceVariant = Color(SURFACE_VARIANT_BLACK),
        textPrimary = Color(PRIMARY_INK),
        textSecondary = Color(DIMMED_TEXT),
        divider = Color(HAIRLINE_DIVIDER),
        error = Color(SIGNAL_RED),
        gradientStop = Color(VOID_BLACK),
    )

// Light mode does not exist, and this alias is how that stays visible rather than accidental.
//
// It used to spell out the same ten fields a second time and stay switched off purely by luck:
// `toScheme` branched on `t == DarkTokens`, [DylanTokens] is a `data class`, so value equality took
// the dark branch for the light object too. Meanwhile `LocalDylanTokens` defaulted to *this*
// instance, so every reader got the dark palette while the theme flag claimed light.
//
// Aliasing makes the coupling unrepresentable instead of maintained: `LightTokens` cannot drift
// away from [DarkTokens] because it *is* [DarkTokens]. Rendering is unchanged — the ten duplicated
// fields held the values written above, so `LightTokens == DarkTokens` held for every reader, and
// the old `if (t == DarkTokens)` branch therefore took the dark path for both sets. Verified rather
// than argued: compiled against material3 1.4.0, all 98 roles of the old scheme compare equal to
// the scheme [toScheme] builds now, for `darkTheme` both true and false.
//
// To land light mode: give this its own ten values, restore the light branch in [toScheme], and do
// both in one commit. Read the note there first — `lightColorScheme` is not a token-only switch.
val LightTokens: DylanTokens = DarkTokens

// Default is [DarkTokens], not [LightTokens]: the dark palette is the only palette that exists, so
// defaulting to `LightTokens` was a lie — every `LocalDylanTokens.current` reader resolved dark
// values whatever the flag said. Behaviour-identical while the two are `==` (they are the same
// object now), and still the safe default if light mode lands: a reader composed outside
// `DylanTheme` gets dark, not an unimplemented light palette.
val LocalDylanTokens = staticCompositionLocalOf { DarkTokens }

// Dark scheme only, deliberately. The app has exactly one palette ([LightTokens] is an alias), so
// this is what every user sees, light flag or not.
//
// Do not re-add a `lightColorScheme` branch on the argument that "the tokens are passed in anyway":
// ten roles are overridden below, but a `ColorScheme` carries 98, and eleven of the rest flip with
// the baseline — `surfaceContainer` and its Low/Lowest/High/Highest siblings, `surfaceDim`,
// `surfaceBright`, `inverseSurface`, `inverseOnSurface`, `inversePrimary`. Those are light greys and
// a lavender tint, over a `#FF000000` background: a black app with grey sheets. (`surfaceTint` is
// the near miss — both baselines default it to `primary`, which is overridden here, so it survives.)
// Light mode needs real values for the un-overridden roles too: a design decision, not a code change.
private fun toScheme(t: DylanTokens) =
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
    // Flag-driven, not equality-driven: the theme flag picks the token set, and both sets are the
    // dark palette today ([LightTokens] is an alias), so `darkTheme` changes nothing on screen. It
    // is kept because it is the seam the light palette lands on.
    val tokens = if (darkTheme) DarkTokens else LightTokens
    CompositionLocalProvider(LocalDylanTokens provides tokens) {
        MaterialTheme(colorScheme = toScheme(tokens), typography = DylTypography, shapes = DylShapes, content = content)
    }
}
