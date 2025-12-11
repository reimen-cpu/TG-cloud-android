package com.telegram.cloud.ui.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * Transiciones de navegación personalizadas
 */

/**
 * Transición de entrada para pantallas
 */
fun slideInFromRight() = slideInHorizontally(
    initialOffsetX = { fullWidth -> fullWidth },
    animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
) + fadeIn(
    animationSpec = tween(300)
)

/**
 * Transición de salida para pantallas
 */
fun slideOutToLeft() = slideOutHorizontally(
    targetOffsetX = { fullWidth -> -fullWidth },
    animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
) + fadeOut(
    animationSpec = tween(300)
)

/**
 * Transición de entrada desde abajo (para diálogos modales)
 */
fun slideInFromBottom() = slideInVertically(
    initialOffsetY = { fullHeight -> fullHeight },
    animationSpec = tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)
) + fadeIn(
    animationSpec = tween(400)
)

/**
 * Transición de salida hacia abajo
 */
fun slideOutToBottom() = slideOutVertically(
    targetOffsetY = { fullHeight -> fullHeight },
    animationSpec = tween(400, easing = androidx.compose.animation.core.FastOutSlowInEasing)
) + fadeOut(
    animationSpec = tween(400)
)

/**
 * Transición de entrada desde arriba
 */
fun slideInFromTop() = slideInVertically(
    initialOffsetY = { fullHeight -> -fullHeight },
    animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
) + fadeIn(
    animationSpec = tween(300)
)

/**
 * Transición de salida hacia arriba
 */
fun slideOutToTop() = slideOutVertically(
    targetOffsetY = { fullHeight -> -fullHeight },
    animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
) + fadeOut(
    animationSpec = tween(300)
)

/**
 * Transición de escala para elementos compartidos
 */
fun scaleIn() = scaleIn(
    initialScale = 0.8f,
    animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
) + fadeIn(
    animationSpec = tween(300)
)

/**
 * Transición de escala de salida
 */
fun scaleOut() = scaleOut(
    targetScale = 0.8f,
    animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing)
) + fadeOut(
    animationSpec = tween(300)
)




