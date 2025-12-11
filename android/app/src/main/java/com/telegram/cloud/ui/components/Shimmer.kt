package com.telegram.cloud.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.telegram.cloud.ui.theme.Radius

/**
 * Efecto shimmer para estados de carga
 */
@Composable
fun ShimmerEffect(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = Radius.md
) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    
    val shimmerTranslateAnim by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 1200,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer_translate"
    )
    
    val shimmerColors = listOf(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    )
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = shimmerColors,
                    start = Offset(shimmerTranslateAnim - 300f, shimmerTranslateAnim - 300f),
                    end = Offset(shimmerTranslateAnim, shimmerTranslateAnim)
                )
            )
    )
}

/**
 * Skeleton para FileCard
 */
@Composable
fun FileCardSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Icon skeleton
        ShimmerEffect(
            modifier = Modifier.size(40.dp),
            cornerRadius = Radius.sm
        )
        
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ShimmerEffect(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .height(16.dp),
                cornerRadius = Radius.xs
            )
            ShimmerEffect(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(12.dp),
                cornerRadius = Radius.xs
            )
        }
    }
}

/**
 * Skeleton para StatsCard
 */
@Composable
fun StatsCardSkeleton(
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(18.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        repeat(3) {
            Column(
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ShimmerEffect(
                    modifier = Modifier.size(38.dp),
                    cornerRadius = Radius.round
                )
                ShimmerEffect(
                    modifier = Modifier
                        .width(60.dp)
                        .height(17.dp),
                    cornerRadius = Radius.xs
                )
                ShimmerEffect(
                    modifier = Modifier
                        .width(40.dp)
                        .height(11.dp),
                    cornerRadius = Radius.xs
                )
            }
        }
    }
}




