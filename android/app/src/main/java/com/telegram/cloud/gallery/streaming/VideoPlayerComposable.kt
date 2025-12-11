package com.telegram.cloud.gallery.streaming

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

/**
 * Composable video player with controls for streaming chunked videos
 */
@OptIn(UnstableApi::class)
@Composable
fun StreamingVideoPlayer(
    player: ChunkedVideoPlayer,
    modifier: Modifier = Modifier,
    showControls: Boolean = true,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val playerState by player.playerState.collectAsState()
    val currentPosition by player.currentPosition.collectAsState()
    val bufferedPosition by player.bufferedPosition.collectAsState()
    val duration by player.duration.collectAsState()
    val isPlaying by player.isPlaying.collectAsState()
    
    var controlsVisible by remember { mutableStateOf(true) }
    var lastInteractionTime by remember { mutableStateOf(System.currentTimeMillis()) }
    
    // Auto-hide controls
    LaunchedEffect(controlsVisible, lastInteractionTime) {
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
        // Video surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player.getPlayer()
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { view ->
                view.player = player.getPlayer()
            },
            modifier = Modifier.fillMaxSize()
        )
        
        // Loading/Buffering indicator
        when (playerState) {
            is ChunkedVideoPlayer.PlayerState.Preparing,
            is ChunkedVideoPlayer.PlayerState.Buffering -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(
                            color = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = if (playerState is ChunkedVideoPlayer.PlayerState.Preparing) 
                                "Loading..." else "Buffering...",
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            is ChunkedVideoPlayer.PlayerState.Error -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = (playerState as ChunkedVideoPlayer.PlayerState.Error).message,
                            color = Color.White,
                            fontSize = 14.sp
                        )
                    }
                }
            }
            else -> {}
        }
        
        // Controls overlay
        if (showControls) {
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top bar with back button
                    onBack?.let {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.5f))
                                .padding(8.dp)
                                .align(Alignment.TopCenter),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.Default.ArrowBack,
                                    contentDescription = "Back",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                    
                    // Center play/pause button
                    IconButton(
                        onClick = {
                            if (isPlaying) player.pause() else player.play()
                            lastInteractionTime = System.currentTimeMillis()
                        },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(64.dp)
                            .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
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
                        VideoProgressBar(
                            currentPosition = currentPosition,
                            bufferedPosition = bufferedPosition,
                            duration = duration,
                            onSeek = { position ->
                                player.seekTo(position)
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
}

@Composable
private fun VideoProgressBar(
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
                .background(Color.White)
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

private fun formatDuration(ms: Long): String {
    if (ms <= 0) return "0:00"
    
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 1000 / 60) % 60
    val hours = ms / 1000 / 60 / 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}


