package com.telegram.cloud.ui.wizard

/**
 * Wizard step enumeration representing each screen in the setup wizard
 */
enum class WizardStep(val index: Int, val isOptional: Boolean = false) {
    WELCOME(0),
    BOT_TOKEN(1),
    CHANNEL(2),
    SYNC_PASSWORD(3),
    ADDITIONAL_TOKENS(4, isOptional = true),
    VERIFICATION(5);
    
    companion object {
        val TOTAL_STEPS = entries.size
        
        fun fromIndex(index: Int): WizardStep = entries.find { it.index == index } ?: WELCOME
    }
}

/**
 * Password strength levels
 */
enum class PasswordStrength {
    WEAK,
    MEDIUM,
    STRONG,
    VERY_STRONG
}

/**
 * Validation result for fields
 */
sealed class ValidationResult {
    object Valid : ValidationResult()
    object Empty : ValidationResult()
    data class Invalid(val messageResId: Int) : ValidationResult()
    object Validating : ValidationResult()
}

/**
 * Channel detection state
 */
sealed class ChannelDetectionState {
    object Idle : ChannelDetectionState()
    object Detecting : ChannelDetectionState()
    data class Success(val channels: List<DetectedChannel>) : ChannelDetectionState()
    data class Error(val message: String) : ChannelDetectionState()
}

/**
 * Detected channel information
 */
data class DetectedChannel(
    val id: String,
    val title: String,
    val memberCount: Int? = null
)

/**
 * Token validation state
 */
sealed class TokenValidationState {
    object Idle : TokenValidationState()
    object Validating : TokenValidationState()
    data class Valid(val botName: String, val botUsername: String) : TokenValidationState()
    data class Invalid(val error: String) : TokenValidationState()
}

/**
 * Connection test state
 */
sealed class ConnectionTestState {
    object Idle : ConnectionTestState()
    object Testing : ConnectionTestState()
    data class Success(val message: String) : ConnectionTestState()
    data class Failed(val error: String, val isAdminError: Boolean = false) : ConnectionTestState()
}

/**
 * Main wizard state
 */
data class WizardState(
    val currentStep: WizardStep = WizardStep.WELCOME,
    
    // Bot Token
    val primaryToken: String = "",
    val primaryTokenValidation: TokenValidationState = TokenValidationState.Idle,
    
    // Channel
    val channelId: String = "",
    val channelValidation: ValidationResult = ValidationResult.Empty,
    val channelDetectionState: ChannelDetectionState = ChannelDetectionState.Idle,
    
    // Chat ID (optional)
    val chatId: String = "",
    
    // Sync Configuration
    val syncChannelId: String = "",
    val syncBotToken: String = "",
    val syncPassword: String = "",
    val syncPasswordStrength: PasswordStrength = PasswordStrength.WEAK,
    
    // Additional Tokens
    val additionalTokens: List<String> = listOf("", "", "", ""),
    val additionalTokenValidations: List<TokenValidationState> = listOf(
        TokenValidationState.Idle,
        TokenValidationState.Idle,
        TokenValidationState.Idle,
        TokenValidationState.Idle
    ),
    
    // Verification
    val connectionTestState: ConnectionTestState = ConnectionTestState.Idle,
    
    // Navigation
    val canProceed: Boolean = false,
    val isCompleting: Boolean = false
) {
    /**
     * Get all valid tokens (primary + additional)
     */
    fun getAllValidTokens(): List<String> {
        val tokens = mutableListOf<String>()
        if (primaryToken.isNotBlank()) {
            tokens.add(primaryToken)
        }
        additionalTokens.forEach { token ->
            if (token.isNotBlank() && token.length > 10) {
                tokens.add(token)
            }
        }
        return tokens
    }
    
    /**
     * Check if primary configuration is complete
     */
    fun isPrimaryConfigComplete(): Boolean {
        return primaryToken.isNotBlank() && 
               primaryTokenValidation is TokenValidationState.Valid &&
               channelId.isNotBlank() &&
               channelValidation is ValidationResult.Valid
    }
    
    /**
     * Check if sync configuration is enabled
     */
    fun isSyncConfigured(): Boolean {
        return syncChannelId.isNotBlank() && 
               syncBotToken.isNotBlank() && 
               syncPassword.isNotBlank()
    }
}
