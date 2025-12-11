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
            val linkData = shareLinkManager.readLinkFile(linkFile, password)
            if (linkData == null) {
                Log.e(TAG, "Failed to read link file or wrong password")
                return@withContext emptyList()
            }

            val totalFiles = linkData.files.size
            var completedFiles = 0
            var currentFileProgress = 0.0
            var lastFileIndex = 0
            
            val results = linkDownloadManager.downloadFromLink(
                linkData = linkData,
                destinationDir = destDir,
                filePassword = null // Assuming internal encryption is handled or not used for now
            ) { completed, total, phase, percent ->
                // El callback reporta progreso de chunks o descarga directa
                // percent es el porcentaje del archivo actual (0-100)
                currentFileProgress = (percent / 100.0).coerceIn(0.0, 1.0)
                
                // Extraer el índice del archivo actual del phase string que contiene "[N/M]"
                val phaseMatch = Regex("\\[(\\d+)/(\\d+)\\]").find(phase)
                val currentFileIndex = phaseMatch?.groupValues?.get(1)?.toIntOrNull() ?: (lastFileIndex + 1)
                
                // Si cambiamos de archivo, el anterior está completo
                if (currentFileIndex > lastFileIndex && lastFileIndex > 0) {
                    completedFiles = lastFileIndex
                }
                lastFileIndex = currentFileIndex
                
                // Si el archivo está completo (percent >= 100), actualizar completedFiles
                if (percent >= 100.0 && currentFileIndex == totalFiles) {
                    completedFiles = totalFiles
                    currentFileProgress = 1.0
                }
                
                // Calcular progreso total: archivos completados + progreso del archivo actual
                val totalProgress = if (totalFiles > 0) {
                    val baseProgress = completedFiles.toFloat() / totalFiles.toFloat()
                    val currentFileContribution = (currentFileProgress.toFloat() / totalFiles.toFloat())
                    (baseProgress + currentFileContribution).coerceIn(0f, 1f)
                } else {
                    currentFileProgress.toFloat()
                }
                
                onProgress?.invoke(totalProgress, phase)
            }
            
            results
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading from multi-link", e)
            emptyList()
        }
    }
}
