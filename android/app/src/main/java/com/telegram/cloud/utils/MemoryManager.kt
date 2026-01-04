package com.telegram.cloud.utils

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.ui.unit.IntSize
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Utility class for monitoring and managing application memory usage.
 * Helps prevent OutOfMemoryError by providing memory-aware decisions.
 */
object MemoryManager {
    private const val TAG = "MemoryManager"
    
    // Memory thresholds
    private const val LOW_MEMORY_THRESHOLD_MB = 100
    private const val CRITICAL_MEMORY_THRESHOLD_MB = 50
    
    // Image size limits based on memory
    private const val MAX_IMAGE_PIXELS_LOW_MEMORY = 4_000_000 // ~2000x2000
    private const val MAX_IMAGE_PIXELS_NORMAL = 16_000_000 // ~4000x4000
    private const val MAX_IMAGE_PIXELS_HIGH_MEMORY = 64_000_000 // ~8000x8000
    
    /**
     * Get available memory in megabytes
     */
    fun getAvailableMemoryMB(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.availMem / (1024 * 1024)
    }
    
    /**
     * Get total device memory in megabytes
     */
    fun getTotalMemoryMB(context: Context): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        return memoryInfo.totalMem / (1024 * 1024)
    }
    
    /**
     * Check if device is in low memory state
     */
    fun isLowMemory(context: Context): Boolean {
        val availableMB = getAvailableMemoryMB(context)
        val isLow = availableMB < LOW_MEMORY_THRESHOLD_MB
        
        if (isLow) {
            Log.w(TAG, "Low memory detected: ${availableMB}MB available")
        }
        
        return isLow
    }
    
    /**
     * Check if device is in critical memory state
     */
    fun isCriticalMemory(context: Context): Boolean {
        val availableMB = getAvailableMemoryMB(context)
        val isCritical = availableMB < CRITICAL_MEMORY_THRESHOLD_MB
        
        if (isCritical) {
            Log.e(TAG, "CRITICAL memory state: ${availableMB}MB available")
        }
        
        return isCritical
    }
    
    /**
     * Calculate optimal image size based on available memory and screen size
     * Returns maximum dimensions that should be loaded
     */
    fun calculateOptimalImageSize(
        context: Context,
        screenSize: IntSize,
        originalWidth: Int,
        originalHeight: Int
    ): IntSize {
        val availableMB = getAvailableMemoryMB(context)
        
        // Determine max pixels based on memory
        val maxPixels = when {
            availableMB < LOW_MEMORY_THRESHOLD_MB -> MAX_IMAGE_PIXELS_LOW_MEMORY
            availableMB < 300 -> MAX_IMAGE_PIXELS_NORMAL
            else -> MAX_IMAGE_PIXELS_HIGH_MEMORY
        }
        
        val originalPixels = originalWidth * originalHeight
        
        // If image is small enough, return original size
        if (originalPixels <= maxPixels) {
            Log.d(TAG, "Image size OK: ${originalWidth}x${originalHeight} (${originalPixels} pixels)")
            return IntSize(originalWidth, originalHeight)
        }
        
        // Calculate downscale factor
        val scaleFactor = sqrt(maxPixels.toDouble() / originalPixels.toDouble())
        val targetWidth = (originalWidth * scaleFactor).toInt()
        val targetHeight = (originalHeight * scaleFactor).toInt()
        
        // Also consider screen size - no need to load larger than 2x screen
        val maxScreenWidth = screenSize.width * 2
        val maxScreenHeight = screenSize.height * 2
        
        val finalWidth = min(targetWidth, maxScreenWidth)
        val finalHeight = min(targetHeight, maxScreenHeight)
        
        Log.d(TAG, "Downscaling image: ${originalWidth}x${originalHeight} -> ${finalWidth}x${finalHeight} (memory: ${availableMB}MB)")
        
        return IntSize(finalWidth, finalHeight)
    }
    
    /**
     * Determine if hardware bitmap should be used
     * Hardware bitmaps are more memory efficient but have some limitations
     */
    fun shouldUseHardwareBitmap(context: Context, imageWidth: Int, imageHeight: Int): Boolean {
        // Hardware bitmaps require Android O+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false
        }
        
        val availableMB = getAvailableMemoryMB(context)
        val pixels = imageWidth * imageHeight
        
        // Use hardware bitmap for large images when memory is available
        // Don't use for very large images as they may fail to allocate
        return when {
            availableMB < LOW_MEMORY_THRESHOLD_MB -> false // Too risky in low memory
            pixels > 25_000_000 -> false // Too large for hardware bitmap
            else -> true
        }
    }
    
    /**
     * Get recommended chunk buffer size for video streaming
     */
    fun getRecommendedChunkBufferSize(context: Context): Int {
        val availableMB = getAvailableMemoryMB(context)
        
        return when {
            availableMB < LOW_MEMORY_THRESHOLD_MB -> 2 // Minimal buffering
            availableMB < 300 -> 3 // Conservative buffering
            availableMB < 500 -> 5 // Normal buffering
            else -> 7 // Aggressive buffering for smooth playback
        }
    }
    
    /**
     * Log current memory status
     */
    fun logMemoryStatus(context: Context, tag: String = TAG) {
        val availableMB = getAvailableMemoryMB(context)
        val totalMB = getTotalMemoryMB(context)
        val usedMB = totalMB - availableMB
        val usedPercent = (usedMB * 100.0 / totalMB).toInt()
        
        Log.d(tag, "Memory: ${usedMB}MB used / ${totalMB}MB total (${usedPercent}%) - ${availableMB}MB available")
    }
    
    /**
     * Request garbage collection if memory is low
     * Note: This is just a hint to the system, not guaranteed
     */
    fun requestGCIfNeeded(context: Context) {
        if (isLowMemory(context)) {
            Log.d(TAG, "Requesting garbage collection due to low memory")
            System.gc()
        }
    }
}
