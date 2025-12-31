package com.telegram.cloud.data.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Represents a single database operation that needs to be synchronized.
 * Each log entry captures a INSERT, UPDATE, or DELETE operation on a table.
 */
@Entity(
    tableName = "sync_logs",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["is_uploaded"]),
        Index(value = ["device_id"])
    ]
)
data class SyncLogEntity(
    @PrimaryKey 
    @ColumnInfo(name = "log_id")
    val logId: String,  // UUID
    
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,  // Unix timestamp in milliseconds
    
    @ColumnInfo(name = "device_id")
    val deviceId: String,  // Unique device identifier
    
    @ColumnInfo(name = "operation")
    val operation: SyncOperation,  // INSERT, UPDATE, DELETE
    
    @ColumnInfo(name = "table_name")
    val tableName: String,  // Name of the affected table
    
    @ColumnInfo(name = "primary_key")
    val primaryKey: String,  // Primary key of the affected row
    
    @ColumnInfo(name = "data_json")
    val dataJson: String?,  // New data as JSON (null for DELETE)
    
    @ColumnInfo(name = "previous_data_json")
    val previousDataJson: String?,  // Previous data as JSON (for UPDATE/DELETE)
    
    @ColumnInfo(name = "is_uploaded")
    val isUploaded: Boolean = false,  // Whether this log has been uploaded to Telegram
    
    @ColumnInfo(name = "telegram_message_id")
    val telegramMessageId: Long? = null,  // Message ID if uploaded
    
    @ColumnInfo(name = "checksum")
    val checksum: String? = null  // SHA-256 checksum of the log data
)

/**
 * Types of database operations that can be synchronized.
 */
enum class SyncOperation {
    INSERT,
    UPDATE,
    DELETE;
    
    companion object {
        fun fromString(value: String): SyncOperation {
            return when (value.uppercase()) {
                "INSERT" -> INSERT
                "UPDATE" -> UPDATE
                "DELETE" -> DELETE
                else -> throw IllegalArgumentException("Unknown operation: $value")
            }
        }
    }
}

/**
 * Type converters for Room database.
 */
class SyncOperationConverter {
    @androidx.room.TypeConverter
    fun fromSyncOperation(operation: SyncOperation?): String? = operation?.name
    
    @androidx.room.TypeConverter
    fun toSyncOperation(value: String?): SyncOperation? = value?.let { SyncOperation.fromString(it) }
}
