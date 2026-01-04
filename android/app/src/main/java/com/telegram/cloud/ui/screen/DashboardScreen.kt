package com.telegram.cloud.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import com.telegram.cloud.R
import com.telegram.cloud.data.prefs.BotConfig
import com.telegram.cloud.domain.model.CloudFile
import com.telegram.cloud.ui.DashboardState
import com.telegram.cloud.tasks.TaskType
import com.telegram.cloud.ui.DashboardActions
import com.telegram.cloud.ui.theme.Spacing
import com.telegram.cloud.ui.theme.Radius
import com.telegram.cloud.ui.theme.Elevation
import com.telegram.cloud.ui.theme.ComponentSize
import com.telegram.cloud.ui.components.AnimatedIconButton
import com.telegram.cloud.ui.utils.HapticFeedbackType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Semantic colors using MaterialTheme
private object AppColors {
    val success = Color(0xFF66BB6A)   // Green for uploads
    val download = Color(0xFF42A5F5)  // Blue for downloads
    val share = Color(0xFFAB47BC)     // Purple for share
    val warning = Color(0xFFFF9800)   // Orange for gallery sync
    
    // File type colors (muted)
    val fileImage = Color(0xFF66BB6A)
    val fileVideo = Color(0xFFEF5350)
    val fileAudio = Color(0xFFAB47BC)
    val fileDocument = Color(0xFF42A5F5)
    val fileOther = Color(0xFF78909C)
}

enum class SortBy { NAME, SIZE, DATE, TYPE }
enum class SortOrder { ASCENDING, DESCENDING }

