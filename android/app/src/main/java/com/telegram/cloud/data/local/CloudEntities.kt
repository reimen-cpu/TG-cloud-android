package com.telegram.cloud.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cloud_files")
data class CloudFileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "telegram_message_id") val telegramMessageId: Long,
    @ColumnInfo(name = "file_id") val fileId: String,
    @ColumnInfo(name = "file_unique_id") val fileUniqueId: String?, // For chunked files: comma-separated telegram file IDs
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "mime_type") val mimeType: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "uploaded_at") val uploadedAt: Long,
    @ColumnInfo(name = "caption") val caption: String?,
    @ColumnInfo(name = "share_link") val shareLink: String?,
    @ColumnInfo(name = "checksum") val checksum: String?,
    @ColumnInfo(name = "uploader_tokens", defaultValue = "") val uploaderTokens: String? = null // For chunked files: comma-separated bot tokens per chunk
)

@Entity(tableName = "upload_tasks")
data class UploadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "uri") val uri: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "status") val status: UploadStatus,
    @ColumnInfo(name = "progress") val progress: Int,
    @ColumnInfo(name = "error") val error: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    // Progress tracking for resumable uploads
    @ColumnInfo(name = "file_id") val fileId: String? = null,
    @ColumnInfo(name = "completed_chunks_json") val completedChunksJson: String? = null,
    @ColumnInfo(name = "token_offset") val tokenOffset: Int = 0
)

@Entity(tableName = "download_tasks")
data class DownloadTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "file_id") val fileId: String,
    @ColumnInfo(name = "target_path") val targetPath: String,
    @ColumnInfo(name = "status") val status: DownloadStatus,
    @ColumnInfo(name = "progress") val progress: Int,
    @ColumnInfo(name = "error") val error: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    // Progress tracking for resumable downloads
    @ColumnInfo(name = "completed_chunks_json") val completedChunksJson: String? = null,
    @ColumnInfo(name = "chunk_file_ids") val chunkFileIds: String? = null,
    @ColumnInfo(name = "temp_chunk_dir") val tempChunkDir: String? = null,
    @ColumnInfo(name = "total_chunks") val totalChunks: Int = 0
)

enum class UploadStatus { QUEUED, RUNNING, PAUSED, FAILED, COMPLETED }
enum class DownloadStatus { QUEUED, RUNNING, FAILED, COMPLETED }

@Dao
interface CloudFileDao {
    @Query("SELECT * FROM cloud_files ORDER BY uploaded_at DESC")
    fun observeFiles(): Flow<List<CloudFileEntity>>
    
    @Query("SELECT * FROM cloud_files ORDER BY uploaded_at DESC")
    suspend fun getAllFiles(): List<CloudFileEntity>

    @Query("SELECT * FROM cloud_files WHERE id = :id")
    suspend fun getById(id: Long): CloudFileEntity?
    
    @Query("SELECT * FROM cloud_files WHERE telegram_message_id = :messageId LIMIT 1")
    suspend fun getByTelegramMessageId(messageId: Long): CloudFileEntity?
    
    @Query("SELECT * FROM cloud_files WHERE file_id = :fileId LIMIT 1")
    suspend fun getByFileId(fileId: String): CloudFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: CloudFileEntity)

    @Query("DELETE FROM cloud_files WHERE id = :id")
    suspend fun deleteById(id: Long)
    
    @Query("DELETE FROM cloud_files WHERE telegram_message_id = :messageId")
    suspend fun deleteByTelegramMessageId(messageId: Long)

    @Query("DELETE FROM cloud_files")
    suspend fun clear()
}

@Dao
interface UploadTaskDao {
    @Query("SELECT * FROM upload_tasks ORDER BY created_at DESC")
    fun observeUploads(): Flow<List<UploadTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: UploadTaskEntity): Long

    @Query("SELECT * FROM upload_tasks WHERE id = :id")
    suspend fun getById(id: Long): UploadTaskEntity?

    @Update
    suspend fun update(task: UploadTaskEntity)
    
    @Query("DELETE FROM upload_tasks")
    suspend fun clear()
    
    @Query("SELECT * FROM upload_tasks WHERE (status = 'RUNNING' OR status = 'FAILED') AND file_id IS NOT NULL")
    suspend fun getIncompleteUploads(): List<UploadTaskEntity>
}

@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_tasks ORDER BY created_at DESC")
    fun observeDownloads(): Flow<List<DownloadTaskEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: DownloadTaskEntity): Long

    @Query("SELECT * FROM download_tasks WHERE id = :id")
    suspend fun getById(id: Long): DownloadTaskEntity?

    @Update
    suspend fun update(task: DownloadTaskEntity)
    
    @Query("DELETE FROM download_tasks")
    suspend fun clear()
    
    @Query("SELECT * FROM download_tasks WHERE (status = 'RUNNING' OR status = 'FAILED') AND total_chunks > 0")
    suspend fun getIncompleteDownloads(): List<DownloadTaskEntity>
}

