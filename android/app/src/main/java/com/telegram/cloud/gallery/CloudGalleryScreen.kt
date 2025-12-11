package com.telegram.cloud.gallery

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.telegram.cloud.R
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.google.accompanist.swiperefresh.SwipeRefresh
import com.google.accompanist.swiperefresh.rememberSwipeRefreshState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

enum class GalleryViewMode {
    ALBUMS,  // Show folders/albums
    MEDIA    // Show media grid
}

enum class MediaDisplayMode {
    GRID_3,      // 3 column grid
    GRID_4,      // 4 column compact grid
    LIST         // List view
}

enum class SortBy {
    DATE, NAME, SIZE, TYPE
}

enum class SortOrder {
    ASCENDING, DESCENDING
}

enum class FilterType {
    ALL, IMAGES, VIDEOS
}

// Saver for GalleryViewMode to work with rememberSaveable
private val GalleryViewModeSaver = androidx.compose.runtime.saveable.Saver<GalleryViewMode, String>(
    save = { it.name },
    restore = { GalleryViewMode.valueOf(it) }
)

private val MediaDisplayModeSaver = androidx.compose.runtime.saveable.Saver<MediaDisplayMode, String>(
    save = { it.name },
    restore = { MediaDisplayMode.valueOf(it) }
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun CloudGalleryScreen(
    uiState: GalleryViewModel.GalleryUiState,
    filterState: GalleryViewModel.FilterState,
    onUpdateFilter: (GalleryViewModel.FilterState.() -> GalleryViewModel.FilterState) -> Unit,
    syncState: GallerySyncManager.SyncState,
    syncProgress: Float,
    restoreState: GalleryRestoreManager.RestoreState,
    restoreProgress: Float,
    syncedCount: Int,
    totalCount: Int,
    onScanMedia: () -> Unit,
    onSyncAll: () -> Unit,
    onRestoreAll: () -> Unit,
    onMediaClick: (GalleryMediaEntity) -> Unit,
    onMediaLongClick: (GalleryMediaEntity) -> Unit,
    onBack: () -> Unit,
    onCancelSync: () -> Unit = {},
    onCancelRestore: () -> Unit = {},
    // Selection mode callbacks
    onSelectedSync: ((List<GalleryMediaEntity>) -> Unit)? = null,
    onSelectedDelete: ((List<GalleryMediaEntity>) -> Unit)? = null,
    onSelectedShare: ((List<GalleryMediaEntity>) -> Unit)? = null,
    onSelectedDownload: ((List<GalleryMediaEntity>) -> Unit)? = null
) {
    val materialTheme = MaterialTheme.colorScheme
    
    var viewMode by rememberSaveable(stateSaver = GalleryViewModeSaver) { mutableStateOf(GalleryViewMode.ALBUMS) }
    // selectedAlbumPath is now in filterState
    
    var horizontalDragOffset by remember { mutableStateOf(0f) }
    
    // Selection mode state
    var isSelectionMode by remember { mutableStateOf(false) }
    var selectedMediaIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    
    // Search and filter state - now in filterState
    var showSortMenu by remember { mutableStateOf(false) }
    
    // Display mode state
    var displayMode by rememberSaveable(stateSaver = MediaDisplayModeSaver) { mutableStateOf(MediaDisplayMode.GRID_3) }
    
    // Pull to refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    val swipeRefreshState = rememberSwipeRefreshState(isRefreshing)
    val scope = rememberCoroutineScope()
    
    val haptic = LocalHapticFeedback.current
    
    // Preserve scroll state across recompositions (e.g., when returning from viewer)
    val albumsGridState = rememberLazyGridState()
    val mediaGridState = rememberLazyGridState()
    val mediaListState = rememberLazyListState()
    
    // Get data from uiState
    val albums = uiState.albums
    val currentMedia = uiState.currentMedia
    
    val selectedAlbum = remember(filterState.selectedAlbumPath, albums) {
        filterState.selectedAlbumPath?.let { path -> albums.find { it.path == path } }
    }
    
    // Sync viewMode with selectedAlbumPath
    LaunchedEffect(filterState.selectedAlbumPath) {
        if (filterState.selectedAlbumPath != null) {
            viewMode = GalleryViewMode.MEDIA
        } else {
            viewMode = GalleryViewMode.ALBUMS
        }
    }
    
    // Handle back navigation
    val handleBack: () -> Unit = {
        when {
            viewMode == GalleryViewMode.MEDIA && filterState.selectedAlbumPath != null -> {
                onUpdateFilter { copy(selectedAlbumPath = null) }
                // viewMode update handled by LaunchedEffect
            }
            else -> onBack()
        }
    }
    
    // Handle system back button
    BackHandler(enabled = true) {
        handleBack()
    }
    
    // Get selected media entities
    val selectedMedia = remember(selectedMediaIds, currentMedia) {
        currentMedia.filter { it.id in selectedMediaIds }
    }
    
    // Handle selection mode actions
    val handleSync = {
        if (onSelectedSync != null) {
            onSelectedSync(selectedMedia)
        } else {
            // Fallback: use long click for each (opens context menu)
            selectedMedia.forEach { media ->
                onMediaLongClick(media)
            }
        }
        isSelectionMode = false
        selectedMediaIds = emptySet()
    }
    
    val handleDelete = {
        if (onSelectedDelete != null) {
            onSelectedDelete(selectedMedia)
        } else {
            // Fallback: use long click for each (opens context menu)
            selectedMedia.forEach { media ->
                onMediaLongClick(media)
            }
        }
        isSelectionMode = false
        selectedMediaIds = emptySet()
    }
    
    val handleShare = {
        if (onSelectedShare != null) {
            onSelectedShare(selectedMedia)
        } else {
            // Fallback: share first item
            selectedMedia.firstOrNull()?.let { media ->
                onMediaLongClick(media)
            }
        }
        isSelectionMode = false
        selectedMediaIds = emptySet()
    }
    
    val handleDownload = {
        if (onSelectedDownload != null) {
            onSelectedDownload(selectedMedia)
        }
        isSelectionMode = false
        selectedMediaIds = emptySet()
    }
    
    val handleDeselectAll = {
        isSelectionMode = false
        selectedMediaIds = emptySet()
    }
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(materialTheme.background)
            .pointerInput(Unit) {
                // Umbral de 100dp convertido a píxeles
                val thresholdPx = 100.dp.toPx()
                detectHorizontalDragGestures(
                    onDragEnd = {
                        // Si el desplazamiento es mayor a 100dp (deslizar de izquierda a derecha), volver al dashboard
                        if (horizontalDragOffset > thresholdPx && !isSelectionMode) {
                            onBack()
                        }
                        horizontalDragOffset = 0f
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        // Solo acumular desplazamientos positivos (de izquierda a derecha)
                        if (dragAmount > 0 && !isSelectionMode) {
                            horizontalDragOffset += dragAmount
                        }
                        change.consume()
                    }
                )
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top Bar
            TopAppBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            title = {
                if (isSelectionMode) {
                    Text(
                        stringResource(R.string.selected_count, selectedMediaIds.size),
                        color = materialTheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (selectedAlbum != null) selectedAlbum!!.displayName else stringResource(R.string.cloud_gallery),
                            color = materialTheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!isSelectionMode) {
                            Text(
                                if (selectedAlbum != null) {
                                    stringResource(R.string.synced_count_format, selectedAlbum!!.syncedCount, selectedAlbum!!.mediaCount)
                                } else {
                                    stringResource(R.string.synced_count_format, syncedCount, totalCount)
                                },
                                color = materialTheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                if (isSelectionMode) {
                    IconButton(onClick = {
                        isSelectionMode = false
                        selectedMediaIds = emptySet()
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cancel_selection),
                            tint = materialTheme.onSurface
                        )
                    }
                } else {
                    IconButton(onClick = { handleBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                            tint = materialTheme.onSurface
                        )
                    }
                }
            },
            actions = {
                if (isSelectionMode) {
                    // Select all button
                    IconButton(onClick = {
                        selectedMediaIds = if (selectedMediaIds.size == currentMedia.size) {
                            emptySet()
                        } else {
                            currentMedia.map { it.id }.toSet()
                        }
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }) {
                        Icon(
                            if (selectedMediaIds.size == currentMedia.size) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = stringResource(R.string.select_all),
                            tint = materialTheme.primary
                        )
                    }
                } else {
                    // Search button
                    IconButton(onClick = {
                        // Toggle search visibility handled by search bar component
                    }) {
                        Icon(
                            Icons.Default.Search,
                            contentDescription = stringResource(R.string.search),
                            tint = materialTheme.onSurfaceVariant
                        )
                    }
                    // Display mode toggle (only in media view)
                    if (viewMode == GalleryViewMode.MEDIA || selectedAlbum != null) {
                        IconButton(onClick = {
                            displayMode = when (displayMode) {
                                MediaDisplayMode.GRID_3 -> MediaDisplayMode.GRID_4
                                MediaDisplayMode.GRID_4 -> MediaDisplayMode.LIST
                                MediaDisplayMode.LIST -> MediaDisplayMode.GRID_3
                            }
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }) {
                            Icon(
                                when (displayMode) {
                                    MediaDisplayMode.GRID_3 -> Icons.Default.ViewModule
                                    MediaDisplayMode.GRID_4 -> Icons.Default.GridView
                                    MediaDisplayMode.LIST -> Icons.Default.List
                                },
                                contentDescription = stringResource(R.string.change_view),
                                tint = materialTheme.onSurfaceVariant
                            )
                        }
                    }
                    // Toggle view mode (only when in root)
                    if (selectedAlbum == null) {
                        IconButton(onClick = {
                            viewMode = if (viewMode == GalleryViewMode.ALBUMS) 
                                GalleryViewMode.MEDIA else GalleryViewMode.ALBUMS
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }) {
                            Icon(
                                if (viewMode == GalleryViewMode.ALBUMS) Icons.Default.GridView else Icons.Default.Folder,
                                contentDescription = stringResource(R.string.toggle_view),
                                tint = materialTheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = {
                        isRefreshing = true
                        scope.launch {
                            onScanMedia()
                            kotlinx.coroutines.delay(500)
                            isRefreshing = false
                        }
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.scan_media),
                            tint = materialTheme.primary
                        )
                    }
                    IconButton(onClick = onSyncAll) {
                        Icon(
                            Icons.Default.CloudUpload,
                            contentDescription = stringResource(R.string.sync_all),
                            tint = materialTheme.primary
                        )
                    }
                    IconButton(onClick = onRestoreAll) {
                        Icon(
                            Icons.Default.CloudDownload,
                            contentDescription = stringResource(R.string.restore_all),
                            tint = materialTheme.primary
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = materialTheme.surface
            )
            )
            
            // Sync Progress
            when (val state = syncState) {
            is GallerySyncManager.SyncState.Syncing -> {
                SyncProgressBar(
                    filename = state.currentFile,
                    current = state.current,
                    total = state.total,
                    progress = syncProgress,
                    onCancel = onCancelSync,
                    title = stringResource(R.string.syncing_gallery)
                )
            }
            is GallerySyncManager.SyncState.Error -> {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = materialTheme.errorContainer)
                ) {
                    Text(
                        text = state.message,
                        color = materialTheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        fontSize = 13.sp
                    )
                }
            }
            else -> {}
            }

            // Restore Progress
            when (val state = restoreState) {
                is GalleryRestoreManager.RestoreState.Restoring -> {
                    SyncProgressBar(
                        filename = state.currentFile,
                        current = state.current,
                        total = state.total,
                        progress = restoreProgress,
                        onCancel = onCancelRestore,
                        title = stringResource(R.string.restoring_gallery)
                    )
                }
                is GalleryRestoreManager.RestoreState.Error -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = materialTheme.errorContainer)
                    ) {
                        Text(
                            text = state.message,
                            color = materialTheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp),
                            fontSize = 13.sp
                        )
                    }
                }
                else -> {}
            }
            
            // Search Bar (only in media view)
            if ((viewMode == GalleryViewMode.MEDIA || selectedAlbum != null) && !isSelectionMode) {
            OutlinedTextField(
                value = filterState.searchQuery,
                onValueChange = { onUpdateFilter { copy(searchQuery = it) } },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_placeholder), color = materialTheme.onSurfaceVariant) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = materialTheme.onSurfaceVariant)
                },
                trailingIcon = {
                    if (filterState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onUpdateFilter { copy(searchQuery = "") } }) {
                            Icon(Icons.Default.Clear, contentDescription = stringResource(R.string.clear), tint = materialTheme.onSurfaceVariant)
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = materialTheme.primary,
                    unfocusedBorderColor = materialTheme.outline,
                    focusedTextColor = materialTheme.onSurface,
                    unfocusedTextColor = materialTheme.onSurface
                )
            )
            }
            
            // Filter and Sort Chips (only in media view)
            if ((viewMode == GalleryViewMode.MEDIA || selectedAlbum != null) && !isSelectionMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sync filter
                FilterChip(
                    selected = !filterState.showOnlySynced,
                    onClick = { onUpdateFilter { copy(showOnlySynced = false) } },
                    label = { 
                        Text(
                            "All (${currentMedia.size})",
                            fontSize = 11.sp,
                            maxLines = 1
                        ) 
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = materialTheme.primaryContainer,
                        selectedLabelColor = materialTheme.onPrimaryContainer,
                        containerColor = materialTheme.surfaceVariant,
                        labelColor = materialTheme.onSurfaceVariant
                    )
                )
                FilterChip(
                    selected = filterState.showOnlySynced,
                    onClick = { onUpdateFilter { copy(showOnlySynced = true) } },
                    label = { 
                        Text(
                            "Synced (${currentMedia.count { it.isSynced }})",
                            fontSize = 11.sp,
                            maxLines = 1
                        ) 
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = materialTheme.primaryContainer,
                        selectedLabelColor = materialTheme.onPrimaryContainer,
                        containerColor = materialTheme.surfaceVariant,
                        labelColor = materialTheme.onSurfaceVariant
                    )
                )
                
                Spacer(modifier = Modifier.weight(1f))
                
                // Type filter
                Box {
                    FilterChip(
                        selected = filterState.filterType != FilterType.ALL,
                        onClick = { /* Show menu */ },
                        label = { 
                            Text(
                                when (filterState.filterType) {
                                    FilterType.IMAGES -> "Imágenes"
                                    FilterType.VIDEOS -> "Videos"
                                    FilterType.ALL -> "Tipo"
                                },
                                fontSize = 11.sp,
                                maxLines = 1
                            ) 
                        },
                        leadingIcon = {
                            Icon(
                                when (filterState.filterType) {
                                    FilterType.IMAGES -> Icons.Default.Image
                                    FilterType.VIDEOS -> Icons.Default.Videocam
                                    FilterType.ALL -> Icons.Default.FilterList
                                },
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = materialTheme.secondaryContainer,
                            selectedLabelColor = materialTheme.onSecondaryContainer,
                            containerColor = materialTheme.surfaceVariant,
                            labelColor = materialTheme.onSurfaceVariant
                        )
                    )
                    // TODO: Add dropdown menu for filter type
                }
                
                // Sort button
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { showSortMenu = true },
                        label = { 
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    when (filterState.sortBy) {
                                        SortBy.DATE -> "Fecha"
                                        SortBy.NAME -> "Nombre"
                                        SortBy.SIZE -> "Tamaño"
                                        SortBy.TYPE -> "Tipo"
                                    },
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    if (filterState.sortOrder == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp)
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = materialTheme.surfaceVariant,
                            labelColor = materialTheme.onSurfaceVariant
                        )
                    )
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false }
                    ) {
                        SortBy.entries.forEach { option ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        when (option) {
                                            SortBy.DATE -> "Fecha"
                                            SortBy.NAME -> "Nombre"
                                            SortBy.SIZE -> "Tamaño"
                                            SortBy.TYPE -> "Tipo"
                                        }
                                    )
                                },
                                onClick = {
                                    if (filterState.sortBy == option) {
                                        val newOrder = if (filterState.sortOrder == SortOrder.ASCENDING) 
                                            SortOrder.DESCENDING else SortOrder.ASCENDING
                                        onUpdateFilter { copy(sortOrder = newOrder) }
                                    } else {
                                        onUpdateFilter { copy(sortBy = option) }
                                    }
                                    showSortMenu = false
                                },
                                leadingIcon = {
                                    if (filterState.sortBy == option) {
                                        Icon(
                                            if (filterState.sortOrder == SortOrder.ASCENDING) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                            contentDescription = null
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
            }
            
            // Content
            SwipeRefresh(
            state = swipeRefreshState,
            onRefresh = {
                isRefreshing = true
                scope.launch {
                    onScanMedia()
                    kotlinx.coroutines.delay(500)
                    isRefreshing = false
                }
            }
        ) {
            when {
                uiState.currentMedia.isEmpty() && uiState.albums.isEmpty() -> {
                    EmptyGalleryState(onScanMedia)
                }
                viewMode == GalleryViewMode.ALBUMS && filterState.selectedAlbumPath == null -> {
                    // Albums Grid
                    AlbumsGrid(
                        albums = albums,
                        gridState = albumsGridState,
                        onAlbumClick = { album ->
                            onUpdateFilter { copy(selectedAlbumPath = album.path) }
                            // viewMode update handled by LaunchedEffect
                        }
                    )
                }
                else -> {
                    // Media Grid/List
                    if (currentMedia.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.Image,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = materialTheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Text(
                                    if (filterState.searchQuery.isNotEmpty()) stringResource(R.string.no_search_results) else stringResource(R.string.no_media_in_album),
                                    color = materialTheme.onSurfaceVariant,
                                    fontSize = 16.sp
                                )
                                if (filterState.searchQuery.isNotEmpty()) {
                                    TextButton(onClick = { onUpdateFilter { copy(searchQuery = "") } }) {
                                        Text(stringResource(R.string.clear_search), color = materialTheme.primary)
                                    }
                                }
                            }
                        }
                    } else {
                        when (displayMode) {
                            MediaDisplayMode.GRID_3 -> {
                                MediaGrid(
                                    groupedMedia = uiState.groupedMedia,
                                    gridState = mediaGridState,
                                    columns = 3,
                                    isSelectionMode = isSelectionMode,
                                    selectedMediaIds = selectedMediaIds,
                                    onMediaClick = { media ->
                                        if (isSelectionMode) {
                                            selectedMediaIds = if (selectedMediaIds.contains(media.id)) {
                                                selectedMediaIds - media.id
                                            } else {
                                                selectedMediaIds + media.id
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } else {
                                            onMediaClick(media)
                                        }
                                    },
                                    onMediaLongClick = { media ->
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedMediaIds = setOf(media.id)
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                )
                            }
                            MediaDisplayMode.GRID_4 -> {
                                MediaGrid(
                                    groupedMedia = uiState.groupedMedia,
                                    gridState = mediaGridState,
                                    columns = 4,
                                    isSelectionMode = isSelectionMode,
                                    selectedMediaIds = selectedMediaIds,
                                    onMediaClick = { media ->
                                        if (isSelectionMode) {
                                            selectedMediaIds = if (selectedMediaIds.contains(media.id)) {
                                                selectedMediaIds - media.id
                                            } else {
                                                selectedMediaIds + media.id
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } else {
                                            onMediaClick(media)
                                        }
                                    },
                                    onMediaLongClick = { media ->
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedMediaIds = setOf(media.id)
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                )
                            }
                            MediaDisplayMode.LIST -> {
                                MediaList(
                                    mediaList = currentMedia,
                                    listState = mediaListState,
                                    isSelectionMode = isSelectionMode,
                                    selectedMediaIds = selectedMediaIds,
                                    onMediaClick = { media ->
                                        if (isSelectionMode) {
                                            selectedMediaIds = if (selectedMediaIds.contains(media.id)) {
                                                selectedMediaIds - media.id
                                            } else {
                                                selectedMediaIds + media.id
                                            }
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        } else {
                                            onMediaClick(media)
                                        }
                                    },
                                    onMediaLongClick = { media ->
                                        if (!isSelectionMode) {
                                            isSelectionMode = true
                                            selectedMediaIds = setOf(media.id)
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
        }
        
        // Selection mode action bar (overlay)
        if (isSelectionMode && selectedMediaIds.isNotEmpty()) {
            // Check if we should show sync button (hide if single file is already synced)
            val showSyncButton = remember(selectedMedia) {
                if (selectedMedia.size == 1) {
                    !selectedMedia.first().isSynced
                } else {
                    // For multiple files, show if at least one is not synced
                    selectedMedia.any { !it.isSynced }
                }
            }
            
            SelectionActionBar(
                selectedCount = selectedMediaIds.size,
                onSync = handleSync,
                onDelete = handleDelete,
                onShare = handleShare,
                onDownload = handleDownload,
                onDeselectAll = handleDeselectAll,
                showSyncButton = showSyncButton,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun AlbumsGrid(
    albums: List<GalleryAlbum>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onAlbumClick: (GalleryAlbum) -> Unit
) {
    val materialTheme = MaterialTheme.colorScheme
    
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(
            items = albums,
            key = { it.path },
            contentType = { "album" }
        ) { album ->
            AlbumCard(album = album, onClick = { onAlbumClick(album) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AlbumCard(
    album: GalleryAlbum,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val materialTheme = MaterialTheme.colorScheme
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .shadow(4.dp, shape = MaterialTheme.shapes.medium)
            .combinedClickable(onClick = onClick),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(containerColor = materialTheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Album thumbnail
            val thumbnailFile = album.thumbnailPath?.let { File(it) }
            
            if (thumbnailFile?.exists() == true) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(thumbnailFile)
                        .crossfade(true)
                        .build(),
                    contentDescription = album.displayName,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                // Placeholder
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        getAlbumIcon(album),
                        contentDescription = null,
                        tint = materialTheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }
            
            // Gradient overlay at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.8f)
                            )
                        )
                    )
                    .padding(12.dp)
            ) {
                Column {
                    Text(
                        text = album.displayName,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.items_count, album.mediaCount),
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 12.sp
                        )
                        if (album.syncedCount > 0) {
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                tint = materialTheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                text = "${album.syncedCount}",
                                color = materialTheme.primary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
            
            // Special album badge
            if (album.isSpecialAlbum) {
                val badgeColor = remember(album.path) {
                    when (album.path) {
                        GalleryAlbum.RECENTS_PATH -> materialTheme.primary
                        GalleryAlbum.FAVORITES_PATH -> materialTheme.tertiary
                        GalleryAlbum.SYNCED_PATH -> materialTheme.primary
                        GalleryAlbum.ALL_MEDIA_PATH -> materialTheme.secondary
                        else -> materialTheme.surfaceVariant
                    }
                }
                
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(10.dp)
                        .background(
                            badgeColor.copy(alpha = 0.9f),
                            MaterialTheme.shapes.small
                        )
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Icon(
                        getAlbumIcon(album),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            
            // Media type indicators
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (MediaType.IMAGE in album.mediaTypes) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                if (MediaType.VIDEO in album.mediaTypes) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

private fun getAlbumIcon(album: GalleryAlbum) = when (album.path) {
    GalleryAlbum.RECENTS_PATH -> Icons.Default.Schedule
    GalleryAlbum.FAVORITES_PATH -> Icons.Default.Favorite
    GalleryAlbum.SYNCED_PATH -> Icons.Default.Cloud
    GalleryAlbum.ALL_MEDIA_PATH -> Icons.Default.PhotoLibrary
    else -> Icons.Default.Folder
}


@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(
    groupedMedia: Map<String, List<GalleryMediaEntity>>,
    gridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    columns: Int,
    isSelectionMode: Boolean,
    selectedMediaIds: Set<Long>,
    onMediaClick: (GalleryMediaEntity) -> Unit,
    onMediaLongClick: (GalleryMediaEntity) -> Unit
) {
    val materialTheme = MaterialTheme.colorScheme
    
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        state = gridState,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        groupedMedia.forEach { (date, media) ->
            // Date header
            item(span = { GridItemSpan(columns) }) {
                Text(
                    text = date,
                    color = materialTheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp)
                )
            }
            
            // Media items
            items(
                items = media,
                key = { it.id },
                contentType = { "media" }
            ) { mediaItem ->
                MediaThumbnail(
                    media = mediaItem,
                    isSelected = isSelectionMode && selectedMediaIds.contains(mediaItem.id),
                    onClick = { onMediaClick(mediaItem) },
                    onLongClick = { onMediaLongClick(mediaItem) }
                )
            }
        }
    }
}



@Composable
private fun SyncProgressBar(
    filename: String,
    current: Int,
    total: Int,
    progress: Float,
    onCancel: () -> Unit,
    title: String = "Syncing"
) {
    val materialTheme = MaterialTheme.colorScheme
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .shadow(2.dp, shape = MaterialTheme.shapes.medium),
        colors = CardDefaults.cardColors(containerColor = materialTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = materialTheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$title $current / $total",
                        color = materialTheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${(progress * 100).toInt()}%",
                        color = materialTheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                    IconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = stringResource(R.string.cancel_sync),
                            tint = materialTheme.error
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                filename,
                color = materialTheme.onSurface,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(MaterialTheme.shapes.extraSmall),
                color = materialTheme.primary,
                trackColor = materialTheme.surfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaThumbnail(
    media: GalleryMediaEntity,
    isSelected: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val materialTheme = MaterialTheme.colorScheme
    
    // Check if local file exists
    val localFileExists = remember(media.localPath) {
        File(media.localPath).exists()
    }
    
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(MaterialTheme.shapes.small)
            .shadow(2.dp, shape = MaterialTheme.shapes.small)
            .border(
                width = if (isSelected) 3.dp else 0.dp,
                color = materialTheme.primary,
                shape = MaterialTheme.shapes.small
            )
            .background(
                if (isSelected) materialTheme.primaryContainer.copy(alpha = 0.2f) else materialTheme.surfaceVariant
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    ) {
        // Thumbnail image - always use thumbnail if available (thumbnails are stored locally forever)
        // Fall back to local path only if thumbnail doesn't exist
        val thumbnailFile = media.thumbnailPath?.let { File(it) }
        val localFile = File(media.localPath)
        
        val imageSource = when {
            thumbnailFile?.exists() == true -> thumbnailFile
            localFile.exists() -> localFile
            else -> null // No image available
        }
        
        if (imageSource != null) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(imageSource)
                    .crossfade(true)
                    // For videos without thumbnail, extract first frame
                    .apply {
                        if (media.isVideo && thumbnailFile?.exists() != true) {
                            decoderFactory { result, options, _ ->
                                VideoFrameDecoder(result.source, options)
                            }
                            videoFrameMillis(1000) // Get frame at 1 second
                        }
                    }
                    .build(),
                contentDescription = media.filename,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        } else {
            // Placeholder when no thumbnail or local file exists
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (media.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                    contentDescription = null,
                    tint = materialTheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        
        // Overlay for missing local file (but synced)
        if (!localFileExists && media.isSynced) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = stringResource(R.string.download_from_cloud),
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        
        // Selection indicator
        if (isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .background(
                        materialTheme.primary,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.selected),
                    tint = materialTheme.onPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        
        // Video duration badge
        if (media.isVideo && media.durationMs > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(
                        Color.Black.copy(alpha = 0.75f),
                        MaterialTheme.shapes.small
                    )
                    .padding(horizontal = 5.dp, vertical = 3.dp)
            ) {
                Text(
                    text = formatDuration(media.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
        
        // Sync status indicator
        if (!isSelected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(20.dp)
                    .background(
                        if (media.isSynced) materialTheme.primary else materialTheme.error,
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (media.isSynced) Icons.Default.Cloud else Icons.Default.CloudOff,
                    contentDescription = if (media.isSynced) stringResource(R.string.synced) else stringResource(R.string.not_synced),
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyGalleryState(onScanMedia: () -> Unit) {
    val materialTheme = MaterialTheme.colorScheme
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                Icons.Default.PhotoLibrary,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = materialTheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                stringResource(R.string.no_media_found),
                color = materialTheme.onSurface,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.tap_refresh_to_scan),
                color = materialTheme.onSurfaceVariant,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Button(
                onClick = onScanMedia,
                colors = ButtonDefaults.buttonColors(containerColor = materialTheme.primary),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_media))
            }
        }
    }
}

@Composable
private fun MediaList(
    mediaList: List<GalleryMediaEntity>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    isSelectionMode: Boolean,
    selectedMediaIds: Set<Long>,
    onMediaClick: (GalleryMediaEntity) -> Unit,
    onMediaLongClick: (GalleryMediaEntity) -> Unit
) {
    val materialTheme = MaterialTheme.colorScheme
    
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(
            items = mediaList,
            key = { it.id },
            contentType = { "media_list_item" }
        ) { media ->
            MediaListItem(
                media = media,
                isSelected = isSelectionMode && selectedMediaIds.contains(media.id),
                onClick = { onMediaClick(media) },
                onLongClick = { onMediaLongClick(media) }
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaListItem(
    media: GalleryMediaEntity,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val context = LocalContext.current
    val materialTheme = MaterialTheme.colorScheme
    
    val localFileExists = remember(media.localPath) {
        File(media.localPath).exists()
    }
    
    val thumbnailFile = media.thumbnailPath?.let { File(it) }
    val localFile = File(media.localPath)
    val imageSource = when {
        thumbnailFile?.exists() == true -> thumbnailFile
        localFile.exists() -> localFile
        else -> null
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .shadow(1.dp, shape = MaterialTheme.shapes.small)
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = materialTheme.primary,
                shape = MaterialTheme.shapes.small
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                materialTheme.primaryContainer.copy(alpha = 0.2f) 
            else materialTheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(materialTheme.surfaceVariant)
            ) {
                if (imageSource != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(imageSource)
                            .crossfade(true)
                            .build(),
                        contentDescription = media.filename,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        if (media.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                        contentDescription = null,
                        tint = materialTheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(32.dp)
                    )
                }
                
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(materialTheme.primary.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = stringResource(R.string.selected),
                            tint = materialTheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            
            // Info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = media.filename,
                    color = materialTheme.onSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatFileSize(media.sizeBytes),
                        color = materialTheme.onSurfaceVariant,
                        fontSize = 12.sp
                    )
                    if (media.isVideo && media.durationMs > 0) {
                        Text(
                            text = "• ${formatDuration(media.durationMs)}",
                            color = materialTheme.onSurfaceVariant,
                            fontSize = 12.sp
                        )
                    }
                }
            }
            
            // Sync indicator
            if (!isSelected) {
                Icon(
                    if (media.isSynced) Icons.Default.Cloud else Icons.Default.CloudOff,
                    contentDescription = if (media.isSynced) stringResource(R.string.synced) else stringResource(R.string.not_synced),
                    tint = if (media.isSynced) materialTheme.primary else materialTheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format("%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.2f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.2f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}

private fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000) % 60
    val minutes = (ms / 1000 / 60) % 60
    val hours = ms / 1000 / 60 / 60
    
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%d:%02d", minutes, seconds)
    }
}
