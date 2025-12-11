package com.telegram.cloud.ui.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.telegram.cloud.R
import com.telegram.cloud.ui.components.AnimatedButton
import com.telegram.cloud.ui.theme.Spacing
import com.telegram.cloud.ui.theme.ComponentSize
import com.telegram.cloud.ui.utils.HapticFeedbackType

@Composable
fun SetupScreen(
    isEditing: Boolean,
    initialTokens: List<String> = emptyList(),
    initialChannelId: String? = null,
    initialChatId: String? = null,
    onSave: (tokens: List<String>, channelId: String, chatId: String?) -> Unit,
    onImportBackup: (() -> Unit)? = null,
    onCancel: (() -> Unit)? = null
) {
    val tokens = remember { mutableStateListOf("", "", "", "", "") }
    val channelId = remember { androidx.compose.runtime.mutableStateOf(initialChannelId.orEmpty()) }
    val chatId = remember { androidx.compose.runtime.mutableStateOf(initialChatId.orEmpty()) }

    androidx.compose.runtime.LaunchedEffect(initialTokens) {
        initialTokens.take(5).forEachIndexed { index, value ->
            tokens[index] = value
        }
    }

    val setupTitle = stringResource(R.string.setup_title)
    val editConfigTitle = stringResource(R.string.edit_config)

    val isValid = tokens.any { it.isNotBlank() } && channelId.value.isNotBlank()
    val tokenValidations = tokens.map { it.isNotBlank() && it.length > 10 }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.screenPaddingLarge),
        verticalArrangement = Arrangement.spacedBy(Spacing.screenPadding)
    ) {
        Text(
            text = if (isEditing) editConfigTitle else setupTitle,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground
        )

        tokens.forEachIndexed { index, value ->
            val isValidToken = value.isNotBlank() && value.length > 10
            OutlinedTextField(
                value = value,
                onValueChange = { tokens[index] = it },
                label = { Text("Bot Token ${index + 1}") },
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                trailingIcon = {
                    AnimatedVisibility(
                        visible = value.isNotBlank(),
                        enter = fadeIn() + slideInVertically(),
                        exit = fadeOut() + slideOutVertically()
                    ) {
                        Icon(
                            imageVector = if (isValidToken) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (isValidToken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(ComponentSize.iconSmall)
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = if (isValidToken) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                shape = MaterialTheme.shapes.medium,
                textStyle = MaterialTheme.typography.bodyMedium
            )
        }

        val isChannelIdValid = channelId.value.isNotBlank() && (channelId.value.startsWith("-100") || channelId.value.startsWith("-"))
        OutlinedTextField(
            value = channelId.value,
            onValueChange = { channelId.value = it },
            label = { Text(stringResource(R.string.channel_id) + " (-100...)") },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                AnimatedVisibility(
                    visible = channelId.value.isNotBlank(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Icon(
                        imageVector = if (isChannelIdValid) Icons.Default.CheckCircle else Icons.Default.Error,
                        contentDescription = null,
                        tint = if (isChannelIdValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(ComponentSize.iconSmall)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (isChannelIdValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyMedium
        )

        OutlinedTextField(
            value = chatId.value,
            onValueChange = { chatId.value = it },
            label = { Text(stringResource(R.string.chat_id_optional)) },
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                AnimatedVisibility(
                    visible = chatId.value.isNotBlank(),
                    enter = fadeIn() + slideInVertically(),
                    exit = fadeOut() + slideOutVertically()
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(ComponentSize.iconSmall)
                    )
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                cursorColor = MaterialTheme.colorScheme.primary
            ),
            shape = MaterialTheme.shapes.medium,
            textStyle = MaterialTheme.typography.bodyMedium
        )

        AnimatedButton(
            enabled = isValid,
            onClick = { onSave(tokens.filter { it.isNotBlank() }, channelId.value, chatId.value.ifBlank { null }) },
            modifier = Modifier.fillMaxWidth().height(ComponentSize.buttonHeight),
            hapticType = HapticFeedbackType.MEDIUM_CLICK
        ) {
            Text(stringResource(R.string.save), style = MaterialTheme.typography.labelLarge)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (onImportBackup != null) {
                TextButton(onClick = onImportBackup) {
                    Text(stringResource(R.string.import_backup), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (onCancel != null) {
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.cancel), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

