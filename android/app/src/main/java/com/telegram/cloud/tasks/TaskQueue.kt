package com.telegram.cloud.tasks

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages a queue of tasks with concurrent execution support
 * Inspired by ab-download-manager's DownloadQueue
 */
class TaskQueue(
    val id: String,
    val name: String,
    val type: TaskType,
    private val maxConcurrent: Int = 3,
    private val onTaskStatusChanged: (TaskItem) -> Unit = {},
    private val onTaskCompleted: (TaskItem) -> Unit = {},
    private val onTaskFailed: (TaskItem, String) -> Unit = { _, _ -> }
) {
    companion object {
        private const val TAG = "TaskQueue"
    }
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    
    private val _queueItems = MutableStateFlow<List<TaskItem>>(emptyList())
    val queueItems: StateFlow<List<TaskItem>> = _queueItems.asStateFlow()
    
    private val _activeItems = MutableStateFlow<Set<String>>(emptySet())
    val activeItems: StateFlow<Set<String>> = _activeItems.asStateFlow()
    
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()
    
    private val activeTasks = mutableMapOf<String, Job>()
    
    init {
        // Start queue processor
        scope.launch {
            while (true) {
                processQueue()
                delay(500) // Check every 500ms
            }
        }
    }
    
    /**
     * Add a task to the queue
     */
    suspend fun addTask(task: TaskItem) = mutex.withLock {
        _queueItems.update { current ->
            if (current.any { it.id == task.id }) {
                current // Already exists
            } else {
                current + task
            }
        }
        Log.d(TAG, "Task ${task.id} added to queue $name. Queue size: ${_queueItems.value.size}")
    }
    
    /**
     * Add multiple tasks to the queue
     */
    suspend fun addTasks(tasks: List<TaskItem>) = mutex.withLock {
        _queueItems.update { current ->
            val existingIds = current.map { it.id }.toSet()
            val newTasks = tasks.filter { it.id !in existingIds }
            current + newTasks
        }
        Log.d(TAG, "Added ${tasks.size} tasks to queue $name. Queue size: ${_queueItems.value.size}")
    }
    
    /**
     * Remove a task from the queue
     */
    suspend fun removeTask(taskId: String) = mutex.withLock {
        // Cancel if running
        activeTasks[taskId]?.cancel()
        activeTasks[taskId]?.cancel()
        activeTasks.remove(taskId)
        
        _queueItems.update { it.filter { item -> item.id != taskId } }
        _activeItems.update { it - taskId }
        Log.d(TAG, "Task $taskId removed from queue $name")
    }
    
    /**
     * Pause a task
     */
    suspend fun pauseTask(taskId: String) = mutex.withLock {
        val task = _queueItems.value.find { it.id == taskId } ?: return@withLock
        if (task.status == TaskStatus.RUNNING) {
            task.status = TaskStatus.PAUSED
            activeTasks[taskId]?.cancel()
            activeTasks.remove(taskId)
            _activeItems.update { it - taskId }
            onTaskStatusChanged(task)
            Log.d(TAG, "Task $taskId paused")
        }
    }
    
    /**
     * Resume a paused task
     */
    suspend fun resumeTask(taskId: String) = mutex.withLock {
        val task = _queueItems.value.find { it.id == taskId } ?: return@withLock
        if (task.status == TaskStatus.PAUSED) {
            task.status = TaskStatus.QUEUED
            onTaskStatusChanged(task)
            Log.d(TAG, "Task $taskId resumed")
        }
    }
    
    /**
     * Cancel a task
     */
    suspend fun cancelTask(taskId: String) = mutex.withLock {
        val task = _queueItems.value.find { it.id == taskId } ?: return@withLock
        task.status = TaskStatus.CANCELLED
        activeTasks[taskId]?.cancel()
        activeTasks[taskId]?.cancel()
        activeTasks.remove(taskId)
        _activeItems.update { it - taskId }
        onTaskStatusChanged(task)
        _queueItems.update { it.filter { item -> item.id != taskId } }
        Log.d(TAG, "Task $taskId cancelled")
    }
    
    /**
     * Clear all tasks from queue
     */
    suspend fun clear() = mutex.withLock {
        activeTasks.values.forEach { it.cancel() }
        activeTasks.clear()
        _queueItems.value = emptyList()
        _activeItems.value = emptySet()
        Log.d(TAG, "Queue $name cleared")
    }
    
    /**
     * Start processing the queue
     */
    fun start() {
        if (_isActive.value) return
        _isActive.value = true
        Log.d(TAG, "Queue $name started")
    }
    
    /**
     * Stop processing the queue
     */
    suspend fun stop() = mutex.withLock {
        if (!_isActive.value) return@withLock
        _isActive.value = false
        
        // Pause all active tasks
        activeTasks.values.forEach { it.cancel() }
        activeTasks.clear()
        
        _queueItems.value.forEach { task ->
            if (task.status == TaskStatus.RUNNING) {
                task.status = TaskStatus.PAUSED
                onTaskStatusChanged(task)
            }
        }
        
        _activeItems.value = emptySet()
        Log.d(TAG, "Queue $name stopped")
    }
    
    /**
     * Process the queue - start tasks up to maxConcurrent
     */
    private suspend fun processQueue() = mutex.withLock {
        if (!_isActive.value) return@withLock
        
        val currentActive = _activeItems.value.size
        val availableSlots = maxConcurrent - currentActive
        
        if (availableSlots <= 0) return@withLock
        
        // Get queued tasks that can be started
        val queuedTasks = _queueItems.value
            .filter { it.status == TaskStatus.QUEUED }
            .take(availableSlots)
        
        queuedTasks.forEach { task ->
            startTask(task)
        }
    }
    
    /**
     * Start executing a task
     */
    private fun startTask(task: TaskItem) {
        if (task.id in activeTasks) return // Already running
        
        task.status = TaskStatus.RUNNING
        task.startedAt = System.currentTimeMillis()
        _activeItems.update { it + task.id }
        onTaskStatusChanged(task)
        
        val job = scope.launch {
            try {
                awaitCancellation()
            } finally {
                mutex.withLock {
                    if (activeTasks.remove(task.id) != null) {
                        _activeItems.update { it - task.id }
                        onTaskStatusChanged(task)
                    }
                }
            }
        }
        
        activeTasks[task.id] = job
        Log.d(TAG, "Task ${task.id} started in queue $name")
    }

    suspend fun completeTask(taskId: String) {
        mutex.withLock {
            val task = _queueItems.value.find { it.id == taskId } ?: return
            task.status = TaskStatus.COMPLETED
            task.progress = 1f
            task.completedAt = System.currentTimeMillis()
            activeTasks[taskId]?.cancel()
            activeTasks.remove(taskId)
            _activeItems.update { it - taskId }
            _queueItems.update { tasks -> tasks.map { if (it.id == taskId) task else it } }
            onTaskStatusChanged(task)
            onTaskCompleted(task)
            Log.d(TAG, "Task $taskId completed in queue $name")
        }
        
        // Auto-remove completed task after 2 seconds to clean up the queue
        scope.launch {
            delay(2000)
            mutex.withLock {
                _queueItems.update { tasks -> tasks.filter { it.id != taskId } }
                Log.d(TAG, "Task $taskId removed from queue $name after completion")
            }
        }
    }

    suspend fun failTask(taskId: String, error: String?) {
        mutex.withLock {
            val task = _queueItems.value.find { it.id == taskId } ?: return
            task.status = TaskStatus.FAILED
            task.error = error
            task.completedAt = System.currentTimeMillis()
            activeTasks[taskId]?.cancel()
            activeTasks.remove(taskId)
            _activeItems.update { it - taskId }
            _queueItems.update { tasks -> tasks.map { if (it.id == taskId) task else it } }
            onTaskStatusChanged(task)
            onTaskFailed(task, error ?: "Unknown error")
            Log.d(TAG, "Task $taskId failed in queue $name: $error")
        }
        
        // Auto-remove failed task after 2 seconds to clean up the queue
        scope.launch {
            delay(2000)
            mutex.withLock {
                _queueItems.update { tasks -> tasks.filter { it.id != taskId } }
                Log.d(TAG, "Task $taskId removed from queue $name after failure")
            }
        }
    }
    
    /**
     * Update task progress
     */
    fun updateTaskProgress(taskId: String, progress: Float) {
        val newProgress = progress.coerceIn(0f, 1f)
        val task = _queueItems.value.find { it.id == taskId } ?: return
        if (task.progress != newProgress) {
            val updatedTask = task.copy(progress = newProgress)
            _queueItems.update { tasks -> tasks.map { if (it.id == taskId) updatedTask else it } }
            onTaskStatusChanged(updatedTask)
            Log.d(TAG, "Task $taskId progress updated to ${(newProgress * 100).toInt()}%")
        }
    }
    
    /**
     * Get task by ID
     */
    fun getTask(taskId: String): TaskItem? {
        return _queueItems.value.find { it.id == taskId }
    }
    
    /**
     * Dispose resources
     */
    fun dispose() {
        scope.cancel()
        activeTasks.values.forEach { it.cancel() }
        activeTasks.clear()
    }
}

