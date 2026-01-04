package com.telegram.cloud.gallery

import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.rememberCoroutineScope
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.request.CachePolicy
import com.telegram.cloud.utils.MemoryManager
import com.telegram.cloud.utils.ErrorRecoveryManager
import android.util.Log
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Advanced zoomable image component with improved gesture handling
 * Supports:
 * - Pinch to zoom (2 fingers)
 * - Double tap to zoom
 * - Pan when zoomed
 * - Smooth boundaries
 * - Dynamic zoom limits based on image size
 */
@Composable
fun AdvancedZoomableImage(
    imagePath: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    minScale: Float = 1f,
    maxScale: Float = 5f,
    onTap: (() -> Unit)? = null,
    onDoubleTap: ((Offset) -> Unit)? = null
) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }
    var containerSize by remember { mutableStateOf<Size?>(null) }
    var imageSize by remember { mutableStateOf<Size?>(null) }
    var imageLoadState by remember { mutableStateOf<AsyncImagePainter.State?>(null) }
    
    val context = LocalContext.current
    
    // Calculate dynamic max scale based on image size
    val dynamicMaxScale = remember(imageSize, containerSize) {
        if (imageSize != null && containerSize != null) {
            val imgSize = imageSize!!
            val contSize = containerSize!!
            // Calculate scale needed to fill screen
            val scaleToFill = max(contSize.width / imgSize.width, contSize.height / imgSize.height)
            // Allow zoom up to 10x or 3x fill scale, whichever is larger
            max(maxScale, max(3f * scaleToFill, 5f))
        } else {
            maxScale
        }
    }
    
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        
        // Track retry attempts
        var retryTrigger by remember { mutableStateOf(0) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        
        // Calculate optimal size based on memory
        val optimalSize = remember(containerSize, retryTrigger) {
            if (containerSize != null) {
                // Get image dimensions if available
                val file = File(imagePath)
                if (file.exists()) {
                    try {
                        val options = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        android.graphics.BitmapFactory.decodeFile(imagePath, options)
                        
                        if (options.outWidth > 0 && options.outHeight > 0) {
                            MemoryManager.calculateOptimalImageSize(
                                context,
                                androidx.compose.ui.unit.IntSize(
                                    containerSize!!.width.toInt(),
                                    containerSize!!.height.toInt()
                                ),
                                options.outWidth,
                                options.outHeight
                            )
                        } else null
                    } catch (e: Exception) {
                        Log.e("ZoomableImage", "Error reading image dimensions", e)
                        null
                    }
                } else null
            } else null
        }
        
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(File(imagePath))
                .apply {
                    // Apply optimal size if calculated
                    if (optimalSize != null) {
                        size(optimalSize.width, optimalSize.height)
                        Log.d("ZoomableImage", "Loading image with size: ${optimalSize.width}x${optimalSize.height}")
                    }
                    
                    // Determine if hardware bitmap should be used
                    val useHardware = if (optimalSize != null) {
                        MemoryManager.shouldUseHardwareBitmap(
                            context,
                            optimalSize.width,
                            optimalSize.height
                        )
                    } else {
                        false // Default to software bitmap if size unknown
                    }
                    
                    allowHardware(useHardware)
                    
                    // Enable crossfade for smooth loading
                    crossfade(true)
                    
                    // Add error placeholder
                    error(android.R.drawable.ic_menu_report_image)
                    
                    // Memory cache policy
                    memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                }
                .listener(
                    onError = { request, result ->
                        Log.e("ZoomableImage", "Image load error: ${result.throwable.message}", result.throwable)
                        errorMessage = ErrorRecoveryManager.getUserFriendlyErrorMessage(result.throwable)
                        
                        // Handle memory-related errors
                        if (ErrorRecoveryManager.isMemoryRelatedError(result.throwable)) {
                            // Request GC and retry
                            MemoryManager.requestGCIfNeeded(context)
                            // Retry after a delay by incrementing trigger
                            retryTrigger++
                        }
                    },
                    onSuccess = { _, _ ->
                        errorMessage = null
                        Log.d("ZoomableImage", "Image loaded successfully")
                    }
                )
                .build(),
            contentDescription = contentDescription,
            onState = { state ->
                imageLoadState = state
                // Get image size when loaded
                if (state is AsyncImagePainter.State.Success) {
                    state.result?.drawable?.let { drawable ->
                        imageSize = Size(
                            drawable.intrinsicWidth.toFloat(),
                            drawable.intrinsicHeight.toFloat()
                        )
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { size ->
                    containerSize = Size(size.width.toFloat(), size.height.toFloat())
                }
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
                .pointerInput(scale, offsetX, offsetY, dynamicMaxScale) {
                    // Multi-touch gesture detection for pinch zoom
                    awaitPointerEventScope {
                        var currentScale = scale
                        var currentOffsetX = offsetX
                        var currentOffsetY = offsetY
                        
                        while (true) {
                            val event = awaitPointerEvent()
                            val pointers = event.changes.filter { it.pressed }
                            
                            when {
                                // Single pointer - pan gesture
                                pointers.size == 1 && currentScale > 1f -> {
                                    val change = pointers[0]
                                    val delta = change.position - (change.previousPosition ?: change.position)
                                    currentOffsetX += delta.x
                                    currentOffsetY += delta.y
                                    
                                    // Apply boundaries
                                    val bounds = calculateBounds(containerSize, imageSize, currentScale)
                                    currentOffsetX = currentOffsetX.coerceIn(-bounds.maxX, bounds.maxX)
                                    currentOffsetY = currentOffsetY.coerceIn(-bounds.maxY, bounds.maxY)
                                    
                                    offsetX = currentOffsetX
                                    offsetY = currentOffsetY
                                    change.consume()
                                }
                                
                                // Two pointers - pinch zoom
                                pointers.size == 2 -> {
                                    val pointer1 = pointers[0]
                                    val pointer2 = pointers[1]
                                    
                                    val centroid = (pointer1.position + pointer2.position) / 2f
                                    val distance = sqrt(
                                        (pointer1.position.x - pointer2.position.x).pow(2) +
                                        (pointer1.position.y - pointer2.position.y).pow(2)
                                    )
                                    val previousDistance = if (pointer1.previousPosition != null && pointer2.previousPosition != null) {
                                        sqrt(
                                            (pointer1.previousPosition!!.x - pointer2.previousPosition!!.x).pow(2) +
                                            (pointer1.previousPosition!!.y - pointer2.previousPosition!!.y).pow(2)
                                        )
                                    } else {
                                        distance
                                    }
                                    
                                    if (previousDistance > 0 && containerSize != null) {
                                        val zoomFactor = distance / previousDistance
                                        val newScale = (currentScale * zoomFactor).coerceIn(minScale, dynamicMaxScale)
                                        
                                        // Calculate zoom center relative to container center
                                        val contSize = containerSize!!
                                        val zoomCenterX = centroid.x - contSize.width / 2f
                                        val zoomCenterY = centroid.y - contSize.height / 2f
                                        
                                        // Adjust offset to zoom towards the center point
                                        val scaleChange = newScale / currentScale
                                        currentOffsetX = zoomCenterX - (zoomCenterX - currentOffsetX) * scaleChange
                                        currentOffsetY = zoomCenterY - (zoomCenterY - currentOffsetY) * scaleChange
                                        
                                        currentScale = newScale
                                        
                                        // Apply boundaries
                                        val bounds = calculateBounds(containerSize, imageSize, currentScale)
                                        currentOffsetX = currentOffsetX.coerceIn(-bounds.maxX, bounds.maxX)
                                        currentOffsetY = currentOffsetY.coerceIn(-bounds.maxY, bounds.maxY)
                                        
                                        scale = currentScale
                                        offsetX = currentOffsetX
                                        offsetY = currentOffsetY
                                    }
                                    
                                    pointers.forEach { it.consume() }
                                }
                            }
                        }
                    }
                }
                .pointerInput(scale, offsetX, offsetY, dynamicMaxScale, containerSize) {
                    // Double tap gesture
                    detectTapGestures(
                        onTap = { offset ->
                            onTap?.invoke()
                        },
                        onDoubleTap = { offset ->
                            if (onDoubleTap != null) {
                                onDoubleTap(offset)
                            } else {
                                // Default double tap behavior
                                if (scale > minScale) {
                                    // Reset zoom
                                    scale = minScale
                                    offsetX = 0f
                                    offsetY = 0f
                                } else if (containerSize != null) {
                                    // Zoom to 2x at tap point
                                    val targetScale = 2f.coerceIn(minScale, dynamicMaxScale)
                                    val contSize = containerSize!!
                                    val zoomCenterX = offset.x - contSize.width / 2f
                                    val zoomCenterY = offset.y - contSize.height / 2f
                                    
                                    scale = targetScale
                                    offsetX = -zoomCenterX * (targetScale - 1f)
                                    offsetY = -zoomCenterY * (targetScale - 1f)
                                    
                                    // Apply boundaries
                                    val bounds = calculateBounds(containerSize, imageSize, scale)
                                    offsetX = offsetX.coerceIn(-bounds.maxX, bounds.maxX)
                                    offsetY = offsetY.coerceIn(-bounds.maxY, bounds.maxY)
                                }
                            }
                        }
                    )
                }
                .pointerInput(scale, offsetX, offsetY, dynamicMaxScale, containerSize) {
                    // Transform gestures as fallback
                    detectTransformGestures(
                        onGesture = { centroid, pan, zoom, rotation ->
                            if (containerSize != null) {
                                val newScale = (scale * zoom).coerceIn(minScale, dynamicMaxScale)
                                val scaleChange = newScale / scale
                                
                                // Adjust offset based on zoom center
                                val contSize = containerSize!!
                                val zoomCenterX = centroid.x - contSize.width / 2f
                                val zoomCenterY = centroid.y - contSize.height / 2f
                                
                                var newOffsetX = zoomCenterX - (zoomCenterX - offsetX) * scaleChange
                                var newOffsetY = zoomCenterY - (zoomCenterY - offsetY) * scaleChange
                                
                                // Add pan when zoomed
                                if (newScale > 1f) {
                                    newOffsetX += pan.x
                                    newOffsetY += pan.y
                                }
                                
                                scale = newScale
                                
                                // Apply boundaries
                                val bounds = calculateBounds(containerSize, imageSize, scale)
                                offsetX = newOffsetX.coerceIn(-bounds.maxX, bounds.maxX)
                                offsetY = newOffsetY.coerceIn(-bounds.maxY, bounds.maxY)
                            }
                        }
                    )
                },
            contentScale = ContentScale.Fit
        )
        
        // Show loading indicator while image loads
        if (imageLoadState is AsyncImagePainter.State.Loading) {
            CircularProgressIndicator(
                color = Color(0xFF3390EC),
                modifier = Modifier.size(48.dp)
            )
        }
        
        // Show error message and retry button if loading failed
        if (imageLoadState is AsyncImagePainter.State.Error && errorMessage != null) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(16.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = androidx.compose.material.icons.Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFEF5350),
                    modifier = Modifier.size(48.dp)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.Text(
                    text = errorMessage!!,
                    color = Color.White,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    fontSize = 14.sp
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(16.dp))
                androidx.compose.material3.Button(
                    onClick = { retryTrigger++ },
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF3390EC)
                    )
                ) {
                    androidx.compose.material3.Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Refresh,
                        contentDescription = null
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(8.dp))
                    androidx.compose.material3.Text("Reintentar")
                }
            }
        }
    }
}

/**
 * Calculate pan boundaries based on image size and scale
 */
private data class Bounds(
    val maxX: Float,
    val maxY: Float
)

private fun calculateBounds(
    containerSize: Size?,
    imageSize: Size?,
    scale: Float
): Bounds {
    if (imageSize == null || containerSize == null || scale <= 1f) {
        return Bounds(0f, 0f)
    }
    
    // Calculate scaled image dimensions
    val scaledWidth = imageSize.width * scale
    val scaledHeight = imageSize.height * scale
    
    // Calculate how much the image extends beyond container
    val excessWidth = max(0f, scaledWidth - containerSize.width)
    val excessHeight = max(0f, scaledHeight - containerSize.height)
    
    return Bounds(
        maxX = excessWidth / 2f,
        maxY = excessHeight / 2f
    )
}

