package com.telegram.cloud.gallery

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.telegram.cloud.R
import com.telegram.cloud.gallery.components.MediaThumbnail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    trashItems: List<GalleryMediaEntity>,
    onBack: () -> Unit,
    onRestore: (GalleryMediaEntity) -> Unit,
    onDeletePermanently: (GalleryMediaEntity) -> Unit,
    onEmptyTrash: () -> Unit,
    onRestoreAll: () -> Unit
) {
    var showEmptyTrashDialog by remember { mutableStateOf(false) }
    var selectedMedia by remember { mutableStateOf<GalleryMediaEntity?>(null) }
    var showActionDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("Trash Bin")
                        Text(
                            "${trashItems.size} items • Auto-delete in 30 days",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (trashItems.isNotEmpty()) {
                        IconButton(onClick = { showEmptyTrashDialog = true }) {
                            Icon(Icons.Default.DeleteForever, contentDescription = "Empty Trash")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (trashItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "Trash is empty",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 100.dp),
                    contentPadding = PaddingValues(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(trashItems, key = { it.id }) { media ->
                        MediaThumbnail(
                            media = media,
                            isSelected = false,
                            onClick = { 
                                selectedMedia = media
                                showActionDialog = true
                            },
                            onLongClick = { 
                                selectedMedia = media
                                showActionDialog = true
                            }
                        )
                    }
                }
            }
        }
    }

    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            title = { Text("Empty Trash") },
            text = { Text("Are you sure you want to permanently delete all items in the trash? This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onEmptyTrash()
                        showEmptyTrashDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showActionDialog && selectedMedia != null) {
        AlertDialog(
            onDismissRequest = { showActionDialog = false },
            title = {
                Text(
                    text = selectedMedia!!.filename,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            text = { Text("Choose an action") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRestore(selectedMedia!!)
                        showActionDialog = false
                    }
                ) {
                    Icon(Icons.Default.RestoreFromTrash, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        onDeletePermanently(selectedMedia!!)
                        showActionDialog = false
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Delete")
                }
            }
        )
    }
}
