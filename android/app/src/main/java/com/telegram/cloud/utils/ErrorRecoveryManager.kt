package com.telegram.cloud.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages error recovery strategies for common failures in media viewing.
 * Implements retry logic with exponential backoff and fallback strategies.
 */
object ErrorRecoveryManager {
    private const val TAG = "ErrorRecoveryManager"
    
    // Retry configuration
    private const val MAX_RETRIES = 3
    private const val INITIAL_BACKOFF_MS = 500L
    private const val MAX_BACKOFF_MS = 5000L
    
    /**
     * Result of a recovery attempt
     */
    sealed class RecoveryResult<T> {
        data class Success<T>(val data: T) : RecoveryResult<T>()
        data class Failure<T>(val error: Throwable, val canRetry: Boolean = true) : RecoveryResult<T>()
    }
    
    /**
     * Execute an operation with retry logic and exponential backoff
     */
    suspend fun <T> executeWithRetry(
        operation: String,
        maxRetries: Int = MAX_RETRIES,
        block: suspend (attempt: Int) -> T
    ): RecoveryResult<T> {
        var lastError: Throwable? = null
        
        repeat(maxRetries) { attempt ->
            try {
                Log.d(TAG, "Attempting $operation (attempt ${attempt + 1}/$maxRetries)")
                val result = block(attempt)
                
                if (attempt > 0) {
                    Log.i(TAG, "$operation succeeded after ${attempt + 1} attempts")
                }
                
                return RecoveryResult.Success(result)
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "$operation failed (attempt ${attempt + 1}/$maxRetries): ${e.message}")
                
                // Don't retry on certain errors
                if (!isRetryableError(e)) {
                    Log.e(TAG, "$operation failed with non-retryable error", e)
                    return RecoveryResult.Failure(e, canRetry = false)
                }
                
                // Wait before retrying (exponential backoff)
                if (attempt < maxRetries - 1) {
                    val backoffMs = calculateBackoff(attempt)
                    Log.d(TAG, "Waiting ${backoffMs}ms before retry...")
                    delay(backoffMs)
                }
            }
        }
        
        Log.e(TAG, "$operation failed after $maxRetries attempts", lastError)
        return RecoveryResult.Failure(lastError ?: Exception("Unknown error"), canRetry = false)
    }
    
    /**
     * Calculate exponential backoff delay
     */
    private fun calculateBackoff(attempt: Int): Long {
        val exponentialBackoff = (INITIAL_BACKOFF_MS * 2.0.pow(attempt)).toLong()
        return min(exponentialBackoff, MAX_BACKOFF_MS)
    }
    
    /**
     * Determine if an error is retryable
     */
    private fun isRetryableError(error: Throwable): Boolean {
        return when (error) {
            is OutOfMemoryError -> false // Don't retry OOM errors
            is java.io.FileNotFoundException -> false // File doesn't exist, won't help to retry
            is IllegalArgumentException -> false // Bad parameters, won't help to retry
            is java.net.SocketTimeoutException -> true // Network timeout, can retry
            is java.io.IOException -> true // Network/IO error, can retry
            else -> true // Default to retryable
        }
    }
    
    /**
     * Handle image load error with appropriate recovery strategy
     */
    suspend fun handleImageLoadError(
        context: Context,
        imagePath: String,
        error: Throwable,
        onRetry: suspend () -> Unit,
        onFallback: suspend () -> Unit
    ) {
        Log.e(TAG, "Image load error for $imagePath", error)
        
        when (error) {
            is OutOfMemoryError -> {
                Log.e(TAG, "OutOfMemoryError loading image - requesting GC and using fallback")
                MemoryManager.requestGCIfNeeded(context)
                delay(1000) // Give GC time to work
                onFallback()
            }
            is java.io.FileNotFoundException -> {
                Log.e(TAG, "Image file not found: $imagePath")
                // No point retrying if file doesn't exist
            }
            else -> {
                if (isRetryableError(error)) {
                    Log.d(TAG, "Retrying image load...")
                    delay(INITIAL_BACKOFF_MS)
                    onRetry()
                }
            }
        }
    }
    
    /**
     * Handle streaming error with fallback to full download
     */
    suspend fun handleStreamingError(
        error: Throwable,
        onRetryStreaming: suspend () -> Unit,
        onFallbackToDownload: suspend () -> Unit
    ): Boolean {
        Log.e(TAG, "Streaming error", error)
        
        return when (error) {
            is OutOfMemoryError -> {
                Log.e(TAG, "OutOfMemoryError during streaming - falling back to download")
                onFallbackToDownload()
                true
            }
            is java.net.SocketTimeoutException,
            is java.io.IOException -> {
                Log.d(TAG, "Network error during streaming - retrying...")
                delay(INITIAL_BACKOFF_MS)
                onRetryStreaming()
                true
            }
            else -> {
                Log.e(TAG, "Unrecoverable streaming error - falling back to download")
                onFallbackToDownload()
                false
            }
        }
    }
    
    /**
     * Handle memory error by freeing resources and retrying with reduced quality
     */
    suspend fun handleMemoryError(
        context: Context,
        onReducedQuality: suspend () -> Unit
    ) {
        Log.e(TAG, "Handling memory error - freeing resources")
        
        // Request garbage collection
        MemoryManager.requestGCIfNeeded(context)
        
        // Wait for GC to complete
        delay(1500)
        
        // Log memory status
        MemoryManager.logMemoryStatus(context, TAG)
        
        // Retry with reduced quality
        Log.d(TAG, "Retrying with reduced quality settings")
        onReducedQuality()
    }
    
    /**
     * Create user-friendly error message
     */
    fun getUserFriendlyErrorMessage(error: Throwable): String {
        return when (error) {
            is OutOfMemoryError -> "Archivo demasiado grande para la memoria disponible"
            is java.io.FileNotFoundException -> "Archivo no encontrado"
            is java.net.SocketTimeoutException -> "Tiempo de espera agotado. Verifica tu conexión."
            is java.io.IOException -> "Error de red. Verifica tu conexión."
            is IllegalArgumentException -> "Formato de archivo no válido"
            else -> "Error al cargar el archivo: ${error.message ?: "Error desconocido"}"
        }
    }
    
    /**
     * Check if error suggests low memory condition
     */
    fun isMemoryRelatedError(error: Throwable): Boolean {
        return error is OutOfMemoryError ||
               error.message?.contains("memory", ignoreCase = true) == true ||
               error.message?.contains("allocation", ignoreCase = true) == true
    }
}
