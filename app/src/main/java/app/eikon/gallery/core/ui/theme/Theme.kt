package app.eikon.gallery.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.eikon.gallery.core.ui.SystemBarIcons
import app.eikon.gallery.data.settings.ThemeMode

/**
 * Calm, photo-first palette: neutral surfaces that never compete with the pictures (true black in
 * dark mode, warm off-white in light mode) and one restrained blue for the few interactive accents.
 * No dynamic color on purpose: the tint of a wallpaper should not recolor a photo library.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF2B6CB0),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E6F7),
    onPrimaryContainer = Color(0xFF0B2A47),
    background = Color(0xFFFAFAF9),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFFAFAF9),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFE9E9E8),
    onSurfaceVariant = Color(0xFF5B5E63),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F5F4),
    surfaceContainer = Color(0xFFF0F0EF),
    surfaceContainerHigh = Color(0xFFE9E9E8),
    surfaceContainerHighest = Color(0xFFE2E2E0),
    outline = Color(0xFF8B8E93),
    outlineVariant = Color(0xFFD6D6D4),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CBBEA),
    onPrimary = Color(0xFF06294A),
    primaryContainer = Color(0xFF1E4468),
    onPrimaryContainer = Color(0xFFD6E6F7),
    background = Color(0xFF000000),
    onBackground = Color(0xFFE6E6E4),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFE6E6E4),
    surfaceVariant = Color(0xFF1E1F21),
    onSurfaceVariant = Color(0xFFA0A3A8),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0B0B0C),
    surfaceContainer = Color(0xFF141516),
    surfaceContainerHigh = Color(0xFF1E1F21),
    surfaceContainerHighest = Color(0xFF28292B),
    outline = Color(0xFF6E7176),
    outlineVariant = Color(0xFF2E3033),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

private val EikonTypography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Medium, fontSize = 22.sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.Medium),
    )
}

@Composable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun EikonTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = isDarkTheme(themeMode)
    val colors: ColorScheme = if (dark) DarkColors else LightColors
    // The in-app choice can differ from the system theme, so bar icons are set explicitly.
    SystemBarIcons(lightIcons = dark)
    MaterialTheme(colorScheme = colors, typography = EikonTypography, content = content)
}

/** Text style used for date headers in the library grid. */
val SectionHeaderStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
