package com.telegram.cloud.gallery.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.telegram.cloud.R
import androidx.compose.ui.unit.IntSize
import com.telegram.cloud.data.local.CloudFileEntity
import com.telegram.cloud.utils.cache.OptimizedImageCache
import com.telegram.cloud.utils.performance.PerformanceMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    media: CloudFileEntity,
    isSelected: Boolean = false,
    downloadProgress: Float? = null,
    onClick: (CloudFileEntity) -> Unit,
    onLongClick: (CloudFileEntity) -> Unit,
    targetSize: IntSize // New parameter for target image size
) {
    val context = LocalContext.current
    val materialTheme = MaterialTheme.colorScheme
    val optimizedImageCache = remember { OptimizedImageCache(context) }
    
    var imageBitmap by remember(media.id, targetSize) { mutableStateOf<Bitmap?>(null) }
    val isVideo = remember(media.mimeType) { media.mimeType?.startsWith("video/") == true }

    LaunchedEffect(media.id, targetSize) {
        PerformanceMonitor.measureSuspendOperation("load_media_thumbnail_bitmap") {
            imageBitmap = optimizedImageCache.loadBitmapSafely(
                filePath = media.fileName, // Assuming fileName can be used as localPath for now, needs adjustment
                targetSize = targetSize
            )
        }
    }

    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .shadow(2.dp, shape = MaterialTheme.shapes.small)
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = materialTheme.primary,
                shape = MaterialTheme.shapes.small
            )
            .background(
                if (isSelected) materialTheme.primaryContainer.copy(alpha = 0.2f) else materialTheme.surfaceVariant
            )
            .combinedClickable(
                onClick = { onClick(media) },
                onLongClick = { onLongClick(media) }
            )
    ) {
        if (imageBitmap != null) {
            AsyncImage(
                model = imageBitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
             // Fallback icon
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                 Icon(
                     if (isVideo) Icons.Default.Videocam else Icons.Default.Image,
                     contentDescription = null,
                     tint = materialTheme.onSurfaceVariant.copy(alpha = 0.5f)
                 )
            }
        }
        
        // Video Duration & Type Overlay
        if (isVideo) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp),
                contentAlignment = Alignment.BottomStart
            ) {
                // Duration Badge
                Box(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    // CloudFileEntity does not have durationMs directly, need to derive or add
                    Text(
                        text = "--:--", // Placeholder for now
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        // Selection Overlay - Semi-transparent blue and checkmark
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(materialTheme.primary.copy(alpha = 0.3f)),
                contentAlignment = Alignment.TopEnd
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = materialTheme.primary,
                    modifier = Modifier
                        .padding(4.dp)
                        .size(24.dp)
                        .background(Color.White, CircleShape)
                )
            }
        }
        
        // Sync Status Icons (Top Right when not selected, or slightly offset)
        if (!isSelected) {
            Box(
                modifier = Modifier
            ) {
                 Box(
                    modifier = Modifier
                        .size(22.dp)
                        .background(
                            Color.Black.copy(alpha = 0.4f),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                 ) {
                     // CloudFileEntity does not have isSynced directly, need to derive or add
                     Icon(
                        Icons.Default.CloudOff, // Placeholder for now
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(14.dp)
                    )
                 }
            }
        }
    }
}

private fun formatDuration(durationMs: Long): String {
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs)
    return if (seconds >= 3600) {
        String.format("%d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    } else {
        String.format("%02d:%02d", (seconds % 3600) / 60, seconds % 60)
    }
}
