package com.telegram.cloud.gallery

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.telegram.cloud.R
import com.telegram.cloud.TelegramCloudApp
import com.telegram.cloud.data.local.CloudDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class GalleryRestoreWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "gallery_restore_work"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "gallery_restore_channel"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        createNotificationChannel()
        setForeground(createForegroundInfo(0, 0, "Starting restore..."))

        try {
            val app = applicationContext as TelegramCloudApp
            val database = app.container.database
            val repository = app.container.repository
            val galleryDao = database.galleryMediaDao()
            val syncManager = GallerySyncManager(applicationContext, database, galleryDao, repository)
            val restoreManager = GalleryRestoreManager(applicationContext, database, galleryDao, syncManager)
            
            val config = repository.config.first()
            if (config == null || config.tokens.isEmpty()) {
                return@withContext Result.failure()
            }

            val success = restoreManager.restoreAllSynced(config) { current, total, filename ->
                setForegroundAsync(createForegroundInfo(current, total, filename))
            }

            if (success) Result.success() else Result.failure()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.failure()
        }
    }

    private fun createForegroundInfo(current: Int, total: Int, filename: String): ForegroundInfo {
        val title = applicationContext.getString(R.string.restoring_gallery)
        val progress = if (total > 0) "$current/$total" else ""
        val content = "$progress $filename"

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with actual icon
            .setOngoing(true)
            .setProgress(total, current, total == 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Gallery Restore"
            val descriptionText = "Notifications for gallery restoration"
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
