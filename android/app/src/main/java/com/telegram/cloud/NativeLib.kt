package com.telegram.cloud

import android.util.Log

object NativeLib {
    private const val TAG = "NativeLib"
    
    init {
        try {
            // Load SQLCipher native library first
            System.loadLibrary("sqlcipher")
            Log.i(TAG, "SQLCipher library loaded successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load SQLCipher library", t)
        }
        
        try {
            System.loadLibrary("telegramcloud_core")
            Log.i(TAG, "TelegramCloud core library loaded successfully")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load TelegramCloud core library", t)
            t.printStackTrace()
        }
    }

    external fun nativeInit(): Boolean
    external fun nativeOpenDatabase(path: String, passphrase: String?): Boolean
    external fun nativeCloseDatabase(): Boolean
    external fun nativeExportBackup(path: String): Boolean
    external fun nativeImportBackup(path: String): Boolean
    external fun nativeImportEncryptedBackup(path: String, password: String): Boolean
    external fun nativeStartDownload(url: String, destPath: String): Int
    external fun nativeStopDownload(downloadId: Int): Boolean
    external fun nativeGetDownloadStatus(downloadId: Int): String
    external fun nativeStartUpload(filePath: String, target: String): Int
}
