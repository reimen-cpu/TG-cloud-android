package com.telegram.cloud.ui.wizard

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.telegram.cloud.R
import com.telegram.cloud.ui.theme.ComponentSize
import com.telegram.cloud.ui.theme.Spacing

/**
 * Step indicator showing progress through wizard
 */
@Composable
fun StepIndicator(
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until totalSteps) {
            val isCompleted = i < currentStep
            val isCurrent = i == currentStep
            
            // Step dot
            Box(
                modifier = Modifier
                    .size(if (isCurrent) 12.dp else 10.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isCompleted -> MaterialTheme.colorScheme.primary
                            isCurrent -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                    .then(
                        if (isCurrent) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f), CircleShape)
                        } else Modifier
                    )
            )
            
            // Connector line (except after last step)
            if (i < totalSteps - 1) {
                Box(
                    modifier = Modifier
                        .width(24.dp)
                        .height(2.dp)
                        .background(
                            if (isCompleted) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                )
            }
        }
    }
}

/**
 * Text field with validation feedback
 */
@Composable
fun ValidatedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    validationState: ValidationResult = ValidationResult.Empty,
    isPassword: Boolean = false,
    placeholder: String? = null,
    leadingIcon: ImageVector? = null,
    onValidate: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    var showPassword by remember { mutableStateOf(false) }
    
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            placeholder = placeholder?.let { { Text(it) } },
            leadingIcon = leadingIcon?.let { 
                { Icon(it, contentDescription = null) }
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Password visibility toggle
                    if (isPassword && value.isNotEmpty()) {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                    
                    // Validation indicator
                    AnimatedVisibility(
                        visible = value.isNotBlank(),
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut()
                    ) {
                        when (validationState) {
                            is ValidationResult.Valid -> Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(ComponentSize.iconSmall)
                            )
                            is ValidationResult.Invalid -> Icon(
                                Icons.Default.Error,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(ComponentSize.iconSmall)
                            )
                            is ValidationResult.Validating -> CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                            else -> {}
                        }
                    }
                    
                    // Validate button
                    if (onValidate != null && value.isNotBlank() && validationState !is ValidationResult.Valid) {
                        IconButton(onClick = onValidate) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = "Validate",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            visualTransformation = if (isPassword && !showPassword) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            isError = validationState is ValidationResult.Invalid,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = when (validationState) {
                    is ValidationResult.Valid -> MaterialTheme.colorScheme.primary
                    is ValidationResult.Invalid -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            shape = MaterialTheme.shapes.medium,
            singleLine = true
        )
        
        // Error message
        AnimatedVisibility(visible = validationState is ValidationResult.Invalid) {
            if (validationState is ValidationResult.Invalid) {
                Text(
                    text = stringResource(validationState.messageResId),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                )
            }
        }
    }
}

/**
 * Token input field with validation
 */
@Composable
fun TokenTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    validationState: TokenValidationState,
    onValidate: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var showToken by remember { mutableStateOf(false) }
    
    Column(modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Toggle visibility
                    if (value.isNotEmpty()) {
                        IconButton(onClick = { showToken = !showToken }) {
                            Icon(
                                if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = null
                            )
                        }
                    }
                    
                    // Validation indicator
                    when (validationState) {
                        is TokenValidationState.Valid -> Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(ComponentSize.iconSmall)
                        )
                        is TokenValidationState.Invalid -> Icon(
                            Icons.Default.Error,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(ComponentSize.iconSmall)
                        )
                        is TokenValidationState.Validating -> CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        else -> {}
                    }
                    
                    // Validate button
                    if (value.isNotBlank() && validationState !is TokenValidationState.Valid && validationState !is TokenValidationState.Validating) {
                        IconButton(onClick = onValidate) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Validate",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            },
            visualTransformation = if (!showToken) PasswordVisualTransformation() else VisualTransformation.None,
            isError = validationState is TokenValidationState.Invalid,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = when (validationState) {
                    is TokenValidationState.Valid -> MaterialTheme.colorScheme.primary
                    is TokenValidationState.Invalid -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                }
            ),
            shape = MaterialTheme.shapes.medium,
            singleLine = true
        )
        
        // Validation result message
        AnimatedVisibility(visible = validationState is TokenValidationState.Valid || validationState is TokenValidationState.Invalid) {
            when (validationState) {
                is TokenValidationState.Valid -> {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text = "@${validationState.botUsername}",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                is TokenValidationState.Invalid -> {
                    Text(
                        text = validationState.error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp)
                    )
                }
                else -> {}
            }
        }
    }
}

