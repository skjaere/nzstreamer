package io.skjaere.usenet.app.ui

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import io.skjaere.usenet.app.R

/** Design D: A's palette (dark, amber), B's density. Values from the mockups. */
@Immutable
data class AppColors(
    val background: Color = Color(0xFF0E0F12),
    val surface: Color = Color(0xFF1A1C21),
    val surfaceDim: Color = Color(0xFF16171B),
    val divider: Color = Color(0xFF24262C),
    val chip: Color = Color(0xFF2A2D34),
    val outline: Color = Color(0xFF3A3D44),
    val text: Color = Color(0xFFF3F1EC),
    val textSecondary: Color = Color(0xFFC9CBD1),
    val muted: Color = Color(0xFFA3A6AE),
    val chipText: Color = Color(0xFFE6E4DF),
    val accent: Color = Color(0xFFF2A33A),
    val onAccent: Color = Color(0xFF1A1206),
    val accentTint: Color = Color(0xFF3A2A12),
    val goodBg: Color = Color(0xFF1F3328),
    val good: Color = Color(0xFFA8E0A4),
    val goodDot: Color = Color(0xFF8FD18B),
    val warnBg: Color = Color(0xFF3A2412),
    val warn: Color = Color(0xFFFFC9A3),
    val warnStrong: Color = Color(0xFFFFB27A),
    val langBg: Color = Color(0xFF1E2A3D),
    val lang: Color = Color(0xFFA9C6F0),
    /** A selected row in select mode (DenseShow's current-episode row). */
    val rowSelected: Color = Color(0xFF22201B),
    /** The ring of an unselected mark (DenseShow's mark buttons). */
    val markOutline: Color = Color(0xFF5E626B),
    /** A secondary line on an element turned over by a TV's focus (on [text]'s cream). */
    val turnedSecondary: Color = Color(0xFF44474E),
    /** A warning mark on an element turned over by a TV's focus: 4.4:1 on [text]'s cream, where [warnStrong] has 1.6:1. */
    val turnedWarn: Color = Color(0xFFB35400),
    /** The accent's words and marks on an element turned over (an episode's ✓, what is left of it): 5.8:1 on the cream, where [accent] has 1.9:1. */
    val turnedAccent: Color = Color(0xFF8A5000),
)

val LocalAppColors = staticCompositionLocalOf { AppColors() }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

val Sora = FontFamily(variable(R.font.sora, 600), variable(R.font.sora, 700))
val PlexSans = FontFamily(variable(R.font.ibm_plex_sans, 400), variable(R.font.ibm_plex_sans, 500), variable(R.font.ibm_plex_sans, 600))
val PlexMono = FontFamily(Font(R.font.ibm_plex_mono, FontWeight.Normal))

/**
 * The app's text styles, by role. Each reads the form factor's [TypeScale] ([LocalTypeScale]), so a
 * screen shared by phones and TVs takes the TV's sizes there without asking.
 */
object AppType {
    val hero: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.hero
    val screenTitle: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.screenTitle
    val cardTitle: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.cardTitle
    val section: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.section
    val body: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.body
    val bodyStrong: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.bodyStrong
    val secondary: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.secondary
    val small: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.small
    val release: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.release
    val tag: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.tag
    val address: TextStyle @Composable @ReadOnlyComposable get() = LocalTypeScale.current.address
}

/**
 * The app's theme for [formFactor]: its type sizes and focus look, and [LocalFormFactor] for the
 * layouts. By default the device's, so the player's views and tests that don't say pick it too.
 */
@Composable
fun AppTheme(formFactor: FormFactor = FormFactor.of(LocalContext.current), content: @Composable () -> Unit) {
    val colors = AppColors()
    val scale = if (formFactor == FormFactor.Tv) TypeScale.Tv else TypeScale.Phone
    val scheme = darkColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentTint,
        onPrimaryContainer = colors.accent,
        background = colors.background,
        onBackground = colors.text,
        surface = colors.background,
        onSurface = colors.text,
        surfaceVariant = colors.surface,
        onSurfaceVariant = colors.muted,
        surfaceContainer = colors.surface,
        surfaceContainerHigh = colors.surface,
        surfaceContainerHighest = colors.chip,
        outline = colors.outline,
        outlineVariant = colors.divider,
        error = colors.warnStrong,
    )
    val type = Typography().let { t ->
        t.copy(
            bodyLarge = t.bodyLarge.copy(fontFamily = PlexSans),
            bodyMedium = t.bodyMedium.copy(fontFamily = PlexSans),
            bodySmall = t.bodySmall.copy(fontFamily = PlexSans),
            labelLarge = t.labelLarge.copy(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold),
            labelMedium = t.labelMedium.copy(fontFamily = PlexSans),
            titleLarge = scale.screenTitle,
            titleMedium = scale.cardTitle,
        )
    }
    val focus = if (formFactor == FormFactor.Tv) FocusStyle.Tv else FocusStyle.Phone
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAppColors provides colors,
        LocalFormFactor provides formFactor,
        LocalTypeScale provides scale,
        LocalFocusStyle provides focus,
        // On a TV material's own controls (icon and text buttons) show the focus in the accent, stronger than its default;
        // a phone keeps material's.
        LocalRippleConfiguration provides (focus.ripple?.let { RippleConfiguration(colors.accent, it) } ?: LocalRippleConfiguration.current),
    ) {
        MaterialTheme(colorScheme = scheme, typography = type, content = content)
    }
}

val colors: AppColors @Composable get() = LocalAppColors.current
