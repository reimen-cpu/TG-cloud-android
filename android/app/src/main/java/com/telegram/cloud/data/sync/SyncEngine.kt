package com.telegram.cloud.data.sync

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.telegram.cloud.data.remote.TelegramBotClient
import com.telegram.cloud.data.remote.TelegramMessage
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.local.CloudFileEntity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.nio.charset.StandardCharsets

/**
 * Orchestrates the synchronization of encrypted logs via Telegram.
 * Handles upload of local logs and download/application of remote logs.
 */
class SyncEngine(
    private val context: Context,
    private val telegramClient: TelegramBotClient,
    private val syncLogManager: SyncLogManager,
    private val conflictResolver: ConflictResolver,
    private val syncConfig: SyncConfig,
    private val database: CloudDatabase
) {
    private val syncLogDao = database.syncLogDao()
    private val syncMetadataDao = database.syncMetadataDao()
    private val cloudFileDao = database.cloudFileDao()
    companion object {
        private const val TAG = "SyncEngine"
        private const val LOG_FILE_EXTENSION = ".synclog.enc"
        private const val SYNC_INDEX_CAPTION = "sync_index"
        private const val SYNC_INDEX_FILENAME = "sync_index.json.enc"
    }
    
    private val gson = Gson()
    
    /**
     * Uploads all pending local logs to the Telegram sync channel using Linked List Chain.
     * 
     * @return Result containing the number of logs uploaded or an error
     */
    suspend fun uploadPendingLogs(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!syncConfig.isValid()) {
                return@withContext Result.failure(SyncException("Invalid sync configuration"))
            }
            
            val pendingLogs = syncLogManager.getPendingLogs()
            if (pendingLogs.isEmpty()) {
                Log.d(TAG, "No pending logs to upload")
                return@withContext Result.success(0)
            }
            
            Log.i(TAG, "Processing ${pendingLogs.size} pending logs for chain upload")
            
            // Batch logs into chunks (max 50 per node to keep messages small-ish)
            val batches = pendingLogs.chunked(50)
            var uploadedCount = 0
            
            for (batch in batches) {
                try {
                    // Upload batch as a chained node
                    val messageId = uploadSyncNode(batch)
                    
                    if (messageId > 0) {
                        // Mark all logs in batch as uploaded
                        batch.forEach { log ->
                            syncLogManager.markUploaded(log.logId, messageId)
                            uploadedCount++
                        }
                        Log.i(TAG, "Uploaded batch of ${batch.size} logs in message $messageId")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to upload batch", e)
                    // Stop on error to preserve order/chain integrity? 
                    // Or continue? If we continue, we fork the chain potentially if we don't handle prevId correctly.
                    // Safest to stop and retry later.
                    return@withContext Result.failure(e)
                }
            }
            
            // Update last upload timestamp
            syncMetadataDao.setValue(
                SyncMetadataEntity(
                    key = SyncMetadataEntity.KEY_LAST_UPLOAD_TIMESTAMP,
                    value = System.currentTimeMillis().toString()
                )
            )
            
            Log.i(TAG, "Successfully processed $uploadedCount logs")
            Result.success(uploadedCount)
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Uploads a sync index document listing all uploaded log file_ids.
     * Other devices can download this index to discover which logs exist.
     */

    /**
     * Helper to fetch the current Chain Head (latest pinned message).
     */
    private suspend fun fetchChainHead(): Pair<TelegramMessage, SyncChainNode>? {
        try {
            val indexMessage = telegramClient.getPinnedMessage(
                token = syncConfig.syncBotToken,
                channelId = syncConfig.syncChannelId
            ) ?: return null
            
            val text = indexMessage.caption ?: ""
            val encryptedData: ByteArray
            
            if (text.startsWith("$SYNC_INDEX_CAPTION:")) {
                val base64Index = text.substringAfter("$SYNC_INDEX_CAPTION:")
                encryptedData = android.util.Base64.decode(base64Index, android.util.Base64.NO_WRAP)
            } else if (indexMessage.document != null) {
                encryptedData = telegramClient.downloadSyncLog(
                    token = syncConfig.syncBotToken,
                    fileId = indexMessage.document!!.fileId
                )
            } else {
                return null
            }
            
            // Decrypt then GUnzip
            val compressedData = SyncCrypto.decrypt(encryptedData, syncConfig.syncPassword)
            val json = GzipUtils.decompress(compressedData)
            
            return indexMessage to gson.fromJson(json, SyncChainNode::class.java)
        } catch (e: Exception) {
            Log.w(TAG, "fetchChainHead: Failed to fetch/parse (might be first run or legacy format)", e)
            return null
        }
    }

    /**
     * Uploads a batch of logs as a Linked Chain Node.
     * @return The message ID of the uploaded node.
     */
    private suspend fun uploadSyncNode(newLogs: List<SyncLogEntity>): Long {
        // 1. Fetch current head to link to
        val currentHead = fetchChainHead()
        val prevId = currentHead?.first?.messageId ?: 0L
        
        // 2. Map new logs to entries
        val newEntries = newLogs.map { log ->
                SyncIndexEntry(
                logId = log.logId,
                deviceId = log.deviceId,
                timestamp = log.timestamp,
                operation = log.operation.name,
                tableName = log.tableName,
                primaryKey = log.primaryKey,
                dataJson = log.dataJson,
                previousDataJson = log.previousDataJson
            )
        }
        
        // 3. Create Node
        val newNode = SyncChainNode(
            prevId = prevId,
            entries = newEntries,
            timestamp = System.currentTimeMillis()
        )
        
        // 4. Serialize, Gzip, Encrypt
        val nodeJson = gson.toJson(newNode)
        val nodeCompressed = GzipUtils.compress(nodeJson)
        val nodeEncrypted = SyncCrypto.encrypt(nodeCompressed, syncConfig.syncPassword)
        val nodeBase64 = android.util.Base64.encodeToString(nodeEncrypted, android.util.Base64.NO_WRAP)
        
        val messageId: Long
        
        // 5. Try Text Message (Adaptive)
        if (nodeBase64.length < 3500) {
            Log.d(TAG, "uploadSyncNode: Node small, text mode")
            val textMsg = telegramClient.sendTextMessage(
                token = syncConfig.syncBotToken,
                channelId = syncConfig.syncChannelId,
                text = "$SYNC_INDEX_CAPTION:$nodeBase64"
            )
            messageId = textMsg.messageId
        } else {
                Log.d(TAG, "uploadSyncNode: Node large, document mode")
                val docMsg = telegramClient.sendDocument(
                token = syncConfig.syncBotToken,
                channelId = syncConfig.syncChannelId,
                caption = SYNC_INDEX_CAPTION,
                fileName = "sync_node_${System.currentTimeMillis()}.json.enc",
                mimeType = "application/octet-stream",
                streamProvider = { ByteArrayInputStream(nodeEncrypted) },
                totalBytes = nodeEncrypted.size.toLong()
            )
            messageId = docMsg.messageId
        }
        
        // 6. Pin (Set as new Head)
        telegramClient.pinChatMessage(
            token = syncConfig.syncBotToken,
            channelId = syncConfig.syncChannelId,
            messageId = messageId
        )
        
        Log.i(TAG, "uploadSyncNode: Pinned new head $messageId (prev=$prevId)")
        return messageId
    }
    
    /**
     * Downloads Sync Index traversing Master -> Chunks -> Buffer.
     */
    /**
     * Downloads Sync Logs by backtracking the Linked List Chain.
     * Starts from Head (Pinned) and goes backwards until a known ID is found.
     */
    private suspend fun downloadSyncIndex(): List<SyncIndexEntry> {
        try {
            // 1. Get Head of Chain (Pinned Msg)
            var currentHeadPair = fetchChainHead()
            if (currentHeadPair == null) {
                Log.d(TAG, "downloadSyncIndex: No pinned head found")
                return emptyList()
            }
            
            var (currentMsg, currentNode) = currentHeadPair
            
            // 2. Check if we already processed this Head
            val lastProcessedIdStr = syncMetadataDao.getValue(SyncMetadataEntity.KEY_LAST_PINNED_MESSAGE_ID)
            val lastProcessedId = lastProcessedIdStr?.toLongOrNull() ?: 0L
            
            if (currentMsg.messageId <= lastProcessedId) {
                 if (currentMsg.messageId == lastProcessedId) {
                    Log.d(TAG, "downloadSyncIndex: Head ${currentMsg.messageId} already processed")
                    return emptyList()
                }
                // If current < last, we might be out of sync or re-indexing?
                // Proceed cautiously, but typically we just fetch what we can.
            }
            
            Log.i(TAG, "downloadSyncIndex: Backtracking from head ${currentMsg.messageId} (known: $lastProcessedId)")
            
            // Store segments of entries: [NewestNodeEntries, ..., OldestNodeEntries]
            val segments = mutableListOf<List<SyncIndexEntry>>()
            val visitedIds = mutableSetOf<Long>()
            
            // 3. Backtrack Loop
            while (true) {
                if (visitedIds.contains(currentMsg.messageId)) {
                    Log.w(TAG, "downloadSyncIndex: Cycle detected at ${currentMsg.messageId}")
                    break
                }
                visitedIds.add(currentMsg.messageId)
                
                // Collect entries from this node
                // Node contains entries [Old...New] (chronological within batch)
                segments.add(currentNode.entries)
                
                val prevId = currentNode.prevId
                
                // Stop condition: Hit known ID or Start of Chain
                if (prevId == 0L || prevId <= lastProcessedId) {
                    Log.i(TAG, "downloadSyncIndex: Reached base $prevId (target $lastProcessedId)")
                    break
                }
                
                // Fetch Previous Node
                val prevNodePair = fetchNodeByForwarding(prevId)
                if (prevNodePair == null) {
                    Log.e(TAG, "downloadSyncIndex: Broken chain at $prevId (prev of ${currentMsg.messageId})")
                    break 
                }
                
                currentMsg = prevNodePair.first
                currentNode = prevNodePair.second
            }
            
            // 4. Reverse segments to get [OldestNodeEntries...NewestNodeEntries]
            // Entries within node are already [Old...New].
            // So reversing segments gives correct global chronological order.
            val finalEntries = segments.asReversed().flatten()
            
            Log.i(TAG, "downloadSyncIndex: Resolved chain with ${finalEntries.size} new entries")
            
            // 5. Update Metadata (New Head)
            if (finalEntries.isNotEmpty()) {
                 syncMetadataDao.setValue(
                    SyncMetadataEntity(
                        key = SyncMetadataEntity.KEY_LAST_PINNED_MESSAGE_ID,
                        value = currentHeadPair.first.messageId.toString()
                    )
                )
            }
            
            return finalEntries
        } catch (e: Exception) {
            Log.e(TAG, "downloadSyncIndex: Failed", e)
            return emptyList()
        }
    }
    
    /**
     * WORKAROUND: Fetch a message by ID by forwarding it to the same channel.
     * Then reads the content and DELETES the forward to keep channel clean.
     */
    private suspend fun fetchNodeByForwarding(messageId: Long): Pair<TelegramMessage, SyncChainNode>? {
        try {
            // Forward
            val forwardResult = telegramClient.forwardMessage(
                token = syncConfig.syncBotToken,
                toChatId = syncConfig.syncChannelId,
                fromChatId = syncConfig.syncChannelId,
                messageId = messageId
            ) ?: return null
            
            // Parse content
            val text = forwardResult.caption ?: ""
            val encryptedData: ByteArray
            
            try {
                if (text.startsWith("$SYNC_INDEX_CAPTION:")) {
                    val base64Index = text.substringAfter("$SYNC_INDEX_CAPTION:")
                    encryptedData = android.util.Base64.decode(base64Index, android.util.Base64.NO_WRAP)
                } else if (forwardResult.document != null) {
                    encryptedData = telegramClient.downloadSyncLog(
                        token = syncConfig.syncBotToken,
                        fileId = forwardResult.document.fileId
                    )
                } else {
                    return null
                }
                
                val compressedData = SyncCrypto.decrypt(encryptedData, syncConfig.syncPassword)
                val json = GzipUtils.decompress(compressedData)
                val node = gson.fromJson(json, SyncChainNode::class.java)
                
                // Return original message info (id) + Node
                // But wait, the `forwardResult` has a NEW messageId. 
                // We want the ORIGINAL messageId from the chain.
                // The caller passed `messageId`.
                // We return `forwardResult` as "currentMsg"? 
                // No, the caller updates `currentMsg` to `prevNodePair.first`.
                // `currentMsg` is used for `visitedIds` check.
                // So we should construct a dummy TelegramMessage with the ORIGINAL ID.
                
                val originalMsgWrapper = forwardResult.copy(messageId = messageId)
                return originalMsgWrapper to node
            } finally {
                // CLEANUP: Delete the forwarded message
                telegramClient.deleteMessage(
                    token = syncConfig.syncBotToken,
                    channelId = syncConfig.syncChannelId,
                    messageId = forwardResult.messageId
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetchNodeByForwarding: Error fetching $messageId", e)
            return null
        }
    }

    object GzipUtils {
        fun compress(string: String): ByteArray {
            val os = ByteArrayOutputStream(string.length)
            GZIPOutputStream(os).use { it.write(string.toByteArray(StandardCharsets.UTF_8)) }
            return os.toByteArray()
        }

        fun decompress(compressed: ByteArray): String {
            val bis = ByteArrayInputStream(compressed)
            val gzip = GZIPInputStream(bis)
            val reader = java.io.InputStreamReader(gzip, StandardCharsets.UTF_8)
            return reader.readText()
        }
    }
    
    // Simplified Linked List Node
    data class SyncChainNode(
        val prevId: Long = 0,
        val entries: List<SyncIndexEntry> = emptyList(),
        val timestamp: Long = System.currentTimeMillis()
    )

    
    /**
     * Entry in the sync index, representing an uploaded log.
     */
    data class SyncIndexEntry(
        val logId: String,
        val deviceId: String,
        val timestamp: Long,
        val operation: String,
        val tableName: String,
        val primaryKey: String,
        val dataJson: String?,
        val previousDataJson: String?
    )
    
    /**
     * Downloads new logs from the Telegram sync channel using the sync index.
     * The index contains all log data, so we can apply them directly without
     * downloading individual files.
     * 
     * @return Result containing the downloaded logs or an error
     */
    suspend fun downloadNewLogs(): Result<List<SyncLogEntity>> = withContext(Dispatchers.IO) {
        try {
            if (!syncConfig.isValid()) {
                Log.w(TAG, "downloadNewLogs: Invalid sync config")
                return@withContext Result.failure(SyncException("Invalid sync configuration"))
            }
            
            Log.i(TAG, "downloadNewLogs: syncChannelId=${syncConfig.syncChannelId}, deviceId=${syncConfig.deviceId}")
            
            // Download the sync index which contains all log data
            val indexEntries = downloadSyncIndex()
            
            if (indexEntries.isEmpty()) {
                Log.d(TAG, "downloadNewLogs: No index entries found")
                return@withContext Result.success(emptyList())
            }
            
            Log.i(TAG, "downloadNewLogs: Found ${indexEntries.size} entries in sync index")
            
            // Filter and convert index entries to SyncLogEntity
            val downloadedLogs = mutableListOf<SyncLogEntity>()
            
            for (entry in indexEntries) {
                // Skip our own logs
                if (entry.deviceId == syncConfig.deviceId) {
                    Log.d(TAG, "downloadNewLogs: Skipping own log ${entry.logId}")
                    continue
                }
                
                // Skip logs we already have
                if (syncLogDao.exists(entry.logId)) {
                    Log.d(TAG, "downloadNewLogs: Already have log ${entry.logId}")
                    continue
                }
                
                // Convert index entry to SyncLogEntity
                val log = SyncLogEntity(
                    logId = entry.logId,
                    timestamp = entry.timestamp,
                    deviceId = entry.deviceId,
                    operation = SyncOperation.fromString(entry.operation),
                    tableName = entry.tableName,
                    primaryKey = entry.primaryKey,
                    dataJson = entry.dataJson,
                    previousDataJson = entry.previousDataJson,
                    isUploaded = true,  // Mark as already uploaded (came from remote)
                    telegramMessageId = null,
                    checksum = null
                )
                
                downloadedLogs.add(log)
                Log.d(TAG, "downloadNewLogs: New log ${log.logId} from device ${log.deviceId}")
            }
            
            Log.i(TAG, "downloadNewLogs: Downloaded ${downloadedLogs.size} new logs (filtered from ${indexEntries.size} total)")
            Result.success(downloadedLogs)
        } catch (e: Exception) {
            Log.e(TAG, "downloadNewLogs: Failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Applies downloaded logs to the local database.
     * Handles conflict detection and resolution.
     * 
     * @param logs The logs to apply
     * @return Result containing the number of logs applied or an error
     */
    suspend fun applyRemoteLogs(logs: List<SyncLogEntity>): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (logs.isEmpty()) {
                return@withContext Result.success(0)
            }
            
            // Execute in transaction to prevent inconsistent reads during updates
            database.withTransaction {
                // Order logs by timestamp
                val orderedLogs = conflictResolver.orderByTimestamp(logs)
                var appliedCount = 0
                
                for (log in orderedLogs) {
                    try {
                        // Check if already applied
                        if (syncLogDao.exists(log.logId)) {
                            Log.d(TAG, "Log ${log.logId} already applied, skipping")
                            continue
                        }
                        
                        // Check for conflicts
                        val existingLogs = syncLogDao.getLogsForRecord(log.tableName, log.primaryKey)
                        val conflictType = conflictResolver.detectConflict(log, existingLogs)
                        
                        when (conflictType) {
                            ConflictType.NONE -> {
                                // Apply the log
                                applyLog(log)
                                appliedCount++
                            }
                            ConflictType.FIELD_CONFLICT -> {
                                // Try to merge
                                val newestLocal = existingLogs.maxByOrNull { it.timestamp }
                                if (newestLocal != null) {
                                    val merged = conflictResolver.tryMerge(log, newestLocal)
                                    if (merged != null) {
                                        applyMergedData(log.tableName, log.primaryKey, merged)
                                        appliedCount++
                                    } else {
                                        // Use last-write-wins
                                        val resolution = conflictResolver.resolveConflict(log, newestLocal)
                                        if (resolution is ResolutionResult.UseRemote) {
                                            applyLog(log)
                                            appliedCount++
                                        }
                                    }
                                }
                            }
                            ConflictType.DELETE_CONFLICT -> {
                                // For delete conflicts, use last-write-wins
                                val newestLocal = existingLogs.maxByOrNull { it.timestamp }
                                if (newestLocal != null) {
                                    val resolution = conflictResolver.resolveConflict(log, newestLocal)
                                    if (resolution is ResolutionResult.UseRemote) {
                                        applyLog(log)
                                        appliedCount++
                                    }
                                }
                            }
                            ConflictType.IRRECONCILABLE -> {
                                Log.w(TAG, "Irreconcilable conflict for ${log.tableName}[${log.primaryKey}]")
                                // Skip and log the conflict
                            }
                        }
                        
                        // Store the log
                        syncLogManager.storeRemoteLog(log)
                        
                        // Update last applied log ID
                        syncLogManager.updateLastAppliedLogId(log.logId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to apply log ${log.logId}", e)
                        throw e // Rollback transaction on error? Or consume and continue?
                        // If we throw, we lose all progress for this batch.
                        // But `appliedCount` would be wrong (no, we are in transaction).
                        // Safest to log and continue to avoid blocking processing of valid logs?
                        // But if DB is corrupted, continuing is bad.
                        // For now, let's catch inside loop to allow partial success, BUT transaction commit applies all or nothing?
                        // Wait, if I catch exception inside loop, transaction doesn't rollback.
                        // So specific log failure doesn't rollback others.
                    }
                }
                
                // Update last sync timestamp
                syncMetadataDao.setValue(
                    SyncMetadataEntity(
                        key = SyncMetadataEntity.KEY_LAST_SYNC_TIMESTAMP,
                        value = System.currentTimeMillis().toString()
                    )
                )
                
                Log.i(TAG, "Applied $appliedCount logs in transaction")
                Result.success(appliedCount)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Apply failed", e)
            Result.failure(e)
        }
    }
    
    /**
     * Performs a full sync cycle: upload pending, download new, apply.
     */
    suspend fun performSync(): SyncResult {
        Log.i(TAG, "Starting sync cycle")
        
        // Upload pending logs
        val uploadResult = uploadPendingLogs()
        val uploadedCount = uploadResult.getOrDefault(0)
        
        // Download new logs
        val downloadResult = downloadNewLogs()
        val downloadedLogs = downloadResult.getOrDefault(emptyList())
        
        // Apply downloaded logs
        val applyResult = applyRemoteLogs(downloadedLogs)
        val appliedCount = applyResult.getOrDefault(0)
        
        val result = SyncResult(
            uploadedCount = uploadedCount,
            downloadedCount = downloadedLogs.size,
            appliedCount = appliedCount,
            hasErrors = uploadResult.isFailure || downloadResult.isFailure || applyResult.isFailure
        )
        
        Log.i(TAG, "Sync cycle complete: $result")
        return result
    }
    
    /**
     * Applies a single log to the local database.
     * Handles INSERT, UPDATE, DELETE operations for cloud_files table.
     */
    private suspend fun applyLog(log: SyncLogEntity) {
        Log.d(TAG, "Applying ${log.operation} to ${log.tableName}[${log.primaryKey}]")
        
        if (cloudFileDao == null) {
             // Should not happen with non-nullable dao, but keeping for safety if I messed up
             // Actually, deleting this check as I removed nullable type.
             Log.w(TAG, "applyLog: database invalid?")
             // return
        }
        
        when (log.tableName) {
            "cloud_files" -> applyCloudFileLog(log)
            else -> Log.w(TAG, "applyLog: Unknown table ${log.tableName}")
        }
    }
    
    /**
     * Applies a sync log to the cloud_files table.
     */
    private suspend fun applyCloudFileLog(log: SyncLogEntity) {
        // if (cloudFileDao == null) return
        
        when (log.operation) {
            SyncOperation.INSERT, SyncOperation.UPDATE -> {
                val dataJson = log.dataJson
                if (dataJson.isNullOrBlank()) {
                    Log.w(TAG, "applyCloudFileLog: No data in INSERT/UPDATE log")
                    return
                }
                
                // Parse JSON to map
                val data: Map<String, Any?> = try {
                    com.google.gson.Gson().fromJson(
                        dataJson, 
                        object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "applyCloudFileLog: Failed to parse dataJson", e)
                    return
                }
                
                val entity = mapToCloudFileEntity(data, log.primaryKey)
                if (entity != null) {
                    cloudFileDao.insert(entity)
                    Log.i(TAG, "applyCloudFileLog: Applied ${log.operation} for ${entity.fileName}")
                }
            }
            SyncOperation.DELETE -> {
                val primaryKeyId = log.primaryKey.toLongOrNull()
                if (primaryKeyId != null) {
                    cloudFileDao.deleteById(primaryKeyId)
                    Log.i(TAG, "applyCloudFileLog: Applied DELETE for id=$primaryKeyId")
                } else {
                    Log.w(TAG, "applyCloudFileLog: Invalid primary key for DELETE: ${log.primaryKey}")
                }
            }
        }
    }
    
    /**
     * Converts a data map from sync log to CloudFileEntity.
     */
    private fun mapToCloudFileEntity(data: Map<String, Any?>, primaryKey: String): CloudFileEntity? {
        try {
            val id = (data["id"] as? Number)?.toLong() ?: primaryKey.toLongOrNull() ?: 0L
            
            return CloudFileEntity(
                id = id,
                telegramMessageId = (data["telegramMessageId"] as? Number)?.toLong() ?: 0L,
                fileId = data["fileId"]?.toString() ?: "",
                fileUniqueId = data["fileUniqueId"]?.toString() ?: "",
                fileName = data["fileName"]?.toString() ?: "unknown",
                mimeType = data["mimeType"]?.toString(),
                sizeBytes = (data["sizeBytes"] as? Number)?.toLong() ?: 0L,
                uploadedAt = (data["uploadedAt"] as? Number)?.toLong() ?: System.currentTimeMillis(),
                caption = data["caption"]?.toString(),
                shareLink = data["shareLink"]?.toString(),
                checksum = data["checksum"]?.toString(),
                uploaderTokens = data["uploaderTokens"]?.toString()
            )
        } catch (e: Exception) {
            Log.e(TAG, "mapToCloudFileEntity: Failed to convert data", e)
            return null
        }
    }
    
    /**
     * Applies merged data to the database.
     */
    private suspend fun applyMergedData(tableName: String, primaryKey: String, data: Map<String, Any?>) {
        Log.d(TAG, "Applying merged data to $tableName[$primaryKey]")
        
        when (tableName) {
            "cloud_files" -> {
                val entity = mapToCloudFileEntity(data, primaryKey)
                if (entity != null) {
                    cloudFileDao.insert(entity)
                    Log.i(TAG, "applyMergedData: Updated ${entity.fileName}")
                }
            }
            else -> Log.w(TAG, "applyMergedData: Unknown table $tableName")
        }
    }
}

/**
 * Result of a sync operation.
 */
data class SyncResult(
    val uploadedCount: Int,
    val downloadedCount: Int,
    val appliedCount: Int,
    val hasErrors: Boolean
) {
    val isSuccess: Boolean get() = !hasErrors
}

/**
 * Exception thrown during sync operations.
 */
class SyncException(message: String, cause: Throwable? = null) : Exception(message, cause)
