package com.telegram.cloud.gallery

import android.util.Log
import android.util.LruCache
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-based thumbnail cache to prevent unnecessary regeneration.
 * Holds thumbnail paths in memory for the duration of the app session.
 */
object ThumbnailCache {
    private const val TAG = "ThumbnailCache"
    private const val MAX_CACHE_SIZE = 200 // Maximum number of thumbnails to cache
    
    // LruCache for automatic memory management
    private val cache = LruCache<Long, String>(MAX_CACHE_SIZE)
    
    // Track which media IDs have been checked this session
    // This prevents redundant file existence checks
    private val checkedThisSession = ConcurrentHashMap.newKeySet<Long>()
    
    /**
     * Get thumbnail path for a media item.
     * Returns cached path if available, otherwise validates the provided path.
     * 
     * @param mediaId Media entity ID
     * @param thumbnailPath Path from database (nullable)
     * @return Valid thumbnail path or null if not available
     */
    fun getThumbnail(mediaId: Long, thumbnailPath: String?): String? {
        // Check in-memory cache first
        val cached = cache.get(mediaId)
        if (cached != null) {
            return cached
        }
        
        // If we've already checked this media this session and found no thumbnail,
        // don't check again
        if (checkedThisSession.contains(mediaId) && thumbnailPath == null) {
            return null
        }
        
        // Validate thumbnail path from database
        if (thumbnailPath != null && File(thumbnailPath).exists()) {
            cache.put(mediaId, thumbnailPath)
            checkedThisSession.add(mediaId)
            return thumbnailPath
        }
        
        // Mark as checked even if not found to prevent repeated checks
        checkedThisSession.add(mediaId)
        return null
    }
    
    /**
     * Store a thumbnail path in cache after generation.
     * 
     * @param mediaId Media entity ID
     * @param path Path to generated thumbnail
     */
    fun putThumbnail(mediaId: Long, path: String) {
        cache.put(mediaId, path)
        checkedThisSession.add(mediaId)
        Log.d(TAG, "Cached thumbnail for media $mediaId: $path")
    }
    
    /**
     * Check if a thumbnail exists in cache.
     * 
     * @param mediaId Media entity ID
     * @return true if thumbnail is cached, false otherwise
     */
    fun hasThumbnail(mediaId: Long): Boolean {
        return cache.get(mediaId) != null
    }
    
    /**
     * Clear the entire cache.
     * Should be called when the app is restarted or user clears app data.
     */
    fun clear() {
        cache.evictAll()
        checkedThisSession.clear()
        Log.d(TAG, "Thumbnail cache cleared")
    }
    
    /**
     * Get cache statistics for debugging.
     */
    fun getStats(): CacheStats {
        return CacheStats(
            size = cache.size(),
            maxSize = cache.maxSize(),
            hitCount = cache.hitCount(),
            missCount = cache.missCount(),
            checkedCount = checkedThisSession.size
        )
    }
    
    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hitCount: Int,
        val missCount: Int,
        val checkedCount: Int
    )
}
