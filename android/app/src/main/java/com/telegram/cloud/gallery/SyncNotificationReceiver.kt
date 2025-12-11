package com.telegram.cloud.gallery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager

class SyncNotificationReceiver : BroadcastReceiver() {
    companion object {
        private const val TAG = "SyncNotificationReceiver"
        const val WORK_NAME = "gallery_sync_work"
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            SyncNotificationManager.ACTION_PAUSE -> {
                val resume = intent.getBooleanExtra(SyncNotificationManager.EXTRA_RESUME, false)
                if (resume) {
                    Log.d(TAG, "Resume sync requested")
                    // Note: Resume functionality would need to be implemented in the WorkManager
                    // For now, we'll just update the notification
                    SyncNotificationManager.notifyProgress(
                        context,
                        current = 0,
                        total = 0,
                        currentFile = "Resuming...",
                        isPaused = false
                    )
                } else {
                    Log.d(TAG, "Pause sync requested")
                    // Cancel current work to pause ongoing sync
                    WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                    SyncNotificationManager.notifyProgress(
                        context,
                        current = 0,
                        total = 0,
                        currentFile = "Paused",
                        isPaused = true
                    )
                }
            }
            SyncNotificationManager.ACTION_STOP -> {
                Log.d(TAG, "Stop sync requested")
                // Cancel all sync work
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                // Cancel notification
                SyncNotificationManager.cancelNotification(context)
            }
        }
    }
}

