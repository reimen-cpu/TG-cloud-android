package com.telegram.cloud.ui.utils

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import android.view.HapticFeedbackConstants

/**
 * Tipos de feedback háptico
 */
enum class HapticFeedbackType {
    LIGHT_CLICK,      // Click ligero (botones normales)
    MEDIUM_CLICK,     // Click medio (acciones importantes)
    HEAVY_CLICK,      // Click fuerte (acciones críticas)
    SUCCESS,          // Éxito (completado)
    ERROR,            // Error
    SELECTION         // Selección (chips, switches)
}

/**
 * Obtiene el tipo de vibración según el tipo de feedback
 */
fun getHapticFeedbackConstant(type: HapticFeedbackType): Int {
    return when (type) {
        HapticFeedbackType.LIGHT_CLICK -> HapticFeedbackConstants.VIRTUAL_KEY
        HapticFeedbackType.MEDIUM_CLICK -> HapticFeedbackConstants.KEYBOARD_TAP
        HapticFeedbackType.HEAVY_CLICK -> HapticFeedbackConstants.LONG_PRESS
        HapticFeedbackType.SUCCESS -> HapticFeedbackConstants.CONFIRM
        HapticFeedbackType.ERROR -> HapticFeedbackConstants.REJECT
        HapticFeedbackType.SELECTION -> HapticFeedbackConstants.TEXT_HANDLE_MOVE
    }
}

/**
 * Modifier que agrega feedback háptico al hacer click
 * Nota: Esta función debe ser llamada desde un contexto @Composable
 */
@Composable
fun Modifier.hapticClick(
    type: HapticFeedbackType = HapticFeedbackType.LIGHT_CLICK,
    onClick: () -> Unit
): Modifier {
    val view = LocalView.current
    
    return this
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = rememberRipple(),
            onClick = {
                try {
                    val constant = getHapticFeedbackConstant(type)
                    view.performHapticFeedback(constant)
                } catch (e: Exception) {
                    // Ignorar errores de feedback háptico
                }
                onClick()
            }
        )
}


