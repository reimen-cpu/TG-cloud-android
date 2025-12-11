package com.telegram.cloud.gallery

import android.content.Context
import android.util.Log
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.prefs.BotConfig
import com.telegram.cloud.utils.ResourceGuard
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "GalleryRestoreManager"
private const val MAX_PARALLEL_DOWNLOADS = 3

class GalleryRestoreManager(
    private val context: Context,
    private val database: CloudDatabase,
    private val galleryDao: GalleryMediaDao,
    private val syncManager: GallerySyncManager
) {
    private val restoreMutex = Mutex()
    private var currentJob: Job? = null

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    private val _restoreProgress = MutableStateFlow(0f)
    val restoreProgress: StateFlow<Float> = _restoreProgress.asStateFlow()

    private val _currentFileName = MutableStateFlow<String?>(null)
    val currentFileName: StateFlow<String?> = _currentFileName.asStateFlow()

    sealed class RestoreState {
        object Idle : RestoreState()
        data class Restoring(val currentFile: String, val current: Int, val total: Int) : RestoreState()
        data class Error(val message: String) : RestoreState()
        object Completed : RestoreState()
    }

    suspend fun restoreAllSynced(
        config: BotConfig,
        onProgress: ((Int, Int, String) -> Unit)? = null
    ): Boolean = coroutineScope {
        ResourceGuard.markActive(ResourceGuard.Feature.MANUAL_SYNC) // Reusing manual sync guard
        val job = coroutineContext[Job] ?: error("Missing job context")
        restoreMutex.withLock {
            if (currentJob?.isActive == true) {
                Log.w(TAG, "Restore already running, skipping concurrent request")
                return@coroutineScope false
            }
            currentJob = job
        }

        val tokens = config.tokens
        if (tokens.isEmpty()) {
            Log.e(TAG, "No bot tokens configured")
            _restoreState.value = RestoreState.Error("No bot tokens configured")
            resetJob()
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            return@coroutineScope false
        }

        // Find files that are synced but NOT present locally
        val allSynced = galleryDao.getSynced()
        val toRestore = allSynced.filter { media ->
            val file = File(media.localPath)
            !file.exists()
        }

        if (toRestore.isEmpty()) {
            _restoreState.value = RestoreState.Completed
            resetSummaries()
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            resetJob()
            return@coroutineScope true
        }

        Log.i(TAG, "restoreAllSynced: Found ${toRestore.size} files to restore")
        val total = toRestore.size
        val completedCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)
        val failureCount = AtomicInteger(0)
        val activeProgresses = java.util.concurrent.ConcurrentHashMap<String, Float>()
        
        // Semaphore to limit parallel downloads
        val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)

        try {
            val jobs = toRestore.map { media ->
                async {
                    semaphore.withPermit {
                        ensureActive()
                        
                        // Update UI to show this file is starting
                        _currentFileName.value = media.filename
                        val currentCompleted = completedCount.get()
                        _restoreState.value = RestoreState.Restoring(media.filename, currentCompleted + 1, total)
                        onProgress?.invoke(currentCompleted + 1, total, media.filename)
                        
                        activeProgresses[media.filename] = 0f
                        
                        val resultPath = syncManager.downloadMediaFromTelegram(media, config) { progress ->
                            activeProgresses[media.filename] = progress
                            
                            // Calculate global progress
                            val currentActiveSum = activeProgresses.values.sum()
                            val globalProgress = (completedCount.get() + currentActiveSum) / total
                            _restoreProgress.value = globalProgress
                        }
                        
                        activeProgresses.remove(media.filename)
                        completedCount.incrementAndGet()
                        
                        if (resultPath != null) {
                            successCount.incrementAndGet()
                        } else {
                            failureCount.incrementAndGet()
                        }
                        
                        // Final progress update for this step
                        val globalProgress = completedCount.get().toFloat() / total
                        _restoreProgress.value = globalProgress
                    }
                }
            }
            
            jobs.awaitAll()

            val failures = failureCount.get()
            val success = successCount.get()
            _restoreState.value = if (failures == 0) RestoreState.Completed else RestoreState.Error("Restored $success, failed $failures")
            resetSummaries()
            return@coroutineScope true
        } catch (e: Exception) {
            Log.e(TAG, "restoreAllSynced failed", e)
            _restoreState.value = RestoreState.Error(e.message ?: "Restore failed")
            return@coroutineScope false
        } finally {
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            resetJob()
        }
    }

    suspend fun cancelRestore() {
        restoreMutex.withLock {
            currentJob?.cancel()
            currentJob = null
        }
        _restoreState.value = RestoreState.Idle
        _restoreProgress.value = 0f
        _currentFileName.value = null
    }

    private fun resetSummaries() {
        _currentFileName.value = null
        _restoreProgress.value = 1f
    }

    private suspend fun resetJob() {
        restoreMutex.withLock {
            currentJob = null
        }
    }
}
