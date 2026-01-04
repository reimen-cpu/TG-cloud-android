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
import androidx.compose.runtime.remember
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
import com.telegram.cloud.gallery.GalleryMediaEntity
import java.io.File
import java.util.concurrent.TimeUnit

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MediaThumbnail(
    media: GalleryMediaEntity,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val materialTheme = MaterialTheme.colorScheme
    
    // Check if local file exists
    val localFileExists = remember(media.localPath) {
        File(media.localPath).exists()
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
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // Thumbnail image - always use thumbnail if available (thumbnails are stored locally forever)
        // Fall back to local path only if thumbnail doesn't exist
        val thumbnailFile = media.thumbnailPath?.let { File(it) }
        val localFile = File(media.localPath)
        
        val imageSource = when {
            thumbnailFile?.exists() == true -> thumbnailFile
            localFile.exists() -> localFile
            else -> null // No image available
        }
        
        if (imageSource != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageSource)
                    .crossfade(true)
                    // Optimization: Use disk cache policy
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .apply {
                        // For videos involving local file (no thumbnail), we might need to extract frame
                        // But preferably we use the generated thumbnailPath which should be an image
                        if (media.isVideo && thumbnailFile?.exists() != true) {
                            decoderFactory { result, options, _ ->
                                VideoFrameDecoder(result.source, options)
                            }
                            // Optimize: Grab frame at 50% or at least 1s in to avoid black frames
                            // Using videoFrameMillis to request specific frame
                             val frameTime = if (media.durationMs > 0) media.durationMs / 2 else 1000L
                            videoFrameMillis(frameTime)
                        }
                    }
                    .build(),
                contentDescription = media.filename,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = painterResource(id = R.drawable.ic_launcher_foreground),
                fallback = painterResource(id = R.drawable.ic_launcher_foreground)
            )
        } else {
            // Placeholder when no thumbnail or local file exists
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (media.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                    contentDescription = null,
                    tint = materialTheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        
        // Overlay for missing local file (but synced)
        if (!localFileExists && media.isSynced) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = stringResource(R.string.download_from_cloud),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // Selection indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(materialTheme.primary.copy(alpha = 0.3f)),
                contentAlignment = Alignment.TopEnd
            ) {
                Box(
                    modifier = Modifier
                        .padding(6.dp)
                        .size(24.dp)
                        .background(
                            materialTheme.primary,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = materialTheme.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
        
        // Video duration badge
        if (media.isVideo && media.durationMs > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(
                        Color.Black.copy(alpha = 0.75f),
                        MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 5.dp, vertical = 3.dp)
            ) {
                Text(
                    text = formatDuration(media.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        // Sync status indicator (Cloud icon)
        // Show if synced AND NOT selected (selection overlay takes precedence)
        if (!isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
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
                     Icon(
                        if (media.isSynced) Icons.Default.Cloud else Icons.Default.CloudOff,
                        contentDescription = if (media.isSynced) stringResource(R.string.synced) else stringResource(R.string.not_synced),
                        tint = if (media.isSynced) materialTheme.primary else Color.White.copy(alpha = 0.7f),
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
