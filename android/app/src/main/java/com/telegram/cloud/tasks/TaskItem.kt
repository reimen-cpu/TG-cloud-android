package com.telegram.cloud.tasks

import com.telegram.cloud.domain.model.DownloadRequest
import com.telegram.cloud.domain.model.UploadRequest
import java.util.UUID

/**
 * Represents a single task (upload or download) in the queue
 */
data class TaskItem(
    val id: String = UUID.randomUUID().toString(),
    val type: TaskType,
    val uploadRequest: UploadRequest? = null,
    val downloadRequest: DownloadRequest? = null,
    val fileName: String,
    val sizeBytes: Long,
    var status: TaskStatus = TaskStatus.QUEUED,
    var progress: Float = 0f,
    var error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var startedAt: Long? = null,
    var completedAt: Long? = null
) {
    val isActive: Boolean
        get() = status == TaskStatus.RUNNING || status == TaskStatus.PAUSED
    
    val isCompleted: Boolean
        get() = status == TaskStatus.COMPLETED || status == TaskStatus.FAILED || status == TaskStatus.CANCELLED
}

enum class TaskType {
    UPLOAD,
    DOWNLOAD,
    GALLERY_SYNC
}

enum class TaskStatus {
    QUEUED,      // Waiting in queue
    RUNNING,     // Currently executing
    PAUSED,      // Paused by user
    COMPLETED,   // Successfully completed
    FAILED,      // Failed with error
    CANCELLED    // Cancelled by user
}

