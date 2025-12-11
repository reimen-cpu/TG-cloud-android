package com.telegram.cloud.gallery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import android.content.ContentUris
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Media action types available in context menu
 */
sealed class MediaAction {
    object Share : MediaAction()
    object Sync : MediaAction()
    object Delete : MediaAction()
    object Rename : MediaAction()
    object Properties : MediaAction()
    object Favorite : MediaAction()
    object OpenWith : MediaAction()
    object SetAs : MediaAction()
    object CopyTo : MediaAction()
    object MoveTo : MediaAction()
    object FixDate : MediaAction()
}

/**
 * Context menu for media items - inspired by Fossify Gallery
 */
@Composable
fun MediaContextMenu(
    media: GalleryMediaEntity,
    onDismiss: () -> Unit,
    onAction: (MediaAction) -> Unit
) {
    val context = LocalContext.current
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E)
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                // Header with file info
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (media.isVideo) Icons.Default.Videocam else Icons.Default.Image,
                        contentDescription = null,
                        tint = Color(0xFF3390EC),
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            media.filename,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                            maxLines = 2
                        )
                        Text(
                            formatFileSize(media.sizeBytes),
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                    }
                }
                
                HorizontalDivider(color = Color(0xFF333333))
                
                // Menu items
                MenuOption(Icons.Default.Share, "Share") {
                    onAction(MediaAction.Share)
                    onDismiss()
                }
                
                // Sync option - show only if not synced
                if (!media.isSynced) {
                    MenuOption(Icons.Default.CloudUpload, "Sync to Cloud", tint = Color(0xFFFF9800)) {
                        onAction(MediaAction.Sync)
                        onDismiss()
                    }
                } else {
                    MenuOption(Icons.Default.CloudDone, "Synced", tint = Color(0xFF4CAF50)) {
                        // Already synced, just show status
                        onDismiss()
                    }
                }
                
                MenuOption(Icons.Default.OpenInNew, "Open with...") {
                    onAction(MediaAction.OpenWith)
                    onDismiss()
                }
                
                MenuOption(Icons.Default.Edit, "Rename") {
                    onAction(MediaAction.Rename)
                    onDismiss()
                }
                
                MenuOption(Icons.Default.Favorite, "Add to favorites") {
                    onAction(MediaAction.Favorite)
                    onDismiss()
                }
                
                MenuOption(Icons.Default.Wallpaper, "Set as...") {
                    onAction(MediaAction.SetAs)
                    onDismiss()
                }
                
                MenuOption(Icons.Default.ContentCopy, "Copy to...") {
                    onAction(MediaAction.CopyTo)
                    onDismiss()
                }
                
                MenuOption(Icons.Default.DriveFileMove, "Move to...") {
                    onAction(MediaAction.MoveTo)
                    onDismiss()
                }
                
                MenuOption(Icons.Default.DateRange, "Fix date taken") {
                    onAction(MediaAction.FixDate)
                    onDismiss()
                }
                
                MenuOption(Icons.Default.Info, "Properties") {
                    onAction(MediaAction.Properties)
                    onDismiss()
                }
                
                HorizontalDivider(color = Color(0xFF333333))
                
                MenuOption(Icons.Default.Delete, "Delete", tint = Color(0xFFEF5350)) {
                    onAction(MediaAction.Delete)
                    // Don't dismiss immediately - let the delete dialog show first
                }
            }
        }
    }
}

@Composable
private fun MenuOption(
    icon: ImageVector,
    text: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = text,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Text(
                text,
                color = tint,
                fontSize = 15.sp
            )
        }
    }
}

/**
 * Properties dialog showing media details
 */
