package com.telegram.cloud.tasks

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.telegram.cloud.gallery.DownloadWorker
import com.telegram.cloud.gallery.UploadWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Manages multiple task queues (upload, download, gallery sync)
 * Inspired by ab-download-manager's QueueManager
 */
data class TaskProgress(
    val type: TaskType,
    val progress: Float,
    val fileName: String
)

class TaskQueueManager(private val context: Context) {
    companion object {
        private const val TAG = "TaskQueueManager"
        const val DEFAULT_MAX_CONCURRENT = 3
    }
    
    // Separate queues for different task types
    private val uploadQueue = TaskQueue(
        id = "upload_queue",
        name = "Upload Queue",
        type = TaskType.UPLOAD,
        maxConcurrent = DEFAULT_MAX_CONCURRENT,
        onTaskStatusChanged = { task ->
            Log.d(TAG, "Upload task ${task.id} status changed: ${task.status}")
        },
        onTaskCompleted = { task ->
            Log.d(TAG, "Upload task ${task.id} completed: ${task.fileName}")
        },
        onTaskFailed = { task, error ->
            Log.e(TAG, "Upload task ${task.id} failed: $error")
        }
    )
    
    private val downloadQueue = TaskQueue(
        id = "download_queue",
        name = "Download Queue",
        type = TaskType.DOWNLOAD,
        maxConcurrent = DEFAULT_MAX_CONCURRENT,
        onTaskStatusChanged = { task ->
            Log.d(TAG, "Download task ${task.id} status changed: ${task.status}")
        },
        onTaskCompleted = { task ->
            Log.d(TAG, "Download task ${task.id} completed: ${task.fileName}")
        },
        onTaskFailed = { task, error ->
            Log.e(TAG, "Download task ${task.id} failed: $error")
        }
    )
    
    private val _allTasks = MutableStateFlow<List<TaskItem>>(emptyList())
    val allTasks: StateFlow<List<TaskItem>> = _allTasks.asStateFlow()

