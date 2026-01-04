@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.telegram.cloud.gallery

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import java.io.File
import com.telegram.cloud.utils.getUserVisibleDownloadsDir
import com.telegram.cloud.utils.MemoryManager
import com.telegram.cloud.utils.ErrorRecoveryManager
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.util.Log
import com.telegram.cloud.gallery.streaming.ChunkedVideoPlayer
import com.telegram.cloud.gallery.streaming.ChunkedStreamingManager
import com.telegram.cloud.gallery.streaming.StreamingVideoPlayer
import com.telegram.cloud.data.prefs.BotConfig
import com.telegram.cloud.utils.ChunkedStreamingRegistry
import com.telegram.cloud.utils.ResourceGuard

private const val TAG = "MediaViewerScreen"

/**
 * State for media download
 */
sealed class MediaDownloadState {
    object Idle : MediaDownloadState()
    object Checking : MediaDownloadState()
    data class Downloading(val progress: Float) : MediaDownloadState()
    data class Ready(val localPath: String) : MediaDownloadState()
    data class Error(val message: String) : MediaDownloadState()
}

sealed class MediaUploadState {
    object Idle : MediaUploadState()
    data class Uploading(val progress: Float) : MediaUploadState()
    object Completed : MediaUploadState()
    data class Error(val message: String) : MediaUploadState()
}

