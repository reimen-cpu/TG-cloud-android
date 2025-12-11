@file:OptIn(
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)
package com.telegram.cloud.gallery

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.text.font.FontWeight
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
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
    media: GalleryMediaEntity,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onDownloadFromTelegram: (GalleryMediaEntity, (Float) -> Unit, (String) -> Unit, (String) -> Unit) -> Unit,
    onSyncClick: ((Float) -> Unit)? = null,
    isSyncing: Boolean = false,
    uploadProgress: Float = 0f,
    config: com.telegram.cloud.data.prefs.BotConfig? = null,
    onFileDownloaded: ((String) -> Unit)? = null
) {
    var showControls by remember { mutableStateOf(true) }
    
    var downloadState by remember { mutableStateOf<MediaDownloadState>(MediaDownloadState.Checking) }
    var uploadState by remember { mutableStateOf<MediaUploadState>(MediaUploadState.Idle) }
    
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    
    // Chunked streaming state
    var useChunkedStreaming by remember { mutableStateOf(false) }
    val chunkedPlayer = remember { 
        if (media.isVideo && media.isChunked) {
            ChunkedVideoPlayer(context)
        } else null
    }
    val streamingManager = remember {
        if (media.isVideo && media.isChunked && config != null) {
            ChunkedStreamingManager(context)
        } else null
    }
    
    // Update upload state when progress changes
    LaunchedEffect(uploadProgress, isSyncing) {
        if (isSyncing) {
            if (uploadProgress > 0f && uploadProgress < 1f) {
                uploadState = MediaUploadState.Uploading(uploadProgress)
            } else if (uploadProgress >= 1f) {
                uploadState = MediaUploadState.Uploading(1f)
                kotlinx.coroutines.delay(500)
                uploadState = MediaUploadState.Completed
                kotlinx.coroutines.delay(1500)
                uploadState = MediaUploadState.Idle
            } else {
                uploadState = MediaUploadState.Uploading(0f)
            }
        } else {
            if (uploadState is MediaUploadState.Uploading && uploadProgress >= 1f) {
                // Sync completed
                uploadState = MediaUploadState.Completed
                kotlinx.coroutines.delay(1500)
                uploadState = MediaUploadState.Idle
            } else if (!isSyncing && uploadState !is MediaUploadState.Completed) {
                uploadState = MediaUploadState.Idle
            }
        }
    }
    
    // Check if local file exists, if not and synced, download from Telegram or stream
    LaunchedEffect(media, config) {
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
                downloadState = MediaDownloadState.Error("File synced but download info missing. Please re-sync this file.")
            }
        } else {
            Log.w(TAG, "File not available: localPath=${media.localPath}, isSynced=${media.isSynced}")
            downloadState = MediaDownloadState.Error("File not available locally and not synced")
        }
    }
    
    // Monitor when all chunks are downloaded and save the complete file
    LaunchedEffect(streamingManager, useChunkedStreaming) {
        if (useChunkedStreaming && streamingManager != null) {
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
                    }
                    
                    break // All chunks processed
                }
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
    
    // Create ExoPlayer instance
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            playWhenReady = false
            repeatMode = Player.REPEAT_MODE_OFF
        }
    }
    
    // Player state
    var isPlaying by remember { mutableStateOf(false) }
    var playbackState by remember { mutableStateOf(Player.STATE_IDLE) }
    var currentPosition by remember { mutableStateOf(0L) }
    var duration by remember { mutableStateOf(0L) }
    var bufferedPosition by remember { mutableStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    
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
                    .size(48.dp)
                    .align(Alignment.Center)
            )
        }
        
        // Controls overlay
        if (controlsVisible || playbackState == Player.STATE_IDLE || playbackState == Player.STATE_ENDED) {
            Box(modifier = Modifier.fillMaxSize()) {
                // Center play/pause button
                IconButton(
                    onClick = {
                        if (playbackState == Player.STATE_ENDED) {
                            exoPlayer.seekTo(0)
                            exoPlayer.play()
                        } else if (isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.play()
                        }
                        lastInteractionTime = System.currentTimeMillis()
                    },
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(72.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        when {
                            playbackState == Player.STATE_ENDED -> Icons.Default.Replay
                            isPlaying -> Icons.Default.Pause
                            else -> Icons.Default.PlayArrow
                        },
                        contentDescription = if (isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp)
                    )
                }
                
                // Bottom controls with seek bar
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    // Progress bar
                    VideoSeekBar(
                        currentPosition = currentPosition,
                        bufferedPosition = bufferedPosition,
                        duration = duration,
                        onSeek = { position ->
                            exoPlayer.seekTo(position)
                            lastInteractionTime = System.currentTimeMillis()
                        }
                    )
                    
                    // Time display
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = formatDuration(currentPosition),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                        Text(
                            text = formatDuration(duration),
                            color = Color.White,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

/**
 * Seek bar for video playback
 */
@Composable
private fun VideoSeekBar(
    currentPosition: Long,
    bufferedPosition: Long,
    duration: Long,
    onSeek: (Long) -> Unit
) {
    val progress = if (duration > 0) currentPosition.toFloat() / duration else 0f
    val bufferedProgress = if (duration > 0) bufferedPosition.toFloat() / duration else 0f
    
    var isDragging by remember { mutableStateOf(false) }
    var dragPosition by remember { mutableStateOf(0f) }
    
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        // Background track
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.3f))
        )
        
        // Buffered progress
        Box(
            modifier = Modifier
                .fillMaxWidth(bufferedProgress)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color.White.copy(alpha = 0.5f))
        )
        
        // Current progress
        Box(
            modifier = Modifier
                .fillMaxWidth(if (isDragging) dragPosition else progress)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF3390EC))
        )
        
        // Slider for seeking
        Slider(
            value = if (isDragging) dragPosition else progress,
            onValueChange = { value ->
                isDragging = true
                dragPosition = value
            },
            onValueChangeFinished = {
                isDragging = false
                onSeek((dragPosition * duration).toLong())
            },
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            )
        )
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.2f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 1000 / 60) % 60
    val hours = ms / 1000 / 60 / 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
