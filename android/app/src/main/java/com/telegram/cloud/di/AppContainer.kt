package com.telegram.cloud.di

import android.content.Context
import androidx.room.Room
import com.telegram.cloud.data.backup.BackupManager
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.prefs.configStore
import com.telegram.cloud.data.remote.TelegramBotClient
import com.telegram.cloud.data.repository.TelegramRepository
import com.telegram.cloud.gallery.GallerySyncManager
import com.telegram.cloud.gallery.MediaScanner
import com.telegram.cloud.tasks.TaskQueueManager

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    private val configStore = appContext.configStore()

    val database: CloudDatabase = Room.databaseBuilder(
        appContext,
        CloudDatabase::class.java,
        "cloud.db"
    ).addMigrations(
        CloudDatabase.MIGRATION_1_2, 
        CloudDatabase.MIGRATION_2_3, 
        CloudDatabase.MIGRATION_3_4,
        CloudDatabase.MIGRATION_4_5
    )
        // Don't use fallbackToDestructiveMigration() to preserve data between updates
        // If a migration is missing, the app will crash - this is intentional to catch issues early
        .addCallback(object : androidx.room.RoomDatabase.Callback() {
            override fun onOpen(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                super.onOpen(db)
                // Verify gallery_media table exists
                val cursor = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name='gallery_media'")
                val exists = cursor.moveToFirst()
                cursor.close()
                if (!exists) {
                    android.util.Log.e("AppContainer", "onOpen: gallery_media table missing! Creating it...")
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
        })
        .build()

    private val botClient = TelegramBotClient()

    val repository = TelegramRepository(
        context = appContext,
        configStore = configStore,
        database = database,
        botClient = botClient
    )

    val backupManager = BackupManager(appContext, configStore, database, repository)
    
    // Gallery components
    val mediaScanner = MediaScanner(appContext)
    val gallerySyncManager = GallerySyncManager(
        context = appContext,
        database = database,
        galleryDao = database.galleryMediaDao(),
        repository = repository
    )
    
    // Task queue manager
    val taskQueueManager = TaskQueueManager(appContext)
}