/**
 * Main dashboard screen.
 * Optimized to use [DashboardActions] interface.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
@Suppress("DEPRECATION")

fun DashboardScreen(
    state: DashboardState,
    actions: DashboardActions, // Use the new interface
    // Gallery sync state
    isGallerySyncing: Boolean = false,
    gallerySyncProgress: Float = 0f,
    gallerySyncFileName: String? = null,
    // Link download state
    isLinkDownloading: Boolean = false,
    linkDownloadProgress: Float = 0f,
    linkDownloadFileName: String? = null
) {
    // Unused config parameter removed
    var searchQuery by remember { mutableStateOf("") }
    var sortBy by remember { mutableStateOf(SortBy.DATE) }
    var sortOrder by remember { mutableStateOf(SortOrder.DESCENDING) }
    var showSortMenu by remember { mutableStateOf(false) }
    var showMainMenu by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<CloudFile?>(null) }
    var fileForOptions by remember { mutableStateOf<CloudFile?>(null) }
    var isRefreshing by remember { mutableStateOf(false) }
    var selectedFiles by remember { mutableStateOf<Set<Long>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val swipeRefreshState = rememberSwipeRefreshState(state.isSyncing)
    var horizontalDragOffset by remember { mutableStateOf(0f) }
    val context = LocalContext.current
    
    val filteredFiles by remember(state.files, searchQuery, sortBy, sortOrder) {
        derivedStateOf {
            state.files
                .filter { file ->
                    searchQuery.isEmpty() || 
                    file.fileName.contains(searchQuery, ignoreCase = true)
                }
                .sortedWith { a, b ->
                    val comparison = when (sortBy) {
                        SortBy.NAME -> a.fileName.compareTo(b.fileName, ignoreCase = true)
                        SortBy.SIZE -> a.sizeBytes.compareTo(b.sizeBytes)
                        SortBy.DATE -> a.uploadedAt.compareTo(b.uploadedAt)
                        SortBy.TYPE -> getMimeCategory(a.mimeType).compareTo(getMimeCategory(b.mimeType))
                    }
                    if (sortOrder == SortOrder.ASCENDING) comparison else -comparison
                }
        }
    }
    
    val totalSize = state.files.sumOf { it.sizeBytes }
    val fileCount = state.files.size
    
    // Delete confirmation dialog
    fileToDelete?.let { file ->
        DeleteConfirmationDialog(
            fileName = file.fileName,
            onConfirm = {
                actions.onDeleteLocal(file)
                fileToDelete = null
            },
            onDismiss = { fileToDelete = null }
        )
    }
    
    // File options menu
    fileForOptions?.let { file ->
        FileOptionsMenu(
            file = file,
            onDismiss = { fileForOptions = null },
            onDownload = {
                actions.onDownloadClick(file)
                fileForOptions = null
            },
            onShare = {
                actions.onShareClick(file)
                fileForOptions = null
            },
            onCopyLink = {
                actions.onCopyLink(file)
                fileForOptions = null
            },
            onDelete = {
                fileToDelete = file
                fileForOptions = null
            }
        )
    }
    
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                // Umbral de 100dp convertido a píxeles
                val thresholdPx = 100.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // Si el desplazamiento absoluto es mayor a 100dp (deslizar de derecha a izquierda)
                        if (horizontalDragOffset < -thresholdPx) {
                            actions.onOpenGallery()
                        }
                        horizontalDragOffset = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        // Solo acumular desplazamientos negativos (de derecha a izquierda)
                        if (dragAmount < 0) {
                            horizontalDragOffset += dragAmount
                        }
                        change.consume()
                    }
                )
            },
        topBar = {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainer
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = Spacing.screenPadding, vertical = Spacing.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cloud icon
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(Spacing.sm))
                    
                    // Title - takes available space
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            stringResource(R.string.subtitle_tagline),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // Sync indicator - Optimized with infiniteTransition
                    val primaryColor = MaterialTheme.colorScheme.primary
                    
                    if (state.isSyncing) {
                        // Manual low-level animation loop using frame clock
                        // This avoids any potential issues with Animatable or InfiniteTransition on specific devices
                        val currentRotation = remember { androidx.compose.runtime.mutableStateOf(0f) }
                        val currentPulseAlpha = remember { androidx.compose.runtime.mutableStateOf(0.3f) }
                        
                        androidx.compose.runtime.LaunchedEffect(Unit) {
                            val startTime = androidx.compose.runtime.withFrameNanos { it }
                            while (true) {
                                androidx.compose.runtime.withFrameNanos { time ->
                                    val elapsed = (time - startTime) / 1_000_000L // convert to ms
                                    
                                    // Rotation: Unbounded for smooth multi-speed arcs
                                    currentRotation.value = (elapsed / 2000f) * 360f
                                    
                                    // Pulse: 0.3->0.6->0.3 every 2000ms
                                    val pulseProgress = (elapsed % 2000) / 2000f
                                    currentPulseAlpha.value = if (pulseProgress < 0.5f) {
                                        0.3f + (pulseProgress * 2 * 0.3f) // 0.3 to 0.6
                                    } else {
                                        0.6f - ((pulseProgress - 0.5f) * 2 * 0.3f) // 0.6 to 0.3
                                    }
                                }
                            }
                        }

                        Box(
                            modifier = Modifier.size(48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(modifier = Modifier.size(36.dp)) {
                                // Background glow ring (pulsing)
                                drawArc(
                                    color = primaryColor.copy(alpha = currentPulseAlpha.value * 0.5f),
                                    startAngle = currentRotation.value, // Main rotation for backing too
                                    sweepAngle = 360f,
                                    useCenter = false,
                                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round)
                                )
                                
                                // Secondary arc (slower, offset)
                                drawArc(
                                    color = primaryColor.copy(alpha = 0.3f),
                                    startAngle = currentRotation.value * 0.7f + 180f,
                                    sweepAngle = 120f,
                                    useCenter = false,
                                    style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                                )
                                
                                // Main arc (primary)
                                drawArc(
                                    color = primaryColor,
                                    startAngle = currentRotation.value,
                                    sweepAngle = 90f,
                                    useCenter = false,
                                    style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round)
                                )
                                
                                // Highlight arc (faster, counter-rotating)
                                drawArc(
                                    color = primaryColor.copy(alpha = 0.7f),
                                    startAngle = -currentRotation.value * 1.3f,
                                    sweepAngle = 45f,
                                    useCenter = false,
                                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                                )
                            }
                        }
                    }
                    
                    // Menu button
                    Box {
                        AnimatedIconButton(
                            onClick = { showMainMenu = true },
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Menu",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            hapticType = HapticFeedbackType.LIGHT_CLICK
                        )

                        DropdownMenu(
                            expanded = showMainMenu,
                            onDismissRequest = { showMainMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(ComponentSize.iconMedium))
                                        Spacer(Modifier.width(Spacing.md))
                                        Text(stringResource(R.string.create_backup), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                    }
                                },
                                onClick = {
                                    showMainMenu = false
                                    actions.onCreateBackup()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(ComponentSize.iconMedium))
                                        Spacer(Modifier.width(Spacing.md))
                                        Text(stringResource(R.string.restore_backup), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                    }
                                },
                                onClick = {
                                    showMainMenu = false
                                    actions.onRestoreBackup()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Image, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(ComponentSize.iconMedium))
                                        Spacer(Modifier.width(Spacing.md))
                                        Text(stringResource(R.string.cloud_gallery), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                    }
                                },
                                onClick = {
                                    showMainMenu = false
                                    actions.onOpenGallery()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Queue, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(ComponentSize.iconMedium))
                                        Spacer(Modifier.width(Spacing.md))
                                        Text(stringResource(R.string.task_queue), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                    }
                                },
                                onClick = {
                                    showMainMenu = false
                                    actions.onOpenTaskQueue()
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(ComponentSize.iconMedium))
                                        Spacer(Modifier.width(Spacing.md))
                                        Text(stringResource(R.string.settings), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                    }
                                },
                                onClick = {
                                    showMainMenu = false
                                    actions.onOpenConfig()
                                }
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (selectedFiles.isNotEmpty()) {
                SelectionActionBar(
                    selectedCount = selectedFiles.size,
                    onDownload = {
                        val filesToDownload = filteredFiles.filter { selectedFiles.contains(it.id) }
                        if (filesToDownload.isNotEmpty()) {
                            actions.onDownloadMultiple(filesToDownload)
                        }
                        selectedFiles = emptySet()
                    },
                    onShare = {
                        val filesToShare = filteredFiles.filter { selectedFiles.contains(it.id) }
                        if (filesToShare.isNotEmpty()) {
                            actions.onShareMultiple(filesToShare)
                        }
                        selectedFiles = emptySet()
                    },
                    onDelete = {
                        val filesToDelete = filteredFiles.filter { selectedFiles.contains(it.id) }
                        if (filesToDelete.isNotEmpty()) {
                            actions.onDeleteMultiple(filesToDelete)
                        }
                        selectedFiles = emptySet()
                    },
                    onClear = {
                        selectedFiles = emptySet()
                    }
                )
            }
        }
    ) { innerPadding ->
        SwipeRefresh(
            state = swipeRefreshState,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    actions.onRefresh()
                    delay(500)
                    isRefreshing = false
                }
            },
            indicator = { state, trigger ->
                com.google.accompanist.swiperefresh.SwipeRefreshIndicator(
                    state = state,
                    refreshTriggerDistance = trigger,
                    backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                    scale = true
                )
            }
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = Spacing.screenPadding),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
                contentPadding = PaddingValues(
                    top = Spacing.lg,
                    bottom = Spacing.screenPadding
                )
            ) {

                item {
                    StatsCard(
                        totalSize = totalSize,
                        fileCount = fileCount,
                        onGalleryClick = actions::onOpenGallery
                    )
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                    ) {
                        ActionButton(
                            onClick = actions::onUploadClick,
                            icon = Icons.Default.CloudUpload,
                            label = stringResource(R.string.upload),
                            modifier = Modifier.weight(1f)
                        )

                        ActionButton(
                            onClick = actions::onDownloadFromLinkClick,
                            icon = Icons.Default.CloudDownload,
                            label = stringResource(R.string.download),
                            modifier = Modifier.weight(1f),
                            iconTint = AppColors.download
                        )
                    }
                }

                item {
                    val sortNameLabel = stringResource(R.string.sort_name)
                    val sortSizeLabel = stringResource(R.string.sort_size)
                    val sortDateLabel = stringResource(R.string.sort_date)
                    val sortTypeLabel = stringResource(R.string.sort_type)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Search bar - takes available space
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = {
                                Text(
                                    stringResource(R.string.search_files),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            },
                            trailingIcon = {
                                if (searchQuery.isNotEmpty()) {
                                    IconButton(
                                        onClick = { searchQuery = "" },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Close,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            textStyle = MaterialTheme.typography.bodySmall,
                            shape = RoundedCornerShape(Radius.md),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent
                            )
                        )
                        
                        // Sort selector
                        Box {
                            Card(
                                modifier = Modifier
                                    .height(48.dp)
                                    .clickable(onClick = { showSortMenu = true }),
                                shape = RoundedCornerShape(Radius.md),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                                elevation = CardDefaults.cardElevation(defaultElevation = Elevation.level1)
                            ) {
                                Row(
                                    modifier = Modifier
                                        .padding(horizontal = Spacing.md)
                                        .fillMaxHeight(),
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Sort,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        when (sortBy) {
                                            SortBy.NAME -> sortNameLabel
                                            SortBy.SIZE -> sortSizeLabel
                                            SortBy.DATE -> sortDateLabel
                                            SortBy.TYPE -> sortTypeLabel
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(
                                        if (sortOrder == SortOrder.ASCENDING)
                                            Icons.Default.ArrowUpward
                                        else
                                            Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = showSortMenu,
                                onDismissRequest = { showSortMenu = false }
                            ) {
                                SortBy.entries.forEach { option ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                when (option) {
                                                    SortBy.NAME -> sortNameLabel
                                                    SortBy.SIZE -> sortSizeLabel
                                                    SortBy.DATE -> sortDateLabel
                                                    SortBy.TYPE -> sortTypeLabel
                                                },
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        },
                                        onClick = {
                                            if (sortBy == option) {
                                                sortOrder = if (sortOrder == SortOrder.ASCENDING)
                                                    SortOrder.DESCENDING else SortOrder.ASCENDING
                                            } else {
                                                sortBy = option
                                            }
                                            showSortMenu = false
                                        },
                                        leadingIcon = {
                                            if (sortBy == option) {
                                                Icon(
                                                    if (sortOrder == SortOrder.ASCENDING)
                                                        Icons.Default.ArrowUpward
                                                    else
                                                        Icons.Default.ArrowDownward,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Individual progress cards ...
                items(
                    items = state.activeTasks,
                    key = { it.id }
                ) { task ->
                    AnimatedVisibility(
                        visible = true,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        ProgressCard(
                            isUpload = task.type == TaskType.UPLOAD,
                            fileName = task.fileName,
                            progress = task.progress,
                            onCancel = { actions.onCancelTask(task.id) }
                        )
                    }
                }

                item {
                    AnimatedVisibility(
                        visible = isGallerySyncing,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        GallerySyncProgressCard(
                            fileName = gallerySyncFileName ?: stringResource(R.string.syncing_gallery),
                            progress = gallerySyncProgress
                        )
                    }
                }
                
                item {
                    AnimatedVisibility(
                        visible = isLinkDownloading,
                        enter = slideInVertically() + fadeIn(),
                        exit = slideOutVertically() + fadeOut()
                    ) {
                        ProgressCard(
                            isUpload = false,
                            fileName = linkDownloadFileName ?: stringResource(R.string.downloading_link),
                            progress = linkDownloadProgress
                        )
                    }
                }

                if (filteredFiles.isEmpty()) {
                    item {
                        EmptyState(
                            hasSearch = searchQuery.isNotEmpty(),
                            onClearSearch = { searchQuery = "" }
                        )
                    }
                } else {
                    itemsIndexed(
                        items = filteredFiles,
                        key = { _, file -> file.id }
                    ) { _, file ->
                        FileCard(
                            file = file,
                            isSelected = selectedFiles.contains(file.id),
                            onSelect = {
                                selectedFiles = if (selectedFiles.contains(file.id)) {
                                    selectedFiles - file.id
                                } else {
                                    selectedFiles + file.id
                                }
                            },
                            onLongClick = { fileForOptions = file },
                            onViewMedia = { actions.onViewMedia(file) },
                            modifier = Modifier
                                .semantics(mergeDescendants = false) {
                                    contentDescription = "${context.getString(R.string.file_fallback)} ${file.fileName}, tamaño ${formatFileSize(file.sizeBytes)}, fecha ${formatDate(file.uploadedAt)}"
                                }
                        )
                    }
                }
            }
        }
    }
}

// LEGACY OVERLOAD FOR COMPATIBILITY
// This allows MainActivity to compile while we migrate
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: DashboardState,
    onUploadClick: () -> Unit,
    onDownloadFromLinkClick: () -> Unit,
    onDownloadClick: (CloudFile) -> Unit,
    onShareClick: (CloudFile) -> Unit,
    onCopyLink: (CloudFile) -> Unit,
    onDeleteLocal: (CloudFile) -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onOpenConfig: () -> Unit,
    onOpenGallery: () -> Unit = {},
    onOpenTaskQueue: () -> Unit = {},
    isGallerySyncing: Boolean = false,
    gallerySyncProgress: Float = 0f,
    gallerySyncFileName: String? = null,
    isLinkDownloading: Boolean = false,
    linkDownloadProgress: Float = 0f,
    linkDownloadFileName: String? = null,
    onRefresh: () -> Unit = {},
    onDownloadMultiple: (List<CloudFile>) -> Unit = {},
    onShareMultiple: (List<CloudFile>) -> Unit = {},
    onDeleteMultiple: (List<CloudFile>) -> Unit = {},
    onViewMedia: (CloudFile) -> Unit = {},
    onCancelTask: (String) -> Unit = {}
) {
    val actions = remember(onUploadClick) {
        object : DashboardActions {
            override fun onUploadClick() = onUploadClick()
            override fun onDownloadFromLinkClick() = onDownloadFromLinkClick()
            override fun onDownloadClick(file: CloudFile) = onDownloadClick(file)
            override fun onShareClick(file: CloudFile) = onShareClick(file)
            override fun onCopyLink(file: CloudFile) = onCopyLink(file)
            override fun onDeleteLocal(file: CloudFile) = onDeleteLocal(file)
            override fun onCreateBackup() = onCreateBackup()
            override fun onRestoreBackup() = onRestoreBackup()
            override fun onOpenConfig() = onOpenConfig()
            override fun onOpenGallery() = onOpenGallery()
            override fun onOpenTaskQueue() = onOpenTaskQueue()
            override fun onRefresh() = onRefresh()
            override fun onDownloadMultiple(files: List<CloudFile>) = onDownloadMultiple(files)
            override fun onShareMultiple(files: List<CloudFile>) = onShareMultiple(files)
            override fun onDeleteMultiple(files: List<CloudFile>) = onDeleteMultiple(files)
            override fun onViewMedia(file: CloudFile) = onViewMedia(file)
            override fun onCancelTask(taskId: String) = onCancelTask(taskId)
        }
    }
    
    DashboardScreen(
        state = state,
        actions = actions,
        isGallerySyncing = isGallerySyncing,
        gallerySyncProgress = gallerySyncProgress,
        gallerySyncFileName = gallerySyncFileName,
        isLinkDownloading = isLinkDownloading,
        linkDownloadProgress = linkDownloadProgress,
        linkDownloadFileName = linkDownloadFileName
    )
}

@Composable
private fun DeleteConfirmationDialog(
    fileName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.delete_file),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        text = {
            Text(
                stringResource(R.string.delete_confirmation, fileName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(stringResource(R.string.confirm_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun FileOptionsMenu(
    file: CloudFile,
    onDismiss: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onCopyLink: () -> Unit,
    onDelete: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = file.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.download)) },
                    onClick = onDownload,
                    leadingIcon = {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = AppColors.download)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.share)) },
                    onClick = onShare,
                    leadingIcon = {
                        Icon(Icons.Default.Share, contentDescription = null, tint = AppColors.share)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.copy_link)) },
                    onClick = onCopyLink,
                    leadingIcon = {
                        Icon(Icons.Default.Link, contentDescription = null)
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = onDelete,
                    leadingIcon = {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    }
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
private fun StatsCard(
    totalSize: Long,
    fileCount: Int,
    onGalleryClick: () -> Unit = {}
) {
    val storageLabel = stringResource(R.string.total_storage)
    val filesLabel = stringResource(R.string.files)
    
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        StatItem(
            icon = Icons.Default.Storage,
            value = formatFileSize(totalSize),
            label = storageLabel,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        
        StatItem(
            icon = Icons.Default.Folder,
            value = fileCount.toString(),
            label = filesLabel,
            color = AppColors.success,
            modifier = Modifier.weight(1f)
        )
        
        StatItem(
            icon = Icons.Default.Image,
            value = stringResource(R.string.gallery),
            label = stringResource(R.string.cloud),
            color = AppColors.warning,
            modifier = Modifier.weight(1f),
            onClick = onGalleryClick
        )
    }
}

@Composable
private fun StatItem(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val cardModifier = if (onClick != null) {
        modifier.clickable(onClick = onClick)
    } else {
        modifier
    }
    
    Card(
        modifier = cardModifier
            .fillMaxWidth()
            .height(100.dp),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.level2)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .padding(Spacing.md),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Visible
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    label: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.level2)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { 
            Text(
                stringResource(R.string.search_files),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            ) 
        },
        leadingIcon = {
            Icon(
                Icons.Default.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ComponentSize.iconSmall)
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                AnimatedIconButton(
                    onClick = onClear,
                    imageVector = Icons.Default.Clear,
                    contentDescription = stringResource(R.string.clear),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    hapticType = HapticFeedbackType.LIGHT_CLICK
                )
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(Radius.md),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer
        ),
        textStyle = MaterialTheme.typography.bodySmall
    )
}



@Composable
private fun ProgressCard(
    isUpload: Boolean,
    fileName: String,
    progress: Float,
    onCancel: (() -> Unit)? = null
) {
    // Verde para uploads, azul para downloads
    val color = if (isUpload) AppColors.success else AppColors.download
    var showCancelDialog by remember { mutableStateOf(false) }
    
    // Cancel confirmation dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            title = {
                Text(
                    stringResource(R.string.cancel_operation),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    stringResource(R.string.cancel_operation_confirm, fileName),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelDialog = false
                        onCancel?.invoke()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF5350)
                    )
                ) {
                    Text(stringResource(R.string.cancel))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(R.string.continue_operation))
                }
            }
        )
    }
    
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "progress_animation"
    )
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.level2)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isUpload) Icons.Default.CloudUpload else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(ComponentSize.iconSmall)
                )
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(if (isUpload) R.string.uploading else R.string.downloading),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
                // Cancel button (red square)
                if (onCancel != null) {
                    Spacer(Modifier.width(Spacing.sm))
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFEF5350))
                            .clickable { showCancelDialog = true },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Stop,
                            contentDescription = stringResource(R.string.cancel),
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(Radius.xs)),
                color = color,
                trackColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun GallerySyncProgressCard(
    fileName: String,
    progress: Float
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "gallery_progress_animation"
    )
    
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = Elevation.level2)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    tint = AppColors.warning,
                    modifier = Modifier.size(ComponentSize.iconSmall)
                )
                Spacer(Modifier.width(Spacing.sm))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.gallery_sync),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = fileName,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Text(
                    text = "${(animatedProgress * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.warning
                )
            }
            Spacer(Modifier.height(Spacing.sm))
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(Radius.xs)),
                color = AppColors.warning,
                trackColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.3f)
            )
        }
    }
}

@Composable
private fun EmptyState(
    hasSearch: Boolean,
    onClearSearch: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                if (hasSearch) Icons.Default.Search else Icons.Default.Cloud,
                contentDescription = null,
                modifier = Modifier.size(52.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = stringResource(if (hasSearch) R.string.results else R.string.no_files),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = stringResource(R.string.upload_first_file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (hasSearch) {
                Spacer(Modifier.height(Spacing.lg))
                FilledTonalButton(
                    onClick = onClearSearch,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(stringResource(R.string.clear_search), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SelectionActionBar(
    selectedCount: Int,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = Elevation.level3
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedCount.toString(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = Spacing.sm)
            )
            
            IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = stringResource(R.string.download),
                    tint = AppColors.download,
                    modifier = Modifier.size(28.dp)
                )
            }
            
            IconButton(
                onClick = onShare,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.share),
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .weight(1f)
                    .height(52.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(28.dp)
                )
            }
            
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.close_selection),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun FileCard(
    file: CloudFile,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onLongClick: () -> Unit,
    onViewMedia: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val fileColor = getFileTypeColor(file.mimeType)
    val isMediaFile = file.mimeType?.startsWith("image/") == true || 
                      file.mimeType?.startsWith("video/") == true
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onSelect,
                onLongClick = onLongClick
            )
            .then(
                if (isSelected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(Radius.md)
                    )
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(Radius.md),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) Elevation.level3 else Elevation.level1
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Columna 1: Miniatura (clickable for media files)
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f))
                    .then(
                        if (isMediaFile && onViewMedia != null) {
                            Modifier.clickable(onClick = onViewMedia)
                        } else {
                            Modifier
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    getFileTypeIcon(file.mimeType),
                    contentDescription = null,
                    tint = fileColor,
                    modifier = Modifier.size(22.dp)
                )
            }
            
            // Columna 2: Bloque de texto (centrado verticalmente)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(Spacing.xs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    Text(
                        text = formatFileSize(file.sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    Text(
                        text = formatDate(file.uploadedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (file.caption?.startsWith("[CHUNKED:") == true) {
                        Text(
                            text = "·",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            text = stringResource(R.string.chunked),
                            style = MaterialTheme.typography.labelSmall,
                            color = AppColors.download
                        )
                    }
                }
            }
        }
    }
}

// Helper functions
private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_073_741_824 -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}

private fun formatDate(timestamp: Long): String {
    val sdf = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

private fun getMimeCategory(mimeType: String?): String {
    return when {
        mimeType == null -> "other"
        mimeType.startsWith("image/") -> "image"
        mimeType.startsWith("video/") -> "video"
        mimeType.startsWith("audio/") -> "audio"
        mimeType.startsWith("text/") || mimeType.contains("pdf") || mimeType.contains("document") -> "document"
        else -> "other"
    }
}

private fun getFileTypeIcon(mimeType: String?): ImageVector {
    return when {
        mimeType == null -> Icons.Default.InsertDriveFile
        mimeType.startsWith("image/") -> Icons.Default.Image
        mimeType.startsWith("video/") -> Icons.Default.Movie
        mimeType.startsWith("audio/") -> Icons.Default.MusicNote
        mimeType.contains("pdf") || mimeType.contains("document") -> Icons.Default.Description
        else -> Icons.Default.InsertDriveFile
    }
}

private fun getFileTypeColor(mimeType: String?): Color {
    return when {
        mimeType == null -> AppColors.fileOther
        mimeType.startsWith("image/") -> AppColors.fileImage
        mimeType.startsWith("video/") -> AppColors.fileVideo
        mimeType.startsWith("audio/") -> AppColors.fileAudio
        mimeType.contains("pdf") || mimeType.contains("document") -> AppColors.fileDocument
        else -> AppColors.fileOther
    }
}
