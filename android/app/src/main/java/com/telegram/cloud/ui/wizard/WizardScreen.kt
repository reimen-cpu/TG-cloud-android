package com.telegram.cloud.ui.wizard

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.telegram.cloud.R
import com.telegram.cloud.data.prefs.BotConfig

/**
 * Main wizard screen that orchestrates all wizard steps
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun WizardScreen(
    viewModel: WizardViewModel,
    onComplete: (BotConfig) -> Unit,
    onCancel: (() -> Unit)? = null,
    onImportBackup: (() -> Unit)? = null,
    existingConfig: BotConfig? = null,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    
    // Initialize with existing config if editing
    LaunchedEffect(existingConfig) {
        existingConfig?.let {
            viewModel.initWithExistingConfig(it)
        }
    }
    
    Column(modifier = modifier.fillMaxSize()) {
        // Step indicator
        StepIndicator(
            currentStep = state.currentStep.index,
            totalSteps = WizardStep.TOTAL_STEPS,
            modifier = Modifier.padding(vertical = 16.dp, horizontal = 24.dp)
        )
        
        // Step counter text
        Text(
            text = stringResource(
                R.string.wizard_step_counter,
                state.currentStep.index + 1,
                WizardStep.TOTAL_STEPS
            ),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
        
        // Content with animations
        AnimatedContent(
            targetState = state.currentStep,
            transitionSpec = {
                val direction = if (targetState.index > initialState.index) {
                    // Forward
                    slideInHorizontally(
                        animationSpec = tween(300),
                        initialOffsetX = { fullWidth -> fullWidth }
                    ) + fadeIn(animationSpec = tween(300)) togetherWith
                    slideOutHorizontally(
                        animationSpec = tween(300),
                        targetOffsetX = { fullWidth -> -fullWidth }
                    ) + fadeOut(animationSpec = tween(300))
                } else {
                    // Backward
                    slideInHorizontally(
                        animationSpec = tween(300),
                        initialOffsetX = { fullWidth -> -fullWidth }
                    ) + fadeIn(animationSpec = tween(300)) togetherWith
                    slideOutHorizontally(
                        animationSpec = tween(300),
                        targetOffsetX = { fullWidth -> fullWidth }
                    ) + fadeOut(animationSpec = tween(300))
                }
                direction.using(SizeTransform(clip = false))
            },
            modifier = Modifier.weight(1f),
            label = "WizardContent"
        ) { step ->
            when (step) {
                WizardStep.WELCOME -> {
                    WelcomeStep(
                        onNext = { viewModel.nextStep() },
                        onImportBackup = onImportBackup,
                        currentLanguage = state.currentLanguage,
                        onLanguageSelected = { viewModel.setLanguage(it) }
                    )
                }
                
                WizardStep.BOT_TOKEN -> {
                    BotTokenStep(
                        token = state.primaryToken,
                        validationState = state.primaryTokenValidation,
                        onTokenChange = { viewModel.updatePrimaryToken(it) },
                        onValidate = { viewModel.validatePrimaryToken() },
                        onBack = { viewModel.previousStep() },
                        onNext = { viewModel.nextStep() }
                    )
                }
                
                WizardStep.CHANNEL -> {
                    ChannelStep(
                        channelId = state.channelId,
                        validationState = state.channelValidation,
                        detectionState = state.channelDetectionState,
                        onChannelIdChange = { viewModel.updateChannelId(it) },
                        onDetectChannels = { viewModel.detectChannels() },
                        onSelectChannel = { viewModel.selectDetectedChannel(it) },
                        chatId = state.chatId,
                        onChatIdChange = { viewModel.updateChatId(it) },
                        onBack = { viewModel.previousStep() },
                        onNext = { viewModel.nextStep() }
                    )
                }
                
                WizardStep.SYNC_PASSWORD -> {
                    SyncPasswordStep(
                        syncChannelId = state.syncChannelId,
                        onSyncChannelIdChange = { viewModel.updateSyncChannelId(it) },
                        syncBotToken = state.syncBotToken,
                        onSyncBotTokenChange = { viewModel.updateSyncBotToken(it) },
                        syncPassword = state.syncPassword,
                        onSyncPasswordChange = { viewModel.updateSyncPassword(it) },
                        passwordStrength = state.syncPasswordStrength,
                        onUsePrimaryChannel = { viewModel.usePrimaryChannelForSync() },
                        onUsePrimaryToken = { viewModel.usePrimaryTokenForSync() },
                        onBack = { viewModel.previousStep() },
                        onNext = { viewModel.nextStep() },
                        onSkip = { viewModel.skipStep() }
                    )
                }
                
                WizardStep.ADDITIONAL_TOKENS -> {
                    AdditionalTokensStep(
                        tokens = state.additionalTokens,
                        validations = state.additionalTokenValidations,
                        onTokenChange = { index, token -> viewModel.updateAdditionalToken(index, token) },
                        onValidate = { viewModel.validateAdditionalToken(it) },
                        onBack = { viewModel.previousStep() },
                        onNext = { viewModel.nextStep() },
                        onSkip = { viewModel.skipStep() }
                    )
                }
                
                WizardStep.VERIFICATION -> {
                    VerificationStep(
                        state = state,
                        connectionTestState = state.connectionTestState,
                        onTestConnection = { viewModel.testConnection() },
                        onBack = { viewModel.previousStep() },
                        onComplete = { viewModel.completeWizard(onComplete) }
                    )
                }
            }
        }
    }
}
