package com.telegram.cloud.data.remote

import android.util.Log
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "UploadCancellation"

/**
 * Global singleton to track and cancel upload jobs.
 * 
 * Since UploadWorker creates its own TelegramRepository instance,
 * we need a global registry to track jobs across instances.
 */
object UploadCancellationManager {
    
    // Map of task queue ID -> Job
    private val activeJobs = ConcurrentHashMap<String, Job>()
    
    // Set of cancelled task IDs (used to prevent starting cancelled tasks)
    private val cancelledTasks = ConcurrentHashMap.newKeySet<String>()
    
    /**
     * Register a job for a task.
     * Call this when starting an upload.
     */
    fun registerJob(taskQueueId: String, job: Job) {
        // Check if was cancelled before starting
        if (taskQueueId in cancelledTasks) {
            Log.i(TAG, "Task $taskQueueId was cancelled before starting, cancelling job immediately")
            job.cancel()
            cancelledTasks.remove(taskQueueId)
            return
        }
        
        activeJobs[taskQueueId] = job
        Log.d(TAG, "Registered job for task $taskQueueId, active jobs: ${activeJobs.size}")
    }
    
    /**
     * Unregister a job when upload completes or fails.
     */
    fun unregisterJob(taskQueueId: String) {
        activeJobs.remove(taskQueueId)
        cancelledTasks.remove(taskQueueId)
        Log.d(TAG, "Unregistered job for task $taskQueueId, active jobs: ${activeJobs.size}")
    }
    
    /**
     * Cancel an upload by its task queue ID.
     * Returns true if a job was found and cancelled.
     */
    fun cancelUpload(taskQueueId: String): Boolean {
        // Mark as cancelled (in case job hasn't started yet)
        cancelledTasks.add(taskQueueId)
        
        val job = activeJobs.remove(taskQueueId)
        return if (job != null) {
            Log.i(TAG, "Cancelling upload for task $taskQueueId")
            job.cancel()
            true
        } else {
            Log.w(TAG, "No active job found for task $taskQueueId (marked for cancellation)")
            false
        }
    }
    
    /**
     * Check if a task has been cancelled.
     * Call this before starting expensive operations.
     */
    fun isCancelled(taskQueueId: String): Boolean {
        return taskQueueId in cancelledTasks
    }
    
    /**
     * Get count of active uploads.
     */
    fun getActiveCount(): Int = activeJobs.size
}
