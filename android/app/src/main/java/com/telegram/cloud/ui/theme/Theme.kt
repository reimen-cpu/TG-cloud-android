package com.telegram.cloud.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val DarkColors = darkColorScheme(
    primary = Color(0xFF3390EC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1E5A9E),
    onPrimaryContainer = Color(0xFFE3F2FD),
    
    secondary = Color(0xFF42A5F5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1C2638),
    onSecondaryContainer = Color(0xFFE3F2FD),
    
    tertiary = Color(0xFFAB47BC),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF7B1FA2),
    onTertiaryContainer = Color(0xFFF3E5F5),
    
    error = Color(0xFFEF5350),
    onError = Color.White,
    errorContainer = Color(0xFFB71C1C),
    onErrorContainer = Color(0xFFFFEBEE),
    
    background = Color(0xFF121212),
    onBackground = Color(0xFFF0F0F0),
    
    surface = Color(0xFF1A1A1A),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF252525),
    onSurfaceVariant = Color(0xFFB0B0B0),
    
    outline = Color(0xFF3A3A3A),
    outlineVariant = Color(0xFF2A2A2A),
    
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF0F0F0),
    inverseOnSurface = Color(0xFF1A1A1A),
    inversePrimary = Color(0xFF3390EC),
    
    surfaceDim = Color(0xFF121212),
    surfaceBright = Color(0xFF2A2A2A),
    surfaceContainerLowest = Color(0xFF0D0D0D),
    surfaceContainerLow = Color(0xFF1A1A1A),
    surfaceContainer = Color(0xFF1F1F1F),
    surfaceContainerHigh = Color(0xFF2A2A2A),
    surfaceContainerHighest = Color(0xFF353535),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

@Composable
fun TelegramCloudTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = TelegramCloudTypography,
        shapes = AppShapes,
        content = content
    )
}


