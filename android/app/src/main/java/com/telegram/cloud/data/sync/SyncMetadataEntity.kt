package com.telegram.cloud.data.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores sync metadata like last applied log ID, device ID, etc.
 * Uses a key-value structure for flexibility.
 */
@Entity(tableName = "sync_metadata")
data class SyncMetadataEntity(
    @PrimaryKey
    @ColumnInfo(name = "key")
    val key: String,
    
    @ColumnInfo(name = "value")
    val value: String,
    
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        // Metadata keys
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_APPLIED_LOG_ID = "last_applied_log_id"
        const val KEY_LAST_SYNC_TIMESTAMP = "last_sync_timestamp"
        const val KEY_LAST_UPLOAD_TIMESTAMP = "last_upload_timestamp"
        const val KEY_LAST_DOWNLOAD_MESSAGE_ID = "last_download_message_id"
        const val KEY_LAST_PINNED_MESSAGE_ID = "last_pinned_message_id"
        const val KEY_PROCESSED_CHUNKS = "processed_chunks"
        const val KEY_SYNC_ENABLED = "sync_enabled"
    }
}
