package com.telegram.cloud.data.sync

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.prefs.ConfigStore
import com.telegram.cloud.data.remote.TelegramBotClient
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background worker for periodic database synchronization.
 * Uses WorkManager for reliable background execution.
 */
class SyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "database_sync_work"
        private const val DEFAULT_INTERVAL_MINUTES = 15L
        private const val MIN_INTERVAL_MINUTES = 15L  // WorkManager minimum
        
        /**
         * Schedules periodic sync work.
         * 
         * @param context Application context
         * @param intervalMinutes Sync interval (minimum 15 minutes)
         */
        fun schedulePeriodicSync(context: Context, intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES) {
            val interval = maxOf(intervalMinutes, MIN_INTERVAL_MINUTES)
            
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val syncRequest = PeriodicWorkRequestBuilder<SyncWorker>(
                interval, TimeUnit.MINUTES
            )
                .setConstraints(constraints)
                .addTag(WORK_NAME)
                .build()
            
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    syncRequest
                )
            
            Log.i(TAG, "Scheduled periodic sync every $interval minutes")
        }
        
        /**
         * Cancels the periodic sync work.
         */
        fun cancelSync(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            Log.i(TAG, "Cancelled periodic sync")
        }
        
        /**
         * Triggers an immediate one-time sync.
         */
        fun triggerImmediateSync(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            
            val syncRequest = androidx.work.OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(constraints)
                .addTag("${WORK_NAME}_immediate")
                .build()
            
            WorkManager.getInstance(context).enqueue(syncRequest)
            Log.i(TAG, "Triggered immediate sync")
        }
    }
    
    override suspend fun doWork(): Result {
        Log.i(TAG, "Starting sync work")
        
        return try {
            // Load sync configuration
            val configStore = ConfigStore(applicationContext)
            val botConfig = configStore.configFlow.first()
            
            if (botConfig == null) {
                Log.w(TAG, "No bot configuration, skipping sync")
                return Result.success()
            }
            
            // Check if sync is configured
            if (botConfig.syncChannelId.isNullOrBlank() || 
                botConfig.syncBotToken.isNullOrBlank() || 
                botConfig.syncPassword.isNullOrBlank()) {
                Log.d(TAG, "Sync not configured, skipping")
                return Result.success()
            }
            
            // Get or create device ID
            val database = CloudDatabase.getDatabase(applicationContext)
            val syncMetadataDao = database.syncMetadataDao()
            
            var deviceId = syncMetadataDao.getDeviceId()
            if (deviceId == null) {
                deviceId = SyncConfig.generateDeviceId()
                syncMetadataDao.setValue(
                    SyncMetadataEntity(
                        key = SyncMetadataEntity.KEY_DEVICE_ID,
                        value = deviceId
                    )
                )
            }
            
            // Create sync configuration
            val syncConfig = SyncConfig(
                syncChannelId = botConfig.syncChannelId!!,
                syncBotToken = botConfig.syncBotToken!!,
                syncPassword = botConfig.syncPassword!!,
                deviceId = deviceId
            )
            
            // Create sync components
            val telegramClient = TelegramBotClient()
            val syncLogDao = database.syncLogDao()
            val syncLogManager = SyncLogManager(syncLogDao, syncMetadataDao, deviceId)
            val conflictResolver = ConflictResolver()
            
            val syncEngine = SyncEngine(
                context = applicationContext,
                telegramClient = telegramClient,
                syncLogManager = syncLogManager,
                conflictResolver = conflictResolver,
                syncConfig = syncConfig,
                database = database
            )
            
            // Perform sync
            val syncResult = syncEngine.performSync()
            
            Log.i(TAG, "Sync completed: uploaded=${syncResult.uploadedCount}, " +
                       "downloaded=${syncResult.downloadedCount}, applied=${syncResult.appliedCount}")
            
            if (syncResult.hasErrors) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Sync work failed", e)
            Result.retry()
        }
    }
}
