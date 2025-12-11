package com.telegram.cloud.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Manages progress notifications for uploads, downloads, and sync operations
 */
class ProgressNotificationManager(private val context: Context) {
    
    companion object {
        private const val TAG = "ProgressNotification"
        private const val CHANNEL_ID_UPLOAD = "upload_channel"
        private const val CHANNEL_ID_DOWNLOAD = "download_channel"
        private const val NOTIFICATION_ID_UPLOAD = 3001
        private const val NOTIFICATION_ID_DOWNLOAD = 3002
    }
    
    private val notificationManager = NotificationManagerCompat.from(context)
    
    init {
        createNotificationChannels()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            
            val uploadChannel = NotificationChannel(
                CHANNEL_ID_UPLOAD,
                "File Uploads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows file upload progress"
                setShowBadge(false)
            }
            
            val downloadChannel = NotificationChannel(
                CHANNEL_ID_DOWNLOAD,
                "File Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows file download progress"
                setShowBadge(false)
            }
            
            manager.createNotificationChannel(uploadChannel)
            manager.createNotificationChannel(downloadChannel)
        }
    }
    
    fun showUploadProgress(fileName: String, progress: Int, isChunked: Boolean = false) {
        try {
            val title = if (isChunked) "Uploading (chunked)" else "Uploading"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_UPLOAD)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle(title)
                .setContentText(fileName)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID_UPLOAD, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission for upload")
        }
    }
    
    fun showUploadComplete(fileName: String, success: Boolean) {
        try {
            val (icon, title) = if (success) {
                android.R.drawable.stat_sys_upload_done to "Upload Complete"
            } else {
                android.R.drawable.stat_notify_error to "Upload Failed"
            }
            
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_UPLOAD)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(fileName)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID_UPLOAD, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission")
        }
    }
    
    fun showDownloadProgress(fileName: String, progress: Int, isChunked: Boolean = false) {
        try {
            val title = if (isChunked) "Downloading (chunked)" else "Downloading"
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOAD)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(fileName)
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID_DOWNLOAD, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission for download")
        }
    }
    
    fun showDownloadComplete(fileName: String, success: Boolean) {
        try {
            val (icon, title) = if (success) {
                android.R.drawable.stat_sys_download_done to "Download Complete"
            } else {
                android.R.drawable.stat_notify_error to "Download Failed"
            }
            
            val notification = NotificationCompat.Builder(context, CHANNEL_ID_DOWNLOAD)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(fileName)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            
            notificationManager.notify(NOTIFICATION_ID_DOWNLOAD, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission")
        }
    }
    
    fun cancelUploadNotification() {
        notificationManager.cancel(NOTIFICATION_ID_UPLOAD)
    }
    
    fun cancelDownloadNotification() {
        notificationManager.cancel(NOTIFICATION_ID_DOWNLOAD)
    }
    
    fun cancelAll() {
        notificationManager.cancel(NOTIFICATION_ID_UPLOAD)
        notificationManager.cancel(NOTIFICATION_ID_DOWNLOAD)
    }
}


