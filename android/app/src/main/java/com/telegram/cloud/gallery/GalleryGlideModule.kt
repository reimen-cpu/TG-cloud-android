package com.telegram.cloud.gallery

import android.content.Context
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions

/**
 * Custom Glide module for gallery optimizations:
 * - Larger disk cache for thumbnails
 * - Optimized memory cache
 * - HEIC format support (via system decoder)
 * - Progressive loading defaults
 */
@GlideModule
class GalleryGlideModule : AppGlideModule() {
    
    companion object {
        private const val TAG = "GalleryGlideModule"
        // 500 MB disk cache for gallery thumbnails
        private const val DISK_CACHE_SIZE_BYTES = 500L * 1024 * 1024
        // 1/8 of available memory for image cache
        private const val MEMORY_CACHE_FRACTION = 8
    }
    
    override fun applyOptions(context: Context, builder: GlideBuilder) {
        // Calculate memory cache size
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).memoryClass
        val memoryCacheSize = (memoryClass * 1024 * 1024 / MEMORY_CACHE_FRACTION).toLong()
        
        Log.d(TAG, "Configuring Glide: diskCache=${DISK_CACHE_SIZE_BYTES / 1024 / 1024}MB, memoryCache=${memoryCacheSize / 1024 / 1024}MB")
        
        builder.apply {
            // Larger disk cache for gallery thumbnails
            setDiskCache(InternalCacheDiskCacheFactory(context, "gallery_cache", DISK_CACHE_SIZE_BYTES))
            
            // Memory cache
            setMemoryCache(LruResourceCache(memoryCacheSize))
            
            // Default request options for quality
            setDefaultRequestOptions(
                RequestOptions()
                    .format(DecodeFormat.PREFER_RGB_565) // Use less memory for thumbnails
                    .disallowHardwareConfig() // Avoid hardware bitmap issues on some devices
            )
            
            // Log level for debugging
            setLogLevel(Log.WARN)
        }
    }
    
    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        // HEIC/HEIF is automatically supported on Android 9+ via ImageDecoder
        // which Glide uses internally. No special decoder needed.
        Log.d(TAG, "Glide components registered. HEIC support: Android 9+ built-in")
    }
    
    override fun isManifestParsingEnabled(): Boolean = false
}