    private val _progressUpdates = MutableSharedFlow<TaskProgress>(
        extraBufferCapacity = 32,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val progressUpdates = _progressUpdates.asSharedFlow()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    init {
        // Start queues
        uploadQueue.start()
        downloadQueue.start()
        
        // Combine all tasks from all queues
        scope.launch {
            combine(
                uploadQueue.queueItems,
                downloadQueue.queueItems
            ) { uploadTasks, downloadTasks ->
                uploadTasks + downloadTasks
            }.collect { tasks ->
                _allTasks.value = tasks
            }
        }
    }
    
    /**
     * Add upload task(s) to queue
     */
    suspend fun addUploadTasks(requests: List<com.telegram.cloud.domain.model.UploadRequest>) {
        val tasks = requests.map { request ->
            TaskItem(
                type = TaskType.UPLOAD,
                uploadRequest = request,
                fileName = request.displayName,
                sizeBytes = request.sizeBytes
            )
        }
        
        uploadQueue.addTasks(tasks)
        
        // Enqueue WorkManager jobs for each task
        tasks.forEach { task ->
            val inputData = workDataOf(
                UploadWorker.KEY_URI to task.uploadRequest!!.uri,
                UploadWorker.KEY_DISPLAY_NAME to task.fileName,
                UploadWorker.KEY_SIZE_BYTES to task.sizeBytes,
                "task_id" to task.id
            )
            
            val workRequest = OneTimeWorkRequestBuilder<UploadWorker>()
                .setInputData(inputData)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
        }
        
        Log.d(TAG, "Added ${tasks.size} upload tasks to queue")
    }
    
    /**
     * Add download task(s) to queue
     */
    suspend fun addDownloadTasks(requests: List<com.telegram.cloud.domain.model.DownloadRequest>) {
        val tasks = requests.map { request ->
            TaskItem(
                type = TaskType.DOWNLOAD,
                downloadRequest = request,
                fileName = request.file.fileName,
                sizeBytes = request.file.sizeBytes
            )
        }
        
        downloadQueue.addTasks(tasks)
        
        // Enqueue WorkManager jobs for each task
        tasks.forEach { task ->
            val downloadRequest = task.downloadRequest ?: return@forEach
            val inputData = workDataOf(
                DownloadWorker.KEY_FILE_NAME to task.fileName,
                DownloadWorker.KEY_MESSAGE_ID to downloadRequest.file.messageId,
                DownloadWorker.KEY_TARGET_PATH to downloadRequest.targetPath,
                "task_id" to task.id
            )
            
            val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(inputData)
                .build()
            
            WorkManager.getInstance(context).enqueue(workRequest)
        }
        
        Log.d(TAG, "Added ${tasks.size} download tasks to queue")
    }
    
    /**
     * Get upload queue
     */
    fun getUploadQueue(): TaskQueue = uploadQueue
    
    /**
     * Get download queue
     */
    fun getDownloadQueue(): TaskQueue = downloadQueue
    
    /**
     * Pause a task
     */
    suspend fun pauseTask(taskId: String) {
        val task = _allTasks.value.find { it.id == taskId } ?: return
        when (task.type) {
            TaskType.UPLOAD -> uploadQueue.pauseTask(taskId)
            TaskType.DOWNLOAD -> downloadQueue.pauseTask(taskId)
            TaskType.GALLERY_SYNC -> { /* Gallery sync handled separately */ }
        }
    }
    
    /**
     * Resume a task
     */
    suspend fun resumeTask(taskId: String) {
        val task = _allTasks.value.find { it.id == taskId } ?: return
        when (task.type) {
            TaskType.UPLOAD -> uploadQueue.resumeTask(taskId)
            TaskType.DOWNLOAD -> downloadQueue.resumeTask(taskId)
            TaskType.GALLERY_SYNC -> { /* Gallery sync handled separately */ }
        }
    }
    
    /**
     * Cancel a task
     */
    suspend fun cancelTask(taskId: String) {
        val task = _allTasks.value.find { it.id == taskId } ?: return
        when (task.type) {
            TaskType.UPLOAD -> uploadQueue.cancelTask(taskId)
            TaskType.DOWNLOAD -> downloadQueue.cancelTask(taskId)
            TaskType.GALLERY_SYNC -> { /* Gallery sync handled separately */ }
        }
        
        // Note: WorkManager jobs are cancelled automatically when task is removed from queue
        // Individual work IDs would need to be tracked if we want to cancel specific jobs
    }
    
    /**
     * Update task progress
     */
    fun updateTaskProgress(taskId: String, progress: Float) {
        val task = _allTasks.value.find { it.id == taskId } ?: return
        when (task.type) {
            TaskType.UPLOAD -> {
                uploadQueue.updateTaskProgress(taskId, progress)
                // Get updated task from queue after progress update
                val updatedTask = uploadQueue.getTask(taskId) ?: task
                emitProgress(updatedTask)
            }
            TaskType.DOWNLOAD -> {
                downloadQueue.updateTaskProgress(taskId, progress)
                // Get updated task from queue after progress update
                val updatedTask = downloadQueue.getTask(taskId) ?: task
                emitProgress(updatedTask)
            }
            TaskType.GALLERY_SYNC -> { /* Gallery sync handled separately */ }
        }
    }

    suspend fun markUploadTaskCompleted(taskId: String) {
        uploadQueue.completeTask(taskId)
    }

    suspend fun markUploadTaskFailed(taskId: String, error: String?) {
        uploadQueue.failTask(taskId, error)
    }

    suspend fun markDownloadTaskCompleted(taskId: String) {
        downloadQueue.completeTask(taskId)
    }

    suspend fun markDownloadTaskFailed(taskId: String, error: String?) {
        downloadQueue.failTask(taskId, error)
    }

    private fun emitProgress(task: TaskItem) {
        _progressUpdates.tryEmit(
            TaskProgress(
                type = task.type,
                progress = task.progress,
                fileName = task.fileName
            )
        )
    }
    
    /**
     * Clear upload queue
     */
    suspend fun clearUploadQueue() {
        uploadQueue.clear()
    }
    
    /**
     * Clear download queue
     */
    suspend fun clearDownloadQueue() {
        downloadQueue.clear()
    }
    
    /**
     * Get active tasks count
     */
    fun getActiveTasksCount(): Int {
        return uploadQueue.activeItems.value.size + downloadQueue.activeItems.value.size
    }
    
    /**
     * Get queued tasks count
     */
    fun getQueuedTasksCount(): Int {
        return uploadQueue.queueItems.value.count { it.status == TaskStatus.QUEUED } +
               downloadQueue.queueItems.value.count { it.status == TaskStatus.QUEUED }
    }
    
    /**
     * Dispose all queues
     */
    fun dispose() {
        uploadQueue.dispose()
        downloadQueue.dispose()
    }
}

