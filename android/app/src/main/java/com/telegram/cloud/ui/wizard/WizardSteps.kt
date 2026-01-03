package com.telegram.cloud.ui.wizard

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.telegram.cloud.R
import com.telegram.cloud.ui.theme.Spacing

/**
 * Step 1: Welcome screen
 */
@Composable
fun WelcomeStep(
    onNext: () -> Unit,
    onImportBackup: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screenPaddingLarge),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(32.dp))
        
        // App icon/logo
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(120.dp)
        )
        
        Spacer(Modifier.height(24.dp))
        
        Text(
            text = stringResource(R.string.wizard_welcome_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.wizard_welcome_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        
        Spacer(Modifier.height(32.dp))
        
        // Requirements
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.screenPadding)) {
                Text(
                    text = stringResource(R.string.wizard_requirements_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(Modifier.height(12.dp))
                
                RequirementItem(
                    icon = Icons.Default.SmartToy,
                    text = stringResource(R.string.wizard_requirement_bot)
                )
                RequirementItem(
                    icon = Icons.Default.Forum,
                    text = stringResource(R.string.wizard_requirement_channel)
                )
                RequirementItem(
                    icon = Icons.Default.Wifi,
                    text = stringResource(R.string.wizard_requirement_internet)
                )
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        // What we'll do
        InfoCard(
            title = stringResource(R.string.wizard_what_well_do_title),
            description = stringResource(R.string.wizard_what_well_do_description),
            icon = Icons.Default.Checklist
        )
        
        Spacer(Modifier.weight(1f))
        
        // Next button (Primary Action)
        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = stringResource(R.string.wizard_get_started),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
        
        // Import backup button (Secondary Action)
        if (onImportBackup != null) {
            Spacer(Modifier.height(16.dp))
            
            TextButton(
                onClick = onImportBackup,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.wizard_import_backup),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        

    }
}

@Composable
private fun RequirementItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String
) {
    Row(
        modifier = Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

/**
 * Step 2: Bot Token configuration
 */
@Composable
fun BotTokenStep(
    token: String,
    validationState: TokenValidationState,
    onTokenChange: (String) -> Unit,
    onValidate: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screenPaddingLarge)
    ) {
        Text(
            text = stringResource(R.string.wizard_bot_token_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.wizard_bot_token_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(24.dp))
        
        // How to get token
        InfoCard(
            title = stringResource(R.string.wizard_how_to_get_token_title),
            description = stringResource(R.string.wizard_how_to_get_token_description),
            icon = Icons.Default.Help,
            onClick = {
                // Open BotFather
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/BotFather"))
                context.startActivity(intent)
            }
        )
        
        Spacer(Modifier.height(24.dp))
        
        // Token input
        TokenTextField(
            value = token,
            onValueChange = onTokenChange,
            label = stringResource(R.string.wizard_bot_token_label),
            validationState = validationState,
            onValidate = onValidate
        )
        
        Spacer(Modifier.height(16.dp))
        
        // Validation button
        if (token.isNotBlank() && validationState !is TokenValidationState.Valid) {
            Button(
                onClick = onValidate,
                enabled = validationState !is TokenValidationState.Validating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (validationState is TokenValidationState.Validating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wizard_validating))
                } else {
                    Icon(Icons.Default.CheckCircle, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wizard_validate_token))
                }
            }
        }
        
        Spacer(Modifier.weight(1f))
        
        // Navigation
        WizardNavButtons(
            onBack = onBack,
            onNext = onNext,
            nextEnabled = validationState is TokenValidationState.Valid
        )
    }
}

/**
 * Step 3: Channel configuration
 */
