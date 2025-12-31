package com.telegram.cloud.data.sync

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * Manages the creation, serialization, encryption, and deserialization of sync logs.
 * Acts as the central point for all sync log operations.
 */
class SyncLogManager(
    private val syncLogDao: SyncLogDao,
    private val syncMetadataDao: SyncMetadataDao,
    private val deviceId: String
) {
    companion object {
        private const val TAG = "SyncLogManager"
    }
    
    private val gson = Gson()
    
    /**
     * Creates and stores a sync log for a database operation.
     * 
     * @param operation The type of operation (INSERT, UPDATE, DELETE)
     * @param tableName The name of the affected table
     * @param primaryKey The primary key of the affected row
     * @param data The new data (null for DELETE)
     * @param previousData The previous data (null for INSERT)
     * @return The created sync log entity
     */
    suspend fun logOperation(
        operation: SyncOperation,
        tableName: String,
        primaryKey: String,
        data: Map<String, Any?>? = null,
        previousData: Map<String, Any?>? = null
    ): SyncLogEntity {
        val logId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        
        val dataJson = data?.let { gson.toJson(it) }
        val previousDataJson = previousData?.let { gson.toJson(it) }
        
        // Generate checksum for integrity
        val logContent = "$logId|$timestamp|$deviceId|$operation|$tableName|$primaryKey|$dataJson|$previousDataJson"
        val checksum = SyncCrypto.generateChecksum(logContent.toByteArray(Charsets.UTF_8))
        
        val log = SyncLogEntity(
            logId = logId,
            timestamp = timestamp,
            deviceId = deviceId,
            operation = operation,
            tableName = tableName,
            primaryKey = primaryKey,
            dataJson = dataJson,
            previousDataJson = previousDataJson,
            isUploaded = false,
            checksum = checksum
        )
        
        syncLogDao.insert(log)
        Log.d(TAG, "Created sync log: $logId for $operation on $tableName[$primaryKey]")
        
        return log
    }
    
    /**
     * Serializes and encrypts a sync log for upload.
     * 
     * @param log The sync log to serialize
     * @param password The encryption password
     * @return Encrypted bytes ready for upload
     */
    fun serializeAndEncrypt(log: SyncLogEntity, password: String): ByteArray {
        val json = gson.toJson(log)
        val jsonBytes = json.toByteArray(Charsets.UTF_8)
        return SyncCrypto.encrypt(jsonBytes, password)
    }
    
    /**
     * Decrypts and deserializes a downloaded sync log.
     * 
     * @param encryptedData The encrypted log data
     * @param password The decryption password
     * @return The deserialized sync log entity
     * @throws SyncCryptoException if decryption fails
     */
    fun decryptAndDeserialize(encryptedData: ByteArray, password: String): SyncLogEntity {
        val decrypted = SyncCrypto.decrypt(encryptedData, password)
        val json = String(decrypted, Charsets.UTF_8)
        return gson.fromJson(json, SyncLogEntity::class.java)
    }
    
    /**
     * Parses the data JSON from a sync log.
     */
    fun parseDataJson(log: SyncLogEntity): Map<String, Any?>? {
        if (log.dataJson == null) return null
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        return gson.fromJson(log.dataJson, type)
    }
    
    /**
     * Parses the previous data JSON from a sync log.
     */
    fun parsePreviousDataJson(log: SyncLogEntity): Map<String, Any?>? {
        if (log.previousDataJson == null) return null
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        return gson.fromJson(log.previousDataJson, type)
    }
    
    /**
     * Gets all pending (not uploaded) logs.
     */
    suspend fun getPendingLogs(): List<SyncLogEntity> {
        return syncLogDao.getPendingLogs()
    }
    
    /**
     * Marks a log as successfully uploaded.
     */
    suspend fun markUploaded(logId: String, telegramMessageId: Long) {
        syncLogDao.markUploaded(logId, telegramMessageId)
        Log.d(TAG, "Marked log $logId as uploaded (message: $telegramMessageId)")
    }
    
    /**
     * Stores a remote log that was downloaded from Telegram.
     */
    suspend fun storeRemoteLog(log: SyncLogEntity) {
        // Check if we already have this log
        if (syncLogDao.exists(log.logId)) {
            Log.d(TAG, "Log ${log.logId} already exists, skipping")
            return
        }
        
        // Store the log as already uploaded (since it came from remote)
        val remoteLog = log.copy(isUploaded = true)
        syncLogDao.insert(remoteLog)
        Log.d(TAG, "Stored remote log: ${log.logId}")
    }
    
    /**
     * Verifies the integrity of a sync log using its checksum.
     */
    fun verifyIntegrity(log: SyncLogEntity): Boolean {
        if (log.checksum == null) return true // No checksum to verify
        
        val logContent = "${log.logId}|${log.timestamp}|${log.deviceId}|${log.operation}|${log.tableName}|${log.primaryKey}|${log.dataJson}|${log.previousDataJson}"
        val expectedChecksum = SyncCrypto.generateChecksum(logContent.toByteArray(Charsets.UTF_8))
        
        return log.checksum == expectedChecksum
    }
    
    /**
     * Gets the current device ID.
     */
    fun getDeviceId(): String = deviceId
    
    /**
     * Updates the last applied log ID in metadata.
     */
    suspend fun updateLastAppliedLogId(logId: String) {
        syncMetadataDao.setValue(
            SyncMetadataEntity(
                key = SyncMetadataEntity.KEY_LAST_APPLIED_LOG_ID,
                value = logId
            )
        )
    }
    
    /**
     * Gets the last applied log ID.
     */
    suspend fun getLastAppliedLogId(): String? {
        return syncMetadataDao.getLastAppliedLogId()
    }
}
