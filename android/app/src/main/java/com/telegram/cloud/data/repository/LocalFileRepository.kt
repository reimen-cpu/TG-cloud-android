package com.telegram.cloud.data.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.telegram.cloud.utils.moveFileToDownloads
import com.telegram.cloud.utils.MoveResult

class LocalFileRepository(private val context: Context) {

    suspend fun copyUriToCache(uri: Uri, fileName: String): File = withContext(Dispatchers.IO) {
        val cacheDir = context.cacheDir
        val targetFile = File(cacheDir, fileName)
        
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        return@withContext targetFile
    }

    suspend fun getDocumentMeta(uri: Uri): DocumentMeta = withContext(Dispatchers.IO) {
        var name = "archivo.bin"
        var size = 0L
        
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex != -1) name = cursor.getString(nameIndex) ?: "archivo.bin"
                    if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        
        return@withContext DocumentMeta(name, size)
    }

    suspend fun moveToDownloads(source: File, mimeType: String): MoveResult? = withContext(Dispatchers.IO) {
        moveFileToDownloads(
            context = context,
            source = source,
            displayName = source.name,
            mimeType = mimeType,
            subfolder = "telegram cloud app/Shared"
        )
    }
}

data class DocumentMeta(val name: String, val size: Long)
