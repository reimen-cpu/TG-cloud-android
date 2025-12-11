package com.telegram.cloud.gallery

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.telegram.cloud.R

/**
 * Action bar that appears when media items are selected
 * Provides contextual actions for single or multiple selected items
 */
@Composable
fun SelectionActionBar(
    selectedCount: Int,
    modifier: Modifier = Modifier,
    onSync: () -> Unit = {},
    onDelete: () -> Unit = {},
    onShare: () -> Unit = {},
    onDownload: () -> Unit = {},
    onCopy: () -> Unit = {},
    onDeselectAll: () -> Unit = {},
    showSyncButton: Boolean = true
) {
    val materialTheme = MaterialTheme.colorScheme
    
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        color = materialTheme.surface,
        shadowElevation = 8.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header with count and deselect button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (selectedCount == 1) stringResource(R.string.item_selected, selectedCount) else stringResource(R.string.items_selected, selectedCount),
                    color = materialTheme.onSurface,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                TextButton(onClick = onDeselectAll) {
                    Text(stringResource(R.string.deselect_all), color = materialTheme.primary)
                }
            }
            
            // Action buttons row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Sync action (only show if showSyncButton is true)
                if (showSyncButton) {
                    SelectionActionButton(
                        icon = Icons.Default.CloudUpload,
                        label = stringResource(R.string.sync_short),
                        onClick = onSync,
                        modifier = Modifier.weight(1f),
                        color = materialTheme.primary
                    )
                }
                
                // Download action
                SelectionActionButton(
                    icon = Icons.Default.CloudDownload,
                    label = stringResource(R.string.download_short),
                    onClick = onDownload,
                    modifier = Modifier.weight(1f),
                    color = materialTheme.secondary
                )
                
                // Share action
                SelectionActionButton(
                    icon = Icons.Default.Share,
                    label = stringResource(R.string.share_short),
                    onClick = onShare,
                    modifier = Modifier.weight(1f),
                    color = materialTheme.tertiary
                )
                
                // Delete action
                SelectionActionButton(
                    icon = Icons.Default.Delete,
                    label = stringResource(R.string.delete_short),
                    onClick = onDelete,
                    modifier = Modifier.weight(1f),
                    color = materialTheme.error
                )
            }
        }
    }
}

@Composable
private fun SelectionActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: androidx.compose.ui.graphics.Color
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = color.copy(alpha = 0.15f),
            contentColor = color
        ),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(28.dp),
            tint = color
        )
    }
}

