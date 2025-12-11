package com.telegram.cloud.gallery

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Animatable
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import com.telegram.cloud.MainActivity
import com.telegram.cloud.R

object SyncNotificationManager {
    private const val CHANNEL_ID = "sync_channel"
    private const val NOTIFICATION_ID_SYNC = 1001
    private const val NOTIFICATION_ID_UPLOAD = 1002
    private const val NOTIFICATION_ID_DOWNLOAD = 1003
    internal const val ACTION_PAUSE = "pause_sync"
    internal const val ACTION_STOP = "stop_sync"
    internal const val EXTRA_RESUME = "sync_resume"
    internal const val EXTRA_OPERATION_TYPE = "operation_type"
    
    enum class OperationType {
        SYNC, UPLOAD, DOWNLOAD
    }
    
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Gallery Sync",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Shows progress of gallery synchronization"
                setShowBadge(false)
                enableVibration(false)
                enableLights(false)
            }
            
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    private fun getNotificationId(operationType: OperationType): Int {
        return when (operationType) {
            OperationType.SYNC -> NOTIFICATION_ID_SYNC
            OperationType.UPLOAD -> NOTIFICATION_ID_UPLOAD
            OperationType.DOWNLOAD -> NOTIFICATION_ID_DOWNLOAD
        }
    }
    
    private fun getTitle(operationType: OperationType, isPaused: Boolean): String {
        val baseTitle = when (operationType) {
            OperationType.SYNC -> "Gallery Sync"
            OperationType.UPLOAD -> "Uploading"
            OperationType.DOWNLOAD -> "Downloading"
        }
        return if (isPaused) "$baseTitle (Paused)" else "$baseTitle in Progress"
    }
    
    private fun animateIcon(context: Context) {
        val drawable = ContextCompat.getDrawable(context, R.drawable.avd_arrow_rotate)
        (drawable as? Animatable)?.start()
    }

    private fun buildProgressNotification(
        context: Context,
        current: Int,
        total: Int,
        currentFile: String,
        operationType: OperationType = OperationType.SYNC,
        isPaused: Boolean = false
    ): Notification {
        val progress = if (total > 0) (current * 100 / total) else 0
        
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Pause/Resume action
        val pauseIntent = Intent(context, SyncNotificationReceiver::class.java).apply {
            action = ACTION_PAUSE
            putExtra(EXTRA_RESUME, isPaused) // When paused, button becomes "Resume"
            putExtra(EXTRA_OPERATION_TYPE, operationType.name)
        }
        val pausePendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            pauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        // Stop action
        val stopIntent = Intent(context, SyncNotificationReceiver::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_OPERATION_TYPE, operationType.name)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val progressText = when (operationType) {
            OperationType.SYNC -> "Syncing: $currentFile"
            OperationType.UPLOAD -> "Uploading: $currentFile"
            OperationType.DOWNLOAD -> "Downloading: $currentFile"
        }

        val title = getTitle(operationType, isPaused)
        val bigText = when (operationType) {
            OperationType.SYNC -> "Syncing $current of $total files\nCurrent: $currentFile"
            OperationType.UPLOAD -> "Uploading $current of $total files\nCurrent: $currentFile"
            OperationType.DOWNLOAD -> "Downloading $current of $total files\nCurrent: $currentFile"
        }

        animateIcon(context)
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.avd_arrow_rotate)
            .setContentTitle(title)
            .setContentText(progressText)
            .setProgress(100, progress, false)
            .setContentIntent(mainPendingIntent)
            .setOngoing(!isPaused)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .addAction(
                if (isPaused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pausePendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(bigText)
            )
            .build()
    }
    
    fun createForegroundInfo(
        context: Context,
        current: Int,
        total: Int,
        currentFile: String,
        operationType: OperationType = OperationType.SYNC,
        isPaused: Boolean = false
    ): ForegroundInfo {
        val notification = buildProgressNotification(context, current, total, currentFile, operationType, isPaused)
        val notificationId = getNotificationId(operationType)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }
    
    fun notifyProgress(
        context: Context,
        current: Int,
        total: Int,
        currentFile: String,
        operationType: OperationType = OperationType.SYNC,
        isPaused: Boolean = false
    ) {
        val notification = buildProgressNotification(context, current, total, currentFile, operationType, isPaused)
        val notificationId = getNotificationId(operationType)
        NotificationManagerCompat.from(context).notify(notificationId, notification)
    }
    
    fun showCompletedNotification(context: Context, totalCompleted: Int, operationType: OperationType = OperationType.SYNC) {
        val mainIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val mainPendingIntent = PendingIntent.getActivity(
            context,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        val title = when (operationType) {
            OperationType.SYNC -> "Gallery Sync Completed"
            OperationType.UPLOAD -> "Upload Completed"
            OperationType.DOWNLOAD -> "Download Completed"
        }
        val text = when (operationType) {
            OperationType.SYNC -> "Successfully synced $totalCompleted file(s)"
            OperationType.UPLOAD -> "Successfully uploaded $totalCompleted file(s)"
            OperationType.DOWNLOAD -> "Successfully downloaded $totalCompleted file(s)"
        }
        
        animateIcon(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.avd_arrow_rotate)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(mainPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        
        val notificationId = getNotificationId(operationType)
        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }
    
    fun cancelNotification(context: Context, operationType: OperationType = OperationType.SYNC) {
        val notificationId = getNotificationId(operationType)
        NotificationManagerCompat.from(context).cancel(notificationId)
    }
    
    fun cancelAllNotifications(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_SYNC)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_UPLOAD)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_DOWNLOAD)
    }
}

