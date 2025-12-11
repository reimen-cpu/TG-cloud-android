package com.telegram.cloud.utils

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

private const val TAG = "MediaStoreUtils"

data class MoveResult(val uri: Uri?, val file: File?)

fun moveFileToDownloads(
    context: Context,
    source: File,
    displayName: String = source.name,
    mimeType: String = "application/octet-stream",
    subfolder: String = "telegram cloud app"
): MoveResult? {
    if (!source.exists()) return null
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            deleteExistingDownload(resolver, displayName, subfolder)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/${subfolder}/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { it.copyTo(output) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            source.delete()
            Log.i(TAG, "Moved file to MediaStore: $displayName")
            MoveResult(uri, null)
        } else {
            val downloadsDir = File(Environment.getExternalStorageDirectory(), "${Environment.DIRECTORY_DOWNLOADS}/$subfolder")
            downloadsDir.mkdirs()
            val dest = File(downloadsDir, displayName)
            dest.delete()
            val moved = source.renameTo(dest)
            if (!moved) {
                source.copyTo(dest, overwrite = true)
                source.delete()
            }
            Log.i(TAG, "Moved file to downloads: ${dest.absolutePath}")
            MoveResult(Uri.fromFile(dest), dest)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to move file to downloads: ${e.message}", e)
        null
    }
}

private fun deleteExistingDownload(
    resolver: ContentResolver,
    displayName: String,
    subfolder: String
) {
    val selection = "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?"
    val selectionArgs = arrayOf(displayName, "${Environment.DIRECTORY_DOWNLOADS}/${subfolder}/")
    try {
        resolver.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI, selection, selectionArgs)
    } catch (_: Exception) {
        // Ignore
    }
}

