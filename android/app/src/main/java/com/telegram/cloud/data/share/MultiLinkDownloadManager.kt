package com.telegram.cloud.data.share

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages downloads from multi-file .link files
 */
class MultiLinkDownloadManager(
    private val linkDownloadManager: LinkDownloadManager,
    private val shareLinkManager: ShareLinkManager
) {
    companion object {
        private const val TAG = "MultiLinkDownloadManager"
    }

    /**
     * Download files from a .link file (single or batch)
     */
    suspend fun downloadFromMultiLink(
        linkFile: File,
        password: String,
        destDir: File,
        onProgress: ((Float, String) -> Unit)? = null
    ): List<LinkDownloadManager.DownloadResult> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "downloadFromMultiLink: file=${linkFile.absolutePath}, password.length=${password.length}")
            val linkData = shareLinkManager.readLinkFile(linkFile, password)
            if (linkData == null) {
                Log.e(TAG, "Failed to read link file or wrong password. File size: ${linkFile.length()} bytes")
                return@withContext emptyList()
            }

            val totalFiles = linkData.files.size
            var completedFiles = 0
            var currentFileProgress = 0.0
            
            val results = linkDownloadManager.downloadFromLink(
                linkData = linkData,
                destinationDir = destDir,
                filePassword = null // Assuming internal encryption is handled or not used for now
            ) { completed, total, phase, percent ->
                // El callback reporta progreso de chunks o descarga directa
                // percent es el porcentaje del archivo actual (0-100)
                Log.i(TAG, "Progress callback: completed=$completed, total=$total, phase=$phase, percent=$percent")
                
                // For single file downloads, just use the percentage directly
                val totalProgress = if (totalFiles == 1) {
                    // Single file - use the percentage directly (convert 0-100 to 0-1)
                    (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
                } else {
                    // Multiple files - calculate based on completed files + current file progress
                    currentFileProgress = (percent / 100.0).coerceIn(0.0, 1.0)
                    
                    // Extraer el índice del archivo actual del phase string que contiene "[N/M]"
                    val phaseMatch = Regex("\\[(\\d+)/(\\d+)\\]").find(phase)
                    val currentFileIndex = phaseMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    
                    // Si el archivo está completo (percent >= 100), actualizar completedFiles
                    if (percent >= 100.0) {
                        completedFiles = currentFileIndex
                    }
                    
                    // Calcular progreso total: archivos completados + progreso del archivo actual
                    val baseProgress = (completedFiles - 1).coerceAtLeast(0).toFloat() / totalFiles.toFloat()
                    val currentFileContribution = (currentFileProgress.toFloat() / totalFiles.toFloat())
                    (baseProgress + currentFileContribution).coerceIn(0f, 1f)
                }
                
                Log.i(TAG, "Forwarding progress: totalProgress=$totalProgress")
                onProgress?.invoke(totalProgress, phase)
            }
            
            results
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading from multi-link", e)
            emptyList()
        }
    }
}
