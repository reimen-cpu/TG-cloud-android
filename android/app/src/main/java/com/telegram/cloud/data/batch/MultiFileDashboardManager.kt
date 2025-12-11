package com.telegram.cloud.data.batch

import android.util.Log
import com.telegram.cloud.data.share.MultiLinkGenerator
import com.telegram.cloud.domain.model.CloudFile
import com.telegram.cloud.domain.model.DownloadRequest
import com.telegram.cloud.ui.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages multiple file operations for Dashboard (CloudFile)
 */
class MultiFileDashboardManager(
    private val mainViewModel: MainViewModel,
    private val multiLinkGenerator: MultiLinkGenerator
) {
    companion object {
        private const val TAG = "MultiFileDashboardManager"
    }

    /**
     * Delete multiple files
     */
    suspend fun deleteMultiple(
        files: List<CloudFile>,
        onProgress: ((Int, Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting batch delete for ${files.size} files")
        
        files.forEachIndexed { index, file ->
            onProgress?.invoke(index + 1, files.size)
            try {
                mainViewModel.deleteFile(file)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete ${file.fileName}", e)
            }
        }
    }

    /**
     * Download multiple files
     */
    fun downloadMultiple(files: List<CloudFile>, targetDir: File) {
        Log.i(TAG, "Starting batch download for ${files.size} files")
        
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        
        val requests = files.map { file ->
            val safeName = sanitizeFileName(file.fileName.ifBlank { "tg-file-${file.messageId}" })
            val targetFile = File(targetDir, safeName)
            DownloadRequest(
                file = file,
                targetPath = targetFile.absolutePath
            )
        }
        
        mainViewModel.downloadMultiple(requests)
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (sanitized.isBlank()) "tg-file" else sanitized
    }

    /**
     * Generate .link file for multiple files
     */
    suspend fun generateBatchLink(
        files: List<CloudFile>,
        password: String,
        outputFile: File
    ): Boolean {
        return multiLinkGenerator.generateForDashboard(files, password, outputFile)
    }
}
