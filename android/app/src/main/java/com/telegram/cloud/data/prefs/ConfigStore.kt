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
    val chatId: String?
)

class ConfigStore(private val context: Context) {
    private val primaryTokenKey = stringPreferencesKey("bot_token_primary")
    private val channelIdKey = stringPreferencesKey("channel_id")
    private val chatIdKey = stringPreferencesKey("chat_id")
    private val tokenCountKey = intPreferencesKey("token_count")

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
                    chatId = prefs[chatIdKey]
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
        }
    }

    suspend fun clear() {
        context.dataStore.edit { it.clear() }
    }
}

fun Context.configStore(): ConfigStore = ConfigStore(this)


