package com.shtong.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

private val Ink = Color(0xFF182321)
private val Teal = Color(0xFF0C625C)
private val TealDark = Color(0xFF083E3A)
private val Mint = Color(0xFFD8EEE9)
private val Paper = Color(0xFFF4F7F5)
private val Gold = Color(0xFF9B6A19)

private val LightColors = lightColorScheme(
    primary = Teal, onPrimary = Color.White,
    primaryContainer = Mint, onPrimaryContainer = TealDark,
    secondary = Color(0xFF445B56), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E9E6), onSecondaryContainer = Ink,
    tertiary = Gold, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFEBC3), onTertiaryContainer = Color(0xFF3B2908),
    background = Paper, onBackground = Ink,
    surface = Color(0xFFFCFDFC), onSurface = Ink,
    surfaceVariant = Color(0xFFE8EEEB), onSurfaceVariant = Color(0xFF52615D),
    outline = Color(0xFFB5C2BE), outlineVariant = Color(0xFFD7DFDC),
    error = Color(0xFFA72C2C), onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF72D5C9), onPrimary = Color(0xFF003733),
    primaryContainer = Color(0xFF174F4A), onPrimaryContainer = Color(0xFFC3F2EB),
    secondary = Color(0xFFB8CCC6), onSecondary = Color(0xFF243732),
    secondaryContainer = Color(0xFF344943), onSecondaryContainer = Color(0xFFD7E7E2),
    tertiary = Color(0xFFEAC27B), onTertiary = Color(0xFF412D08),
    tertiaryContainer = Color(0xFF5B4319), onTertiaryContainer = Color(0xFFFFE4AD),
    background = Color(0xFF111917), onBackground = Color(0xFFE7EFEC),
    surface = Color(0xFF17211F), onSurface = Color(0xFFE7EFEC),
    surfaceVariant = Color(0xFF293531), onSurfaceVariant = Color(0xFFBBC8C4),
    outline = Color(0xFF82928D), outlineVariant = Color(0xFF3D4A46),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
)

private val ShtongTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 32.sp, lineHeight = 39.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 25.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 27.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 23.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 17.sp),
)

private val ShtongShapes = Shapes(
    extraSmall = RoundedCornerShape(3.dp), small = RoundedCornerShape(5.dp),
    medium = RoundedCornerShape(8.dp), large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(8.dp),
)

@Composable
fun ShtongTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ShtongTypography,
        shapes = ShtongShapes,
        content = content,
    )
}
