package com.omvcompanion.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B7BBA),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCEE6F5),
    onPrimaryContainer = Color(0xFF161F26),
    background = Color(0xFFF5F5F5),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE7E9EA),
    surfaceContainer = Color(0xFFEEF0F2),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFDCE0E4),
    surfaceContainerHighest = Color(0xFFC3C7CA),
    surfaceContainerLowest = Color.White,
    onSurfaceVariant = Color(0xFF536373),
    onSurface = Color(0xFF161F26),
    outline = Color(0xFF708090),
    outlineVariant = Color(0xFFC3C7CA),
    secondary = Color(0xFF3B95CF),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE6F5),
    onSecondaryContainer = Color(0xFF161F26),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF85C3EC),
    onPrimary = Color(0xFF161F26),
    primaryContainer = Color(0xFF29363F),
    onPrimaryContainer = Color(0xFFB9DFF8),
    background = Color(0xFF161F26),
    surface = Color(0xFF222E36),
    surfaceVariant = Color(0xFF36454F),
    surfaceContainer = Color(0xFF29363F),
    surfaceContainerLow = Color(0xFF222E36),
    surfaceContainerHigh = Color(0xFF303E48),
    surfaceContainerHighest = Color(0xFF36454F),
    surfaceContainerLowest = Color(0xFF10171C),
    onSurfaceVariant = Color(0xFFB8C0C8),
    onSurface = Color(0xFFEEF0F2),
    outline = Color(0xFF8593A1),
    outlineVariant = Color(0xFF546169),
    secondary = Color(0xFF5DACDF),
    onSecondary = Color(0xFF161F26),
    secondaryContainer = Color(0xFF29363F),
    onSecondaryContainer = Color(0xFFB9DFF8),
    error = Color(0xFFFFB4AB),
)

/** OMV corporate blue and charcoal palette, with expressive geometry.
 * Source: github.com/openmediavault/openmediavault, workbench/scss/defaults/_colors.scss. */
@Composable
fun OmvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
            headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
            titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 27.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 24.sp),
        ),
        shapes = Shapes(extraSmall = RoundedCornerShape(12.dp), small = RoundedCornerShape(20.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(26.dp), extraLarge = RoundedCornerShape(32.dp)),
        content = content,
    )
}
