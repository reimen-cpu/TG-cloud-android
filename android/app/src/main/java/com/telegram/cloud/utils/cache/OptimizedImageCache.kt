package com.telegram.cloud.utils.cache

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.unit.IntSize
import androidx.core.util.LruCache
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.size.Scale
import com.telegram.cloud.utils.performance.PerformanceMonitor
import timber.log.Timber
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

class OptimizedImageCache(private val context: Context) {
    
    private val memoryCache: LruCache<String, Bitmap>
    private val diskCacheDir: File
    private val imageLoader: ImageLoader
    
    init {
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        val cacheSize = calculateOptimalCacheSize(memoryClass)
        
        memoryCache = object : LruCache<String, Bitmap>(cacheSize) {
            override fun sizeOf(key: String, bitmap: Bitmap): Int {
                return bitmap.byteCount
            }
            
            override fun entryRemoved(
                evicted: Boolean,
                key: String,
                oldValue: Bitmap,
                newValue: Bitmap?
            ) {
                if (evicted) {
                    Timber.d("Image evicted from cache: $key")
                }
            }
        }
        
        diskCacheDir = File(context.cacheDir, "optimized_image_cache")
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs()
        }
        
        imageLoader = createOptimizedImageLoader()
    }
    
    private fun calculateOptimalCacheSize(memoryClass: Int): Int {
        // Use percentage of available heap based on device memory class
        val heapSize = (Runtime.getRuntime().maxMemory() / 1024 / 1024).toInt()
        
        return when {
            memoryClass < 128 -> (heapSize * 0.08).toInt() * 1024 * 1024 // 8% for low-end
            memoryClass < 256 -> (heapSize * 0.12).toInt() * 1024 * 1024 // 12% for mid-range  
            else -> (heapSize * 0.16).toInt() * 1024 * 1024 // 16% for high-end
        }
    }
    
    private fun createOptimizedImageLoader(): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(0.25) // 25% of available memory
                    .build()
            }
            .diskCache {
                val diskCacheSize = when {
                    getTotalDiskSpace() < 2_000_000_000L -> 50 * 1024 * 1024L // 50MB for low storage
                    getTotalDiskSpace() < 8_000_000_000L -> 100 * 1024 * 1024L // 100MB for medium
                    else -> 200 * 1024 * 1024L // 200MB for high storage
                }
                
                DiskCache.Builder()
                    .directory(File(context.cacheDir, "coil_cache"))
                    .maxSizeBytes(diskCacheSize)
                    .build()
            }
            .respectCacheHeaders(false) // Aggressive caching
            .defaultRequestOptions {
                coil.request.Options(
                    memoryCachePolicy = CachePolicy.ENABLED,
                    diskCachePolicy = CachePolicy.ENABLED,
                    networkCachePolicy = CachePolicy.ENABLED,
                    scale = Scale.FILL,
                    allowRgb565 = shouldUseRgb565(),
                    precision = coil.size.Precision.INEXACT
                )
            }
            .build()
    }
    
    private fun shouldUseRgb565(): Boolean {
        val memoryClass = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).memoryClass
        return memoryClass < 256 // Use RGB565 on low memory devices
    }
    
    private fun getTotalDiskSpace(): Long {
        return try {
            val stat = android.os.StatFs(context.cacheDir.path)
            stat.blockCountLong * stat.blockSizeLong
        } catch (e: Exception) {
            1_000_000_000L // Default to 1GB on error
        }
    }
    
    suspend fun loadOptimizedImage(
        url: String,
        targetSize: IntSize,
        priority: coil.request.Priority = coil.request.Priority.NORMAL
): Bitmap? = PerformanceMonitor.measureSuspendOperation("load_image") {
        try {
            val cacheKey = generateCacheKey(url, targetSize)
            
            // Check memory cache first
            memoryCache.get(cacheKey)?.let { return@measureSuspendOperation it }
            
            // Load with optimized settings
            val request = ImageRequest.Builder(context)
                .data(url)
                .size(targetSize.width, targetSize.height)
                .priority(priority)
                .target { drawable ->
                    val bitmap = Bitmap.createBitmap(
                        drawable.intrinsicWidth,
                        drawable.intrinsicHeight,
                        Bitmap.Config.ARGB_8888
                    )
                    memoryCache.put(cacheKey, bitmap)
                }
                .build()
            
            val result = imageLoader.execute(request)
            result.drawable?.let { drawable ->
                val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth,
                    drawable.intrinsicHeight,
                    Bitmap.Config.ARGB_8888
                )
                memoryCache.put(cacheKey, bitmap)
                return@measureSuspendOperation bitmap
            }
            
            return@measureSuspendOperation null
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "OOM loading image: $url, clearing cache")
            clearMemoryCache()
            System.gc()
            return@measureSuspendOperation null
        } catch (e: Exception) {
            Timber.e(e, "Error loading image: $url")
            return@measureSuspendOperation null
        }
    }
    
    fun loadBitmapSafely(
        filePath: String,
        targetSize: IntSize
    ): Bitmap? = PerformanceMonitor.measureOperation("load_bitmap_safe") {
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            
            // Get image dimensions without loading into memory
            BitmapFactory.decodeFile(filePath, options)
            
            // Calculate sample size to fit memory budget
            options.inSampleSize = calculateSampleSize(options, targetSize)
            options.inJustDecodeBounds = false
            
            // Choose appropriate config based on memory pressure
            val memoryPressure = PerformanceMonitor.getMemoryPressureLevel(context)
            options.inPreferredConfig = when (memoryPressure) {
                PerformanceMonitor.MemoryPressure.CRITICAL -> Bitmap.Config.RGB_565
                PerformanceMonitor.MemoryPressure.HIGH -> Bitmap.Config.RGB_565
                else -> Bitmap.Config.ARGB_8888
            }
            
            // Enable hardware bitmap when possible
            if (memoryPressure == PerformanceMonitor.MemoryPressure.LOW) {
                options.inPreferredConfig = Bitmap.Config.HARDWARE
            }
            
            return@measureOperation BitmapFactory.decodeFile(filePath, options)
        } catch (e: OutOfMemoryError) {
            Timber.w(e, "OOM loading bitmap: $filePath")
            clearMemoryCache()
            System.gc()
            // Retry with smaller size
            return@measureOperation loadBitmapSafely(filePath, IntSize(targetSize.width / 2, targetSize.height / 2))
        } catch (e: Exception) {
            Timber.e(e, "Error loading bitmap: $filePath")
            return@measureOperation null
        }
    }
    
    private fun calculateSampleSize(options: BitmapFactory.Options, targetSize: IntSize): Int {
        val (targetWidth, targetHeight) = targetSize
        val (width, height) = Pair(options.outWidth, options.outHeight)
        
        var sampleSize = 1
        if (height > targetHeight || width > targetWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            
            while (halfHeight / sampleSize >= targetHeight && halfWidth / sampleSize >= targetWidth) {
                sampleSize *= 2
            }
        }
        
        return sampleSize
    }
    
    private fun generateCacheKey(url: String, size: IntSize): String {
        val input = "$url:${size.width}x${size.height}"
        val md = MessageDigest.getInstance("MD5")
        return BigInteger(1, md.digest(input.toByteArray())).toString(16).substring(0, 16)
    }
    
    fun clearMemoryCache() {
        memoryCache.evictAll()
        Timber.d("Memory cache cleared")
    }
    
    fun clearDiskCache() {
        try {
            diskCacheDir.deleteRecursively()
            diskCacheDir.mkdirs()
            Timber.d("Disk cache cleared")
        } catch (e: Exception) {
            Timber.e(e, "Error clearing disk cache")
        }
    }
    
    fun getCacheStats(): CacheStats {
        return CacheStats(
            memorySize = memoryCache.size(),
            memoryMaxSize = memoryCache.maxSize(),
            memoryHitCount = memoryCache.hitCount(),
            memoryMissCount = memoryCache.missCount(),
            diskSize = calculateDiskCacheSize()
        )
    }
    
    private fun calculateDiskCacheSize(): Long {
        return try {
            diskCacheDir.walkTopDown()
                .filter { it.isFile }
                .map { it.length() }
                .sum()
        } catch (e: Exception) {
            0L
        }
    }
    
    fun optimizeForMemoryPressure() {
        val pressure = PerformanceMonitor.getMemoryPressureLevel(context)
        
        when (pressure) {
            PerformanceMonitor.MemoryPressure.CRITICAL -> {
                clearMemoryCache()
                // Evict 75% of Coil memory cache
                imageLoader.memoryCache?.let { cache ->
                    val size = cache.size
                    val targetSize = (size * 0.25).toLong()
                    if (targetSize > 0) {
                        cache.trimToSize(targetSize.toInt())
                    }
                }
            }
            PerformanceMonitor.MemoryPressure.HIGH -> {
                // Evict 50% of Coil memory cache
                imageLoader.memoryCache?.let { cache ->
                    val size = cache.size
                    val targetSize = (size * 0.5).toLong()
                    if (targetSize > 0) {
                        cache.trimToSize(targetSize.toInt())
                    }
                }
            }
            PerformanceMonitor.MemoryPressure.MEDIUM -> {
                // Evict 25% of Coil memory cache
                imageLoader.memoryCache?.let { cache ->
                    val size = cache.size
                    val targetSize = (size * 0.75).toLong()
                    if (targetSize > 0) {
                        cache.trimToSize(targetSize.toInt())
                    }
                }
            }
            else -> {
                // No action needed for LOW pressure
            }
        }
    }
    
    data class CacheStats(
        val memorySize: Int,
        val memoryMaxSize: Int,
        val memoryHitCount: Int,
        val memoryMissCount: Int,
        val diskSize: Long
    ) {
        val memoryUsagePercent: Float
            get() = if (memoryMaxSize > 0) (memorySize.toFloat() / memoryMaxSize) * 100 else 0f
            
        val hitRatePercent: Float
            get() {
                val total = memoryHitCount + memoryMissCount
                return if (total > 0) (memoryHitCount.toFloat() / total) * 100 else 0f
            }
    }
}