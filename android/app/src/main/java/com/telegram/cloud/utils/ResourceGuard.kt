package com.telegram.cloud.utils

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.bumptech.glide.Glide
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralizes resource usage so we can aggressively release memory when
 * no heavy feature (auto-sync, manual sync, streaming) is running.
 */
object ResourceGuard {
    private const val TAG = "ResourceGuard"
    private val activeFeatures = ConcurrentHashMap.newKeySet<Feature>()
    private lateinit var appContext: Context

    enum class Feature {
        AUTO_SYNC,
        MANUAL_SYNC,
        STREAMING
    }

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    fun markActive(feature: Feature) {
        activeFeatures.add(feature)
        Log.d(TAG, "Feature $feature marked active. Active=${activeFeatures.size}")
    }

    fun markIdle(feature: Feature) {
        if (activeFeatures.remove(feature)) {
            Log.d(TAG, "Feature $feature marked idle. Remaining=${activeFeatures.size}")
        }
        if (activeFeatures.isEmpty()) {
            releaseIdleResources()
        }
    }

    fun releaseIdleResources() {
        if (!::appContext.isInitialized) return
        Log.d(TAG, "Releasing idle resources")
        try {
            ChunkedStreamingRegistry.releaseAll()
            Glide.get(appContext).clearMemory()
            WorkManager.getInstance(appContext).pruneWork()
            Runtime.getRuntime().gc()
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing resources", e)
        }
    }

    fun onTrimMemory(level: Int) {
        if (!::appContext.isInitialized) return
        try {
            Glide.get(appContext).trimMemory(level)
        } catch (_: IllegalStateException) {
            // Glide not initialized yet
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            releaseIdleResources()
        }
    }
}


