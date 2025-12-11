package com.telegram.cloud.tasks

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Screen to display and manage task queues
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskQueueScreen(
    taskQueueManager: TaskQueueManager,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val uploadQueue = taskQueueManager.getUploadQueue()
    val downloadQueue = taskQueueManager.getDownloadQueue()
    
    val uploadTasks by uploadQueue.queueItems.collectAsState()
    val downloadTasks by downloadQueue.queueItems.collectAsState()
    val activeUploadTasks by uploadQueue.activeItems.collectAsState()
    val activeDownloadTasks by downloadQueue.activeItems.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Task Queue") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            scope.launch {
                                taskQueueManager.clearUploadQueue()
                                taskQueueManager.clearDownloadQueue()
                            }
                        }
                    ) {
                        Icon(Icons.Default.ClearAll, contentDescription = "Clear All")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Statistics
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatCard(
                    title = "Uploads",
                    active = activeUploadTasks.size,
                    queued = uploadTasks.count { it.status == TaskStatus.QUEUED },
                    total = uploadTasks.size
                )
                StatCard(
                    title = "Downloads",
                    active = activeDownloadTasks.size,
                    queued = downloadTasks.count { it.status == TaskStatus.QUEUED },
                    total = downloadTasks.size
                )
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Upload tasks
            Text(
                "Upload Queue",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            if (uploadTasks.isEmpty()) {
                Text(
                    "No upload tasks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uploadTasks) { task ->
                        TaskItemCard(
                            task = task,
                            onPause = { scope.launch { taskQueueManager.pauseTask(task.id) } },
                            onResume = { scope.launch { taskQueueManager.resumeTask(task.id) } },
                            onCancel = { scope.launch { taskQueueManager.cancelTask(task.id) } }
                        )
                    }
                }
            }
            
            Spacer(Modifier.height(16.dp))
            
            // Download tasks
            Text(
                "Download Queue",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            if (downloadTasks.isEmpty()) {
                Text(
                    "No download tasks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(downloadTasks) { task ->
                        TaskItemCard(
                            task = task,
                            onPause = { scope.launch { taskQueueManager.pauseTask(task.id) } },
                            onResume = { scope.launch { taskQueueManager.resumeTask(task.id) } },
                            onCancel = { scope.launch { taskQueueManager.cancelTask(task.id) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    active: Int,
    queued: Int,
    total: Int
) {
    Card(
        modifier = Modifier
            .padding(horizontal = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "$active active",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "$queued queued",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "$total total",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TaskItemCard(
    task: TaskItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (task.status) {
                TaskStatus.RUNNING -> MaterialTheme.colorScheme.primaryContainer
                TaskStatus.COMPLETED -> MaterialTheme.colorScheme.tertiaryContainer
                TaskStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
                TaskStatus.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
                else -> MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.fileName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${(task.sizeBytes / 1024.0 / 1024.0).format(2)} MB • ${task.status.name}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Row {
                    when (task.status) {
                        TaskStatus.RUNNING -> {
                            IconButton(onClick = onPause) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause")
                            }
                        }
                        TaskStatus.PAUSED -> {
                            IconButton(onClick = onResume) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                            }
                        }
                        else -> {}
                    }
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Cancel, contentDescription = "Cancel")
                    }
                }
            }
            
            if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PAUSED) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${(task.progress * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            if (task.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Error: ${task.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private fun Double.format(decimals: Int): String {
    return "%.${decimals}f".format(this)
}