class StatusConverters {
    @androidx.room.TypeConverter
    fun fromUploadStatus(status: UploadStatus?): String? = status?.name

    @androidx.room.TypeConverter
    fun toUploadStatus(value: String?): UploadStatus? = value?.let { UploadStatus.valueOf(it) }

    @androidx.room.TypeConverter
    fun fromDownloadStatus(status: DownloadStatus?): String? = status?.name

    @androidx.room.TypeConverter
    fun toDownloadStatus(value: String?): DownloadStatus? = value?.let { DownloadStatus.valueOf(it) }
}

@Database(
    entities = [CloudFileEntity::class, UploadTaskEntity::class, DownloadTaskEntity::class, com.telegram.cloud.gallery.GalleryMediaEntity::class],
    version = 5,
    exportSchema = false
)
@androidx.room.TypeConverters(StatusConverters::class)
abstract class CloudDatabase : androidx.room.RoomDatabase() {
    abstract fun cloudFileDao(): CloudFileDao
    abstract fun uploadTaskDao(): UploadTaskDao
    abstract fun downloadTaskDao(): DownloadTaskDao
    abstract fun galleryMediaDao(): com.telegram.cloud.gallery.GalleryMediaDao
    
    fun invalidateAllTables() {
        invalidationTracker.refreshVersionsAsync()
    }
    
    fun closeAndInvalidate() {
        // Close all connections so external writes are visible
        close()
    }
    
    companion object {
        @Volatile
        private var INSTANCE: CloudDatabase? = null
        
        fun getDatabase(context: Context): CloudDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    CloudDatabase::class.java,
                    "cloud.db"
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5
                ).addCallback(object : androidx.room.RoomDatabase.Callback() {
                    override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onCreate(db)
                        android.util.Log.i("CloudDatabase", "onCreate: Creating all tables from scratch")
                        // Room will create all tables automatically, but we log it for debugging
                    }
                    
                    override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Verify gallery_media table exists
                        val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='gallery_media'")
                        val exists = cursor.moveToFirst()
                        cursor.close()
                        if (!exists) {
                            android.util.Log.e("CloudDatabase", "onOpen: gallery_media table missing! Creating it...")
                            // Create gallery_media table if missing (shouldn't happen, but safety check)
                            db.execSQL("""
                                CREATE TABLE IF NOT EXISTS gallery_media (
                                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                    local_path TEXT NOT NULL,
                                    filename TEXT NOT NULL,
                                    mime_type TEXT NOT NULL,
                                    size_bytes INTEGER NOT NULL,
                                    date_taken INTEGER NOT NULL,
                                    date_modified INTEGER NOT NULL,
                                    width INTEGER NOT NULL DEFAULT 0,
                                    height INTEGER NOT NULL DEFAULT 0,
                                    duration_ms INTEGER NOT NULL DEFAULT 0,
                                    thumbnail_path TEXT,
                                    is_synced INTEGER NOT NULL DEFAULT 0,
                                    telegram_file_id TEXT,
                                    telegram_message_id INTEGER,
                                    telegram_file_unique_id TEXT,
                                    telegram_uploader_tokens TEXT,
                                    sync_error TEXT,
                                    last_sync_attempt INTEGER NOT NULL DEFAULT 0
                                )
                            """.trimIndent())
                            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_gallery_media_local_path ON gallery_media (local_path)")
                        }
                    }
                }).build()
                INSTANCE = instance
                instance
            }
        }
        
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE cloud_files ADD COLUMN uploader_tokens TEXT DEFAULT ''")
            }
        }
        
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS gallery_media (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        local_path TEXT NOT NULL,
                        filename TEXT NOT NULL,
                        mime_type TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        date_taken INTEGER NOT NULL,
                        date_modified INTEGER NOT NULL,
                        width INTEGER NOT NULL DEFAULT 0,
                        height INTEGER NOT NULL DEFAULT 0,
                        duration_ms INTEGER NOT NULL DEFAULT 0,
                        thumbnail_path TEXT,
                        is_synced INTEGER NOT NULL DEFAULT 0,
                        telegram_file_id TEXT,
                        telegram_message_id INTEGER,
                        sync_error TEXT,
                        last_sync_attempt INTEGER NOT NULL DEFAULT 0
                    )
                """)
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_gallery_media_local_path ON gallery_media (local_path)")
            }
        }
        
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add columns for chunked upload support in gallery_media
                db.execSQL("ALTER TABLE gallery_media ADD COLUMN telegram_file_unique_id TEXT")
                db.execSQL("ALTER TABLE gallery_media ADD COLUMN telegram_uploader_tokens TEXT")
            }
        }
        
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                // Add progress tracking fields for resumable uploads
                db.execSQL("ALTER TABLE upload_tasks ADD COLUMN file_id TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE upload_tasks ADD COLUMN completed_chunks_json TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE upload_tasks ADD COLUMN token_offset INTEGER NOT NULL DEFAULT 0")
                
                // Add progress tracking fields for resumable downloads
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN completed_chunks_json TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN chunk_file_ids TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN temp_chunk_dir TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN total_chunks INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

