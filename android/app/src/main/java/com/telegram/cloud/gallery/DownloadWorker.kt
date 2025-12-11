package com.telegram.cloud.gallery

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.prefs.ConfigStore
import com.telegram.cloud.data.repository.TelegramRepository
import com.telegram.cloud.domain.model.CloudFile
import com.telegram.cloud.domain.model.DownloadRequest
import com.telegram.cloud.TelegramCloudApp
import com.telegram.cloud.tasks.TaskQueueManager
import com.telegram.cloud.utils.ResourceGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class DownloadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "DownloadWorker"
        const val WORK_NAME = "download_work"
        const val KEY_FILE_NAME = "file_name"
        const val KEY_MESSAGE_ID = "message_id"
        const val KEY_TARGET_PATH = "target_path"
    }
    
    override suspend fun doWork(): Result {
        ResourceGuard.markActive(ResourceGuard.Feature.MANUAL_SYNC)
        
        // Create notification channel
        SyncNotificationManager.createNotificationChannel(context)
        val taskId = inputData.getString("task_id")
        val taskQueueManager = (context.applicationContext as TelegramCloudApp).container.taskQueueManager
        
        return try {
            Log.d(TAG, "Starting download worker...")
            
            // Get parameters
            val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
            val messageId = inputData.getLong(KEY_MESSAGE_ID, 0L)
            val targetPath = inputData.getString(KEY_TARGET_PATH) ?: return Result.failure()
            
            // Get bot config
            val configStore = ConfigStore(context)
            val config = configStore.configFlow.first()
            if (config == null) {
                Log.w(TAG, "Bot config not available")
                SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.DOWNLOAD)
                return Result.failure()
            }
            
            // Get database and repository
            val database = CloudDatabase.getDatabase(context)
            val botClient = com.telegram.cloud.data.remote.TelegramBotClient()
            val repository = TelegramRepository(context, configStore, database, botClient)
            
            // Get the actual CloudFile from repository
            val files = repository.files.first()
            val cloudFile = files.find { it.messageId == messageId && it.fileName == fileName }
                ?: CloudFile(
                    id = 0L,
                    fileName = fileName,
                    messageId = messageId,
                    fileId = "", // Will be retrieved by repository
                    sizeBytes = 0L,
                    mimeType = "",
                    uploadedAt = System.currentTimeMillis(),
                    caption = null,
                    checksum = null,
                    shareLink = null
                )
            
            // Set initial foreground info
            setForeground(
                SyncNotificationManager.createForegroundInfo(
                    context,
                    current = 0,
                    total = 1,
                    currentFile = fileName,
                    operationType = SyncNotificationManager.OperationType.DOWNLOAD
                )
            )
            
            // Create download request
            val downloadRequest = DownloadRequest(
                file = cloudFile,
                targetPath = targetPath
            )
            
            // Use a coroutine scope to update foreground info asynchronously
            val foregroundUpdateScope = CoroutineScope(Dispatchers.Main)
            
            // Check if this download can be resumed
            val downloadTaskId = inputData.getLong("task_id", -1L)
            val canResume = if (downloadTaskId != -1L) {
                val task = database.downloadTaskDao().getById(downloadTaskId)
                task?.totalChunks ?: 0 > 0 && task?.tempChunkDir != null
            } else {
                false
            }
            
            if (canResume && downloadTaskId != -1L) {
                Log.d(TAG, "Resuming download for task $downloadTaskId")
                // Update initial progress to show progress bar immediately
                taskId?.let { taskQueueManager.updateTaskProgress(it, 0.01f) }
                try {
                    repository.resumeDownload(downloadTaskId) { progress ->
                        // Update task queue progress
                        taskId?.let { taskQueueManager.updateTaskProgress(it, progress) }
                        
                        // Update notification
                        val progressInt = (progress * 100).toInt()
                        SyncNotificationManager.notifyProgress(
                            context,
                            current = progressInt,
                            total = 100,
                            currentFile = fileName,
                            operationType = SyncNotificationManager.OperationType.DOWNLOAD
                        )
                        
                        // Update foreground info periodically
                        if (progressInt % 10 == 0 || progressInt == 100) {
                            foregroundUpdateScope.launch {
                                try {
                                    setForeground(
                                        SyncNotificationManager.createForegroundInfo(
                                            context,
                                            current = progressInt,
                                            total = 100,
                                            currentFile = fileName,
                                            operationType = SyncNotificationManager.OperationType.DOWNLOAD
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to update foreground info", e)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Resume failed, falling back to normal download", e)
                    // Fall through to normal download
                    // Update initial progress to show progress bar immediately
                    taskId?.let { taskQueueManager.updateTaskProgress(it, 0.01f) }
                    repository.download(downloadRequest) { progress ->
                        taskId?.let { taskQueueManager.updateTaskProgress(it, progress) }
                        val progressInt = (progress * 100).toInt()
                        SyncNotificationManager.notifyProgress(
                            context,
                            current = progressInt,
                            total = 100,
                            currentFile = fileName,
                            operationType = SyncNotificationManager.OperationType.DOWNLOAD
                        )
                        if (progressInt % 10 == 0 || progressInt == 100) {
                            foregroundUpdateScope.launch {
                                try {
                                    setForeground(
                                        SyncNotificationManager.createForegroundInfo(
                                            context,
                                            current = progressInt,
                                            total = 100,
                                            currentFile = fileName,
                                            operationType = SyncNotificationManager.OperationType.DOWNLOAD
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to update foreground info", e)
                                }
                            }
                        }
                    }
                }
            } else {
                // Normal download
                // Update initial progress to show progress bar immediately
                taskId?.let { taskQueueManager.updateTaskProgress(it, 0.01f) }
                
                repository.download(downloadRequest) { progress ->
                
                // Update task queue progress
                taskId?.let { taskQueueManager.updateTaskProgress(it, progress) }
                
                // Update notification
                val progressInt = (progress * 100).toInt()
                SyncNotificationManager.notifyProgress(
                    context,
                    current = progressInt,
                    total = 100,
                    currentFile = fileName,
                    operationType = SyncNotificationManager.OperationType.DOWNLOAD
                )
                
                // Update foreground info periodically
                if (progressInt % 10 == 0 || progressInt == 100) {
                    foregroundUpdateScope.launch {
                        try {
                            setForeground(
                                SyncNotificationManager.createForegroundInfo(
                                    context,
                                    current = progressInt,
                                    total = 100,
                                    currentFile = fileName,
                                    operationType = SyncNotificationManager.OperationType.DOWNLOAD
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update foreground info", e)
                        }
                    }
                }
            }
            }

            taskId?.let { taskQueueManager.updateTaskProgress(it, 100f) }

            // Show completion notification
            SyncNotificationManager.showCompletedNotification(context, 1, SyncNotificationManager.OperationType.DOWNLOAD)
            
            Log.d(TAG, "Download worker completed successfully")
            taskId?.let { taskQueueManager.markDownloadTaskCompleted(it) }
            Result.success()
            
        } catch (e: CancellationException) {
            Log.d(TAG, "Download worker was cancelled")
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.DOWNLOAD)
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in download worker", e)
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.DOWNLOAD)
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            taskId?.let { taskQueueManager.markDownloadTaskFailed(it, e.message) }
            Result.failure()
        } finally {
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
        }
    }
}

