package com.telegram.cloud.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "telegram_cloud")

data class BotConfig(
    val tokens: List<String>,
    val channelId: String,
    val chatId: String?,
    // Database sync configuration (optional)
    val syncChannelId: String? = null,
    val syncBotToken: String? = null,
    val syncPassword: String? = null
)

class ConfigStore(private val context: Context) {
    private val primaryTokenKey = stringPreferencesKey("bot_token_primary")
    private val channelIdKey = stringPreferencesKey("channel_id")
    private val chatIdKey = stringPreferencesKey("chat_id")
    private val tokenCountKey = intPreferencesKey("token_count")
    // Sync configuration keys
    private val syncChannelIdKey = stringPreferencesKey("sync_channel_id")
    private val syncBotTokenKey = stringPreferencesKey("sync_bot_token")
    private val syncPasswordKey = stringPreferencesKey("sync_password")

    val configFlow: Flow<BotConfig?> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val count = prefs[tokenCountKey] ?: 0
            val tokens = mutableListOf<String>()
            for (i in 0 until count) {
                prefs[stringPreferencesKey("bot_token_$i")]?.let { tokens.add(it) }
            }
            val channel = prefs[channelIdKey]
            if (tokens.isEmpty() || channel.isNullOrBlank()) {
                null
            } else {
                BotConfig(
                    tokens = tokens,
                    channelId = channel,
                    chatId = prefs[chatIdKey],
                    syncChannelId = prefs[syncChannelIdKey],
                    syncBotToken = prefs[syncBotTokenKey],
                    syncPassword = prefs[syncPasswordKey]
                )
            }
        }

    suspend fun save(config: BotConfig) {
        context.dataStore.edit { prefs ->
            prefs[tokenCountKey] = config.tokens.size
            config.tokens.forEachIndexed { index, token ->
                prefs[stringPreferencesKey("bot_token_$index")] = token
            }
            prefs[primaryTokenKey] = config.tokens.first()
            prefs[channelIdKey] = config.channelId
            if (config.chatId.isNullOrBlank()) {
                prefs.remove(chatIdKey)
            } else {
                prefs[chatIdKey] = config.chatId
            }
            // Save sync configuration
            if (config.syncChannelId.isNullOrBlank()) {
                prefs.remove(syncChannelIdKey)
            } else {
                prefs[syncChannelIdKey] = config.syncChannelId
            }
            if (config.syncBotToken.isNullOrBlank()) {
                prefs.remove(syncBotTokenKey)
            } else {
                prefs[syncBotTokenKey] = config.syncBotToken
            }
            if (config.syncPassword.isNullOrBlank()) {
                prefs.remove(syncPasswordKey)
            } else {
                prefs[syncPasswordKey] = config.syncPassword
            }
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

fun Context.configStore(): ConfigStore = ConfigStore(this)