@Composable
fun ChannelStep(
    channelId: String,
    validationState: ValidationResult,
    detectionState: ChannelDetectionState,
    onChannelIdChange: (String) -> Unit,
    onDetectChannels: () -> Unit,
    onSelectChannel: (DetectedChannel) -> Unit,
    chatId: String,
    onChatIdChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screenPaddingLarge)
    ) {
        Text(
            text = stringResource(R.string.wizard_channel_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.wizard_channel_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(24.dp))
        
        // Instructions
        InfoCard(
            title = stringResource(R.string.wizard_channel_instructions_title),
            description = stringResource(R.string.wizard_channel_instructions_description),
            icon = Icons.Default.Info
        )
        
        Spacer(Modifier.height(12.dp))
        
        // Admin warning
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier.padding(Spacing.screenPadding),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.wizard_admin_required_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.wizard_admin_required_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        // Auto-detect button
        OutlinedButton(
            onClick = onDetectChannels,
            enabled = detectionState !is ChannelDetectionState.Detecting,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (detectionState is ChannelDetectionState.Detecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.Search, contentDescription = null)
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.wizard_detect_channels))
        }
        
        // Detected channels
        AnimatedVisibility(visible = detectionState is ChannelDetectionState.Success) {
            if (detectionState is ChannelDetectionState.Success) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.wizard_detected_channels),
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(Modifier.height(8.dp))
                    detectionState.channels.forEach { channel ->
                        DetectedChannelCard(
                            channel = channel,
                            onClick = { onSelectChannel(channel) }
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
        
        // Detection error
        AnimatedVisibility(visible = detectionState is ChannelDetectionState.Error) {
            if (detectionState is ChannelDetectionState.Error) {
                Text(
                    text = detectionState.message,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        // Manual input
        Text(
            text = stringResource(R.string.wizard_or_enter_manually),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(8.dp))
        
        OutlinedTextField(
            value = channelId,
            onValueChange = onChannelIdChange,
            label = { Text(stringResource(R.string.wizard_channel_id_label)) },
            placeholder = { Text("-100...") },
            leadingIcon = { Icon(Icons.Default.Forum, contentDescription = null) },
            trailingIcon = {
                AnimatedVisibility(visible = channelId.isNotBlank()) {
                    when (validationState) {
                        is ValidationResult.Valid -> Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        is ValidationResult.Invalid -> Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        else -> {}
                    }
                }
            },
            isError = validationState is ValidationResult.Invalid,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            singleLine = true
        )
        
        if (validationState is ValidationResult.Invalid) {
            Text(
                text = stringResource(validationState.messageResId),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp)
            )
        }
        
        Spacer(Modifier.height(16.dp))
        
        // Chat ID (optional)
        OutlinedTextField(
            value = chatId,
            onValueChange = onChatIdChange,
            label = { Text(stringResource(R.string.chat_id_optional)) },
            leadingIcon = { Icon(Icons.Default.Chat, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            singleLine = true
        )
        
        Spacer(Modifier.weight(1f))
        
        // Navigation
        WizardNavButtons(
            onBack = onBack,
            onNext = onNext,
            nextEnabled = validationState is ValidationResult.Valid
        )
    }
}

/**
 * Step 4: Sync Password configuration
 */
@Composable
fun SyncPasswordStep(
    syncChannelId: String,
    onSyncChannelIdChange: (String) -> Unit,
    syncBotToken: String,
    onSyncBotTokenChange: (String) -> Unit,
    syncPassword: String,
    onSyncPasswordChange: (String) -> Unit,
    passwordStrength: PasswordStrength,
    onUsePrimaryChannel: () -> Unit,
    onUsePrimaryToken: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screenPaddingLarge)
    ) {
        Text(
            text = stringResource(R.string.wizard_sync_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.wizard_sync_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(16.dp))
        
        // Optional badge
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = stringResource(R.string.wizard_optional),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        
        Spacer(Modifier.height(24.dp))
        
        // Sync Channel ID
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = syncChannelId,
                onValueChange = onSyncChannelIdChange,
                label = { Text(stringResource(R.string.sync_channel_id)) },
                placeholder = { Text("-100...") },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                singleLine = true
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onUsePrimaryChannel) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.wizard_use_same),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Text(
            text = stringResource(R.string.wizard_tap_to_copy_channel),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
        )
        
        Spacer(Modifier.height(16.dp))
        
        // Sync Bot Token
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = syncBotToken,
                onValueChange = onSyncBotTokenChange,
                label = { Text(stringResource(R.string.sync_bot_token)) },
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium,
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = onUsePrimaryToken) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = stringResource(R.string.wizard_use_same),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        
        Text(
            text = stringResource(R.string.wizard_tap_to_copy_token),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, top = 4.dp)
        )
        
        Spacer(Modifier.height(16.dp))
        
        // Sync Password
        OutlinedTextField(
            value = syncPassword,
            onValueChange = onSyncPasswordChange,
            label = { Text(stringResource(R.string.sync_password)) },
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
        )
        
        if (syncPassword.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            PasswordStrengthIndicator(strength = passwordStrength)
        }
        
        Spacer(Modifier.height(16.dp))
        
        InfoCard(
            title = stringResource(R.string.wizard_sync_tip_title),
            description = stringResource(R.string.wizard_sync_tip_description),
            icon = Icons.Default.Lightbulb
        )
        
        Spacer(Modifier.weight(1f))
        
        // Navigation
        WizardNavButtons(
            onBack = onBack,
            onNext = onNext,
            onSkip = onSkip,
            nextEnabled = true
        )
    }
}