/**
 * Password strength indicator
 */
@Composable
fun PasswordStrengthIndicator(
    strength: PasswordStrength,
    modifier: Modifier = Modifier
) {
    val color = when (strength) {
        PasswordStrength.WEAK -> MaterialTheme.colorScheme.error
        PasswordStrength.MEDIUM -> Color(0xFFFFA000) // Orange
        PasswordStrength.STRONG -> Color(0xFF4CAF50) // Green
        PasswordStrength.VERY_STRONG -> MaterialTheme.colorScheme.primary
    }
    
    val label = when (strength) {
        PasswordStrength.WEAK -> stringResource(R.string.wizard_password_weak)
        PasswordStrength.MEDIUM -> stringResource(R.string.wizard_password_medium)
        PasswordStrength.STRONG -> stringResource(R.string.wizard_password_strong)
        PasswordStrength.VERY_STRONG -> stringResource(R.string.wizard_password_very_strong)
    }
    
    val progress = when (strength) {
        PasswordStrength.WEAK -> 0.25f
        PasswordStrength.MEDIUM -> 0.5f
        PasswordStrength.STRONG -> 0.75f
        PasswordStrength.VERY_STRONG -> 1f
    }
    
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.wizard_password_strength),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = color,
                fontWeight = FontWeight.Medium
            )
        }
        
        Spacer(Modifier.height(4.dp))
        
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/**
 * Info card for tips and help
 */
@Composable
fun InfoCard(
    title: String,
    description: String,
    icon: ImageVector = Icons.Default.Info,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(Spacing.screenPadding),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onClick != null) {
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * Detected channel card
 */
@Composable
fun DetectedChannelCard(
    channel: DetectedChannel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.screenPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Forum,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = channel.title,
                    style = MaterialTheme.typography.titleSmall
                )
                Text(
                    text = "ID: ${channel.id}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Wizard navigation buttons
 */
@Composable
fun WizardNavButtons(
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
    onSkip: (() -> Unit)? = null,
    nextEnabled: Boolean = true,
    nextLabel: String = stringResource(R.string.wizard_next),
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Back button
        if (onBack != null) {
            TextButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.back))
            }
        } else {
            Spacer(Modifier.width(1.dp))
        }
        
        Row {
            // Skip button
            if (onSkip != null) {
                TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.wizard_skip))
                }
                Spacer(Modifier.width(8.dp))
            }
            
            // Next button
            Button(
                onClick = onNext,
                enabled = nextEnabled && !isLoading
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(nextLabel)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Default.ArrowForward, contentDescription = null)
                }
            }
        }
    }
}

/**
 * Connection test result card
 */
@Composable
fun ConnectionTestCard(
    state: ConnectionTestState,
    onTest: () -> Unit,
    onOpenChannel: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                is ConnectionTestState.Success -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                is ConnectionTestState.Failed -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(Spacing.screenPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state) {
                is ConnectionTestState.Idle -> {
                    Icon(
                        Icons.Default.WifiFind,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.wizard_test_connection_description),
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onTest) {
                        Text(stringResource(R.string.wizard_test_connection))
                    }
                }
                is ConnectionTestState.Testing -> {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.wizard_testing),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                is ConnectionTestState.Success -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                }
                is ConnectionTestState.Failed -> {
                    Icon(
                        Icons.Default.Error,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = state.error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    Spacer(Modifier.height(16.dp))
                    
                    if (state.isAdminError && onOpenChannel != null) {
                        // Priority action: Open Channel
                        Button(
                            onClick = onOpenChannel,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.wizard_open_channel))
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        
                        // Secondary action: Retry
                        TextButton(
                            onClick = onTest,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.wizard_retry))
                        }
                    } else {
                        // Standard retry
                        OutlinedButton(onClick = onTest) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.wizard_retry))
                        }
                    }
                }
            }
        }
    }
}