@Composable
fun MediaPropertiesDialog(
    media: GalleryMediaEntity,
    onDismiss: () -> Unit
) {
    val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1E1E1E)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Properties",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
                
                Spacer(Modifier.height(16.dp))
                
                PropertyRow("Name", media.filename)
                PropertyRow("Path", media.localPath)
                PropertyRow("Size", formatFileSize(media.sizeBytes))
                PropertyRow("Type", media.mimeType)
                PropertyRow("Date taken", dateFormat.format(Date(media.dateTaken)))
                PropertyRow("Date modified", dateFormat.format(Date(media.dateModified)))
                
                if (media.width > 0 && media.height > 0) {
                    PropertyRow("Resolution", "${media.width} × ${media.height}")
                }
                
                if (media.isVideo && media.durationMs > 0) {
                    PropertyRow("Duration", formatDuration(media.durationMs))
                }
                
                PropertyRow("Synced", if (media.isSynced) "Yes" else "No")
                
                if (media.telegramFileId != null) {
                    PropertyRow("Telegram ID", media.telegramFileId.take(20) + "...")
                }
                
                Spacer(Modifier.height(16.dp))
                
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Close", color = Color(0xFF3390EC))
                }
            }
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(
            label,
            color = Color.Gray,
            fontSize = 12.sp
        )
        Text(
            value,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

/**
 * Rename dialog
 */
@Composable
fun RenameMediaDialog(
    media: GalleryMediaEntity,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var newName by remember { mutableStateOf(media.filename) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename", color = Color.White) },
        text = {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("File name") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF3390EC),
                    unfocusedBorderColor = Color.Gray
                )
            )
        },
        confirmButton = {
            TextButton(onClick = { onRename(newName) }) {
                Text("Rename", color = Color(0xFF3390EC))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF1E1E1E)
    )
}

/**
 * Delete confirmation dialog
 */
@Composable
fun DeleteMediaDialog(
    media: GalleryMediaEntity,
    onDismiss: () -> Unit,
    onDelete: (deleteFromTelegram: Boolean) -> Unit
) {
    var deleteFromTelegram by remember { mutableStateOf(false) }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete", color = Color.White) },
        text = {
            Column {
                Text(
                    "Delete \"${media.filename}\"?",
                    color = Color.White
                )
                Spacer(Modifier.height(12.dp))
                if (media.isSynced) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteFromTelegram,
                            onCheckedChange = { deleteFromTelegram = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF3390EC)
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Also delete from Telegram Cloud",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDelete(deleteFromTelegram) }) {
                Text("Delete", color = Color(0xFFEF5350))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.Gray)
            }
        },
        containerColor = Color(0xFF1E1E1E)
    )
}

// Helper functions for media actions
object MediaActionHelper {
    