/**
 * Step 5: Additional Tokens
 */
@Composable
fun AdditionalTokensStep(
    tokens: List<String>,
    validations: List<TokenValidationState>,
    onTokenChange: (Int, String) -> Unit,
    onValidate: (Int) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screenPaddingLarge)
    ) {
        Text(
            text = stringResource(R.string.wizard_additional_tokens_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.wizard_additional_tokens_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(16.dp))
        
        // Optional badge
        Surface(
            color = MaterialTheme.colorScheme.secondaryContainer,
            shape = MaterialTheme.shapes.small
        ) {
            Text(
                text = stringResource(R.string.wizard_optional),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
        
        Spacer(Modifier.height(24.dp))
        
        // Benefits info
        InfoCard(
            title = stringResource(R.string.wizard_tokens_benefits_title),
            description = stringResource(R.string.wizard_tokens_benefits_description),
            icon = Icons.Default.Speed
        )
        
        Spacer(Modifier.height(24.dp))
        
        // Token inputs
        tokens.forEachIndexed { index, token ->
            TokenTextField(
                value = token,
                onValueChange = { onTokenChange(index, it) },
                label = "Bot Token ${index + 2}",
                validationState = validations[index],
                onValidate = { onValidate(index) }
            )
            
            if (index < tokens.lastIndex) {
                Spacer(Modifier.height(12.dp))
            }
        }
        
        Spacer(Modifier.weight(1f))
        
        // Navigation
        WizardNavButtons(
            onBack = onBack,
            onNext = onNext,
            onSkip = onSkip,
            nextEnabled = true
        )
    }
}

/**
 * Step 6: Verification
 */
@Composable
fun VerificationStep(
    state: WizardState,
    connectionTestState: ConnectionTestState,
    onTestConnection: () -> Unit,
    onBack: () -> Unit,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screenPaddingLarge)
    ) {
        Text(
            text = stringResource(R.string.wizard_verification_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = stringResource(R.string.wizard_verification_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(24.dp))
        
        // Configuration summary
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        ) {
            Column(modifier = Modifier.padding(Spacing.screenPadding)) {
                Text(
                    text = stringResource(R.string.wizard_summary_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                
                Spacer(Modifier.height(12.dp))
                
                SummaryRow(
                    label = stringResource(R.string.wizard_summary_tokens),
                    value = "${state.getAllValidTokens().size}"
                )
                SummaryRow(
                    label = stringResource(R.string.wizard_summary_channel),
                    value = state.channelId.take(15) + "..."
                )
                if (state.isSyncConfigured()) {
                    SummaryRow(
                        label = stringResource(R.string.wizard_summary_sync),
                        value = stringResource(R.string.wizard_enabled)
                    )
                }
            }
        }
        
        Spacer(Modifier.height(24.dp))
        
        // Connection test
        Text(
            text = stringResource(R.string.wizard_connection_test_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        
        Spacer(Modifier.height(12.dp))
        
        ConnectionTestCard(
            state = connectionTestState,
            onTest = onTestConnection,
            onOpenChannel = {
                val intent = try {
                    val rawId = state.channelId.removePrefix("-100")
                    val uri = if (state.channelId.trim().startsWith("-100")) {
                        // For private channels with ID, trying to open a specific message (e.g. 1) 
                        // sometimes triggers the app better than just the channel ID
                         Uri.parse("tg://resolve?domain=c/$rawId/1")
                    } else if (state.channelId.trim().startsWith("@")) {
                         Uri.parse("tg://resolve?domain=${state.channelId.removePrefix("@")}")
                    } else {
                         Uri.parse("https://t.me/${state.channelId.removePrefix("@")}")
                    }
                    Intent(Intent.ACTION_VIEW, uri)
                } catch (e: Exception) {
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://telegram.org"))
                }
                
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to browser if app not found
                     try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/${state.channelId.removePrefix("@")}")))
                     } catch (e2: Exception) {
                        // Ignore
                     }
                }
            }
        )
        
        Spacer(Modifier.weight(1f))
        
        // Navigation
        WizardNavButtons(
            onBack = onBack,
            onNext = onComplete,
            nextEnabled = connectionTestState is ConnectionTestState.Success,
            nextLabel = stringResource(R.string.wizard_complete),
            isLoading = state.isCompleting
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}
