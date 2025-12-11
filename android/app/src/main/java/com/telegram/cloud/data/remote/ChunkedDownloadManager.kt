package com.telegram.cloud.data.remote

import android.util.Log
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "ChunkedDownloadManager"

data class ChunkDownloadResult(
    val success: Boolean,
    val outputFile: File?,
    val error: String? = null
)

/**
 * Manages chunked downloads for large files.
 * Downloads chunks in parallel and reassembles them into the original file.
 */
class ChunkedDownloadManager(
    private val botClient: TelegramBotClient
) {
    
    /**
     * Downloads a chunked file by downloading all chunks and reassembling.
     * @param chunkFileIds List of Telegram file IDs for each chunk (in order)
     * @param tokens List of bot tokens for parallel download
     * @param outputFile Target file to write the reassembled content
     * @param totalSize Expected total file size (optional, for validation)
     * @param onProgress Progress callback (completedChunks, totalChunks, percent)
     * @return ChunkDownloadResult
     */
    suspend fun downloadChunked(
        chunkFileIds: List<String>,
        tokens: List<String>,
        outputFile: File,
        totalSize: Long? = null,
        skipChunks: Set<Int> = emptySet(),
        existingChunkDir: File? = null,
        onProgress: ((Int, Int, Float) -> Unit)? = null,
        onChunkDownloaded: ((Int, File) -> Unit)? = null
    ): ChunkDownloadResult = withContext(Dispatchers.IO) {
        
        val totalChunks = chunkFileIds.size
        Log.i(TAG, "Starting chunked download: ${outputFile.name}")
        Log.i(TAG, "  Total chunks: $totalChunks")
        Log.i(TAG, "  Bot pool: ${tokens.size} tokens")
        
        val completedChunks = AtomicInteger(0)
        val chunkDataMap = mutableMapOf<Int, ByteArray>()
        val errors = mutableListOf<String>()
        val failedChunkIndices = mutableSetOf<Int>()
        
        // Use existing temp directory or create new one
        val tempDir = existingChunkDir ?: File(outputFile.parentFile, ".chunks_${System.currentTimeMillis()}")
        if (!tempDir.exists()) {
            tempDir.mkdirs()
        }
        
        // Load existing chunks from disk if resuming
        val initialCompleted = skipChunks.size
        if (skipChunks.isNotEmpty()) {
            Log.i(TAG, "Resuming download: loading ${skipChunks.size} existing chunks from disk")
            completedChunks.set(initialCompleted)
            
            for (index in skipChunks) {
                val chunkFile = File(tempDir, "chunk_${index}.tmp")
                if (chunkFile.exists()) {
                    val chunkData = chunkFile.readBytes()
                    synchronized(chunkDataMap) {
                        chunkDataMap[index] = chunkData
                    }
                    Log.d(TAG, "Loaded chunk $index from disk (${chunkData.size} bytes)")
                } else {
                    Log.w(TAG, "Chunk file missing for index $index: ${chunkFile.absolutePath}")
                }
            }
        }
        
        try {
            // Use all available bots in parallel (round-robin like desktop)
            // Each bot handles its own chunks to avoid rate limiting per bot
            val parallelism = tokens.size
            val semaphore = kotlinx.coroutines.sync.Semaphore(parallelism)
            Log.i(TAG, "Using parallelism: $parallelism (${tokens.size} bots)")
            
            val jobs = chunkFileIds.mapIndexed { index, fileId ->
                // Skip if already completed
                if (index in skipChunks) {
                    return@mapIndexed null
                }
                
                async {
                    semaphore.acquire()
                    try {
                        // Round-robin: assign token based on chunk index (same as desktop)
                        val token = tokens[index % tokens.size]
                        val chunkData = downloadSingleChunk(fileId, token)
                        
                        if (chunkData != null) {
                            // Save chunk to disk immediately
                            val chunkFile = File(tempDir, "chunk_${index}.tmp")
                            chunkFile.writeBytes(chunkData)
                            Log.d(TAG, "Saved chunk $index to disk: ${chunkFile.absolutePath}")
                            
                            synchronized(chunkDataMap) {
                                chunkDataMap[index] = chunkData
                            }
                            val completed = completedChunks.incrementAndGet()
                            val percent = completed.toFloat() / totalChunks * 100
                            Log.i(TAG, "Chunk $index downloaded ($completed/$totalChunks - ${percent.toInt()}%)")
                            onProgress?.invoke(completed, totalChunks, percent)
                            
                            // Notify callback for progress persistence
                            onChunkDownloaded?.invoke(index, chunkFile)
                        } else {
                            synchronized(failedChunkIndices) {
                                failedChunkIndices.add(index)
                            }
                            synchronized(errors) {
                                errors.add("Chunk $index failed")
                            }
                        }
                    } finally {
                        semaphore.release()
                    }
                }
            }
            .filterNotNull()
            
            // Wait for all downloads
            jobs.awaitAll()
            
            // If there are failed chunks but some succeeded, retry failed chunks after all others completed
            if (failedChunkIndices.isNotEmpty() && completedChunks.get() > 0) {
                Log.i(TAG, "Retrying ${failedChunkIndices.size} failed chunks after successful downloads completed")
                val retryJobs = failedChunkIndices.map { index ->
                    async {
                        semaphore.acquire()
                        try {
                            val fileId = chunkFileIds[index]
                            val token = tokens[index % tokens.size]
                            val chunkData = downloadSingleChunk(fileId, token)
                            
                            if (chunkData != null) {
                                synchronized(chunkDataMap) {
                                    chunkDataMap[index] = chunkData
                                }
                                val completed = completedChunks.incrementAndGet()
                                val percent = completed.toFloat() / totalChunks * 100
                                Log.i(TAG, "Chunk $index retry succeeded ($completed/$totalChunks - ${percent.toInt()}%)")
                                onProgress?.invoke(completed, totalChunks, percent)
                                synchronized(failedChunkIndices) {
                                    failedChunkIndices.remove(index)
                                }
                                synchronized(errors) {
                                    errors.removeIf { it.contains("Chunk $index") }
                                }
                            } else {
                                Log.w(TAG, "Chunk $index retry also failed")
                            }
                        } finally {
                            semaphore.release()
                        }
                    }
                }
                retryJobs.awaitAll()
            }
            
            if (completedChunks.get() != totalChunks) {
                Log.e(TAG, "Download incomplete: ${completedChunks.get()}/$totalChunks chunks")
                return@withContext ChunkDownloadResult(
                    success = false,
                    outputFile = null,
                    error = "Failed chunks: ${errors.joinToString()}"
                )
            }
            
            // Reassemble chunks
            Log.i(TAG, "Reassembling ${totalChunks} chunks...")
            FileOutputStream(outputFile).use { output ->
                for (i in 0 until totalChunks) {
                    val chunkData = chunkDataMap[i] 
                        ?: throw Exception("Missing chunk $i")
                    output.write(chunkData)
                }
            }
            
            Log.i(TAG, "Chunked download completed: ${outputFile.absolutePath}")
            Log.i(TAG, "Final file size: ${outputFile.length()} bytes")
            
            ChunkDownloadResult(
                success = true,
                outputFile = outputFile
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Chunked download failed", e)
            // Don't delete temp directory on failure - allow resumption
            ChunkDownloadResult(
                success = false,
                outputFile = null,
                error = e.message
            )
        }
        // Note: tempDir is NOT deleted here to allow resumption
        // It should be deleted after successful completion or by cleanup mechanism
    }
    
    /**
     * Downloads a chunked file using message IDs to get file info first.
     * This is used when we have message IDs stored instead of file IDs.
     */
    suspend fun downloadChunkedByMessageIds(
        messageIds: List<Long>,
        token: String,
        channelId: String,
        outputFile: File,
        tokens: List<String>,
        onProgress: ((Int, Int, Float) -> Unit)? = null
    ): ChunkDownloadResult = withContext(Dispatchers.IO) {
        
        Log.i(TAG, "Resolving ${messageIds.size} message IDs to file IDs...")
        
        // For each message, we need to get the file_id
        // This would require forwarding or using getChat/getMessage API
        // For now, we assume file IDs are stored directly
        
        // This is a placeholder - in practice you'd need to store file_ids during upload
        Log.w(TAG, "downloadChunkedByMessageIds: Not fully implemented, need file_ids")
        
        ChunkDownloadResult(
            success = false,
            outputFile = null,
            error = "Need to implement message ID to file ID resolution"
        )
    }
    
    private suspend fun downloadSingleChunk(
        fileId: String,
        token: String,
        maxRetries: Int = 5
    ): ByteArray? {
        var lastError: Exception? = null
        
        repeat(maxRetries) { attempt ->
            try {
                if (attempt > 0) {
                    // Exponential backoff: 1s, 2s, 4s, 8s, 16s
                    val delayMs = (1000L * (1 shl (attempt - 1)))
                    Log.d(TAG, "Retry $attempt for chunk, waiting ${delayMs}ms...")
                    delay(delayMs)
                }
                
                Log.d(TAG, "Downloading chunk with fileId=${fileId.take(20)}... (attempt ${attempt + 1})")
                
                // Get file path from Telegram
                val telegramFile = botClient.getFile(token, fileId)
                
                // Download the file content
                val data = botClient.downloadFileToBytes(token, telegramFile.filePath)
                
                if (data != null && data.isNotEmpty()) {
                    return data
                }
                
            } catch (e: Exception) {
                lastError = e
                
                // Determine if error is recoverable
                val isRecoverable = when (e) {
                    is java.io.IOException, is java.net.SocketTimeoutException -> true
                    else -> {
                        // Check for specific HTTP error codes in message
                        val message = e.message ?: ""
                        when {
                            message.contains("429") -> true // Rate limit
                            message.contains("500") -> true // Internal server error
                            message.contains("502") -> true // Bad gateway
                            message.contains("503") -> true // Service unavailable
                            message.contains("504") -> true // Gateway timeout
                            message.contains("400") -> false // Bad request
                            message.contains("401") -> false // Unauthorized
                            message.contains("403") -> false // Forbidden
                            message.contains("404") -> false // Not found
                            else -> false
                        }
                    }
                }
                
                if (!isRecoverable) {
                    Log.e(TAG, "Chunk download: Non-recoverable error, not retrying", e)
                    return null
                }
                
                Log.w(TAG, "Chunk download attempt ${attempt + 1}/$maxRetries failed: ${e.message}")
                
                if (attempt == maxRetries - 1) {
                    Log.e(TAG, "Chunk download: Failed after $maxRetries attempts", e)
                    return null
                }
            }
        }
        
        Log.e(TAG, "Failed to download chunk after $maxRetries attempts", lastError)
        return null
    }
    
    /**
     * Resumes a chunked download from where it left off.
     * @param chunkFileIds List of Telegram file IDs for each chunk
     * @param tokens List of bot tokens for parallel download
     * @param outputFile Target file to write reassembled content
     * @param completedChunkIndices Set of chunk indices already downloaded
     * @param tempChunkDir Temporary directory containing partial chunks
     * @param totalSize Expected total file size (optional)
     * @param onProgress Progress callback
     * @param onChunkDownloaded Callback after each chunk download
     * @return ChunkDownloadResult
     */
    suspend fun resumeChunkedDownload(
        chunkFileIds: List<String>,
        tokens: List<String>,
        outputFile: File,
        completedChunkIndices: Set<Int>,
        tempChunkDir: File,
        totalSize: Long? = null,
        onProgress: ((Int, Int, Float) -> Unit)? = null,
        onChunkDownloaded: ((Int, File) -> Unit)? = null
    ): ChunkDownloadResult {
        
        Log.i(TAG, "Resuming chunked download: ${outputFile.name}")
        Log.i(TAG, "  Temp dir: ${tempChunkDir.absolutePath}")
        Log.i(TAG, "  Already completed: ${completedChunkIndices.size}/${chunkFileIds.size} chunks")
        
        // Verify temp directory exists
        if (!tempChunkDir.exists()) {
            Log.e(TAG, "Temp chunk directory does not exist: ${tempChunkDir.absolutePath}")
            return ChunkDownloadResult(
                success = false,
                outputFile = null,
                error = "Temp directory not found"
            )
        }
        
        // Scan for existing chunk files and merge with completedChunkIndices
        val existingChunks = mutableSetOf<Int>()
        existingChunks.addAll(completedChunkIndices)
        
        tempChunkDir.listFiles()?.forEach { file ->
            if (file.name.startsWith("chunk_") && file.name.endsWith(".tmp")) {
                val index = file.name.removePrefix("chunk_").removeSuffix(".tmp").toIntOrNull()
                if (index != null) {
                    existingChunks.add(index)
                }
            }
        }
        
        Log.i(TAG, "Found ${existingChunks.size} existing chunks on disk")
        
        return downloadChunked(
            chunkFileIds = chunkFileIds,
            tokens = tokens,
            outputFile = outputFile,
            totalSize = totalSize,
            skipChunks = existingChunks,
            existingChunkDir = tempChunkDir,
            onProgress = onProgress,
            onChunkDownloaded = onChunkDownloaded
        )
    }
    
    companion object {
        /**
         * Checks if a file entity represents a chunked file.
         */
        fun isChunkedFile(caption: String?): Boolean {
            return caption?.startsWith("[CHUNKED:") == true
        }
        
        /**
         * Extracts chunk count from caption.
         * Format: [CHUNKED:N|msgId1,msgId2,...] or [CHUNKED:N]
         */
        fun getChunkCount(caption: String?): Int? {
            if (caption == null) return null
            // Match [CHUNKED:N| or [CHUNKED:N]
            val match = Regex("\\[CHUNKED:(\\d+)[|\\]]").find(caption)
            return match?.groupValues?.get(1)?.toIntOrNull()
        }
    }
}

