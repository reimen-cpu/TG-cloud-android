package com.telegram.cloud

import android.app.Application
import android.content.ComponentCallbacks2
import com.telegram.cloud.di.AppContainer
import com.telegram.cloud.gallery.SyncNotificationManager
import com.telegram.cloud.utils.ResourceGuard

import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.decode.VideoFrameDecoder

class TelegramCloudApp : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Create notification channel for gallery sync
        SyncNotificationManager.createNotificationChannel(this)
        ResourceGuard.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        ResourceGuard.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            ResourceGuard.releaseIdleResources()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        ResourceGuard.releaseIdleResources()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache"))
                    .maxSizePercent(0.02)
                    .build()
            }
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .crossfade(true)
            .build()
    }
}


