package com.telegram.cloud.gallery

import android.util.Log
import com.telegram.cloud.data.prefs.BotConfig
import com.telegram.cloud.data.share.MultiLinkGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages multiple file operations for GalleryMediaEntity
 */
class MultiFileGalleryManager(
    private val galleryViewModel: GalleryViewModel,
    private val multiLinkGenerator: MultiLinkGenerator
) {
    companion object {
        private const val TAG = "MultiFileGalleryManager"
    }

    data class MultiOperationResult(
        val successful: Int,
        val failed: Int,
        val errors: List<String>
    )

    /**
     * Delete multiple media files
     */
    suspend fun deleteMultiple(
        mediaList: List<GalleryMediaEntity>,
        deleteFromTelegram: Boolean,
        config: BotConfig?,
        onProgress: ((Int, Int) -> Unit)? = null
    ): MultiOperationResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting batch delete for ${mediaList.size} items")
        
        var successful = 0
        var failed = 0
        val errors = mutableListOf<String>()
        
        mediaList.forEachIndexed { index, media ->
            onProgress?.invoke(index + 1, mediaList.size)
            
            try {
                galleryViewModel.deleteMedia(media, deleteFromTelegram, config)
                successful++
            } catch (e: Exception) {
                failed++
                errors.add("${media.filename}: ${e.message}")
                Log.e(TAG, "Failed to delete ${media.filename}", e)
            }
        }
        
        Log.i(TAG, "Batch delete completed: $successful successful, $failed failed")
        MultiOperationResult(successful, failed, errors)
    }

    /**
     * Download multiple media files from Telegram
     */
    suspend fun downloadMultiple(
        mediaList: List<GalleryMediaEntity>,
        config: BotConfig,
        onProgress: ((Int, Int, Float) -> Unit)? = null
    ): MultiOperationResult = withContext(Dispatchers.IO) {
        Log.i(TAG, "Starting batch download for ${mediaList.size} items")
        
        var successful = 0
        var failed = 0
        val errors = mutableListOf<String>()
        val total = mediaList.size
        
        // Process sequentially to avoid overwhelming the network/UI with too many concurrent downloads
        // or use a limited parallelism if needed. For now, sequential is safer for progress tracking.
        mediaList.forEachIndexed { index, media ->
            try {
                // We use a suspendCoroutine or similar if we want to wait for the callback-based downloadFromTelegram
                // But GalleryViewModel.downloadFromTelegram is callback based. 
                // We need to wrap it to be suspendable or just fire and forget?
                // The user requirement says "Reutiliza GalleryViewModel.downloadFromTelegram()".
                // Since that function launches a coroutine scope, we might need to be careful.
                // Ideally, we should refactor downloadFromTelegram to be suspend, but we must not change existing code unnecessarily.
                // So we will use a latch mechanism here.
                
                val success = downloadSingleMediaAndWait(media, config) { progress ->
                    onProgress?.invoke(index + 1, total, progress)
                }
                
                if (success) successful++ else failed++
                
            } catch (e: Exception) {
                failed++
                errors.add("${media.filename}: ${e.message}")
            }
        }
        
        MultiOperationResult(successful, failed, errors)
    }
    
    private suspend fun downloadSingleMediaAndWait(
        media: GalleryMediaEntity,
        config: BotConfig,
        onProgress: (Float) -> Unit
    ): Boolean = kotlinx.coroutines.suspendCancellableCoroutine { continuation ->
        galleryViewModel.downloadFromTelegram(
            media = media,
            config = config,
            onProgress = onProgress,
            onSuccess = { 
                if (continuation.isActive) continuation.resume(true, null) 
            },
            onError = { 
                if (continuation.isActive) continuation.resume(false, null) 
            }
        )
    }

    /**
     * Generate .link file for multiple media items
     */
    suspend fun generateBatchLink(
        mediaList: List<GalleryMediaEntity>,
        password: String,
        outputFile: File
    ): Boolean {
        return multiLinkGenerator.generateForGallery(mediaList, password, outputFile)
    }
}
