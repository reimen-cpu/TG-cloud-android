package com.telegram.cloud.data.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Data Access Object for sync metadata.
 */
@Dao
interface SyncMetadataDao {
    
    /**
     * Gets a metadata value by key.
     */
    @Query("SELECT value FROM sync_metadata WHERE key = :key")
    suspend fun getValue(key: String): String?
    
    /**
     * Gets the full metadata entity by key.
     */
    @Query("SELECT * FROM sync_metadata WHERE key = :key")
    suspend fun getByKey(key: String): SyncMetadataEntity?
    
    /**
     * Sets a metadata value (insert or replace).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setValue(metadata: SyncMetadataEntity)
    
    /**
     * Gets the device ID for this device.
     */
    @Query("SELECT value FROM sync_metadata WHERE key = 'device_id'")
    suspend fun getDeviceId(): String?
    
    /**
     * Gets the last applied log ID.
     */
    @Query("SELECT value FROM sync_metadata WHERE key = 'last_applied_log_id'")
    suspend fun getLastAppliedLogId(): String?
    
    /**
     * Gets the last download message ID from Telegram.
     */
    @Query("SELECT value FROM sync_metadata WHERE key = 'last_download_message_id'")
    suspend fun getLastDownloadMessageId(): String?
    
    /**
     * Gets the last sync timestamp.
     */
    @Query("SELECT value FROM sync_metadata WHERE key = 'last_sync_timestamp'")
    suspend fun getLastSyncTimestamp(): String?
    
    /**
     * Deletes a metadata entry.
     */
    @Query("DELETE FROM sync_metadata WHERE key = :key")
    suspend fun delete(key: String)
    
    /**
     * Clears all metadata.
     */
    @Query("DELETE FROM sync_metadata")
    suspend fun clear()
    
    /**
     * Gets all metadata entries.
     */
    @Query("SELECT * FROM sync_metadata")
    suspend fun getAll(): List<SyncMetadataEntity>
}
