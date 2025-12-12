package com.telegram.cloud.gallery

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.telegram.cloud.TelegramCloudApp
import com.telegram.cloud.data.prefs.ConfigStore
import com.telegram.cloud.utils.ResourceGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull

class GalleryRestoreWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GalleryRestoreWorker"
        const val WORK_NAME = "gallery_restore_work"
    }

    private val manager by lazy {
        (context.applicationContext as TelegramCloudApp).container.galleryRestoreManager
    }

    override suspend fun doWork(): Result {
        ResourceGuard.markActive(ResourceGuard.Feature.MANUAL_SYNC)
        val config = ConfigStore(context).configFlow.firstOrNull()
        if (config == null) {
            Log.w(TAG, "Gallery restore config missing")
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            return Result.failure()
        }

        setForeground(
            SyncNotificationManager.createForegroundInfo(
                context,
                current = 0,
                total = 0,
                currentFile = "Preparing restore...",
                operationType = SyncNotificationManager.OperationType.RESTORE
            )
        )

        return try {
            manager.restoreAllSynced(config) { current, total, fileName ->
                SyncNotificationManager.notifyProgress(
                    context,
                    current = current,
                    total = total,
                    currentFile = fileName,
                    operationType = SyncNotificationManager.OperationType.RESTORE
                )
            }
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.RESTORE)
            Result.success()
        } catch (e: CancellationException) {
            Log.d(TAG, "Gallery restore worker cancelled", e)
            manager.cancelRestore()
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.RESTORE)
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Gallery restore worker failed", e)
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.RESTORE)
            Result.failure()
        } finally {
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
        }
    }
}
