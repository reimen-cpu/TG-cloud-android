package com.telegram.cloud.gallery.streaming

import android.content.Context
import android.util.Log
import com.telegram.cloud.data.prefs.BotConfig
import com.telegram.cloud.data.remote.TelegramBotClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Manages streaming of chunked video files from Telegram.
 * Downloads chunks progressively while allowing playback to start immediately.
 */
class ChunkedStreamingManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "ChunkedStreamingMgr"
        private const val CHUNK_SIZE = 4 * 1024 * 1024L // 4MB
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val downloadJobs = mutableMapOf<Int, Job>()
    
    private val _downloadProgress = MutableStateFlow<Map<Int, Float>>(emptyMap())
    val downloadProgress: StateFlow<Map<Int, Float>> = _downloadProgress.asStateFlow()
    
    private val _totalProgress = MutableStateFlow(0f)
    val totalProgress: StateFlow<Float> = _totalProgress.asStateFlow()
    
    private val _availableChunks = MutableStateFlow<Set<Int>>(emptySet())
    val availableChunks: StateFlow<Set<Int>> = _availableChunks.asStateFlow()
    
    private var streamingDir: File? = null
    private var config: BotConfig? = null
    private var chunkFileIds: List<String> = emptyList()
    private var uploaderTokens: List<String> = emptyList()
    
    /**
     * Initialize streaming for a chunked file
     * 
     * @param fileId Base file ID or UUID for the chunked file
     * @param chunkFileIds Comma-separated Telegram file IDs for each chunk
     * @param uploaderTokens Comma-separated bot tokens used for uploading each chunk
     * @param config Bot configuration
     */
    fun initStreaming(
        fileId: String,
        chunkFileIds: String,
        uploaderTokens: String,
        config: BotConfig
    ) {
        this.config = config
        this.chunkFileIds = chunkFileIds.split(",").map { it.trim() }
        this.uploaderTokens = uploaderTokens.split(",").map { it.trim() }
        
        // Create streaming directory
        streamingDir = File(context.cacheDir, "streaming/$fileId").apply {
            mkdirs()
        }
        
        Log.d(TAG, "Initialized streaming: ${this.chunkFileIds.size} chunks")
        
        // Start downloading first few chunks immediately for quick playback start
        startPriorityDownloads()
    }
    
    /**
     * Get total number of chunks
     */
    fun getTotalChunks(): Int = chunkFileIds.size
    
    /**
     * Get chunk size in bytes
     */
    fun getChunkSize(): Long = CHUNK_SIZE
    
    /**
     * Get input stream for a chunk (blocks until chunk is available)
     */
    suspend fun getChunkStream(chunkIndex: Int): InputStream? {
        if (chunkIndex >= chunkFileIds.size) return null
        
        val chunkFile = getChunkFile(chunkIndex)
        
        // If chunk exists and is complete, return stream
        if (chunkFile.exists() && isChunkComplete(chunkIndex)) {
            return FileInputStream(chunkFile)
        }
        
        // Start downloading this chunk if not already
        startChunkDownload(chunkIndex)
        
        // Wait for chunk to be available
        while (!isChunkComplete(chunkIndex)) {
            delay(100)
        }
        
        return if (chunkFile.exists()) FileInputStream(chunkFile) else null
    }
    
    /**
     * Get list of available chunk files for immediate playback
     */
    fun getAvailableChunkFiles(): List<File> {
        return (0 until chunkFileIds.size).mapNotNull { index ->
            val file = getChunkFile(index)
            if (file.exists() && isChunkComplete(index)) file else null
        }
    }
    
    /**
     * Check if a chunk is fully downloaded
     */
    fun isChunkComplete(chunkIndex: Int): Boolean {
        return _availableChunks.value.contains(chunkIndex)
    }
    
    /**
     * Start downloading priority chunks (first 2-3 for quick start)
     */
    private fun startPriorityDownloads() {
        val priorityChunks = minOf(3, chunkFileIds.size)
        for (i in 0 until priorityChunks) {
            startChunkDownload(i)
        }
    }
    
    /**
     * Start downloading a specific chunk
     */
    private fun startChunkDownload(chunkIndex: Int) {
        if (downloadJobs.containsKey(chunkIndex)) return // Already downloading
        if (_availableChunks.value.contains(chunkIndex)) return // Already complete
        
        val job = scope.launch {
            try {
                downloadChunk(chunkIndex)
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading chunk $chunkIndex", e)
            }
        }
        downloadJobs[chunkIndex] = job
    }
    
    /**
     * Download a single chunk
     * Uses round-robin token assignment (same as upload and ChunkedDownloadManager)
     */
    private suspend fun downloadChunk(chunkIndex: Int) {
        val cfg = config ?: return
        if (chunkIndex >= chunkFileIds.size) return
        
        val fileId = chunkFileIds[chunkIndex]
        // Round-robin token assignment (same as upload): token = tokens[chunkIndex % tokens.size]
        val token = if (uploaderTokens.isNotEmpty()) {
            uploaderTokens[chunkIndex % uploaderTokens.size]
        } else {
            cfg.tokens[chunkIndex % cfg.tokens.size]
        }
        
        val chunkFile = getChunkFile(chunkIndex)
        
        Log.d(TAG, "Downloading chunk $chunkIndex with token ${token.take(10)}... (using token index ${chunkIndex % uploaderTokens.size})")
        
        val botClient = TelegramBotClient(token)
        
        try {
            // Get file path from Telegram using the correct token
            val fileResponse = botClient.getFile(fileId)
            val telegramFilePath = fileResponse.filePath
            
            if (telegramFilePath.isBlank()) {
                Log.e(TAG, "Failed to get file path for chunk $chunkIndex")
                return
            }
            
            // Download file with progress
            val bytes = botClient.downloadFileToBytes(telegramFilePath) { progress ->
                updateChunkProgress(chunkIndex, progress)
            }
            
            if (bytes != null) {
                chunkFile.writeBytes(bytes)
                markChunkComplete(chunkIndex)
                Log.d(TAG, "Chunk $chunkIndex downloaded: ${bytes.size} bytes")
                
                // Start downloading next chunk
                if (chunkIndex + 1 < chunkFileIds.size) {
                    startChunkDownload(chunkIndex + 1)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading chunk $chunkIndex", e)
        } finally {
            downloadJobs.remove(chunkIndex)
        }
    }
    
    private fun getChunkFile(chunkIndex: Int): File {
        return File(streamingDir, "chunk_$chunkIndex.part")
    }
    
    private fun updateChunkProgress(chunkIndex: Int, progress: Float) {
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            this[chunkIndex] = progress
        }
        
        // Calculate total progress
        val totalChunks = chunkFileIds.size
        val completedChunks = _availableChunks.value.size
        val inProgressSum = _downloadProgress.value.values.sum()
        _totalProgress.value = (completedChunks + inProgressSum / totalChunks) / totalChunks
    }
    
    private fun markChunkComplete(chunkIndex: Int) {
        _availableChunks.value = _availableChunks.value + chunkIndex
        _downloadProgress.value = _downloadProgress.value.toMutableMap().apply {
            remove(chunkIndex)
        }
        
        // Update total progress
        val totalChunks = chunkFileIds.size
        _totalProgress.value = _availableChunks.value.size.toFloat() / totalChunks
    }
    
    /**
     * Check if all chunks are downloaded
     */
    fun areAllChunksComplete(): Boolean {
        return _availableChunks.value.size == chunkFileIds.size
    }
    
    /**
     * Cancel all downloads and clean up
     * @param keepChunks If true, chunks will not be deleted (for permanent storage)
     */
    fun release(keepChunks: Boolean = false) {
        downloadJobs.values.forEach { it.cancel() }
        downloadJobs.clear()
        
        // Only clean up streaming directory if not keeping chunks
        if (!keepChunks) {
            streamingDir?.deleteRecursively()
            streamingDir = null
        }
        
        Log.d(TAG, "Streaming manager released (keepChunks=$keepChunks)")
    }
    
    /**
     * Reassemble chunks into a single file
     */
    suspend fun reassembleFile(outputFile: File): Boolean = withContext(Dispatchers.IO) {
        try {
            outputFile.outputStream().use { output ->
                for (i in 0 until chunkFileIds.size) {
                    val chunkFile = getChunkFile(i)
                    if (!chunkFile.exists()) {
                        Log.e(TAG, "Chunk $i missing during reassembly")
                        return@withContext false
                    }
                    chunkFile.inputStream().use { input ->
                        input.copyTo(output)
                    }
                }
            }
            Log.d(TAG, "File reassembled: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error reassembling file", e)
            false
        }
    }
}

