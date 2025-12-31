package com.telegram.cloud.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for sync log operations.
 */
@Dao
interface SyncLogDao {
    
    /**
     * Gets all logs that haven't been uploaded yet, ordered by timestamp.
     */
    @Query("SELECT * FROM sync_logs WHERE is_uploaded = 0 ORDER BY timestamp ASC")
    suspend fun getPendingLogs(): List<SyncLogEntity>
    
    /**
     * Gets the count of pending (not uploaded) logs.
     */
    @Query("SELECT COUNT(*) FROM sync_logs WHERE is_uploaded = 0")
    suspend fun getPendingLogCount(): Int
    
    /**
     * Gets recent logs for display/debugging.
     */
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentLogs(limit: Int = 50): List<SyncLogEntity>
    
    /**
     * Gets all logs from a specific device.
     */
    @Query("SELECT * FROM sync_logs WHERE device_id = :deviceId ORDER BY timestamp DESC")
    suspend fun getLogsByDevice(deviceId: String): List<SyncLogEntity>
    
    /**
     * Gets logs newer than a specific timestamp from other devices.
     * Used for determining what logs need to be downloaded.
     */
    @Query("SELECT * FROM sync_logs WHERE device_id != :localDeviceId AND timestamp > :afterTimestamp ORDER BY timestamp ASC")
    suspend fun getRemoteLogsAfter(localDeviceId: String, afterTimestamp: Long): List<SyncLogEntity>
    
    /**
     * Inserts a new sync log.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: SyncLogEntity)
    
    /**
     * Inserts multiple sync logs.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(logs: List<SyncLogEntity>)
    
    /**
     * Updates an existing log.
     */
    @Update
    suspend fun update(log: SyncLogEntity)
    
    /**
     * Marks a log as uploaded with the Telegram message ID.
     */
    @Query("UPDATE sync_logs SET is_uploaded = 1, telegram_message_id = :messageId WHERE log_id = :logId")
    suspend fun markUploaded(logId: String, messageId: Long)
    
    /**
     * Gets all logs that have been uploaded (for sync index).
     */
    @Query("SELECT * FROM sync_logs WHERE is_uploaded = 1 ORDER BY timestamp ASC")
    suspend fun getUploadedLogs(): List<SyncLogEntity>
    
    /**
     * Gets the latest timestamp of remote logs (from other devices).
     */
    @Query("SELECT MAX(timestamp) FROM sync_logs WHERE device_id != :localDeviceId")
    suspend fun getLatestRemoteTimestamp(localDeviceId: String): Long?
    
    /**
     * Gets a log by its ID.
     */
    @Query("SELECT * FROM sync_logs WHERE log_id = :logId")
    suspend fun getById(logId: String): SyncLogEntity?
    
    /**
     * Checks if a log with the given ID already exists.
     */
    @Query("SELECT EXISTS(SELECT 1 FROM sync_logs WHERE log_id = :logId)")
    suspend fun exists(logId: String): Boolean
    
    /**
     * Gets logs affecting a specific table and primary key.
     * Used for conflict detection.
     */
    @Query("SELECT * FROM sync_logs WHERE table_name = :tableName AND primary_key = :primaryKey ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getLogsForRecord(tableName: String, primaryKey: String, limit: Int = 10): List<SyncLogEntity>
    
    /**
     * Observes all sync logs (for UI display).
     */
    @Query("SELECT * FROM sync_logs ORDER BY timestamp DESC")
    fun observeAllLogs(): Flow<List<SyncLogEntity>>
    
    /**
     * Observes pending upload count.
     */
    @Query("SELECT COUNT(*) FROM sync_logs WHERE is_uploaded = 0")
    fun observePendingCount(): Flow<Int>
    
    /**
     * Deletes old uploaded logs to save space.
     * Keeps logs from the last N days.
     */
    @Query("DELETE FROM sync_logs WHERE is_uploaded = 1 AND timestamp < :beforeTimestamp")
    suspend fun deleteOldLogs(beforeTimestamp: Long): Int
    
    /**
     * Clears all sync logs.
     */
    @Query("DELETE FROM sync_logs")
    suspend fun clear()
}
