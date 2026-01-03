package com.telegram.cloud.ui.wizard

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.telegram.cloud.R
import com.telegram.cloud.data.prefs.BotConfig
import com.telegram.cloud.data.remote.TelegramBotClient
import com.telegram.cloud.data.repository.TelegramRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers


class WizardViewModel(
    private val context: Context,
    private val repository: TelegramRepository
) : ViewModel() {
    
    private val _state = MutableStateFlow(WizardState(
        currentLanguage = com.telegram.cloud.util.LanguageManager.getCurrentLanguage()
    ))
    val state: StateFlow<WizardState> = _state.asStateFlow()
    
    private val telegramClient = TelegramBotClient()
    
    companion object {
        private const val TAG = "WizardViewModel"
    }

    // Language
    
    fun setLanguage(code: String) {
        com.telegram.cloud.util.LanguageManager.setLanguage(code)
        _state.update { it.copy(currentLanguage = code) }
    }
    
    // Navigation
    
    fun nextStep() {
        val current = _state.value.currentStep
        val nextIndex = current.index + 1
        if (nextIndex < WizardStep.TOTAL_STEPS) {
            _state.update { it.copy(currentStep = WizardStep.fromIndex(nextIndex)) }
            updateCanProceed()
        }
    }
    
    fun previousStep() {
        val current = _state.value.currentStep
        val prevIndex = current.index - 1
        if (prevIndex >= 0) {
            _state.update { it.copy(currentStep = WizardStep.fromIndex(prevIndex)) }
            updateCanProceed()
        }
    }
    
    fun skipStep() {
        if (_state.value.currentStep.isOptional) {
            nextStep()
        }
    }
    
    fun goToStep(step: WizardStep) {
        _state.update { it.copy(currentStep = step) }
        updateCanProceed()
    }
    
    // Primary Token
    
    fun updatePrimaryToken(token: String) {
        _state.update { 
            it.copy(
                primaryToken = token,
                primaryTokenValidation = TokenValidationState.Idle
            )
        }
        updateCanProceed()
    }
    
    fun validatePrimaryToken() {
        val token = _state.value.primaryToken
        if (token.isBlank() || token.length < 10) {
            _state.update { 
                it.copy(primaryTokenValidation = TokenValidationState.Invalid("Token too short"))
            }
            return
        }
        
        _state.update { it.copy(primaryTokenValidation = TokenValidationState.Validating) }
        
        viewModelScope.launch {
            try {
                // Call getMe API directly
                val url = "https://api.telegram.org/bot$token/getMe"
                val request = okhttp3.Request.Builder().url(url).get().build()
                
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = org.json.JSONObject(body)
                            if (json.optBoolean("ok")) {
                                val result = json.getJSONObject("result")
                                val botName = result.getString("first_name")
                                val botUsername = result.optString("username", "")
                                _state.update { 
                                    it.copy(
                                        primaryTokenValidation = TokenValidationState.Valid(
                                            botName = botName,
                                            botUsername = botUsername
                                        )
                                    )
                                }
                                Log.i(TAG, "Token validated: @$botUsername")
                            } else {
                                _state.update { 
                                    it.copy(primaryTokenValidation = TokenValidationState.Invalid("Invalid token"))
                                }
                            }
                        } else {
                            _state.update { 
                                it.copy(primaryTokenValidation = TokenValidationState.Invalid("Invalid token"))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Token validation error", e)
                _state.update { 
                    it.copy(primaryTokenValidation = TokenValidationState.Invalid(e.message ?: "Error"))
                }
            }
            updateCanProceed()
        }
    }
    
    // Channel
    
    fun updateChannelId(channelId: String) {
        _state.update { 
            it.copy(
                channelId = channelId,
                channelValidation = validateChannelIdFormat(channelId)
            )
        }
        updateCanProceed()
    }
    
    private fun validateChannelIdFormat(channelId: String): ValidationResult {
        if (channelId.isBlank()) return ValidationResult.Empty
        
        // Channel IDs should start with -100 for supergroups/channels
        if (!channelId.startsWith("-")) {
            return ValidationResult.Invalid(R.string.wizard_channel_must_start_dash)
        }
        
        // Try to parse as Long
        channelId.toLongOrNull() ?: return ValidationResult.Invalid(R.string.wizard_channel_invalid_format)
        
        return ValidationResult.Valid
    }
    
    fun updateChatId(chatId: String) {
        _state.update { it.copy(chatId = chatId) }
    }
    
    fun detectChannels() {
        val token = _state.value.primaryToken
        if (token.isBlank()) return
        
        _state.update { it.copy(channelDetectionState = ChannelDetectionState.Detecting) }
        
        viewModelScope.launch {
            try {
                // Use getUpdates to try to find channels where bot is admin
                val url = "https://api.telegram.org/bot$token/getUpdates"
                val formBody = okhttp3.FormBody.Builder()
                    .add("allowed_updates", "[\"channel_post\",\"message\"]")
                    .add("limit", "100")
                    .build()
                
                val request = okhttp3.Request.Builder().url(url).post(formBody).build()
                
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (!response.isSuccessful || body == null) {
                            _state.update { 
                                it.copy(channelDetectionState = ChannelDetectionState.Error("Failed to fetch updates"))
                            }
                            return@withContext
                        }
                        
                        val json = org.json.JSONObject(body)
                        if (!json.optBoolean("ok")) {
                            _state.update { 
                                it.copy(channelDetectionState = ChannelDetectionState.Error(json.optString("description", "Error")))
                            }
                            return@withContext
                        }
                        
                        val results = json.optJSONArray("result")
                        val detectedChannels = mutableListOf<DetectedChannel>()
                        val seenIds = mutableSetOf<String>()
                        
                        if (results != null) {
                            for (i in 0 until results.length()) {
                                val update = results.getJSONObject(i)
                                val message = update.optJSONObject("channel_post") ?: update.optJSONObject("message")
                                
                                if (message != null) {
                                    val chat = message.optJSONObject("chat")
                                    if (chat != null) {
                                        val chatId = chat.optLong("id").toString()
                                        val chatTitle = chat.optString("title", "")
                                        val chatType = chat.optString("type", "")
                                        
                                        if ((chatType == "channel" || chatType == "supergroup") && !seenIds.contains(chatId)) {
                                            seenIds.add(chatId)
                                            detectedChannels.add(DetectedChannel(
                                                id = chatId,
                                                title = chatTitle.ifEmpty { "Channel $chatId" }
                                            ))
                                        }
                                    }
                                }
                            }
                        }
                        
                        _state.update { 
                            it.copy(
                                channelDetectionState = if (detectedChannels.isNotEmpty()) {
                                    ChannelDetectionState.Success(detectedChannels)
                                } else {
                                    ChannelDetectionState.Error(context.getString(R.string.wizard_no_channels_found))
                                }
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Channel detection error", e)
                _state.update { 
                    it.copy(channelDetectionState = ChannelDetectionState.Error(e.message ?: "Error"))
                }
            }
        }
    }
    
    fun selectDetectedChannel(channel: DetectedChannel) {
        _state.update { 
            it.copy(
                channelId = channel.id,
                channelValidation = ValidationResult.Valid,
                channelDetectionState = ChannelDetectionState.Idle
            )
        }
        updateCanProceed()
    }
    
    // Sync Configuration
    
    fun updateSyncChannelId(channelId: String) {
        _state.update { it.copy(syncChannelId = channelId) }
    }
    
    fun updateSyncBotToken(token: String) {
        _state.update { it.copy(syncBotToken = token) }
    }
    
    fun updateSyncPassword(password: String) {
        _state.update { 
            it.copy(
                syncPassword = password,
                syncPasswordStrength = calculatePasswordStrength(password)
            )
        }
        updateCanProceed()
    }
    
    /**
     * Copy primary token to sync token for convenience
     */
    fun usePrimaryTokenForSync() {
        _state.update { 
            it.copy(syncBotToken = it.primaryToken)
        }
    }
    
    /**
     * Copy primary channel to sync channel for convenience
     */
    fun usePrimaryChannelForSync() {
        _state.update { 
            it.copy(syncChannelId = it.channelId)
        }
    }
    
    private fun calculatePasswordStrength(password: String): PasswordStrength {
        if (password.isEmpty()) return PasswordStrength.WEAK
        
        var score = 0
        
        // Length checks
        if (password.length >= 8) score++
        if (password.length >= 12) score++
        if (password.length >= 16) score++
        
        // Character variety
        if (password.any { it.isUpperCase() }) score++
        if (password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++
        
        return when {
            score >= 6 -> PasswordStrength.VERY_STRONG
            score >= 4 -> PasswordStrength.STRONG
            score >= 2 -> PasswordStrength.MEDIUM
            else -> PasswordStrength.WEAK
        }
    }
    
    // Additional Tokens
    
    fun updateAdditionalToken(index: Int, token: String) {
        if (index !in 0..3) return
        
        _state.update { state ->
            val newTokens = state.additionalTokens.toMutableList().apply {
                this[index] = token
            }
            val newValidations = state.additionalTokenValidations.toMutableList().apply {
                this[index] = TokenValidationState.Idle
            }
            state.copy(
                additionalTokens = newTokens,
                additionalTokenValidations = newValidations
            )
        }
    }
    
    fun validateAdditionalToken(index: Int) {
        if (index !in 0..3) return
        
        val token = _state.value.additionalTokens[index]
        if (token.isBlank()) return
        if (token.length < 10) {
            _state.update { state ->
                val newValidations = state.additionalTokenValidations.toMutableList().apply {
                    this[index] = TokenValidationState.Invalid("Token too short")
                }
                state.copy(additionalTokenValidations = newValidations)
            }
            return
        }
        
        _state.update { state ->
            val newValidations = state.additionalTokenValidations.toMutableList().apply {
                this[index] = TokenValidationState.Validating
            }
            state.copy(additionalTokenValidations = newValidations)
        }
        
        viewModelScope.launch {
            try {
                val url = "https://api.telegram.org/bot$token/getMe"
                val request = okhttp3.Request.Builder().url(url).get().build()
                
                val validation = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = org.json.JSONObject(body)
                            if (json.optBoolean("ok")) {
                                val result = json.getJSONObject("result")
                                val botName = result.getString("first_name")
                                val botUsername = result.optString("username", "")
                                TokenValidationState.Valid(botName, botUsername)
                            } else {
                                TokenValidationState.Invalid("Invalid token")
                            }
                        } else {
                            TokenValidationState.Invalid("Invalid token")
                        }
                    }
                }
                
                _state.update { state ->
                    val newValidations = state.additionalTokenValidations.toMutableList().apply {
                        this[index] = validation
                    }
                    state.copy(additionalTokenValidations = newValidations)
                }
            } catch (e: Exception) {
                _state.update { state ->
                    val newValidations = state.additionalTokenValidations.toMutableList().apply {
                        this[index] = TokenValidationState.Invalid(e.message ?: "Error")
                    }
                    state.copy(additionalTokenValidations = newValidations)
                }
            }
        }
    }
    
    // Verification
    
    fun testConnection() {
        viewModelScope.launch {
            runConnectionTest()
        }
    }

    private suspend fun runConnectionTest(): Boolean {
        _state.update { it.copy(connectionTestState = ConnectionTestState.Testing) }
        
        try {
            val state = _state.value
            val token = state.primaryToken
            val channelId = state.channelId
            
            // Test 1: Check if bot is admin of the channel
            try {
                val botId = token.substringBefore(":")
                val url = "https://api.telegram.org/bot$token/getChatMember"
                val formBody = okhttp3.FormBody.Builder()
                    .add("chat_id", channelId)
                    .add("user_id", botId)
                    .build()
                
                val request = okhttp3.Request.Builder().url(url).post(formBody).build()
                
                val isAdmin = withContext(Dispatchers.IO) {
                    okhttp3.OkHttpClient().newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = org.json.JSONObject(body)
                            if (json.optBoolean("ok")) {
                                val result = json.getJSONObject("result")
                                val status = result.optString("status", "")
                                status == "administrator" || status == "creator"
                            } else {
                                false
                            }
                        } else {
                            false
                        }
                    }
                }
                
                if (!isAdmin) {
                    _state.update { 
                        it.copy(connectionTestState = ConnectionTestState.Failed(
                            context.getString(R.string.wizard_bot_not_admin),
                            isAdminError = true
                        ))
                    }
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Admin check failed", e)
                _state.update { 
                    it.copy(connectionTestState = ConnectionTestState.Failed(
                        context.getString(R.string.wizard_bot_not_admin),
                        isAdminError = true
                    ))
                }
                return false
            }
            
            // Test 2: Try to send a test message (and delete it)
            try {
                val testMessage = telegramClient.sendTextMessage(
                    token = token,
                    channelId = channelId,
                    text = "🔧 Telegram Cloud - Connection Test"
                )
                
                // Delete the test message
                if (testMessage != null) {
                    try {
                        telegramClient.deleteMessage(token, channelId, testMessage.messageId)
                    } catch (e: Exception) {
                        // Ignore deletion error
                    }
                }
                
                if (testMessage != null) {
                    _state.update { 
                        it.copy(connectionTestState = ConnectionTestState.Success(
                            context.getString(R.string.wizard_connection_success)
                        ))
                    }
                    return true
                } else {
                    _state.update { 
                        it.copy(connectionTestState = ConnectionTestState.Failed(
                            context.getString(R.string.wizard_bot_no_permission)
                        ))
                    }
                    return false
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection test failed", e)
                _state.update { 
                    it.copy(connectionTestState = ConnectionTestState.Failed(
                        e.message ?: context.getString(R.string.wizard_channel_access_error)
                    ))
                }
                return false
            }
        } catch (e: Exception) {
            _state.update { 
                it.copy(connectionTestState = ConnectionTestState.Failed(e.message ?: "Unknown error"))
            }
            return false
        }
    }

    // Completion
    
    fun completeWizard(onComplete: (BotConfig) -> Unit) {
        if (_state.value.isCompleting) return
        
        _state.update { it.copy(isCompleting = true) }
        
        viewModelScope.launch {
            // Force validation again to ensure permissions haven't changed or token isn't invalid
            val isSuccess = runConnectionTest()
            
            if (isSuccess) {
                val state = _state.value
                val config = BotConfig(
                    tokens = state.getAllValidTokens(),
                    channelId = state.channelId,
                    chatId = state.chatId.ifBlank { null },
                    syncChannelId = state.syncChannelId.ifBlank { null },
                    syncBotToken = state.syncBotToken.ifBlank { null },
                    syncPassword = state.syncPassword.ifBlank { null }
                )
                
                repository.saveConfig(config)
                
                _state.update { it.copy(isCompleting = false) }
                onComplete(config)
            } else {
                _state.update { it.copy(isCompleting = false) }
            }
        }
    }
    
    // Helpers
    
    private fun updateCanProceed() {
        val state = _state.value
        
        val canProceed = when (state.currentStep) {
            WizardStep.WELCOME -> true
            WizardStep.BOT_TOKEN -> state.primaryTokenValidation is TokenValidationState.Valid
            WizardStep.CHANNEL -> state.channelValidation is ValidationResult.Valid
            WizardStep.SYNC_PASSWORD -> true // Optional step
            WizardStep.ADDITIONAL_TOKENS -> true // Optional step
            WizardStep.VERIFICATION -> state.connectionTestState is ConnectionTestState.Success
        }
        
        _state.update { it.copy(canProceed = canProceed) }
    }
    
    /**
     * Initialize wizard with existing config for editing
     */
    fun initWithExistingConfig(config: BotConfig) {
        _state.update { state ->
            state.copy(
                primaryToken = config.tokens.firstOrNull() ?: "",
                channelId = config.channelId,
                chatId = config.chatId ?: "",
                syncChannelId = config.syncChannelId ?: "",
                syncBotToken = config.syncBotToken ?: "",
                syncPassword = config.syncPassword ?: "",
                additionalTokens = listOf(
                    config.tokens.getOrNull(1) ?: "",
                    config.tokens.getOrNull(2) ?: "",
                    config.tokens.getOrNull(3) ?: "",
                    config.tokens.getOrNull(4) ?: ""
                )
            )
        }
    }
}

class WizardViewModelFactory(
    private val context: Context,
    private val repository: TelegramRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(WizardViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return WizardViewModel(context, repository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
