package com.telegram.cloud.gallery

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.prefs.BotConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.math.max

class GalleryViewModel(
    private val context: Context,
    private val mediaScanner: MediaScanner,
    private val syncManager: GallerySyncManager,
    private val database: CloudDatabase,
    private val restoreManager: GalleryRestoreManager
) : ViewModel() {
    
    companion object {
        private const val TAG = "GalleryViewModel"
    }
    
    private val galleryDao = database.galleryMediaDao()
    
    // Use Flow for real-time updates from database
    private val _mediaList: Flow<List<GalleryMediaEntity>> = galleryDao.observeAll()
    
    // UI State
    data class GalleryUiState(
        val albums: List<GalleryAlbum> = emptyList(),
        val currentMedia: List<GalleryMediaEntity> = emptyList(),
        val groupedMedia: Map<String, List<GalleryMediaEntity>> = emptyMap(),
        val isLoading: Boolean = true
    )
    
    // Filter & Sort State
    private val _filterState = MutableStateFlow(FilterState())
    val filterState = _filterState.asStateFlow()
    
    data class FilterState(
        val selectedAlbumPath: String? = null,
        val showOnlySynced: Boolean = false,
        val searchQuery: String = "",
        val filterType: FilterType = FilterType.ALL,
        val sortBy: SortBy = SortBy.DATE,
        val sortOrder: SortOrder = SortOrder.DESCENDING
    )
    
    // Streaming State
    private val _streamingProgress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val streamingProgress = _streamingProgress.asStateFlow()
    
    private val activeStreamingManagers = mutableMapOf<Long, com.telegram.cloud.gallery.streaming.ChunkedStreamingManager>()

    val uiState: StateFlow<GalleryUiState> = combine(
        _mediaList,
        _filterState
    ) { list, filter -> Pair(list, filter) }
    .transformLatest { (mediaList, filter) ->
        // Emit loading state immediately with empty lists to prevent stale data
        emit(GalleryUiState(isLoading = true))
        
        // Run heavy grouping and filtering on Default dispatcher
        val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
             val albums = AlbumGrouper.groupByAlbums(mediaList)
            
            var filteredMedia = if (filter.selectedAlbumPath != null) {
                AlbumGrouper.getMediaForAlbum(mediaList, filter.selectedAlbumPath)
            } else {
                mediaList
            }
            
            // Filter by sync status
            if (filter.showOnlySynced) {
                filteredMedia = filteredMedia.filter { it.isSynced }
            }
            
            // Filter by search query
            if (filter.searchQuery.isNotBlank()) {
                filteredMedia = filteredMedia.filter { 
                    it.filename.contains(filter.searchQuery, ignoreCase = true) 
                }
            }
            
            // Filter by type
            filteredMedia = when (filter.filterType) {
                FilterType.IMAGES -> filteredMedia.filter { it.isImage }
                FilterType.VIDEOS -> filteredMedia.filter { it.isVideo }
                FilterType.ALL -> filteredMedia
            }
            
            // Sort
            filteredMedia = filteredMedia.sortedWith(compareBy<GalleryMediaEntity> {
                when (filter.sortBy) {
                    SortBy.DATE -> it.dateTaken
                    SortBy.NAME -> it.filename.lowercase()
                    SortBy.SIZE -> it.sizeBytes
                    SortBy.TYPE -> if (it.isImage) 0 else 1
                }
            }.let { comparator ->
                if (filter.sortOrder == SortOrder.DESCENDING) comparator.reversed() else comparator
            })
            
            // Group by date for grid view
            val groupedMedia = filteredMedia.groupBy { media ->
                val calendar = java.util.Calendar.getInstance()
                calendar.timeInMillis = media.dateTaken
                val today = java.util.Calendar.getInstance()
                val yesterday = java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
                
                when {
                    isSameDay(calendar, today) -> "Hoy"
                    isSameDay(calendar, yesterday) -> "Ayer"
                    else -> java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.getDefault()).format(java.util.Date(media.dateTaken))
                }
            }
            
            GalleryUiState(
                albums = albums,
                currentMedia = filteredMedia,
                groupedMedia = groupedMedia,
                isLoading = false
            )
        }
        emit(result)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GalleryUiState(isLoading = true))
    
    private fun isSameDay(cal1: java.util.Calendar, cal2: java.util.Calendar): Boolean {
        return cal1.get(java.util.Calendar.YEAR) == cal2.get(java.util.Calendar.YEAR) &&
                cal1.get(java.util.Calendar.DAY_OF_YEAR) == cal2.get(java.util.Calendar.DAY_OF_YEAR)
    }
    
    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()
    
    // Sync State
    val syncState = syncManager.syncState
    val syncProgress = syncManager.syncProgress
    val currentSyncFileName = syncManager.currentFileName
    
    // Restore State
    val restoreState = restoreManager.restoreState
    val restoreProgress = restoreManager.restoreProgress
    val currentRestoreFileName = restoreManager.currentFileName
    
    private var syncJob: Job? = null
    
    // These now automatically update when mediaList changes
    val syncedCount: StateFlow<Int> = _mediaList.map { list ->
        list.count { it.isSynced }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    
    val totalCount: StateFlow<Int> = _mediaList.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    // Trash State
    val trashItems: StateFlow<List<GalleryMediaEntity>> = galleryDao.observeTrash()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
        
    val trashCount: StateFlow<Int> = galleryDao.getTrashCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    fun updateFilter(update: FilterState.() -> FilterState) {
        _filterState.value = _filterState.value.update()
    }
    
    fun scanMedia() {
        if (_isScanning.value) return
        
        viewModelScope.launch {
            _isScanning.value = true
            try {
                Log.d(TAG, "Starting media scan...")
                val scannedMedia = mediaScanner.scanAllMedia()
                Log.d(TAG, "Scanned ${scannedMedia.size} media files")
                
                // Get existing media paths
                val existingPaths = galleryDao.getAll().map { it.localPath }.toSet()
                
                // Insert only new media
                val newMedia = scannedMedia.filter { it.localPath !in existingPaths }
                if (newMedia.isNotEmpty()) {
                    galleryDao.insertAll(newMedia)
                    Log.d(TAG, "Inserted ${newMedia.size} new media files")
                }
                
                // Generate thumbnails for ALL media without thumbnails or with missing files
                val allMedia = galleryDao.getAll()
                val mediaWithoutThumbs = allMedia.filter { 
                    it.thumbnailPath == null || !java.io.File(it.thumbnailPath).exists() 
                }
                val totalThumbs = mediaWithoutThumbs.size
                Log.d(TAG, "Generating thumbnails for $totalThumbs media files (new or missing)...")
                
                val generatedCount = java.util.concurrent.atomic.AtomicInteger(0)
                val progressStep = if (totalThumbs <= 0) 1 else max(1, totalThumbs / 20)
                
                // Parallelize thumbnail generation with Semaphore to avoid OOM but speed up processing
                // Use 4 concurrent workers (good balance for I/O + CPU image processing)
                val parallelism = 4
                val semaphore = kotlinx.coroutines.sync.Semaphore(parallelism)
                
                // Process in parallel
                // Process in parallel with batching to avoid overheating/OOM
                // Chunking ensures we don't create thousands of coroutine objects at once
                val batchSize = 50
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    mediaWithoutThumbs.chunked(batchSize).forEach { batch ->
                        val jobs = batch.map { media ->
                            async {
                                semaphore.acquire()
                                try {
                                    val thumbPath = mediaScanner.generateThumbnail(media)
                                    if (thumbPath != null) {
                                        galleryDao.updateThumbnail(media.id, thumbPath)
                                    } else {
                                        Log.w(TAG, "Failed to generate thumbnail for: ${media.filename}")
                                    }
                                    
                                    val count = generatedCount.incrementAndGet()
                                    val remaining = totalThumbs - count
                                    if (count % progressStep == 0 || count == totalThumbs) {
                                        Log.d(
                                            TAG,
                                            "Thumbnail progress: $count/$totalThumbs (remaining $remaining)"
                                        )
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error generating thumbnail for ${media.filename}", e)
                                } finally {
                                    semaphore.release()
                                }
                                Unit // Explicit return
                            }
                        }
                        jobs.awaitAll()
                        // Yield to allow other tasks to process if needed
                        kotlinx.coroutines.yield()
                    }
                }
                
                Log.d(TAG, "Thumbnail generation complete: ${generatedCount.get()}/$totalThumbs")
                // No need to reload - Flow will auto-update
            } catch (e: Exception) {
                Log.e(TAG, "Error scanning media", e)
            } finally {
                _isScanning.value = false
            }
        }
    }
    
    fun syncAllMedia(config: BotConfig) {
        // Use WorkManager to run sync in background
        val workRequest = OneTimeWorkRequestBuilder<GallerySyncWorker>()
            .build()
        
        WorkManager.getInstance(context).enqueueUniqueWork(
            GallerySyncWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        
        Log.d(TAG, "Gallery sync work enqueued")
    }
    
    fun cancelSync() {
        syncJob?.cancel()
        syncJob = null
        syncManager.resetState()
        WorkManager.getInstance(context).cancelUniqueWork(GallerySyncWorker.WORK_NAME)
    }
    
    fun restoreAllSynced(config: BotConfig) {
        // Use WorkManager to run restore in background
        val workRequest = OneTimeWorkRequestBuilder<GalleryRestoreWorker>()
            .build()
            
        WorkManager.getInstance(context).enqueueUniqueWork(
            GalleryRestoreWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            workRequest
        )
        
        Log.d(TAG, "Gallery restore work enqueued")
    }
    
    fun cancelRestore() {
        viewModelScope.launch {
            restoreManager.cancelRestore()
            WorkManager.getInstance(context).cancelUniqueWork(GalleryRestoreWorker.WORK_NAME)
        }
    }
    
    fun syncSingleMedia(
        media: GalleryMediaEntity, 
        config: BotConfig,
        onProgress: ((Float) -> Unit)? = null
    ) {
        viewModelScope.launch {
            syncManager.syncSingleMedia(media, config, onProgress)
            // No need to reload - Flow will auto-update
        }
    }
    
    /**
     * Download media from Telegram when local file is deleted
     */
    fun downloadFromTelegram(
        media: GalleryMediaEntity,
        config: BotConfig,
        onProgress: (Float) -> Unit,
        onSuccess: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val result = syncManager.downloadMediaFromTelegram(media, config, onProgress)
                if (result != null) {
                    onSuccess(result)
                } else {
                    onError("Download failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading from Telegram", e)
                onError(e.message ?: "Unknown error")
            }
        }
    }
    
    fun resetSyncState() {
        syncManager.resetState()
    }
    
    /**
     * Rename a media file
     */
    fun renameMedia(media: GalleryMediaEntity, newName: String) {
        viewModelScope.launch {
            try {
                val oldFile = java.io.File(media.localPath)
                if (oldFile.exists()) {
                    val newFile = java.io.File(oldFile.parent, newName)
                    if (oldFile.renameTo(newFile)) {
                        galleryDao.updateLocalPath(media.id, newFile.absolutePath)
                        // Also update filename in entity
                        val updated = media.copy(localPath = newFile.absolutePath, filename = newName)
                        galleryDao.update(updated)
                        Log.d(TAG, "Renamed: ${media.filename} -> $newName")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error renaming media", e)
            }
        }
    }
    
    /**
     * Move media to trash (Soft delete)
     */
    fun moveToTrash(media: GalleryMediaEntity) {
        moveToTrash(listOf(media))
    }
    
    fun moveToTrash(mediaList: List<GalleryMediaEntity>) {
        viewModelScope.launch {
            try {
                val ids = mediaList.map { it.id }
                galleryDao.moveToTrash(ids)
                Log.d(TAG, "Moved ${ids.size} items to trash")
            } catch (e: Exception) {
                Log.e(TAG, "Error moving to trash", e)
            }
        }
    }
    
    /**
     * Restore media from trash
     */
    fun restoreFromTrash(media: GalleryMediaEntity) {
        restoreFromTrash(listOf(media))
    }
    
    fun restoreFromTrash(mediaList: List<GalleryMediaEntity>) {
        viewModelScope.launch {
            try {
                val ids = mediaList.map { it.id }
                galleryDao.restoreFromTrash(ids)
                Log.d(TAG, "Restored ${ids.size} items from trash")
            } catch (e: Exception) {
                Log.e(TAG, "Error restoring from trash", e)
            }
        }
    }
    
    /**
     * Delete permanently from device and optionally from Telegram
     */
    @Suppress("UNUSED_PARAMETER")
    fun deletePermanently(media: GalleryMediaEntity, deleteFromTelegram: Boolean, config: BotConfig?) {
        viewModelScope.launch {
            try {
                // Delete local file
                val localFile = java.io.File(media.localPath)
                if (localFile.exists()) {
                    localFile.delete()
                    Log.d(TAG, "Deleted local file: ${media.localPath}")
                }
                
                // Delete thumbnail - ONLY when deleted via Cloud Gallery
                media.thumbnailPath?.let { thumbPath ->
                    val thumbFile = java.io.File(thumbPath)
                    if (thumbFile.exists()) {
                        thumbFile.delete()
                        Log.d(TAG, "Deleted thumbnail: $thumbPath")
                    }
                }
                
                // Delete from Telegram - ALWAYS when synced (user chose to delete from gallery)
                if (deleteFromTelegram && media.isSynced && media.telegramMessageId != null && config != null) {
                    val deleted = syncManager.deleteFromTelegram(media, config)
                    if (deleted) {
                        Log.d(TAG, "Deleted from Telegram: ${media.filename}")
                        
                        // Also delete from main cloud files list
                        database.cloudFileDao().deleteByTelegramMessageId(media.telegramMessageId.toLong())
                    }
                }
                
                // Delete from gallery database
                galleryDao.delete(media)
                
                Log.d(TAG, "Deleted from gallery: ${media.filename}")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting media", e)
            }
        }
    }
    
    /**
     * Empty trash (Delete all items in trash permanently)
     */
    fun emptyTrash(config: BotConfig?) {
        viewModelScope.launch {
            val trashItems = galleryDao.observeTrash().first()
            trashItems.forEach { media ->
                deletePermanently(media, deleteFromTelegram = media.isSynced, config = config)
            }
        }
    }
    
    /**
     * Update local path for a media item (used when file is downloaded during streaming)
     */
    fun updateLocalPath(mediaId: Long, localPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val media = galleryDao.getById(mediaId)
                if (media != null) {
                    // Extract video duration and dimensions if needed
                    var updatedMedia = media.copy(localPath = localPath)
                    
                    if (media.isVideo && media.durationMs == 0L && File(localPath).exists()) {
                        try {
                            val retriever = android.media.MediaMetadataRetriever()
                            retriever.setDataSource(localPath)
                            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                            val width = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                            val height = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                            retriever.release()
                            
                            updatedMedia = updatedMedia.copy(
                                durationMs = duration,
                                width = if (width > 0) width else media.width,
                                height = if (height > 0) height else media.height
                            )
                            Log.d(TAG, "Extracted video metadata for ${media.filename}: duration=${duration}ms, size=${width}x${height}")
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to extract video metadata for ${media.filename}: ${e.message}")
                        }
                    }
                    
                    galleryDao.update(updatedMedia)
                    Log.d(TAG, "Updated local path for media ID $mediaId: $localPath")
                } else {
                    // Fallback if media not found
                    galleryDao.updateLocalPath(mediaId, localPath)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating local path", e)
            }
        }
    }

    /**
     * Find a gallery media entity corresponding to a CloudFile.
     * Searches by telegram message ID preferably, then by filename.
     */
    suspend fun findMediaByFile(fileMessageId: Long, fileName: String, fileId: String? = null, fileSize: Long = 0, date: Long = 0): GalleryMediaEntity? {
        Log.d(TAG, "findMediaByFile: Searching for messageId=$fileMessageId, fileName='$fileName'")
        
        // Try to find by message ID first (most reliable)
        val byMessageId = galleryDao.getByTelegramMessageId(fileMessageId.toInt())
        if (byMessageId != null) {
            Log.d(TAG, "findMediaByFile: Found by ID: ${byMessageId.id} (${byMessageId.filename})")
            return byMessageId
        }
        
        // Fallback to filename
        val byName = galleryDao.getByFilename(fileName)
        if (byName != null) {
            Log.d(TAG, "findMediaByFile: Found by filename: ${byName.id}")
            return byName
        }
        
        // If not found, and we have fileId (streaming/cloud file), create a temporary entity
        if (fileId != null) {
            Log.d(TAG, "findMediaByFile: Not found locally, creating cloud entity for $fileName")
            val mimeType = if (fileName.endsWith(".mp4", ignoreCase = true) || fileName.endsWith(".mkv", ignoreCase = true)) "video/mp4" else "image/jpeg" 
            // Better mime detection would be ideal but simple is okay for now
            
            val newEntity = GalleryMediaEntity(
                localPath = "cloud:$fileId", // Placeholder path
                filename = fileName,
                mimeType = mimeType,
                sizeBytes = fileSize,
                dateTaken = date,
                dateModified = date,
                isSynced = true,
                telegramFileId = fileId, // Use this for standard file_id
                telegramMessageId = fileMessageId.toInt(),
                // Use fileId as unique ID if it looks like a UUID (chunked), otherwise just standard ID
                telegramFileUniqueId = if (fileId.length > 20 && fileId.contains("-")) fileId else null 
            )
            
            val newId = galleryDao.insert(newEntity)
            return newEntity.copy(id = newId)
        }
        
        Log.d(TAG, "findMediaByFile: Not found by either method and no fileId provided")
        return null
    }

    /**
     * Get or initialize a streaming manager for a specific media item.
     * This allows sharing the download manager between MediaViewer and background tasks.
     */
    /**
     * Get or initialize a streaming manager for a specific media item.
     * This allows sharing the download manager between MediaViewer and background tasks.
     */
    fun getOrInitStreamingManager(
        media: GalleryMediaEntity,
        config: com.telegram.cloud.data.prefs.BotConfig
    ): com.telegram.cloud.gallery.streaming.ChunkedStreamingManager {
        return activeStreamingManagers.getOrPut(media.id) {
            com.telegram.cloud.gallery.streaming.ChunkedStreamingManager(context).apply {
                val fileId = media.telegramFileId ?: media.telegramFileUniqueId?.split(",")?.firstOrNull() ?: ""
                val chunkFileIds = media.telegramFileUniqueId ?: ""
                val uploaderTokens = media.telegramUploaderTokens ?: config.tokens.first()
                initStreaming(fileId, chunkFileIds, uploaderTokens, config)
                
                // Observe progress and handle completion
                viewModelScope.launch {
                    totalProgress.collect { progress ->
                        _streamingProgress.update { current ->
                            current + (media.id to progress)
                        }
                        
                        if (progress >= 1.0f) {
                            Log.d(TAG, "Download complete for ${media.filename}, finalizing...")
                            finalizeDownload(media, this@apply)
                        }
                    }
                }
            }
        }
    }

    private suspend fun finalizeDownload(media: GalleryMediaEntity, manager: com.telegram.cloud.gallery.streaming.ChunkedStreamingManager) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                // Determine permanent storage location (Pictures/TelegramCloud)
                val galleryDir = java.io.File(
                    android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), 
                    "TelegramCloud"
                )
                if (!galleryDir.exists()) galleryDir.mkdirs()
                
                val finalFile = java.io.File(galleryDir, media.filename)
                
                // Reassemble chunks into final file
                if (manager.reassembleFile(finalFile)) {
                    Log.d(TAG, "File finalized at ${finalFile.absolutePath}")
                    
                    // Update database with new local path
                    galleryDao.updateLocalPath(media.id, finalFile.absolutePath)
                    
                    // Scan so system gallery sees it
                    try {
                        android.media.MediaScannerConnection.scanFile(
                            context, 
                            arrayOf(finalFile.absolutePath), 
                            arrayOf(media.mimeType), 
                            null
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to scan file: ${e.message}")
                    }
                    
                    // Release manager and clean up cache (chunks)
                    manager.release(keepChunks = false)
                    activeStreamingManagers.remove(media.id)
                    
                    // Update streaming progress map to remove it
                    _streamingProgress.update { current ->
                        current - media.id
                    }
                } else {
                    Log.e(TAG, "Failed to reassemble file ${media.filename}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error finalizing download for ${media.filename}", e)
            }
        }
    }
}

class GalleryViewModelFactory(
    private val context: Context,
    private val mediaScanner: MediaScanner,
    private val syncManager: GallerySyncManager,
    private val database: CloudDatabase,
    private val restoreManager: GalleryRestoreManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GalleryViewModel::class.java)) {
            return GalleryViewModel(context, mediaScanner, syncManager, database, restoreManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

