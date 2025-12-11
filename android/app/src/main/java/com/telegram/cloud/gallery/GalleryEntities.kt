package com.telegram.cloud.gallery

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/**
 * Represents a local media file (photo/video) from the device
 */
@Entity(tableName = "gallery_media", indices = [Index(value = ["local_path"], unique = true)])
data class GalleryMediaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "local_path") val localPath: String,
    @ColumnInfo(name = "filename") val filename: String,
    @ColumnInfo(name = "mime_type") val mimeType: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    @ColumnInfo(name = "width") val width: Int = 0,
    @ColumnInfo(name = "height") val height: Int = 0,
    @ColumnInfo(name = "duration_ms") val durationMs: Long = 0, // For videos
    @ColumnInfo(name = "thumbnail_path") val thumbnailPath: String? = null,
    
    // Telegram sync status
    @ColumnInfo(name = "is_synced") val isSynced: Boolean = false,
    @ColumnInfo(name = "telegram_file_id") val telegramFileId: String? = null, // For direct: file_id, for chunked: UUID
    @ColumnInfo(name = "telegram_message_id") val telegramMessageId: Int? = null,
    @ColumnInfo(name = "telegram_file_unique_id") val telegramFileUniqueId: String? = null, // For chunked: comma-separated file_ids
    @ColumnInfo(name = "telegram_uploader_tokens") val telegramUploaderTokens: String? = null, // Token(s) used for upload
    @ColumnInfo(name = "sync_error") val syncError: String? = null,
    @ColumnInfo(name = "last_sync_attempt") val lastSyncAttempt: Long = 0
) {
    val isVideo: Boolean get() = mimeType.startsWith("video/")
    val isImage: Boolean get() = mimeType.startsWith("image/")
    val isChunked: Boolean get() = telegramFileUniqueId?.contains(",") == true
}

@Dao
interface GalleryMediaDao {
    @Query("SELECT * FROM gallery_media ORDER BY date_taken DESC")
    suspend fun getAll(): List<GalleryMediaEntity>
    
    // Observable Flow for real-time updates
    @Query("SELECT * FROM gallery_media ORDER BY date_taken DESC")
    fun observeAll(): Flow<List<GalleryMediaEntity>>

    @Query("SELECT * FROM gallery_media ORDER BY date_taken DESC LIMIT :pageSize OFFSET :offset")
    fun observePaged(pageSize: Int, offset: Int): Flow<List<GalleryMediaEntity>>
    
    @Query("SELECT * FROM gallery_media ORDER BY date_taken DESC LIMIT :pageSize OFFSET :offset")
    suspend fun getPaged(pageSize: Int, offset: Int): List<GalleryMediaEntity>
    
    @Query("SELECT COUNT(*) FROM gallery_media")
    fun getTotalCount(): Flow<Int>
    
    @Query("SELECT * FROM gallery_media WHERE is_synced = 0 ORDER BY date_taken DESC")
    suspend fun getUnsynced(): List<GalleryMediaEntity>
    
    @Query("SELECT * FROM gallery_media WHERE is_synced = 1 ORDER BY date_taken DESC")
    suspend fun getSynced(): List<GalleryMediaEntity>
    
    @Query("SELECT * FROM gallery_media WHERE local_path = :path LIMIT 1")
    suspend fun getByPath(path: String): GalleryMediaEntity?
    
    @Query("SELECT * FROM gallery_media WHERE id = :id")
    suspend fun getById(id: Long): GalleryMediaEntity?
    
    @Query("SELECT * FROM gallery_media WHERE filename = :filename AND size_bytes = :size LIMIT 1")
    suspend fun getByNameAndSize(filename: String, size: Long): GalleryMediaEntity?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(media: GalleryMediaEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(media: List<GalleryMediaEntity>)
    
    @Update
    suspend fun update(media: GalleryMediaEntity)
    
    @Query("UPDATE gallery_media SET is_synced = 1, telegram_file_id = :fileId, telegram_message_id = :messageId, sync_error = NULL WHERE id = :id")
    suspend fun markSynced(id: Long, fileId: String, messageId: Int)
    
    @Query("""
        UPDATE gallery_media 
        SET is_synced = 1, telegram_file_id = :fileId, telegram_message_id = :messageId, sync_error = NULL 
        WHERE filename = :filename AND ABS(size_bytes - :sizeBytes) <= :tolerance
    """)
    suspend fun markSyncedByNameAndSize(filename: String, sizeBytes: Long, tolerance: Long, fileId: String, messageId: Int): Int
    
    @Query("UPDATE gallery_media SET is_synced = 1, telegram_file_id = :fileId, telegram_message_id = :messageId, telegram_file_unique_id = :telegramFileIds, telegram_uploader_tokens = :uploaderTokens, sync_error = NULL WHERE id = :id")
    suspend fun markSyncedChunked(id: Long, fileId: String, messageId: Int, telegramFileIds: String, uploaderTokens: String)
    
    @Query("""
        UPDATE gallery_media 
        SET is_synced = 1, telegram_file_id = :fileId, telegram_message_id = :messageId, telegram_file_unique_id = :telegramFileIds, telegram_uploader_tokens = :uploaderTokens, sync_error = NULL 
        WHERE filename = :filename AND ABS(size_bytes - :sizeBytes) <= :tolerance
    """)
    suspend fun markSyncedChunkedByNameAndSize(
        filename: String,
        sizeBytes: Long,
        tolerance: Long,
        fileId: String,
        messageId: Int,
        telegramFileIds: String,
        uploaderTokens: String
    ): Int
    
    @Query("UPDATE gallery_media SET telegram_uploader_tokens = :token WHERE id = :id")
    suspend fun updateUploaderToken(id: Long, token: String)
    
    @Query("""
        UPDATE gallery_media 
        SET telegram_uploader_tokens = :token 
        WHERE filename = :filename AND ABS(size_bytes - :sizeBytes) <= :tolerance
    """)
    suspend fun updateUploaderTokenByName(filename: String, sizeBytes: Long, tolerance: Long, token: String): Int
    
    @Query("UPDATE gallery_media SET sync_error = :error, last_sync_attempt = :timestamp WHERE id = :id")
    suspend fun markSyncError(id: Long, error: String, timestamp: Long)
    
    @Query("UPDATE gallery_media SET thumbnail_path = :thumbPath WHERE id = :id")
    suspend fun updateThumbnail(id: Long, thumbPath: String)
    
    @Query("UPDATE gallery_media SET local_path = :localPath WHERE id = :id")
    suspend fun updateLocalPath(id: Long, localPath: String)
    
    @Delete
    suspend fun delete(media: GalleryMediaEntity)
    
    @Query("DELETE FROM gallery_media")
    suspend fun clear()
    
    @Query("SELECT COUNT(*) FROM gallery_media")
    suspend fun count(): Int
    
    @Query("SELECT COUNT(*) FROM gallery_media WHERE is_synced = 1")
    suspend fun countSynced(): Int
}

