package com.telegram.cloud.gallery

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.local.CloudFileEntity
import com.telegram.cloud.data.prefs.BotConfig
import com.telegram.cloud.data.repository.TelegramRepository
import com.telegram.cloud.data.remote.ChunkedDownloadManager
import com.telegram.cloud.data.remote.ChunkedUploadManager
import com.telegram.cloud.data.remote.TelegramBotClient
import com.telegram.cloud.utils.ResourceGuard
import com.telegram.cloud.utils.getUserVisibleDownloadsDir
import com.telegram.cloud.utils.moveFileToDownloads
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "GallerySyncManager"
private const val MAX_PARALLEL_UPLOADS = 3

class GallerySyncManager(
    private val context: Context,
    private val database: CloudDatabase,
    private val galleryDao: GalleryMediaDao,
    private val repository: TelegramRepository
) {
    private val botClient = TelegramBotClient()
    private val chunkedUploadManager = ChunkedUploadManager(botClient, context.contentResolver)
    private val chunkedDownloadManager = ChunkedDownloadManager(botClient)
    private val syncMutex = Mutex()
    private val directUploadTokenIndex = AtomicInteger(0)
    private var currentJob: Job? = null

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _syncProgress = MutableStateFlow(0f)
    val syncProgress: StateFlow<Float> = _syncProgress.asStateFlow()

    private val _currentFileName = MutableStateFlow<String?>(null)
    val currentFileName: StateFlow<String?> = _currentFileName.asStateFlow()

    sealed class SyncState {
        object Idle : SyncState()
        data class Syncing(val currentFile: String, val current: Int, val total: Int) : SyncState()
        data class Error(val message: String) : SyncState()
        object Completed : SyncState()
    }

    suspend fun syncAllMedia(
        config: BotConfig,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Boolean = coroutineScope {
        ResourceGuard.markActive(ResourceGuard.Feature.MANUAL_SYNC)
        val job = coroutineContext[Job] ?: error("Missing job context")
        syncMutex.withLock {
            if (currentJob?.isActive == true) {
                Log.w(TAG, "Sync already running, skipping concurrent request")
                return@coroutineScope false
            }
            currentJob = job
        }

        val tokens = config.tokens
        if (tokens.isEmpty()) {
            Log.e(TAG, "No bot tokens configured")
            _syncState.value = SyncState.Error("No bot tokens configured")
            resetJob()
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            return@coroutineScope false
        }

        val unsynced = galleryDao.getUnsynced()
        if (unsynced.isEmpty()) {
            _syncState.value = SyncState.Completed
            resetSummaries()
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            resetJob()
            return@coroutineScope true
        }

        Log.i(TAG, "syncAllMedia: Found ${tokens.size} bot tokens, syncing ${unsynced.size} files")
        val total = unsynced.size
        val completedCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val activeProgresses = java.util.concurrent.ConcurrentHashMap<String, Float>()
        
        // Semaphore to limit parallel uploads
        val semaphore = kotlinx.coroutines.sync.Semaphore(MAX_PARALLEL_UPLOADS)

        try {
            val jobs = unsynced.map { media ->
                async {
                    semaphore.withPermit {
                        ensureActive()
                        
                        // Update UI to show this file is starting
                        _currentFileName.value = media.filename
                        val currentCompleted = completedCount.get()
                        _syncState.value = SyncState.Syncing(media.filename, currentCompleted + 1, total)
                        onProgress?.invoke(currentCompleted + 1, total, media.filename)
                        
                        activeProgresses[media.filename] = 0f
                        
                        val result = uploadMedia(media, config.channelId, tokens) { progress ->
                            activeProgresses[media.filename] = progress
                            
                            // Calculate global progress
                            val currentActiveSum = activeProgresses.values.sum()
                            val globalProgress = (completedCount.get() + currentActiveSum) / total
                            _syncProgress.value = globalProgress
                        }
                        
                        activeProgresses.remove(media.filename)
                        completedCount.incrementAndGet()
                        
                        if (result) {
                            successCount.incrementAndGet()
                        } else {
                            failureCount.incrementAndGet()
                        }
                        
                        // Final progress update for this step
                        val globalProgress = completedCount.get().toFloat() / total
                        _syncProgress.value = globalProgress
                    }
                }
            }
            
            jobs.awaitAll()

            val failures = failureCount.get()
            val success = successCount.get()
            _syncState.value = if (failures == 0) SyncState.Completed else SyncState.Error("Synced $success, failed $failures")
            resetSummaries()
            return@coroutineScope true
        } catch (e: Exception) {
            Log.e(TAG, "syncAllMedia failed", e)
            _syncState.value = SyncState.Error(e.message ?: "Sync failed")
            return@coroutineScope false
        } finally {
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            resetJob()
        }
    }

    suspend fun cancelSync() {
        syncMutex.withLock {
            currentJob?.cancel()
            currentJob = null
        }
        _syncState.value = SyncState.Idle
        _syncProgress.value = 0f
        _currentFileName.value = null
    }

    private suspend fun uploadMedia(
        media: GalleryMediaEntity, 
        channelId: String, 
        tokens: List<String>,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        return try {
            val file = File(media.localPath)
            if (!file.exists()) {
                Log.w(TAG, "File not found: ${media.localPath}")
                galleryDao.markSyncError(media.id, "File missing locally", System.currentTimeMillis())
                return false
            }
            val tokenOffset = directUploadTokenIndex.getAndIncrement()
            val result = chunkedUploadManager.uploadChunked(
                uri = android.net.Uri.fromFile(file),
                fileName = media.filename,
                fileSize = file.length(),
                tokens = tokens,
                channelId = channelId,
                tokenOffset = tokenOffset,
                onProgress = { completed, total, percent ->
                    onProgress?.invoke(completed.toFloat() / total)
                }
            )
            return handleChunkedResult(media, result)
        } catch (e: Exception) {
            Log.e(TAG, "uploadMedia failed for ${media.filename}", e)
            galleryDao.markSyncError(media.id, e.message ?: "Upload error", System.currentTimeMillis())
            return false
        }
    }

    private suspend fun handleChunkedResult(media: GalleryMediaEntity, result: com.telegram.cloud.data.remote.ChunkUploadResult): Boolean {
        if (!result.success) {
            galleryDao.markSyncError(media.id, result.error ?: "Chunked upload failed", System.currentTimeMillis())
            return false
        }

        val fileId = result.telegramFileIds.firstOrNull().orEmpty()
        val messageId = result.messageIds.firstOrNull()?.toInt() ?: 0
        val uploaderTokens = result.uploaderBotTokens

        if (messageId == 0 || fileId.isBlank()) {
            galleryDao.markSyncError(media.id, "Upload result missing IDs", System.currentTimeMillis())
            return false
        }

        if (result.totalChunks > 1) {
            galleryDao.markSyncedChunked(
                id = media.id,
                fileId = fileId,
                messageId = messageId,
                telegramFileIds = result.telegramFileIds.joinToString(","),
                uploaderTokens = uploaderTokens.joinToString(",")
            )
        } else {
            galleryDao.markSynced(media.id, fileId, messageId)
            if (uploaderTokens.isNotEmpty()) {
                galleryDao.updateUploaderToken(media.id, uploaderTokens.first())
            }
        }

        addToCloudFiles(media)
        return true
    }

    private suspend fun addToCloudFiles(media: GalleryMediaEntity) {
        try {
            delay(50)
            val updated = galleryDao.getById(media.id) ?: return
            if (!updated.isSynced) return
            val cloudEntry = CloudFileEntity(
                telegramMessageId = updated.telegramMessageId?.toLong() ?: 0L,
                fileId = updated.telegramFileId ?: "",
                fileUniqueId = updated.telegramFileUniqueId,
                fileName = updated.filename,
                mimeType = updated.mimeType,
                sizeBytes = updated.sizeBytes,
                uploadedAt = System.currentTimeMillis(),
                caption = "Gallery: ${updated.filename}",
                shareLink = null,
                checksum = null,
                uploaderTokens = updated.telegramUploaderTokens
            )
            database.cloudFileDao().insert(cloudEntry)
            // Recargar archivos desde la base de datos inmediatamente (similar a LoadFiles en desktop)
            repository.reloadFilesFromDatabase()
        } catch (e: Exception) {
            Log.e(TAG, "addToCloudFiles failed for ${media.filename}", e)
        }
    }

    fun resetState() {
        _syncState.value = SyncState.Idle
        _syncProgress.value = 0f
        _currentFileName.value = null
    }

    suspend fun syncSingleMedia(
        media: GalleryMediaEntity,
        config: BotConfig,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean {
        val result = uploadMedia(media, config.channelId, config.tokens, onProgress)
        onProgress?.invoke(if (result) 1f else 0f)
        if (result) {
            addToCloudFiles(media)
        }
        return result
    }

    suspend fun downloadMediaFromTelegram(
        media: GalleryMediaEntity,
        config: BotConfig,
        onProgress: (Float) -> Unit
    ): String? {
        val tokens = config.tokens
        if (tokens.isEmpty()) {
            Log.e(TAG, "No tokens available for download")
            return null
        }

        val isChunked = media.telegramFileUniqueId?.contains(",") == true

        if (isChunked) {
            val telegramFileIds = media.telegramFileUniqueId?.split(",")?.map { it.trim() }
            if (telegramFileIds.isNullOrEmpty()) {
                Log.e(TAG, "No chunk file IDs found for ${media.filename}")
                return null
            }
            val uploaderTokens = if (!media.telegramUploaderTokens.isNullOrBlank()) {
                media.telegramUploaderTokens.split(",").map { it.trim() }
            } else {
                tokens
            }
            Log.d(TAG, "Chunked file: ${telegramFileIds.size} chunks, ${uploaderTokens.size} tokens")
            onProgress(0.1f)
            // Descargar a directorio temporal primero
            val tempDir = File(context.cacheDir, "gallery_downloads").apply { mkdirs() }
            val tempFile = File(tempDir, media.filename)
            val result = chunkedDownloadManager.downloadChunked(
                chunkFileIds = telegramFileIds,
                tokens = uploaderTokens,
                outputFile = tempFile,
                totalSize = media.sizeBytes,
                onProgress = { _, _, _ ->
                    // progress emitted through onProgress separately
                }
            )
            if (!result.success) {
                Log.e(TAG, "Chunked download failed: ${result.error}")
                return null
            }
            onProgress(0.9f)
            // Mover a Downloads/telegram cloud app/Downloads usando MediaStore
            val moveResult = moveFileToDownloads(
                context = context,
                source = tempFile,
                displayName = media.filename,
                mimeType = media.mimeType ?: "application/octet-stream",
                subfolder = "telegram cloud app/Downloads"
            )
            if (moveResult == null) {
                Log.e(TAG, "Failed to move file to Downloads")
                return null
            }
            // Construir la ruta final esperada: /storage/emulated/0/Download/telegram cloud app/Downloads/filename
            val finalPath = moveResult.file?.absolutePath ?: run {
                // En Android Q+, construir la ruta basándonos en el subfolder
                val downloadsPath = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                File(downloadsPath, "telegram cloud app/Downloads/${media.filename}").absolutePath
            }
            // Escanear el archivo para que sea visible en la galería
            MediaScannerConnection.scanFile(context, arrayOf(finalPath), arrayOf(media.mimeType ?: "application/octet-stream"), null)
            // Actualizar localPath en la base de datos
            galleryDao.updateLocalPath(media.id, finalPath)
            Log.d(TAG, "Updated localPath for ${media.filename}: $finalPath")
            onProgress(1f)
            return finalPath
        } else {
            val fileId = media.telegramFileId?.takeIf { it.isNotBlank() }
            if (fileId.isNullOrBlank()) {
                Log.e(TAG, "No fileId for ${media.filename}")
                return null
            }
            val token = media.telegramUploaderTokens?.split(",")?.firstOrNull()?.trim()
                ?: tokens.first()
            onProgress(0.2f)
            val telegramFile = botClient.getFile(token, fileId)
            val filePath = telegramFile.filePath
            if (filePath.isBlank()) return null
            // Descargar a archivo temporal primero
            val tempDir = File(context.cacheDir, "gallery_downloads").apply { mkdirs() }
            val tempFile = File(tempDir, media.filename)
            onProgress(0.3f)
            tempFile.outputStream().use {
                botClient.downloadFile(token, filePath, it)
            }
            onProgress(0.8f)
            // Mover a Downloads/telegram cloud app/Downloads usando MediaStore
            val moveResult = moveFileToDownloads(
                context = context,
                source = tempFile,
                displayName = media.filename,
                mimeType = media.mimeType ?: "application/octet-stream",
                subfolder = "telegram cloud app/Downloads"
            )
            if (moveResult == null) {
                Log.e(TAG, "Failed to move file to Downloads")
                return null
            }
            // Construir la ruta final esperada: /storage/emulated/0/Download/telegram cloud app/Downloads/filename
            val finalPath = moveResult.file?.absolutePath ?: run {
                // En Android Q+, construir la ruta basándonos en el subfolder
                val downloadsPath = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                File(downloadsPath, "telegram cloud app/Downloads/${media.filename}").absolutePath
            }
            // Escanear el archivo para que sea visible en la galería
            MediaScannerConnection.scanFile(context, arrayOf(finalPath), arrayOf(media.mimeType ?: "application/octet-stream"), null)
            // Actualizar localPath en la base de datos
            galleryDao.updateLocalPath(media.id, finalPath)
            Log.d(TAG, "Updated localPath for ${media.filename}: $finalPath")
            onProgress(1f)
            return finalPath
        }
    }

    suspend fun deleteFromTelegram(media: GalleryMediaEntity, config: BotConfig): Boolean {
        val messageId = media.telegramMessageId ?: return false
        val tokens = config.tokens
        if (tokens.isEmpty()) return false
        val token = if (media.telegramFileUniqueId?.contains(",") == true) {
            tokens.first()
        } else {
            media.telegramUploaderTokens ?: tokens.first()
        }
        return try {
            botClient.deleteMessage(token, config.channelId, messageId.toLong())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting from Telegram", e)
            false
        }
    }

    private fun resetSummaries() {
        _currentFileName.value = null
        _syncProgress.value = 1f
    }

    private suspend fun resetJob() {
        syncMutex.withLock {
            currentJob = null
        }
    }
}

