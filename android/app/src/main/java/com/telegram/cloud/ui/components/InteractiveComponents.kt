package com.telegram.cloud.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.telegram.cloud.ui.theme.Spacing
import com.telegram.cloud.ui.utils.HapticFeedbackType
import com.telegram.cloud.ui.utils.getHapticFeedbackConstant
import androidx.compose.ui.platform.LocalView

/**
 * IconButton con animación de escala al presionar
 */
@Composable
fun AnimatedIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    hapticType: HapticFeedbackType = HapticFeedbackType.LIGHT_CLICK
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val view = LocalView.current
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "icon_button_scale"
    )
    
    IconButton(
        onClick = {
            try {
                val constant = getHapticFeedbackConstant(hapticType)
                view.performHapticFeedback(constant)
            } catch (e: Exception) {
                // Ignorar errores de feedback háptico
            }
            onClick()
        },
        modifier = modifier.scale(scale),
        interactionSource = interactionSource
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint
        )
    }
}

/**
 * Botón con animación mejorada
 */
@Composable
fun AnimatedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType = HapticFeedbackType.MEDIUM_CLICK,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val view = LocalView.current
    
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.95f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "button_scale"
    )
    
    Button(
        onClick = {
            try {
                val constant = getHapticFeedbackConstant(hapticType)
                view.performHapticFeedback(constant)
            } catch (e: Exception) {
                // Ignorar errores de feedback háptico
            }
            onClick()
        },
        modifier = modifier.scale(scale),
        enabled = enabled,
        interactionSource = interactionSource
    ) {
        content()
    }
}

/**
 * Checkmark animado para completado
 */
@Composable
fun AnimatedCheckmark(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "checkmark")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 400,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "checkmark_scale"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = 300,
                easing = FastOutSlowInEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "checkmark_alpha"
    )
    
    Icon(
        imageVector = Icons.Default.CheckCircle,
        contentDescription = null,
        modifier = modifier,
        tint = color.copy(alpha = alpha),
        // Note: scale modifier would need to be applied separately if needed
    )
}

/**
 * Indicador de pulso para elementos activos
 */
@Composable
fun PulsingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
    
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Circle,
            contentDescription = null,
            tint = color.copy(alpha = alpha),
            modifier = Modifier.scale(scale)
        )
    }
}

