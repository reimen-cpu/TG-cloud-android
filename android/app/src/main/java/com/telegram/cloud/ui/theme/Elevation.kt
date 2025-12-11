package com.telegram.cloud.ui.theme

import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.unit.dp

/**
 * Sistema de elevación y sombras
 * Define niveles consistentes de profundidad visual
 */
object Elevation {
    val level0 = 0.dp
    val level1 = 2.dp
    val level2 = 4.dp
    val level3 = 8.dp
    val level4 = 16.dp
    val level5 = 24.dp
    
    // Sombras sutiles para componentes elevados
    val shadowSmall = Shadow(
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.1f),
        offset = androidx.compose.ui.geometry.Offset(0f, 2f),
        blurRadius = 4f
    )
    
    val shadowMedium = Shadow(
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.15f),
        offset = androidx.compose.ui.geometry.Offset(0f, 4f),
        blurRadius = 8f
    )
    
    val shadowLarge = Shadow(
        color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.2f),
        offset = androidx.compose.ui.geometry.Offset(0f, 8f),
        blurRadius = 16f
    )
}




