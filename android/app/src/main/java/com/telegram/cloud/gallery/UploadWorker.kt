package com.telegram.cloud.gallery

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.prefs.ConfigStore
import com.telegram.cloud.data.repository.TelegramRepository
import com.telegram.cloud.domain.model.UploadRequest
import com.telegram.cloud.TelegramCloudApp
import com.telegram.cloud.tasks.TaskQueueManager
import com.telegram.cloud.utils.ResourceGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class UploadWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "UploadWorker"
        const val WORK_NAME = "upload_work"
        const val KEY_URI = "uri"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SIZE_BYTES = "size_bytes"
        const val KEY_TASK_ID = "task_id"
    }
    
    override suspend fun doWork(): Result {
        ResourceGuard.markActive(ResourceGuard.Feature.MANUAL_SYNC)
        
        // Create notification channel
        SyncNotificationManager.createNotificationChannel(context)
        val taskId = inputData.getString(KEY_TASK_ID)
        val taskQueueManager = (context.applicationContext as TelegramCloudApp).container.taskQueueManager
        
        return try {
            Log.d(TAG, "Starting upload worker...")
            
            // Get parameters
            val uri = inputData.getString(KEY_URI) ?: return Result.failure()
            val displayName = inputData.getString(KEY_DISPLAY_NAME) ?: "Unknown"
            val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)
            
            // Get bot config
            val configStore = ConfigStore(context)
            val config = configStore.configFlow.first()
            if (config == null) {
                Log.w(TAG, "Bot config not available")
                SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.UPLOAD)
                return Result.failure()
            }
            
            // Get database and repository
            val database = CloudDatabase.getDatabase(context)
            val botClient = com.telegram.cloud.data.remote.TelegramBotClient()
            val repository = TelegramRepository(context, configStore, database, botClient)
            // Set initial foreground info
            setForeground(
                SyncNotificationManager.createForegroundInfo(
                    context,
                    current = 0,
                    total = 1,
                    currentFile = displayName,
                    operationType = SyncNotificationManager.OperationType.UPLOAD
                )
            )
            
            // Create upload request
            val uploadRequest = UploadRequest(
                uri = uri,
                displayName = displayName,
                caption = null,
                sizeBytes = sizeBytes
            )
            
            // Use a coroutine scope to update foreground info asynchronously
            val foregroundUpdateScope = CoroutineScope(Dispatchers.Main)
            
            // Check if this upload can be resumed
            val uploadTaskId = inputData.getLong(KEY_TASK_ID, -1L)
            val canResume = if (uploadTaskId != -1L) {
                val task = database.uploadTaskDao().getById(uploadTaskId)
                task?.fileId != null && task.completedChunksJson != null
            } else {
                false
            }
            
            if (canResume && uploadTaskId != -1L) {
                Log.d(TAG, "Resuming upload for task $uploadTaskId")
                try {
                    repository.resumeUpload(uploadTaskId) { progress ->
                        // Update task queue progress
                        taskId?.let { taskQueueManager.updateTaskProgress(it, progress) }
                        
                        // Update notification
                        val progressInt = (progress * 100).toInt()
                        SyncNotificationManager.notifyProgress(
                            context,
                            current = progressInt,
                            total = 100,
                            currentFile = displayName,
                            operationType = SyncNotificationManager.OperationType.UPLOAD
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
                                            currentFile = displayName,
                                            operationType = SyncNotificationManager.OperationType.UPLOAD
                                        )
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to update foreground info", e)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Resume failed, falling back to normal upload", e)
                    // Fall through to normal upload
                    repository.upload(uploadRequest) { progress ->
                        taskId?.let { taskQueueManager.updateTaskProgress(it, progress) }
                        val progressInt = (progress * 100).toInt()
                        SyncNotificationManager.notifyProgress(
                            context,
                            current = progressInt,
                            total = 100,
                            currentFile = displayName,
                            operationType = SyncNotificationManager.OperationType.UPLOAD
                        )
                        if (progressInt % 10 == 0 || progressInt == 100) {
                            foregroundUpdateScope.launch {
                                try {
                                    setForeground(
                                        SyncNotificationManager.createForegroundInfo(
                                            context,
                                            current = progressInt,
                                            total = 100,
                                            currentFile = displayName,
                                            operationType = SyncNotificationManager.OperationType.UPLOAD
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
                // Normal upload
                repository.upload(uploadRequest) { progress ->
                
                // Update task queue progress
                taskId?.let { taskQueueManager.updateTaskProgress(it, progress) }
                
                // Update notification
                val progressInt = (progress * 100).toInt()
                SyncNotificationManager.notifyProgress(
                    context,
                    current = progressInt,
                    total = 100,
                    currentFile = displayName,
                    operationType = SyncNotificationManager.OperationType.UPLOAD
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
                                    currentFile = displayName,
                                    operationType = SyncNotificationManager.OperationType.UPLOAD
                                )
                            )
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to update foreground info", e)
                        }
                    }
                }
            }
            }
            
            // Show completion notification
            SyncNotificationManager.showCompletedNotification(context, 1, SyncNotificationManager.OperationType.UPLOAD)
            
            // Pequeño delay para asegurar que la inserción en la base de datos se complete
            delay(150)
            
            // Recargar archivos desde la base de datos inmediatamente (similar a LoadFiles en desktop)
            repository.reloadFilesFromDatabase()
            
            Log.d(TAG, "Upload worker completed successfully, files cache reloaded, marking task $taskId as completed")
            taskId?.let { taskQueueManager.markUploadTaskCompleted(it) }
            Result.success()
            
        } catch (e: CancellationException) {
            Log.d(TAG, "Upload worker was cancelled")
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.UPLOAD)
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in upload worker", e)
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.UPLOAD)
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            taskId?.let { taskQueueManager.markUploadTaskFailed(it, e.message) }
            Result.failure()
        } finally {
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
        }
    }
}