@Composable
fun MediaViewerScreen(
    initialMediaId: Long,
    mediaList: List<GalleryMediaEntity>,
    onBack: () -> Unit,
    onSync: (GalleryMediaEntity) -> Unit,
    onDownloadFromTelegram: (GalleryMediaEntity, (Float) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit,
    onSyncClick: ((Float) -> Unit)? = null,
    isSyncing: Boolean = false,
    currentSyncMediaId: Long? = null,
    uploadProgress: Float = 0f,
    config: com.telegram.cloud.data.prefs.BotConfig? = null,
    onFileDownloaded: ((Long, String) -> Unit)? = null,
    getStreamingManager: ((GalleryMediaEntity, com.telegram.cloud.data.prefs.BotConfig) -> com.telegram.cloud.gallery.streaming.ChunkedStreamingManager?)? = null
) {
    if (mediaList.isEmpty()) {
        onBack()
        return
    }

    val initialIndex = remember(initialMediaId, mediaList) {
        val index = mediaList.indexOfFirst { it.id == initialMediaId }
        if (index >= 0) index else 0
    }

    val pagerState = rememberPagerState(
        initialPage = initialIndex,
        pageCount = { mediaList.size }
    )

    HorizontalPager(
        state = pagerState,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        beyondBoundsPageCount = 1 // Preload adjacent pages
    ) { pageIndex ->
        val media = mediaList.getOrNull(pageIndex)
        if (media != null) {
            val isCurrentPageSyncing = isSyncing && (currentSyncMediaId == media.id)
            
            MediaViewerPage(
                media = media,
                onBack = onBack,
                onSync = { onSync(media) },
                onDownloadFromTelegram = onDownloadFromTelegram,
                onSyncClick = onSyncClick,
                isSyncing = isCurrentPageSyncing,
                uploadProgress = if (isCurrentPageSyncing) uploadProgress else 0f,
                config = config,
                onFileDownloaded = { path -> onFileDownloaded?.invoke(media.id, path) },
                getStreamingManager = getStreamingManager
            )
        }
    }
}

@Composable
fun MediaViewerPage(
    media: GalleryMediaEntity,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onDownloadFromTelegram: (GalleryMediaEntity, (Float) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit,
    onSyncClick: ((Float) -> Unit)? = null,
    isSyncing: Boolean = false,
    uploadProgress: Float = 0f,
    config: com.telegram.cloud.data.prefs.BotConfig? = null,
    onFileDownloaded: ((String) -> Unit)? = null,
    getStreamingManager: ((GalleryMediaEntity, com.telegram.cloud.data.prefs.BotConfig) -> com.telegram.cloud.gallery.streaming.ChunkedStreamingManager?)? = null
) {
    var showControls by remember { mutableStateOf(true) }
    
    var downloadState by remember { mutableStateOf<MediaDownloadState>(MediaDownloadState.Checking) }
    var uploadState by remember { mutableStateOf<MediaUploadState>(MediaUploadState.Idle) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Chunked streaming state
    var useChunkedStreaming by remember { mutableStateOf(false) }
    
    // Remember players only for this specific media item
    // Keying by media.id ensures we get fresh state if media changes (though in pager media is constant for this composable)
    val chunkedPlayer = remember(media.id) { 
        if (media.isVideo && media.isChunked) {
            ChunkedVideoPlayer(context)
        } else null
    }
    
    val streamingManager = remember(media.id, config) {
        if (media.isVideo && media.isChunked && config != null) {
            if (getStreamingManager != null) {
                getStreamingManager(media, config)
            } else {
                // Fallback for previews without global manager
                ChunkedStreamingManager(context).apply {
                    val fileId = media.telegramFileId ?: media.telegramFileUniqueId?.split(",")?.firstOrNull() ?: ""
                    val chunkFileIds = media.telegramFileUniqueId ?: ""
                    val uploaderTokens = media.telegramUploaderTokens ?: config.tokens.first()
                    initStreaming(fileId, chunkFileIds, uploaderTokens, config)
                }
            }
        } else null
    }
    
    // Update upload state when progress changes
    LaunchedEffect(uploadProgress, isSyncing) {
        if (isSyncing || uploadProgress > 0f) {
            // Active upload - update progress
            when {
                uploadProgress >= 1f -> {
                    uploadState = MediaUploadState.Uploading(1f)
                    kotlinx.coroutines.delay(500)
                    uploadState = MediaUploadState.Completed
                    kotlinx.coroutines.delay(1500)
                    uploadState = MediaUploadState.Idle
                }
                uploadProgress > 0f -> {
                    uploadState = MediaUploadState.Uploading(uploadProgress)
                }
                isSyncing -> {
                    // Just started syncing, keep showing progress at 0
                    uploadState = MediaUploadState.Uploading(0f)
                }
            }
        } else if (uploadState is MediaUploadState.Completed) {
            // Keep completed state until it transitions to Idle
        } else if (uploadState is MediaUploadState.Uploading) {
            // Upload was cancelled or failed - only reset if we were actually uploading
            // and sync is truly done (not just starting)
            uploadState = MediaUploadState.Idle
        }
    }
    
    // Release player when leaving the screen or changing media
    DisposableEffect(media.id) {
        onDispose {
            Log.d(TAG, "Disposing MediaViewerPage for ${media.filename}")
            chunkedPlayer?.release()
            // We DO NOT release streamingManager here because we want downloads to continue in background
            // The streamingManager will be cleaned up eventually or reused
        }
    }
    
    // Check if local file exists, if not and synced, download from Telegram or stream
    LaunchedEffect(media.id, config) { // Key by ID to ensure refresh on page change if needed
        try {
            // Log memory status before loading
            MemoryManager.logMemoryStatus(context, TAG)
            
            Log.d(TAG, "MediaViewer opened: ${media.filename}, isVideo=${media.isVideo}, isSynced=${media.isSynced}, isChunked=${media.isChunked}")
            val localFile = File(media.localPath)
            if (localFile.exists()) {
                Log.d(TAG, "Local file exists: ${media.localPath}, size=${localFile.length()}")
                downloadState = MediaDownloadState.Ready(media.localPath)
                useChunkedStreaming = false
            } else if (media.isSynced) {
                // Check if we have the necessary info to download/stream
                val hasFileId = if (media.isChunked) {
                    // For chunked: need telegramFileUniqueId (comma-separated file IDs)
                    media.telegramFileUniqueId != null && media.telegramFileUniqueId.isNotBlank()
                } else {
                    // For direct: need telegramFileId OR telegramMessageId (to attempt recovery)
                    media.telegramFileId != null && media.telegramFileId.isNotBlank()
                }
                
                if (hasFileId || media.telegramMessageId != null) {
                    // For chunked videos, use streaming instead of full download
                    if (media.isVideo && media.isChunked && config != null && streamingManager != null) {
                        // Check memory before starting streaming
                        if (MemoryManager.isCriticalMemory(context)) {
                            Log.w(TAG, "Critical memory - cannot start streaming")
                            downloadState = MediaDownloadState.Error("Memoria insuficiente para streaming. Libera espacio e intenta nuevamente.")
                        } else {
                            Log.d(TAG, "Initializing chunked streaming for ${media.filename}")
                            useChunkedStreaming = true
                            val fileId = media.telegramFileId ?: media.telegramFileUniqueId?.split(",")?.firstOrNull() ?: ""
                            val chunkFileIds = media.telegramFileUniqueId ?: ""
                            val uploaderTokens = media.telegramUploaderTokens ?: config.tokens.first()
                            
                            streamingManager.initStreaming(fileId, chunkFileIds, uploaderTokens, config)
                            
                            // Start streaming using custom data source that fetches chunks on demand
                            val totalChunks = streamingManager.getTotalChunks()
                            val chunkSize = streamingManager.getChunkSize()
                            
                            // Pre-download first chunk to ensure playback can start immediately
                            Log.d(TAG, "Pre-downloading first chunk for ${media.filename}")
                            streamingManager.getChunkStream(0) // This will trigger download
                            
                            // Wait a bit for first chunk to be available
                            delay(500)
                            
                            if (chunkedPlayer != null) {
                                // Use streamChunkedVideo which uses custom DataSource for progressive streaming
                                chunkedPlayer.streamChunkedVideo(
                                    getChunkStream = { chunkIndex ->
                                        Log.d(TAG, "Requesting chunk stream: $chunkIndex")
                                        streamingManager.getChunkStream(chunkIndex)
                                    },
                                    totalChunks = totalChunks,
                                    chunkSize = chunkSize
                                )
                                Log.d(TAG, "Started chunked streaming for ${media.filename}: $totalChunks chunks, ${chunkSize / 1024}KB each")
                            }
                            
                            downloadState = MediaDownloadState.Ready("") // Streaming, no local file yet
                        }
                    } else {
                        // Need to download from Telegram (non-chunked or non-video)
                        Log.d(TAG, "File not local, downloading from Telegram. fileId=${media.telegramFileId}, isChunked=${media.isChunked}, telegramFileUniqueId=${media.telegramFileUniqueId?.take(50)}")
                        downloadState = MediaDownloadState.Downloading(0f)
                        onDownloadFromTelegram(
                            media,
                            { progress -> 
                                Log.d(TAG, "Download progress: ${(progress * 100).toInt()}%")
                                downloadState = MediaDownloadState.Downloading(progress) 
                            },
                            { path -> 
                                Log.d(TAG, "Download complete: $path")
                                downloadState = MediaDownloadState.Ready(path)
                                // Notify that file was downloaded (to update database)
                                onFileDownloaded?.invoke(path)
                            },
                            { error -> 
                                Log.e(TAG, "Download error: $error")
                                downloadState = MediaDownloadState.Error(error) 
                            }
                        )
                    }
                } else {
                    Log.w(TAG, "File synced but missing fileId/messageId: localPath=${media.localPath}, isSynced=${media.isSynced}, fileId=${media.telegramFileId}, messageId=${media.telegramMessageId}")
                    downloadState = MediaDownloadState.Error("Archivo sincronizado pero falta información de descarga. Por favor vuelve a sincronizar este archivo.")
                }
            } else {
                Log.w(TAG, "File not available: localPath=${media.localPath}, isSynced=${media.isSynced}")
                downloadState = MediaDownloadState.Error("Archivo no disponible localmente y no sincronizado")
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "OutOfMemoryError in MediaViewer", e)
            downloadState = MediaDownloadState.Error("Memoria insuficiente. Cierra otras aplicaciones e intenta nuevamente.")
            MemoryManager.requestGCIfNeeded(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error in MediaViewer LaunchedEffect", e)
            val errorMsg = ErrorRecoveryManager.getUserFriendlyErrorMessage(e)
            downloadState = MediaDownloadState.Error(errorMsg)
        }
    }
    
    // Monitor when all chunks are downloaded and save the complete file
    LaunchedEffect(streamingManager, useChunkedStreaming) {
        if (useChunkedStreaming && streamingManager != null) {
            try {
                // Monitor chunk completion
                while (true) {
                    delay(1000) // Check every second
                    if (streamingManager.areAllChunksComplete()) {
                        Log.d(TAG, "All chunks downloaded for ${media.filename}, assembling file...")
                        
                        // Save to user-visible Telegram Cloud downloads directory
                        val downloadsDir = getUserVisibleDownloadsDir(context)
                        val outputFile = File(downloadsDir, media.filename)
                        
                        // Reassemble chunks into complete file
                        val success = streamingManager.reassembleFile(outputFile)
                        
                        if (success) {
                            Log.d(TAG, "File saved to Downloads: ${outputFile.absolutePath}")
                            // Update download state
                            downloadState = MediaDownloadState.Ready(outputFile.absolutePath)
                            
                            // Notify that file was downloaded (to update database)
                            onFileDownloaded?.invoke(outputFile.absolutePath)
                        } else {
                            Log.e(TAG, "Failed to reassemble file for ${media.filename}")
                            downloadState = MediaDownloadState.Error("Error al ensamblar el archivo")
                        }
                        
                        break // All chunks processed
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error monitoring chunk completion", e)
                downloadState = MediaDownloadState.Error(ErrorRecoveryManager.getUserFriendlyErrorMessage(e))
            }
        }
    }
    
    // Cleanup streaming manager on dispose (keep chunks if file was saved)
    DisposableEffect(streamingManager) {
        val manager = streamingManager
        if (manager != null) {
            ChunkedStreamingRegistry.register(manager)
            ResourceGuard.markActive(ResourceGuard.Feature.STREAMING)
        }
        onDispose {
            val keepChunks = useChunkedStreaming && manager?.areAllChunksComplete() == true
            manager?.release(keepChunks = keepChunks)
            ChunkedStreamingRegistry.unregister(manager)
            if (manager != null) {
                ResourceGuard.markIdle(ResourceGuard.Feature.STREAMING)
            }
        }
    }
    
    var verticalDragOffset by remember { mutableStateOf(0f) }
    var isDraggingDown by remember { mutableStateOf(false) }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        // If dragged down enough, dismiss
                        if (verticalDragOffset > 200.dp.toPx() && isDraggingDown) {
                            onBack()
                        }
                        verticalDragOffset = 0f
                        isDraggingDown = false
                    },
                    onVerticalDrag = { change, dragAmount ->
                        // Only allow downward drag
                        if (dragAmount > 0) {
                            isDraggingDown = true
                            verticalDragOffset += dragAmount
                            change.consume()
                        }
                    }
                )
            }
            .graphicsLayer {
                translationY = verticalDragOffset
                alpha = if (isDraggingDown && verticalDragOffset > 0) {
                    1f - (verticalDragOffset / 500.dp.toPx()).coerceIn(0f, 0.5f)
                } else {
                    1f
                }
            }
    ) {
        when (val state = downloadState) {
            is MediaDownloadState.Checking -> {
                // Show loading indicator
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color(0xFF3390EC)
                )
            }
            
            is MediaDownloadState.Downloading -> {
                // Show Telegram-style circular progress
                TelegramStyleProgress(
                    progress = state.progress,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            
            is MediaDownloadState.Ready -> {
                // Show media content
                if (media.isVideo) {
                    if (useChunkedStreaming && chunkedPlayer != null) {
                        // Use chunked video player for streaming
                        StreamingVideoPlayer(
                            player = chunkedPlayer,
                            modifier = Modifier.fillMaxSize(),
                            onBack = onBack
                        )
                    } else {
                        // Use regular video player for local files
                        VideoPlayer(
                            videoPath = state.localPath,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    AdvancedZoomableImage(
                        imagePath = state.localPath,
                        contentDescription = media.filename,
                        modifier = Modifier.fillMaxSize(),
                        minScale = 1f,
                        maxScale = 10f,
                        onTap = { showControls = !showControls },
                        onDoubleTap = null // Use default behavior
                    )
                }
            }
            
            is MediaDownloadState.Error -> {
                // Show error state
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = Color(0xFFEF5350),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        state.message,
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp
                    )
                    if (media.isSynced && media.telegramFileId != null) {
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick = {
                                downloadState = MediaDownloadState.Downloading(0f)
                                onDownloadFromTelegram(
                                    media,
                                    { progress -> downloadState = MediaDownloadState.Downloading(progress) },
                                    { path -> downloadState = MediaDownloadState.Ready(path) },
                                    { error -> downloadState = MediaDownloadState.Error(error) }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3390EC))
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry")
                        }
                    }
                }
            }
            
            MediaDownloadState.Idle -> {}
        }
        
        // Show upload progress overlay if uploading
        when (val uploadStateValue = uploadState) {
            is MediaUploadState.Uploading -> {
                // Show Telegram-style circular progress overlay
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    TelegramStyleProgress(
                        progress = uploadStateValue.progress,
                        modifier = Modifier.size(120.dp)
                    )
                }
            }
            is MediaUploadState.Completed -> {
                // Show brief success indicator
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    uploadState = MediaUploadState.Idle
                }
            }
            else -> {}
        }
        
        // Controls overlay
        if (showControls) {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            media.filename,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 16.sp
                        )
                        Text(
                            formatFileSize(media.sizeBytes),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                actions = {
                    if (isSyncing) {
                        // Show progress indicator while syncing
                        Box(
                            modifier = Modifier
                                .padding(end = 16.dp)
                                .size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = Color(0xFFFF9800)
                            )
                        }
                    } else if (media.isSynced) {
                        Icon(
                            Icons.Default.CloudDone,
                            contentDescription = "Synced",
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    } else {
                        IconButton(
                            onClick = { 
                                // Immediately set uploading state to prevent double-clicks
                                uploadState = MediaUploadState.Uploading(0f)
                                onSyncClick?.invoke(0f)
                                onSync()
                            },
                            enabled = uploadState !is MediaUploadState.Uploading
                        ) {
                            if (uploadState is MediaUploadState.Uploading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFFFF9800)
                                )
                            } else {
                                Icon(
                                    Icons.Default.CloudUpload,
                                    contentDescription = "Sync to Cloud",
                                    tint = Color(0xFFFF9800)
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                ),
                modifier = Modifier.align(Alignment.TopCenter)
            )
            
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
                color = Color.Black.copy(alpha = 0.7f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (media.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (media.isVideo) "Video" else "Image",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                        if (media.isVideo && media.durationMs > 0) {
                            Text(
                                " • ${formatDuration(media.durationMs)}",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 14.sp
                            )
                        }
                    }
                    
                    if (media.width > 0 && media.height > 0) {
                        Text(
                            "${media.width} × ${media.height}",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Telegram-style circular progress indicator with percentage
 */
@Composable
private fun TelegramStyleProgress(
    progress: Float,
    modifier: Modifier = Modifier
) {
    val telegramBlue = Color(0xFF3390EC)
    val trackColor = Color.White.copy(alpha = 0.2f)
    
    Box(
        modifier = modifier.size(80.dp),
        contentAlignment = Alignment.Center
    ) {
        // Background circle
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokeWidth = 4.dp.toPx()
            val radius = (size.minDimension - strokeWidth) / 2
            
            // Track
            drawCircle(
                color = trackColor,
                radius = radius,
                style = Stroke(width = strokeWidth)
            )
            
            // Progress arc (no animation, direct value)
            drawArc(
                color = telegramBlue,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(strokeWidth / 2, strokeWidth / 2),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
            )
        }
        
        // Percentage text
        Text(
            text = "${(progress * 100).toInt()}%",
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Advanced video player using ExoPlayer with streaming support
 */
@Composable
private fun VideoPlayer(
    videoPath: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    // Player state
    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableStateOf(Player.STATE_IDLE) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var bufferedPosition by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var playerError by remember { mutableStateOf<String?>(null) }
    
    // Create ExoPlayer instance with optimized configuration
    val exoPlayer = remember {
        // Configure LoadControl for memory-efficient buffering
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                15_000,  // Min buffer: 15 seconds
                30_000,  // Max buffer: 30 seconds  
                2_500,   // Playback buffer: 2.5 seconds
                5_000    // Playback after rebuffer: 5 seconds
            )
            .build()
        
        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = false
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }
    
    // Set media source
    LaunchedEffect(videoPath) {
        val file = File(videoPath)
        Log.d(TAG, "VideoPlayer: Setting up video path=$videoPath, exists=${file.exists()}, size=${file.length()}")
        if (file.exists()) {
            val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            Log.d(TAG, "VideoPlayer: Media item set and preparing")
        } else {
            Log.e(TAG, "VideoPlayer: File does not exist: $videoPath")
        }
    }
    
    // Listen to player events
    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val stateName = when(state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN($state)"
                }
                Log.d(TAG, "VideoPlayer: Playback state changed to $stateName")
                playbackState = state
            }
            override fun onIsPlayingChanged(playing: Boolean) {
                Log.d(TAG, "VideoPlayer: IsPlaying changed to $playing")
                isPlaying = playing
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "VideoPlayer: Player error", error)
                playerError = when (error.errorCode) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "Error de red. Verifica tu conexión."
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_DECODING_FAILED ->
                        "Error al decodificar el video. El formato puede no ser compatible."
                    else -> "Error al reproducir el video: ${error.message ?: "Error desconocido"}"
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            Log.d(TAG, "VideoPlayer: Releasing player")
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }
    
    // Update position periodically
    LaunchedEffect(isPlaying) {
        while (true) {
            currentPosition = exoPlayer.currentPosition
            duration = exoPlayer.duration.takeIf { it > 0 } ?: 0L
            bufferedPosition = exoPlayer.bufferedPosition
            delay(250)
        }
    }
    
    // Auto-hide controls
    LaunchedEffect(controlsVisible, isPlaying) {
        if (controlsVisible && isPlaying) {
            delay(3000)
            if (System.currentTimeMillis() - lastInteractionTime >= 3000) {
                controlsVisible = false
            }
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                controlsVisible = !controlsVisible
                lastInteractionTime = System.currentTimeMillis()
            }
    ) {
        // Video surface using PlayerView
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                view.player = exoPlayer
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Loading indicator
        if (playbackState == Player.STATE_BUFFERING) {
            CircularProgressIndicator(
                color = Color(0xFF3390EC),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(48.dp)
            )
        }
        
        // Error message
        playerError?.let { error ->
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFEF5350),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    color = Color.White,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = { 
                        playerError = null
                        exoPlayer.prepare()
                        exoPlayer.play()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF3390EC))
                ) {
                    Text("Reintentar")
                }
            }
        }
        
        // Play/Pause button (center)
        if (controlsVisible && !isPlaying && playbackState != Player.STATE_BUFFERING && playerError == null) {
            IconButton(
                onClick = { exoPlayer.play() },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(64.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
        
        // Custom Controls (bottom)
        if (controlsVisible && playerError == null) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(8.dp)
            ) {
                // Seek bar
                Slider(
                    value = if (duration > 0) currentPosition.toFloat() / duration else 0f,
                    onValueChange = { value ->
                        val newPosition = (value * duration).toLong()
                        exoPlayer.seekTo(newPosition)
                        currentPosition = newPosition
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color(0xFF3390EC),
                        activeTrackColor = Color(0xFF3390EC),
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        formatDuration(currentPosition),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    
                    Row {
                        IconButton(onClick = { 
                            if (isPlaying) exoPlayer.pause() else exoPlayer.play()
                            lastInteractionTime = System.currentTimeMillis()
                        }) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White
                            )
                        }
                    }
                    
                    Text(
                        formatDuration(duration),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            }
        }
    }
}



// Helper functions removed - using standard androidx.compose.foundation.gestures.transformable and rememberTransformableState
// Make sure to add: import androidx.compose.foundation.gestures.rememberTransformableState
// Make sure to add: import androidx.compose.foundation.gestures.transformable

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.2f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
