package com.telegram.cloud.gallery.streaming

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ConcatenatingMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Video player that supports streaming chunked files from Telegram Cloud.
 * Can play video chunks as they are being downloaded.
 */
@OptIn(UnstableApi::class)
class ChunkedVideoPlayer(
    private val context: Context
) {
    companion object {
        private const val TAG = "ChunkedVideoPlayer"
    }
    
    private var exoPlayer: ExoPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var monitorJob: Job? = null
    
    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()
    
    private val _bufferedPosition = MutableStateFlow(0L)
    val bufferedPosition: StateFlow<Long> = _bufferedPosition.asStateFlow()
    
    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()
    
    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()
    
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()
    
    sealed class PlayerState {
        object Idle : PlayerState()
        object Preparing : PlayerState()
        object Ready : PlayerState()
        object Buffering : PlayerState()
        data class Error(val message: String) : PlayerState()
        object Ended : PlayerState()
    }
    
    /**
     * Get the ExoPlayer instance for use with PlayerView
     */
    fun getPlayer(): ExoPlayer? = exoPlayer
    
    /**
     * Initialize the player
     */
    fun initialize() {
        if (exoPlayer != null) return
        
        exoPlayer = ExoPlayer.Builder(context)
            .build()
            .apply {
                addListener(playerListener)
                playWhenReady = true
            }
        
        startPositionMonitor()
        Log.d(TAG, "Player initialized")
    }
    
    /**
     * Play a single video file (non-chunked)
     */
    fun playFile(file: File) {
        initialize()
        
        val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
        }
        
        _playerState.value = PlayerState.Preparing
        Log.d(TAG, "Playing file: ${file.absolutePath}")
    }
    
    /**
     * Play a video from URI (for local files or content URIs)
     */
    fun playUri(uri: Uri) {
        initialize()
        
        val mediaItem = MediaItem.fromUri(uri)
        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
        }
        
        _playerState.value = PlayerState.Preparing
        Log.d(TAG, "Playing URI: $uri")
    }
    
    /**
     * Play chunked video files in sequence.
     * Chunks are played in order as a continuous stream.
     * Can start playing before all chunks are downloaded.
     * 
     * @param chunkFiles List of chunk files in order (some may not exist yet)
     * @param totalChunks Total number of chunks expected
     * @param onChunkNeeded Callback when a chunk needs to be downloaded
     */
    fun playChunkedVideo(
        chunkFiles: List<File>,
        totalChunks: Int,
        onChunkNeeded: ((Int) -> Unit)? = null
    ) {
        initialize()
        
        if (chunkFiles.isEmpty()) {
            _playerState.value = PlayerState.Error("No chunks available")
            return
        }
        
        Log.d(TAG, "Playing chunked video: ${chunkFiles.size}/$totalChunks chunks available")
        
        // Create media sources for available chunks
        val dataSourceFactory = DefaultDataSource.Factory(context)
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
        
        // Build concatenating source for seamless playback
        val mediaSources = mutableListOf<MediaSource>()
        
        chunkFiles.forEachIndexed { index, file ->
            if (file.exists() && file.length() > 0) {
                val mediaItem = MediaItem.fromUri(Uri.fromFile(file))
                val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
                mediaSources.add(mediaSource)
                Log.d(TAG, "Added chunk $index: ${file.name}")
            } else {
                Log.d(TAG, "Chunk $index not ready: ${file.name}")
                onChunkNeeded?.invoke(index)
            }
        }
        
        if (mediaSources.isNotEmpty()) {
            val concatenatingSource = ConcatenatingMediaSource(*mediaSources.toTypedArray())
            exoPlayer?.apply {
                setMediaSource(concatenatingSource)
                prepare()
            }
        }
        
        _playerState.value = PlayerState.Preparing
    }
    
    /**
     * Stream video chunks as they download.
     * This creates a custom data source that waits for chunks.
     * 
     * @param getChunkStream Function that returns input stream for a chunk (blocks until available)
     * @param totalChunks Total number of chunks
     * @param chunkSize Size of each chunk in bytes
     */
    fun streamChunkedVideo(
        getChunkStream: suspend (chunkIndex: Int) -> InputStream?,
        totalChunks: Int,
        chunkSize: Long
    ) {
        initialize()
        
        Log.d(TAG, "Starting chunked stream: $totalChunks chunks, ${chunkSize / 1024}KB each")
        
        // Create custom data source factory that creates new instances
        val dataSourceFactory = DataSource.Factory {
            ChunkedStreamingDataSource(
                getChunkStream = getChunkStream,
                totalChunks = totalChunks,
                chunkSize = chunkSize,
                scope = scope
            )
        }
        val mediaSourceFactory = ProgressiveMediaSource.Factory(dataSourceFactory)
        
        // Use a virtual URI for the concatenated stream
        val virtualUri = Uri.parse("chunked://telegram-cloud/stream")
        val mediaItem = MediaItem.fromUri(virtualUri)
        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)
        
        exoPlayer?.apply {
            setMediaSource(mediaSource)
            prepare()
        }
        
        _playerState.value = PlayerState.Preparing
    }
    
    /**
     * Add a new chunk to the current playback (for progressive loading)
     */
    fun addChunk(chunkFile: File, chunkIndex: Int) {
        if (!chunkFile.exists()) return
        
        Log.d(TAG, "Chunk $chunkIndex now available: ${chunkFile.name}")
        // ExoPlayer will automatically continue when data becomes available
    }
    
    fun play() {
        exoPlayer?.play()
    }
    
    fun pause() {
        exoPlayer?.pause()
    }
    
    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }
    
    fun release() {
        monitorJob?.cancel()
        exoPlayer?.apply {
            removeListener(playerListener)
            release()
        }
        exoPlayer = null
        _playerState.value = PlayerState.Idle
        Log.d(TAG, "Player released")
    }
    
    private fun startPositionMonitor() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (true) {
                exoPlayer?.let { player ->
                    _currentPosition.value = player.currentPosition
                    _bufferedPosition.value = player.bufferedPosition
                    _duration.value = player.duration.takeIf { it > 0 } ?: 0L
                    _isPlaying.value = player.isPlaying
                }
                delay(250)
            }
        }
    }
    
    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            _playerState.value = when (state) {
                Player.STATE_IDLE -> PlayerState.Idle
                Player.STATE_BUFFERING -> PlayerState.Buffering
                Player.STATE_READY -> PlayerState.Ready
                Player.STATE_ENDED -> PlayerState.Ended
                else -> PlayerState.Idle
            }
            Log.d(TAG, "Playback state: ${_playerState.value}")
        }
        
        override fun onPlayerError(error: PlaybackException) {
            _playerState.value = PlayerState.Error(error.message ?: "Unknown error")
            Log.e(TAG, "Player error: ${error.message}", error)
        }
        
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
        }
    }
}

