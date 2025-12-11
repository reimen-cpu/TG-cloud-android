package com.telegram.cloud.data.remote

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ChunkedUploadManager"

/**
 * Chunk size: 4MB (same as desktop version)
 */
const val CHUNK_SIZE = 4 * 1024 * 1024L // 4MB
const val CHUNK_THRESHOLD = 4 * 1024 * 1024L // Files larger than 4MB use chunking

data class ChunkUploadResult(
    val success: Boolean,
    val fileId: String,
    val messageIds: List<Long>,
    val telegramFileIds: List<String>, // Telegram file IDs for download
    val uploaderBotTokens: List<String>, // Bot tokens used for each chunk (for sharing)
    val totalChunks: Int,
    val error: String? = null
)

data class ChunkInfo(
    val index: Int,
    val messageId: Long,
    val telegramFileId: String,
    val hash: String,
    val uploaderBotToken: String // Bot token used to upload this chunk
)

/**
 * Manages chunked uploads for large files.
 * Splits files into 4MB chunks and uploads them in parallel using multiple bot tokens.
 */
class ChunkedUploadManager(
    private val botClient: TelegramBotClient,
    private val contentResolver: ContentResolver
) {
    
    /**
     * Uploads a large file in chunks.
     * @param uri Content URI of the file
     * @param fileName Display name of the file
     * @param fileSize Total file size in bytes
     * @param tokens List of bot tokens for parallel upload
     * @param channelId Target channel ID
     * @param onProgress Progress callback (completedChunks, totalChunks, percent)
     * @return ChunkUploadResult with all chunk message IDs
     */
    suspend fun uploadChunked(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        tokens: List<String>,
        channelId: String,
        tokenOffset: Int = 0,
        skipChunks: Set<Int> = emptySet(),
        onProgress: ((Int, Int, Float) -> Unit)? = null,
        onChunkCompleted: ((ChunkInfo) -> Unit)? = null
    ): ChunkUploadResult = withContext(Dispatchers.IO) {
        
        val fileId = UUID.randomUUID().toString()
        val totalChunks = ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        
        Log.i(TAG, "Starting chunked upload: $fileName")
        Log.i(TAG, "  File size: $fileSize bytes (${fileSize / 1024.0 / 1024.0} MB)")
        Log.i(TAG, "  Total chunks: $totalChunks")
        Log.i(TAG, "  Chunk size: $CHUNK_SIZE bytes")
        Log.i(TAG, "  Bot pool: ${tokens.size} tokens")
        Log.i(TAG, "  File ID: $fileId")
        Log.i(TAG, "  Token offset: $tokenOffset")
        
        val completedChunks = AtomicInteger(0)
        val uploadedBytes = AtomicLong(0)
        val chunkInfos = mutableListOf<ChunkInfo>()
        val errors = mutableListOf<String>()
        
        // Upload chunks in parallel using all available bots (round-robin like desktop)
        val parallelism = tokens.size // Use all bots
        val semaphore = kotlinx.coroutines.sync.Semaphore(parallelism)
        Log.i(TAG, "Using parallelism: $parallelism (${tokens.size} bots)")
        
        // Filter out already completed chunks
        val chunksToUpload = (0 until totalChunks).filter { it !in skipChunks }
        val initialCompleted = skipChunks.size
        
        if (skipChunks.isNotEmpty()) {
            Log.i(TAG, "Resuming upload: skipping ${skipChunks.size} already completed chunks")
            completedChunks.set(initialCompleted)
        }
        
        val jobs = chunksToUpload.map { chunkIndex ->
            async {
                semaphore.acquire()
                try {
                    // Round-robin: assign token based on chunk index (same as desktop)
                    val tokenIndex = (chunkIndex + tokenOffset) % tokens.size
                    val token = tokens[tokenIndex]
                    
                    Log.d(TAG, "Chunk $chunkIndex using token index $tokenIndex (offset $tokenOffset)")
                    
                    val chunkResult = uploadSingleChunk(
                        uri = uri,
                        fileId = fileId,
                        fileName = fileName,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        fileSize = fileSize,
                        token = token,
                        channelId = channelId
                    )
                    
                    if (chunkResult != null) {
                        synchronized(chunkInfos) {
                            chunkInfos.add(chunkResult)
                        }
                        val completed = completedChunks.incrementAndGet()
                        val percent = completed.toFloat() / totalChunks * 100
                        Log.i(TAG, "Chunk $chunkIndex completed ($completed/$totalChunks - ${percent.toInt()}%)")
                        onProgress?.invoke(completed, totalChunks, percent)
                        
                        // Notify callback for progress persistence
                        onChunkCompleted?.invoke(chunkResult)
                    } else {
                        synchronized(errors) {
                            errors.add("Chunk $chunkIndex failed")
                        }
                    }
                } finally {
                    semaphore.release()
                }
            }
        }
        
        // Wait for all chunks
        jobs.awaitAll()
        
        val success = completedChunks.get() == totalChunks
        val sortedChunks = chunkInfos.sortedBy { it.index }
        
        if (success) {
            Log.i(TAG, "Chunked upload completed successfully: $fileName")
            ChunkUploadResult(
                success = true,
                fileId = fileId,
                messageIds = sortedChunks.map { it.messageId },
                telegramFileIds = sortedChunks.map { it.telegramFileId },
                uploaderBotTokens = sortedChunks.map { it.uploaderBotToken },
                totalChunks = totalChunks
            )
        } else {
            Log.e(TAG, "Chunked upload failed: ${errors.size} chunks failed")
            ChunkUploadResult(
                success = false,
                fileId = fileId,
                messageIds = chunkInfos.map { it.messageId },
                telegramFileIds = chunkInfos.map { it.telegramFileId },
                uploaderBotTokens = chunkInfos.map { it.uploaderBotToken },
                totalChunks = totalChunks,
                error = "Failed chunks: ${errors.joinToString()}"
            )
        }
    }
    
    private suspend fun uploadSingleChunk(
        uri: Uri,
        fileId: String,
        fileName: String,
        chunkIndex: Int,
        totalChunks: Int,
        fileSize: Long,
        token: String,
        channelId: String
    ): ChunkInfo? {
        var lastError: Exception? = null
        val maxRetries = 5
        
        repeat(maxRetries) { attempt ->
            try {
                if (attempt > 0) {
                    // Exponential backoff: 1s, 2s, 4s, 8s, 16s
                    val delayMs = (1000L * (1 shl (attempt - 1)))
                    Log.d(TAG, "Retry attempt $attempt for chunk $chunkIndex, waiting ${delayMs}ms...")
                    delay(delayMs)
                }
                
                val offset = chunkIndex * CHUNK_SIZE
                val chunkSize = minOf(CHUNK_SIZE, fileSize - offset).toInt()
                
                Log.i(TAG, "Reading chunk $chunkIndex: offset=$offset, size=$chunkSize")
                
                // Read chunk data - use skip with verification
                val chunkData = contentResolver.openInputStream(uri)?.use { stream ->
                    // Skip to offset - some streams don't support skip well, so read and discard
                    var skipped = 0L
                    val skipBuffer = ByteArray(8192)
                    while (skipped < offset) {
                        val toRead = minOf(skipBuffer.size.toLong(), offset - skipped).toInt()
                        val read = stream.read(skipBuffer, 0, toRead)
                        if (read == -1) {
                            Log.e(TAG, "Chunk $chunkIndex: EOF while skipping at $skipped/$offset")
                            throw Exception("EOF while skipping to offset $offset")
                        }
                        skipped += read
                    }
                    Log.d(TAG, "Chunk $chunkIndex: Skipped $skipped bytes")
                    
                    // Now read the chunk
                    val buffer = ByteArray(chunkSize)
                    var totalRead = 0
                    while (totalRead < chunkSize) {
                        val read = stream.read(buffer, totalRead, chunkSize - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    Log.d(TAG, "Chunk $chunkIndex: Read $totalRead bytes")
                    buffer.copyOf(totalRead)
                } ?: throw Exception("Cannot open stream for chunk $chunkIndex")
                
                if (chunkData.isEmpty()) {
                    throw Exception("Empty chunk data for chunk $chunkIndex")
                }
                
                // Calculate chunk hash
                val chunkHash = calculateHash(chunkData)
                
                // Generate chunk filename
                val chunkFileName = if (totalChunks == 1) {
                    fileName
                } else {
                    "${fileName}.chunk_${chunkIndex}_of_$totalChunks"
                }
                
                Log.i(TAG, "Uploading chunk $chunkIndex (${chunkData.size} bytes) with token ${token.take(15)}...")
                
                // Upload chunk with retry handled by botClient
                val message = botClient.sendChunk(
                    token = token,
                    channelId = channelId,
                    chunkData = chunkData,
                    chunkFileName = chunkFileName,
                    chunkIndex = chunkIndex,
                    totalChunks = totalChunks,
                    originalFileName = fileName,
                    fileId = fileId,
                    chunkHash = chunkHash
                )
                
                val telegramFileId = message.document?.fileId ?: ""
                Log.i(TAG, "Chunk $chunkIndex uploaded: msgId=${message.messageId}, fileId=${telegramFileId.take(30)}...")
                
                return ChunkInfo(
                    index = chunkIndex,
                    messageId = message.messageId,
                    telegramFileId = telegramFileId,
                    hash = chunkHash,
                    uploaderBotToken = token
                )
                
            } catch (e: Exception) {
                lastError = e
                
                // Determine if error is recoverable
                val isRecoverable = when (e) {
                    is IOException, is SocketTimeoutException -> true
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
                    Log.e(TAG, "Chunk $chunkIndex: Non-recoverable error, not retrying", e)
                    return null
                }
                
                Log.w(TAG, "Chunk $chunkIndex attempt ${attempt + 1}/$maxRetries failed: ${e.message}")
                
                if (attempt == maxRetries - 1) {
                    Log.e(TAG, "Chunk $chunkIndex: Failed after $maxRetries attempts", e)
                    return null
                }
            }
        }
        
        Log.e(TAG, "Failed to upload chunk $chunkIndex after $maxRetries attempts", lastError)
        return null
    }
    
    /**
     * Resumes a chunked upload from where it left off.
     * @param uri Content URI of the file
     * @param fileName Display name of the file
     * @param fileSize Total file size in bytes
     * @param fileId UUID of the upload (from previous attempt)
     * @param completedChunks List of already completed chunks
     * @param tokens List of bot tokens
     * @param channelId Target channel ID
     * @param tokenOffset Token offset to maintain round-robin consistency
     * @param onProgress Progress callback
     * @param onChunkCompleted Callback after each successful chunk
     * @return ChunkUploadResult
     */
    suspend fun resumeChunkedUpload(
        uri: Uri,
        fileName: String,
        fileSize: Long,
        fileId: String,
        completedChunks: List<ChunkInfo>,
        tokens: List<String>,
        channelId: String,
        tokenOffset: Int = 0,
        onProgress: ((Int, Int, Float) -> Unit)? = null,
        onChunkCompleted: ((ChunkInfo) -> Unit)? = null
    ): ChunkUploadResult = withContext(Dispatchers.IO) {
        
        val totalChunks = ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE).toInt()
        val skipChunks = completedChunks.map { it.index }.toSet()
        
        Log.i(TAG, "Resuming chunked upload: $fileName")
        Log.i(TAG, "  File ID: $fileId")
        Log.i(TAG, "  Already completed: ${skipChunks.size}/$totalChunks chunks")
        Log.i(TAG, "  Token offset: $tokenOffset")
        
        val completedChunksCount = AtomicInteger(skipChunks.size)
        val chunkInfos = mutableListOf<ChunkInfo>()
        
        // Restore completed chunks
        synchronized(chunkInfos) {
            chunkInfos.addAll(completedChunks)
        }
        
        val errors = mutableListOf<String>()
        
        // Upload remaining chunks using the modified uploadChunked
        val parallelism = tokens.size
        val semaphore = kotlinx.coroutines.sync.Semaphore(parallelism)
        
        val chunksToUpload = (0 until totalChunks).filter { it !in skipChunks }
        
        val jobs = chunksToUpload.map { chunkIndex ->
            async {
                semaphore.acquire()
                try {
                    val tokenIndex = (chunkIndex + tokenOffset) % tokens.size
                    val token = tokens[tokenIndex]
                    
                    val chunkResult = uploadSingleChunk(
                        uri = uri,
                        fileId = fileId,
                        fileName = fileName,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        fileSize = fileSize,
                        token = token,
                        channelId = channelId
                    )
                    
                    if (chunkResult != null) {
                        synchronized(chunkInfos) {
                            chunkInfos.add(chunkResult)
                        }
                        val completed = completedChunksCount.incrementAndGet()
                        val percent = completed.toFloat() / totalChunks * 100
                        Log.i(TAG, "Chunk $chunkIndex completed ($completed/$totalChunks - ${percent.toInt()}%)")
                        onProgress?.invoke(completed, totalChunks, percent)
                        onChunkCompleted?.invoke(chunkResult)
                    } else {
                        synchronized(errors) {
                            errors.add("Chunk $chunkIndex failed")
                        }
                    }
                } finally {
                    semaphore.release()
                }
            }
        }
        
        jobs.awaitAll()
        
        val success = completedChunksCount.get() == totalChunks
        val sortedChunks = chunkInfos.sortedBy { it.index }
        
        if (success) {
            Log.i(TAG, "Resumed chunked upload completed successfully: $fileName")
            ChunkUploadResult(
                success = true,
                fileId = fileId,
                messageIds = sortedChunks.map { it.messageId },
                telegramFileIds = sortedChunks.map { it.telegramFileId },
                uploaderBotTokens = sortedChunks.map { it.uploaderBotToken },
                totalChunks = totalChunks
            )
        } else {
            Log.e(TAG, "Resumed chunked upload failed: ${errors.size} chunks failed")
            ChunkUploadResult(
                success = false,
                fileId = fileId,
                messageIds = chunkInfos.map { it.messageId },
                telegramFileIds = chunkInfos.map { it.telegramFileId },
                uploaderBotTokens = chunkInfos.map { it.uploaderBotToken },
                totalChunks = totalChunks,
                error = "Failed chunks: ${errors.joinToString()}"
            )
        }
    }
    
    private fun calculateHash(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }.take(16)
    }
    
    companion object {
        /**
         * Checks if a file needs chunked upload.
         */
        fun needsChunking(fileSize: Long): Boolean = fileSize > CHUNK_THRESHOLD
    }
}

