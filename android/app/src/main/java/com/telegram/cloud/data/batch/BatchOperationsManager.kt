package com.telegram.cloud.data.batch

import android.util.Log
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.local.CloudFileEntity
import com.telegram.cloud.data.remote.ChunkedDownloadManager
import com.telegram.cloud.data.remote.TelegramBotClient
import com.telegram.cloud.domain.model.CloudFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages batch operations: delete, download, share multiple files
 * Compatible with desktop application's BatchOperations module
 */
// Progress callback type alias at top level
typealias BatchProgressCallback = (current: Int, total: Int, operation: String, currentFileName: String) -> Unit

class BatchOperationsManager(
    private val database: CloudDatabase,
    private val botClient: TelegramBotClient
) {
    companion object {
        private const val TAG = "BatchOperationsManager"
        private const val MAX_CONCURRENT = 3
    }
    
    data class BatchResult(
        val successful: Int,
        val failed: Int,
        val errors: List<String>
    )
    
    /**
     * Delete multiple files from both local DB and Telegram
     */
    suspend fun deleteFiles(
        files: List<CloudFile>,
        tokens: List<String>,
        channelId: String,
        progressCallback: BatchProgressCallback? = null
    ): BatchResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting batch delete for ${files.size} files")
        
        var successful = 0
        var failed = 0
        val errors = mutableListOf<String>()
        
        files.forEachIndexed { index, file ->
            progressCallback?.invoke(index + 1, files.size, "Eliminando", file.fileName)
            
            try {
                val entity = database.cloudFileDao().getById(file.id)
                
                if (entity != null) {
                    val token = tokens.firstOrNull() ?: ""
                    val isChunked = ChunkedDownloadManager.isChunkedFile(entity.caption)
                    
                    if (isChunked) {
                        // Delete all chunk messages - extract from caption
                        val messageIds = extractMessageIdsFromCaption(entity.caption)
                        for (msgId in messageIds) {
                            botClient.deleteMessage(token, channelId, msgId)
                        }
                    } else {
                        // Delete single message
                        botClient.deleteMessage(token, channelId, entity.telegramMessageId)
                    }
                }
                
                // Delete from local DB
                database.cloudFileDao().deleteById(file.id)
                successful++
                Log.i(TAG, "Deleted: ${file.fileName}")
                
            } catch (e: Exception) {
                failed++
                errors.add("${file.fileName}: ${e.message}")
                Log.e(TAG, "Failed to delete ${file.fileName}", e)
            }
        }
        
        Log.i(TAG, "Batch delete completed: $successful successful, $failed failed")
        BatchResult(successful, failed, errors)
    }
    
    /**
     * Download multiple files
     */
    suspend fun downloadFiles(
        files: List<CloudFile>,
        tokens: List<String>,
        destinationDir: File,
        progressCallback: BatchProgressCallback? = null
    ): BatchResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting batch download for ${files.size} files to ${destinationDir.absolutePath}")
        
        var successful = 0
        var failed = 0
        val errors = mutableListOf<String>()
        
        // Process in batches for parallel downloads
        files.chunked(MAX_CONCURRENT).forEachIndexed { batchIndex, batch ->
            coroutineScope {
                val results = batch.mapIndexed { indexInBatch, file ->
                    async {
                        val globalIndex = batchIndex * MAX_CONCURRENT + indexInBatch + 1
                        progressCallback?.invoke(globalIndex, files.size, "Descargando", file.fileName)
                        
                        try {
                            val entity = database.cloudFileDao().getById(file.id)
                            if (entity == null) {
                                throw Exception("File not found in database")
                            }
                            
                            val destFile = File(destinationDir, file.fileName)
                            val token = tokens[globalIndex % tokens.size]
                            
                            val isChunked = ChunkedDownloadManager.isChunkedFile(entity.caption)
                            
                            if (isChunked) {
                                downloadChunkedFile(entity, token, destFile)
                            } else {
                                downloadDirectFile(entity, token, destFile)
                            }
                            
                            Log.i(TAG, "Downloaded: ${file.fileName}")
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to download ${file.fileName}", e)
                            errors.add("${file.fileName}: ${e.message}")
                            false
                        }
                    }
                }.awaitAll()
                
                results.forEach { if (it) successful++ else failed++ }
            }
        }
        
        Log.i(TAG, "Batch download completed: $successful successful, $failed failed")
        BatchResult(successful, failed, errors)
    }
    
    private suspend fun downloadDirectFile(
        entity: CloudFileEntity,
        token: String,
        destFile: File
    ) {
        val fileBytes = botClient.downloadFileToBytes(token, entity.fileId)
            ?: throw Exception("Failed to download file from Telegram")
        
        destFile.writeBytes(fileBytes)
    }
    
    private suspend fun downloadChunkedFile(
        entity: CloudFileEntity,
        token: String,
        destFile: File
    ) {
        val telegramFileIds = entity.fileUniqueId?.split(",") ?: throw Exception("No chunk file IDs")
        
        destFile.outputStream().use { output ->
            telegramFileIds.forEach { fileId ->
                val chunkBytes = botClient.downloadFileToBytes(token, fileId.trim())
                    ?: throw Exception("Failed to download chunk $fileId")
                output.write(chunkBytes)
            }
        }
    }
    
    /**
     * Get file info for batch operations
     */
    suspend fun getBatchFileInfo(files: List<CloudFile>): List<BatchFileInfo> {
        return files.mapNotNull { file ->
            val entity = database.cloudFileDao().getById(file.id) ?: return@mapNotNull null
            
            BatchFileInfo(
                fileId = file.id.toString(),
                fileName = file.fileName,
                fileSize = formatFileSize(file.sizeBytes),
                mimeType = file.mimeType ?: "application/octet-stream",
                uploadDate = formatDate(file.uploadedAt),
                isEncrypted = false,
                category = if (ChunkedDownloadManager.isChunkedFile(entity.caption)) "chunked" else "file"
            )
        }
    }
    
    data class BatchFileInfo(
        val fileId: String,
        val fileName: String,
        val fileSize: String,
        val mimeType: String,
        val uploadDate: String,
        val isEncrypted: Boolean,
        val category: String
    )
    
    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1_073_741_824 -> String.format("%.2f GB", bytes / 1_073_741_824.0)
            bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
            bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
    
    private fun formatDate(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(timestamp))
    }
    
    /**
     * Extracts message IDs from chunked file caption.
     * Format: [CHUNKED:N|msgId1,msgId2,...] caption text
     */
    private fun extractMessageIdsFromCaption(caption: String?): List<Long> {
        if (caption == null) return emptyList()
        
        val match = Regex("\\[CHUNKED:\\d+\\|([^\\]]+)\\]").find(caption)
        val idsString = match?.groupValues?.get(1) ?: return emptyList()
        
        return idsString.split(",").mapNotNull { it.trim().toLongOrNull() }
    }
}