    fun shareMedia(context: Context, media: GalleryMediaEntity) {
        try {
            // Use same approach as sync: directly use media.localPath
            val file = java.io.File(media.localPath)
            if (!file.exists()) {
                android.util.Log.w("MediaActionHelper", "File not found: ${media.localPath}")
                Toast.makeText(context, "File not found locally", Toast.LENGTH_SHORT).show()
                return
            }
            
            android.util.Log.d("MediaActionHelper", "Sharing file: ${file.absolutePath}, exists: ${file.exists()}")
            
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
            } catch (e: IllegalArgumentException) {
                android.util.Log.e("MediaActionHelper", "FileProvider error for ${file.absolutePath}: ${e.message}", e)
                Toast.makeText(context, "Error: File path not accessible: ${e.message}", Toast.LENGTH_SHORT).show()
                return
            }
            
            android.util.Log.d("MediaActionHelper", "FileProvider URI: $uri")
            
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = media.mimeType
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            android.util.Log.e("MediaActionHelper", "Error sharing", e)
            Toast.makeText(context, "Error sharing: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun openWith(context: Context, media: GalleryMediaEntity) {
        try {
            val file = findActualFile(context, media)
            if (file == null || !file.exists()) {
                Toast.makeText(context, "File not found locally", Toast.LENGTH_SHORT).show()
                return
            }
            
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("MediaActionHelper", "FileProvider error for ${file.absolutePath}: ${e.message}")
                Toast.makeText(context, "Error: File path not accessible", Toast.LENGTH_SHORT).show()
                return
            }
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, media.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            android.util.Log.e("MediaActionHelper", "Error opening", e)
            Toast.makeText(context, "Error opening: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun setAs(context: Context, media: GalleryMediaEntity) {
        try {
            if (!media.isImage) {
                Toast.makeText(context, "Only images can be set as wallpaper", Toast.LENGTH_SHORT).show()
                return
            }
            
            val file = findActualFile(context, media)
            if (file == null || !file.exists()) {
                Toast.makeText(context, "File not found locally", Toast.LENGTH_SHORT).show()
                return
            }
            
            val uri = try {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.provider",
                    file
                )
            } catch (e: IllegalArgumentException) {
                android.util.Log.w("MediaActionHelper", "FileProvider error for ${file.absolutePath}: ${e.message}")
                Toast.makeText(context, "Error: File path not accessible", Toast.LENGTH_SHORT).show()
                return
            }
            
            val intent = Intent(Intent.ACTION_ATTACH_DATA).apply {
                setDataAndType(uri, media.mimeType)
                putExtra("mimeType", media.mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            context.startActivity(Intent.createChooser(intent, "Set as"))
        } catch (e: Exception) {
            android.util.Log.e("MediaActionHelper", "Error setting as", e)
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    fun shareMultipleMedia(context: Context, mediaList: List<GalleryMediaEntity>) {
        try {
            // Use same approach as sync: directly use media.localPath
            val uris = mediaList.mapNotNull { media ->
                val file = java.io.File(media.localPath)
                if (!file.exists()) {
                    android.util.Log.w("MediaActionHelper", "File not found: ${media.localPath}")
                    return@mapNotNull null
                }
                
                try {
                    FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.provider",
                        file
                    )
                } catch (e: IllegalArgumentException) {
                    android.util.Log.e("MediaActionHelper", "FileProvider error for ${file.absolutePath}: ${e.message}", e)
                    null
                }
            }
            
            if (uris.isEmpty()) {
                Toast.makeText(context, "No files found locally", Toast.LENGTH_SHORT).show()
                return
            }
            
            android.util.Log.d("MediaActionHelper", "Sharing ${uris.size} files")
            
            val intent = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).apply {
                    type = mediaList.firstOrNull()?.mimeType ?: "*/*"
                    putExtra(Intent.EXTRA_STREAM, uris.first())
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            
            context.startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            android.util.Log.e("MediaActionHelper", "Error sharing", e)
            Toast.makeText(context, "Error sharing: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun findActualFile(context: Context, media: GalleryMediaEntity): java.io.File? {
        // First try the stored localPath
        val primaryFile = java.io.File(media.localPath)
        if (primaryFile.exists()) {
            return primaryFile
        }
        
        // Try to find via MediaStore (same way sync does it)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val projection = arrayOf(
                    android.provider.MediaStore.Downloads._ID,
                    android.provider.MediaStore.Downloads.DATA,
                    android.provider.MediaStore.Downloads.DISPLAY_NAME,
                    android.provider.MediaStore.MediaColumns.RELATIVE_PATH
                )
                val selection = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} = ? AND ${android.provider.MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
                val selectionArgs = arrayOf(
                    media.filename,
                    "%telegram cloud app/Downloads%"
                )
                
                resolver.query(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val dataIndex = cursor.getColumnIndex(android.provider.MediaStore.Downloads.DATA)
                        if (dataIndex >= 0) {
                            val path = cursor.getString(dataIndex)
                            if (!path.isNullOrBlank()) {
                                val file = java.io.File(path)
                                if (file.exists()) {
                                    return file
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.w("MediaActionHelper", "Error querying MediaStore", e)
            }
        }
        
        // Try alternative paths (fallback)
        val alternativePaths = listOf(
            // Try in Downloads/telegram cloud app/Downloads
            java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "telegram cloud app/Downloads/${media.filename}"
            ),
            // Try in external storage root
            java.io.File(
                android.os.Environment.getExternalStorageDirectory(),
                "telegram cloud app/Downloads/${media.filename}"
            ),
            // Try in app's external files
            java.io.File(
                context.getExternalFilesDir(null),
                "Downloads/${media.filename}"
            )
        )
        
        return alternativePaths.firstOrNull { it.exists() }
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

