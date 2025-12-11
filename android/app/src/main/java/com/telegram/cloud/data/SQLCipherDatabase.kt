package com.telegram.cloud.data

import android.content.Context
import android.util.Log
import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * SQLCipher database manager for Android.
 * Uses the same encryption parameters as the desktop version for compatibility.
 */
class SQLCipherDatabase(private val context: Context) {
    
    companion object {
        private const val TAG = "SQLCipherDatabase"
        private const val DB_NAME = "telegram_cloud.db"
        
        // SQLCipher 4.x default parameters (must match desktop version)
        private const val CIPHER_PAGE_SIZE = 4096
        private const val KDF_ITER = 256000
    }
    
    private var database: SQLiteDatabase? = null
    
    /**
     * Opens or creates an encrypted database.
     * @param password The encryption password
     * @return true if successful
     */
    fun open(password: String): Boolean {
        return try {
            Log.i(TAG, "Opening database with SQLCipher...")
            
            val dbFile = context.getDatabasePath(DB_NAME)
            dbFile.parentFile?.mkdirs()
            
            Log.i(TAG, "Database path: ${dbFile.absolutePath}")
            Log.i(TAG, "Database exists: ${dbFile.exists()}")
            
            // Open with SQLCipher 4.x compatible settings
            database = SQLiteDatabase.openOrCreateDatabase(
                dbFile,
                password.toByteArray(Charsets.UTF_8),
                null,  // CursorFactory
                null,  // DatabaseErrorHandler
                object : net.zetetic.database.sqlcipher.SQLiteDatabaseHook {
                    override fun preKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection) {
                        Log.d(TAG, "preKey hook called")
                    }
                    
                    override fun postKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection) {
                        Log.d(TAG, "postKey hook called - configuring cipher parameters")
                        // Configure SQLCipher parameters to match desktop version
                        connection.execute("PRAGMA cipher_page_size = $CIPHER_PAGE_SIZE;", emptyArray(), null)
                        connection.execute("PRAGMA kdf_iter = $KDF_ITER;", emptyArray(), null)
                        connection.execute("PRAGMA cipher_hmac_algorithm = HMAC_SHA1;", emptyArray(), null)
                        connection.execute("PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA1;", emptyArray(), null)
                        Log.d(TAG, "Cipher parameters configured")
                    }
                }
            )
            
            // Verify database is accessible
            database?.rawQuery("SELECT count(*) FROM sqlite_master;", null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val count = cursor.getInt(0)
                    Log.i(TAG, "Database opened successfully. Tables count: $count")
                }
            }
            
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open database", e)
            false
        }
    }
    
    /**
     * Opens an existing encrypted database file (for backup import).
     * @param dbFile The database file to open
     * @param password The encryption password
     * @return true if successful
     */
    fun openExisting(dbFile: File, password: String): Boolean {
        return try {
            Log.i(TAG, "Opening existing database: ${dbFile.absolutePath}")
            
            if (!dbFile.exists()) {
                Log.e(TAG, "Database file does not exist")
                return false
            }
            
            database = SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                password.toByteArray(Charsets.UTF_8),
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
                object : net.zetetic.database.sqlcipher.SQLiteDatabaseHook {
                    override fun preKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection) {}
                    
                    override fun postKey(connection: net.zetetic.database.sqlcipher.SQLiteConnection) {
                        // Use same parameters as desktop for compatibility
                        connection.execute("PRAGMA cipher_page_size = $CIPHER_PAGE_SIZE;", emptyArray(), null)
                        connection.execute("PRAGMA kdf_iter = $KDF_ITER;", emptyArray(), null)
                        connection.execute("PRAGMA cipher_hmac_algorithm = HMAC_SHA1;", emptyArray(), null)
                        connection.execute("PRAGMA cipher_kdf_algorithm = PBKDF2_HMAC_SHA1;", emptyArray(), null)
                    }
                }
            )
            
            // Verify
            database?.rawQuery("SELECT count(*) FROM sqlite_master;", null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    Log.i(TAG, "Existing database opened successfully")
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open existing database", e)
            false
        }
    }
    
    /**
     * Reads configuration from the database.
     */
    fun readConfig(): Map<String, String> {
        val config = mutableMapOf<String, String>()
        
        try {
            database?.rawQuery("SELECT key, value FROM config;", null)?.use { cursor ->
                while (cursor.moveToNext()) {
                    val key = cursor.getString(0)
                    val value = cursor.getString(1)
                    config[key] = value
                    Log.d(TAG, "Config: $key = ${value.take(20)}...")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read config", e)
        }
        
        return config
    }
    
    /**
     * Gets all files from the database.
     */
    fun getFiles(): List<FileRecord> {
        val files = mutableListOf<FileRecord>()
        
        try {
            database?.rawQuery(
                "SELECT file_id, file_name, file_size, mime_type, category, upload_date, message_id, telegram_file_id FROM files;",
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    files.add(
                        FileRecord(
                            fileId = cursor.getString(0),
                            fileName = cursor.getString(1),
                            fileSize = cursor.getLong(2),
                            mimeType = cursor.getString(3) ?: "",
                            category = cursor.getString(4) ?: "",
                            uploadDate = cursor.getString(5) ?: "",
                            messageId = cursor.getLong(6),
                            telegramFileId = cursor.getString(7) ?: ""
                        )
                    )
                }
            }
            Log.i(TAG, "Loaded ${files.size} files from database")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get files", e)
        }
        
        return files
    }
    
    fun close() {
        try {
            database?.close()
            database = null
            Log.i(TAG, "Database closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing database", e)
        }
    }
    
    fun isOpen(): Boolean = database?.isOpen == true
}

data class FileRecord(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val category: String,
    val uploadDate: String,
    val messageId: Long,
    val telegramFileId: String
)


