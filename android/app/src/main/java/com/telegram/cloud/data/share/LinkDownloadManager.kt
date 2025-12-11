package com.telegram.cloud.data.share

import android.util.Log
import com.telegram.cloud.data.remote.TelegramBotClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// Progress callback: (completedChunks, totalChunks, phase, percent)
typealias LinkProgressCallback = (Int, Int, String, Double) -> Unit

/**
 * Downloads files from .link files (compatible with desktop application)
 * Compatible with telegram-cloud-cpp/src/universallinkdownloader.cpp
 */
class LinkDownloadManager(
    private val botClient: TelegramBotClient
) {
    companion object {
        private const val TAG = "LinkDownloadManager"
        private const val MAX_PARALLEL = 5
        private const val MAX_RETRIES = 3
        private const val PBKDF2_ITERATIONS = 10000
    }
    
    data class DownloadResult(
        val success: Boolean,
        val filePath: String?,
        val error: String?
    )
    
    /**
     * Download files from parsed link data
     */
    suspend fun downloadFromLink(
        linkData: ShareLinkManager.ShareLinkData,
        destinationDir: File,
        filePassword: String?, // Password if files are encrypted
        progressCallback: LinkProgressCallback? = null
    ): List<DownloadResult> = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting download from link: ${linkData.files.size} file(s)")
        
        val results = mutableListOf<DownloadResult>()
        
        linkData.files.forEachIndexed { index, fileInfo ->
            Log.i(TAG, "Preparing download ${index + 1}/${linkData.files.size}: ${fileInfo.fileName} (${fileInfo.fileSize} bytes)")
            Log.i(TAG, "Downloading file ${index + 1}/${linkData.files.size}: ${fileInfo.fileName}")
            
            val result = downloadSingleFile(
                fileInfo = fileInfo,
                destinationDir = destinationDir,
                filePassword = filePassword,
                currentIndex = index + 1,
                totalFiles = linkData.files.size,
                progressCallback = progressCallback
            )
            
            results.add(result)
        }
        
        val successCount = results.count { it.success }
        Log.i(TAG, "Download completed: $successCount/${results.size} successful")
        
        results
    }
    
    private suspend fun downloadSingleFile(
        fileInfo: ShareLinkManager.SharedFileInfo,
        destinationDir: File,
        filePassword: String?,
        currentIndex: Int,
        totalFiles: Int,
        progressCallback: LinkProgressCallback?
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val destPath = File(destinationDir, fileInfo.fileName)
            Log.i(TAG, "Downloading to: ${destPath.absolutePath}")
            
            // Crear wrapper del callback para incluir información del archivo actual
            val fileProgressCallback: LinkProgressCallback? = if (progressCallback != null) {
                { completed, total, phase, percent ->
                    // Incluir nombre del archivo en la fase si hay múltiples archivos
                    val phaseWithFile = if (totalFiles > 1) {
                        "[${currentIndex}/$totalFiles] $phase: ${fileInfo.fileName}"
                    } else {
                        "$phase: ${fileInfo.fileName}"
                    }
                    progressCallback(completed, total, phaseWithFile, percent)
                }
            } else {
                null
            }
            
            val success = if (fileInfo.category == "chunked" && !fileInfo.chunks.isNullOrEmpty()) {
                downloadChunkedFile(fileInfo, destPath, filePassword, fileProgressCallback)
            } else {
                downloadDirectFile(fileInfo, destPath, filePassword, fileProgressCallback)
            }
            
            if (success) {
                Log.i(TAG, "Download succeeded: ${fileInfo.fileName}")
                DownloadResult(true, destPath.absolutePath, null)
            } else {
                Log.e(TAG, "Download failed (no exception) for ${fileInfo.fileName}")
                DownloadResult(false, null, "Download failed")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download ${fileInfo.fileName}", e)
            DownloadResult(false, null, e.message)
        }
    }
    
    suspend fun downloadChunkedFile(
        fileInfo: ShareLinkManager.SharedFileInfo,
        destPath: File,
        filePassword: String?,
        progressCallback: LinkProgressCallback?
    ): Boolean = withContext(Dispatchers.IO) {
        val chunks = fileInfo.chunks ?: return@withContext false
        
        Log.i(TAG, "Downloading chunked file: ${fileInfo.fileName} (${chunks.size} chunks)")
        
        // Create temp directory
        val tempDir = File(destPath.parent, "temp_link_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        
        try {
            progressCallback?.invoke(0, chunks.size, "Downloading chunks", 0.0)
            
            // Download chunks with controlled parallelism
            val completedCount = AtomicInteger(0)
            val totalChunks = chunks.size
            
            chunks.chunked(MAX_PARALLEL).forEach { batch ->
                coroutineScope {
                    val results = batch.map { chunk ->
                        async {
                            val success = downloadChunkWithRetry(chunk, tempDir, fileInfo.uploaderBotToken)
                            // Actualizar progreso inmediatamente cuando se completa cada chunk
                            if (success) {
                                val current = completedCount.incrementAndGet()
                                val percent = (current.toDouble() / totalChunks) * 100.0
                                progressCallback?.invoke(current, totalChunks, "Downloading chunks", percent.coerceIn(0.0, 100.0))
                            }
                            success
                        }
                    }.awaitAll()
                    
                    if (results.any { !it }) {
                        throw Exception("Some chunks failed to download")
                    }
                }
            }
            
            Log.i(TAG, "All chunks downloaded, reconstructing file...")
            progressCallback?.invoke(0, totalChunks, "Reconstructing file", 0.0)
            
            // Reconstruct file
            FileOutputStream(destPath).use { output ->
                chunks.sortedBy { it.chunkNumber }.forEachIndexed { index, chunk ->
                    val chunkFile = File(tempDir, "chunk_${chunk.chunkNumber}.dat")
                    if (chunkFile.exists()) {
                        chunkFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    
                    val percent = ((index + 1).toDouble() / totalChunks) * 100.0
                    progressCallback?.invoke(index + 1, totalChunks, "Reconstructing file", percent)
                }
            }
            
            // Cleanup temp directory
            tempDir.deleteRecursively()
            
            // Decrypt if needed
            if (fileInfo.isEncrypted && !filePassword.isNullOrEmpty()) {
                Log.i(TAG, "Decrypting file...")
                progressCallback?.invoke(0, 1, "Decrypting file", 0.0)
                
                val tempEncrypted = File(destPath.parent, destPath.name + ".encrypted")
                destPath.renameTo(tempEncrypted)
                
                if (!decryptFile(tempEncrypted, destPath, filePassword)) {
                    tempEncrypted.renameTo(destPath)
                    return@withContext false
                }
                
                tempEncrypted.delete()
                progressCallback?.invoke(1, 1, "Decrypting file", 100.0)
            }
            
            Log.i(TAG, "Chunked file downloaded successfully: ${destPath.absolutePath}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download chunked file", e)
            tempDir.deleteRecursively()
            false
        }
    }
    
    private suspend fun downloadChunkWithRetry(
        chunk: ShareLinkManager.SharedChunkInfo,
        tempDir: File,
        defaultToken: String
    ): Boolean {
        val chunkPath = File(tempDir, "chunk_${chunk.chunkNumber}.dat")
        val tokenToUse = chunk.uploaderBotToken.ifEmpty { defaultToken }
        
        repeat(MAX_RETRIES) { attempt ->
            if (attempt > 0) {
                Log.w(TAG, "Retrying chunk ${chunk.chunkNumber} (attempt ${attempt + 1}/$MAX_RETRIES)")
                delay(1000L * attempt) // Exponential backoff
            }
            
            try {
                // Primero obtener el filePath desde el fileId usando getFile()
                Log.d(TAG, "Getting file info for chunk ${chunk.chunkNumber}, fileId=${chunk.telegramFileId.take(50)}...")
                val fileResponse = botClient.getFile(tokenToUse, chunk.telegramFileId)
                Log.d(TAG, "Chunk ${chunk.chunkNumber} filePath=${fileResponse.filePath}")
                
                // Ahora descargar usando el filePath
                val bytes = botClient.downloadFileToBytes(tokenToUse, fileResponse.filePath)
                chunkPath.writeBytes(bytes)
                Log.d(TAG, "Downloaded chunk ${chunk.chunkNumber + 1}/${chunk.totalChunks} (${bytes.size} bytes)")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Chunk ${chunk.chunkNumber} attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == MAX_RETRIES - 1) {
                    Log.e(TAG, "Chunk ${chunk.chunkNumber} final attempt failed with exception", e)
                }
            }
        }
        
        Log.e(TAG, "Failed to download chunk ${chunk.chunkNumber} after $MAX_RETRIES attempts")
        return false
    }
    
    suspend fun downloadDirectFile(
        fileInfo: ShareLinkManager.SharedFileInfo,
        destPath: File,
        filePassword: String?,
        progressCallback: LinkProgressCallback?
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "Downloading direct file: ${fileInfo.fileName}")
            progressCallback?.invoke(0, 1, "Downloading file", 0.0)
            
            val tokenToUse = fileInfo.uploaderBotToken
            val fileResponse = botClient.getFile(tokenToUse, fileInfo.telegramFileId)
            Log.i(TAG, "Downloading Telegram file ${fileInfo.telegramFileId} -> path=${fileResponse.filePath}")
            
            val totalBytes = fileResponse.fileSize ?: fileInfo.fileSize
            Log.i(TAG, "Direct file total size: $totalBytes bytes")
            
            // Usar downloadFile con callback de progreso para reportar progreso real
            FileOutputStream(destPath).use { output ->
                botClient.downloadFile(
                    token = tokenToUse,
                    filePath = fileResponse.filePath,
                    outputStream = output,
                    totalBytes = totalBytes,
                    onProgress = { bytesDownloaded, total ->
                        if (total > 0) {
                            val percent = (bytesDownloaded.toDouble() / total.toDouble()) * 100.0
                            progressCallback?.invoke(1, 1, "Downloading file", percent.coerceIn(0.0, 100.0))
                        }
                    }
                )
            }
            
            progressCallback?.invoke(1, 1, "Downloading file", 100.0)
            
            // Decrypt if needed
            if (fileInfo.isEncrypted && !filePassword.isNullOrEmpty()) {
                Log.i(TAG, "Decrypting file...")
                progressCallback?.invoke(0, 1, "Decrypting file", 0.0)
                
                val tempEncrypted = File(destPath.parent, destPath.name + ".encrypted")
                destPath.renameTo(tempEncrypted)
                
                if (!decryptFile(tempEncrypted, destPath, filePassword)) {
                    tempEncrypted.renameTo(destPath)
                    return@withContext false
                }
                
                tempEncrypted.delete()
                progressCallback?.invoke(1, 1, "Decrypting file", 100.0)
            }
            
            Log.i(TAG, "Direct file downloaded successfully: ${destPath.absolutePath}")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download direct file", e)
            false
        }
    }
    
    /**
     * Decrypt file using AES-256-CBC (compatible with desktop)
     */
    private fun decryptFile(
        inputFile: File,
        outputFile: File,
        password: String
    ): Boolean {
        return try {
            val encrypted = inputFile.readBytes()
            
            if (encrypted.size < 32) {
                Log.e(TAG, "Invalid encrypted file format")
                return false
            }
            
            val salt = encrypted.copyOfRange(0, 16)
            val iv = encrypted.copyOfRange(16, 32)
            val encryptedData = encrypted.copyOfRange(32, encrypted.size)
            
            val key = deriveKey(password, salt)
            
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
            
            val decrypted = cipher.doFinal(encryptedData)
            outputFile.writeBytes(decrypted)
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "File decryption failed", e)
            false
        }
    }
    
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
}

