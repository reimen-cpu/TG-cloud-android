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

class GallerySyncWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "GallerySyncWorker"
        const val WORK_NAME = "gallery_sync_work"
    }

    private val manager by lazy {
        (context.applicationContext as TelegramCloudApp).container.gallerySyncManager
    }

    override suspend fun doWork(): Result {
        ResourceGuard.markActive(ResourceGuard.Feature.MANUAL_SYNC)
        val config = ConfigStore(context).configFlow.firstOrNull()
        if (config == null) {
            Log.w(TAG, "Gallery sync config missing")
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
            return Result.failure()
        }

        setForeground(
            SyncNotificationManager.createForegroundInfo(
                context,
                current = 0,
                total = 0,
                currentFile = "Preparing sync...",
                operationType = SyncNotificationManager.OperationType.SYNC
            )
        )

        return try {
            manager.syncAllMedia(config) { current, total, fileName ->
                SyncNotificationManager.notifyProgress(
                    context,
                    current = current,
                    total = total,
                    currentFile = fileName,
                    operationType = SyncNotificationManager.OperationType.SYNC
                )
            }
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.SYNC)
            Result.success()
        } catch (e: CancellationException) {
            Log.d(TAG, "Gallery sync worker cancelled", e)
            manager.cancelSync()
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.SYNC)
            Result.failure()
        } catch (e: Exception) {
            Log.e(TAG, "Gallery sync worker failed", e)
            SyncNotificationManager.cancelNotification(context, SyncNotificationManager.OperationType.SYNC)
            Result.failure()
        } finally {
            ResourceGuard.markIdle(ResourceGuard.Feature.MANUAL_SYNC)
        }
    }
}