/**
 * Custom DataSource that streams chunked video data.
 * Fetches chunks on demand as the player needs them.
 */
@OptIn(UnstableApi::class)
class ChunkedStreamingDataSource(
    private val getChunkStream: suspend (Int) -> InputStream?,
    private val totalChunks: Int,
    private val chunkSize: Long,
    private val scope: CoroutineScope
) : DataSource {
    
    companion object {
        private const val TAG = "ChunkedStreamingDS"
    }
    
    private var currentChunkIndex = 0
    private var currentStream: InputStream? = null
    private var bytesReadInChunk = 0L
    private var totalBytesRead = 0L
    private var isOpen = false
    
    override fun addTransferListener(transferListener: TransferListener) {}
    
    override fun open(dataSpec: DataSpec): Long {
        Log.d(TAG, "Opening stream, position: ${dataSpec.position}, length: ${dataSpec.length}")
        
        // Calculate which chunk to start from
        currentChunkIndex = (dataSpec.position / chunkSize).toInt()
        bytesReadInChunk = dataSpec.position % chunkSize
        totalBytesRead = dataSpec.position
        
        isOpen = true
        
        // Pre-fetch first chunk to ensure it's available
        if (currentChunkIndex < totalChunks) {
            try {
                val firstChunkStream = kotlinx.coroutines.runBlocking {
                    var retries = 0
                    var stream: InputStream? = null
                    while (stream == null && retries < 20) {
                        stream = getChunkStream(currentChunkIndex)
                        if (stream == null) {
                            kotlinx.coroutines.delay(100)
                            retries++
                        }
                    }
                    stream
                }
                firstChunkStream?.close()
                Log.d(TAG, "First chunk $currentChunkIndex is ready")
            } catch (e: Exception) {
                Log.w(TAG, "First chunk not ready yet: ${e.message}")
            }
        }
        
        // Return unknown length for streaming
        val totalSize = totalChunks * chunkSize
        val remainingSize = totalSize - dataSpec.position
        return if (dataSpec.length != -1L) dataSpec.length else remainingSize
    }
    
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (!isOpen || currentChunkIndex >= totalChunks) {
            return -1 // End of stream
        }
        
        // Get current chunk stream if needed
        if (currentStream == null) {
            try {
                currentStream = kotlinx.coroutines.runBlocking {
                    var retries = 0
                    var stream: InputStream? = null
                    while (stream == null && retries < 10) {
                        stream = getChunkStream(currentChunkIndex)
                        if (stream == null) {
                            kotlinx.coroutines.delay(200) // Wait 200ms before retry
                            retries++
                        }
                    }
                    stream
                }
                
                if (currentStream == null) {
                    Log.w(TAG, "Chunk $currentChunkIndex not available after retries")
                    throw IOException("Chunk $currentChunkIndex not available")
                }
                
                // Skip to position within chunk
                if (bytesReadInChunk > 0) {
                    currentStream?.skip(bytesReadInChunk)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error getting chunk stream $currentChunkIndex", e)
                throw IOException("Failed to get chunk $currentChunkIndex", e)
            }
        }
        
        val bytesRead = try {
            currentStream?.read(buffer, offset, length) ?: -1
        } catch (e: IOException) {
            Log.e(TAG, "Error reading chunk $currentChunkIndex", e)
            currentStream?.close()
            currentStream = null
            throw e
        }
        
        if (bytesRead == -1) {
            // End of current chunk, move to next
            currentStream?.close()
            currentStream = null
            currentChunkIndex++
            bytesReadInChunk = 0
            
            if (currentChunkIndex < totalChunks) {
                // More chunks available, recursively read
                return read(buffer, offset, length)
            }
            return -1 // All chunks read
        }
        
        bytesReadInChunk += bytesRead
        totalBytesRead += bytesRead
        
        return bytesRead
    }
    
    override fun getUri(): Uri? = Uri.parse("chunked://telegram-cloud/chunk-$currentChunkIndex")
    
    override fun close() {
        currentStream?.close()
        currentStream = null
        isOpen = false
        Log.d(TAG, "Stream closed")
    }
}

