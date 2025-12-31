package com.telegram.cloud.data.sync

import android.content.Context
import java.util.UUID

/**
 * Configuration for database synchronization via Telegram.
 * 
 * @param syncChannelId The Telegram channel ID where encrypted logs are stored
 * @param syncBotToken The bot token used for sync operations
 * @param syncPassword The password used for encrypting logs (source of truth)
 * @param deviceId Unique identifier for this device (auto-generated)
 * @param isEnabled Whether sync is currently enabled
 */
data class SyncConfig(
    val syncChannelId: String,
    val syncBotToken: String,
    val syncPassword: String,
    val deviceId: String = generateDeviceId(),
    val isEnabled: Boolean = true
) {
    companion object {
        private const val PREFS_NAME = "sync_config_prefs"
        private const val KEY_DEVICE_ID = "device_id"
        
        /**
         * Generates a unique device identifier.
         * This ID is used to distinguish logs from different devices.
         */
        fun generateDeviceId(): String = UUID.randomUUID().toString()
        
        /**
         * Gets or creates a persistent device ID.
         * The ID is stored in SharedPreferences to remain consistent across app restarts.
         */
        fun getDeviceId(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existingId = prefs.getString(KEY_DEVICE_ID, null)
            if (existingId != null) {
                return existingId
            }
            // Generate and persist new device ID
            val newId = generateDeviceId()
            prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
            return newId
        }
        
        /**
         * Validates if the sync configuration is complete and valid.
         */
        fun isValid(config: SyncConfig?): Boolean {
            if (config == null) return false
            return config.syncChannelId.isNotBlank() &&
                   config.syncBotToken.isNotBlank() &&
                   config.syncPassword.isNotBlank() &&
                   config.syncChannelId.startsWith("-")
        }
    }
    
    /**
     * Checks if this configuration is valid for sync operations.
     */
    fun isValid(): Boolean = Companion.isValid(this)
}
