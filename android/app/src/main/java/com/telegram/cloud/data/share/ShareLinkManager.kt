package com.telegram.cloud.data.share

import android.content.Context
import android.net.Uri
import android.util.Log
import com.telegram.cloud.data.local.CloudFileEntity
import com.telegram.cloud.data.remote.ChunkedDownloadManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages encrypted .link files compatible with desktop application
 * Format: salt(16) + iv(16) + encrypted_json
 * Encryption: AES-256-CBC with PBKDF2-HMAC-SHA256 key derivation (10000 iterations)
 * 
 * Compatible with:
 * - telegram-cloud-cpp/src/universallinkgenerator.cpp
 * - telegram-cloud-cpp/src/universallinkdownloader.cpp
 */
class ShareLinkManager {
    
    companion object {
        private const val TAG = "ShareLinkManager"
        private const val PBKDF2_ITERATIONS = 10000  // Must match desktop
        private const val KEY_LENGTH = 256
        private const val SALT_LENGTH = 16
        private const val IV_LENGTH = 16
        private const val VERSION = "1.0"
    }
    
    // Data class for parsed share link
    data class ShareLinkData(
        val version: String,
        val type: String, // "single" or "batch"
        val files: List<SharedFileInfo>
    )
    
    data class SharedFileInfo(
        val fileId: String,
        val fileName: String,
        val fileSize: Long,
        val mimeType: String,
        val category: String,  // "file" or "chunked"
        val uploadDate: String,
        val telegramFileId: String,
        val uploaderBotToken: String,
        val isEncrypted: Boolean,
        val chunks: List<SharedChunkInfo>?
    )
    
    data class SharedChunkInfo(
        val chunkNumber: Int,
        val totalChunks: Int,
        val chunkSize: Long,
        val chunkHash: String,
        val telegramFileId: String,
        val uploaderBotToken: String
    )
    
    /**
     * Generate .link file for a single file (compatible with desktop)
     * @param file The file entity to share
     * @param botToken The bot token used for this file
     * @param password User-defined password for encryption
     * @param outputFile The .link file to create
     */
    fun generateLinkFile(
        file: CloudFileEntity,
        botToken: String,
        password: String,
        outputFile: File
    ): Boolean {
        return try {
            Log.i(TAG, "Generating .link file for: ${file.fileName}")
            
            val isChunked = ChunkedDownloadManager.isChunkedFile(file.caption)
            val chunks = if (isChunked) parseChunksFromEntity(file, botToken) else null
            
            val jsonData = buildSingleFileJson(file, botToken, chunks)
            Log.d(TAG, "JSON data: $jsonData")
            
            val encryptedData = encryptData(jsonData, password)
            
            FileOutputStream(outputFile).use { fos ->
                fos.write(encryptedData)
            }
            
            Log.i(TAG, "Link file created: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate link file", e)
            false
        }
    }
    
    /**
     * Generate .link file for multiple files (batch - compatible with desktop)
     */
    fun generateBatchLinkFile(
        files: List<CloudFileEntity>,
        botTokens: List<String>,
        password: String,
        outputFile: File
    ): Boolean {
        return try {
            Log.i(TAG, "Generating batch .link file for ${files.size} files")
            Log.d(TAG, "generateBatchLinkFile: password length = ${password.length}, password = '$password'")
            
            val jsonData = buildBatchJson(files, botTokens)
            val encryptedData = encryptData(jsonData, password)
            
            FileOutputStream(outputFile).use { fos ->
                fos.write(encryptedData)
            }
            
            Log.i(TAG, "Batch link file created: ${outputFile.absolutePath}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate batch link file", e)
            false
        }
    }
    
    /**
     * Read and decrypt a .link file (compatible with desktop-generated files)
     */
    fun readLinkFile(
        linkFile: File,
        password: String
    ): ShareLinkData? {
        return try {
            Log.i(TAG, "Reading .link file: ${linkFile.absolutePath}")
            
            val encryptedData = linkFile.readBytes()
            val jsonData = decryptData(encryptedData, password)
            
            Log.d(TAG, "Decrypted JSON: $jsonData")
            parseJsonData(jsonData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read link file", e)
            null
        }
    }
    
    /**
     * Read .link file from content URI
     */
    fun readLinkFile(
        context: Context,
        uri: Uri,
        password: String
    ): ShareLinkData? {
        return try {
            Log.i(TAG, "Reading .link file from URI: $uri")
            
            val encryptedData = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw Exception("Cannot open URI")
            
            val jsonData = decryptData(encryptedData, password)
            parseJsonData(jsonData)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read link file from URI", e)
            null
        }
    }
    
    /**
     * Get file info preview from .link file without full parsing
     */
    fun getLinkFileInfo(
        linkFile: File,
        password: String
    ): List<SharedFileInfo>? {
        return readLinkFile(linkFile, password)?.files
    }
    
    // ==================== JSON Building (matches desktop format) ====================
    
    private fun buildSingleFileJson(
        file: CloudFileEntity,
        botToken: String,
        chunks: List<SharedChunkInfo>?
    ): String {
        val json = JSONObject().apply {
            put("version", VERSION)
            put("type", "single")
            put("file", buildFileJsonObject(file, botToken, chunks))
        }
        return json.toString()
    }
    
    private fun buildBatchJson(
        files: List<CloudFileEntity>,
        botTokens: List<String>
    ): String {
        val json = JSONObject().apply {
            put("version", VERSION)
            put("type", "batch")
            
            val filesArray = JSONArray()
            files.forEachIndexed { index, file ->
                val botToken = botTokens.getOrElse(index % botTokens.size) { botTokens.first() }
                val isChunked = ChunkedDownloadManager.isChunkedFile(file.caption)
                val chunks = if (isChunked) parseChunksFromEntity(file, botToken) else null
                
                filesArray.put(buildFileJsonObject(file, botToken, chunks))
            }
            put("files", filesArray)
        }
        return json.toString()
    }
    
    private fun buildFileJsonObject(
        file: CloudFileEntity,
        botToken: String,
        chunks: List<SharedChunkInfo>?
    ): JSONObject {
        return JSONObject().apply {
            // Field names must match desktop exactly
            // fileId should be a short identifier (UUID), NOT the telegram file IDs
            // For chunked files, file.fileId is the UUID, file.fileUniqueId contains telegram file IDs
            val isChunked = chunks != null
            val shortFileId = if (isChunked) {
                // For chunked files, use the UUID stored in fileId
                file.fileId
            } else {
                // For direct files, fileUniqueId is the telegram unique ID (short)
                file.fileUniqueId ?: file.fileId
            }
            
            put("fileId", shortFileId)
            put("fileName", file.fileName)
            put("fileSize", file.sizeBytes)
            put("mimeType", file.mimeType ?: "application/octet-stream")
            put("category", if (isChunked) "chunked" else "file")
            put("uploadDate", formatDate(file.uploadedAt))
            // telegramFileId: for direct files it's the actual telegram file_id, for chunked it's not used (chunks have their own)
            put("telegramFileId", if (isChunked) "" else file.fileId)
            put("uploaderBotToken", botToken)
            put("isEncrypted", false)
            
            if (chunks != null && chunks.isNotEmpty()) {
                val chunksArray = JSONArray()
                chunks.forEach { chunk ->
                    chunksArray.put(JSONObject().apply {
                        put("chunkNumber", chunk.chunkNumber)
                        put("totalChunks", chunk.totalChunks)
                        put("chunkSize", chunk.chunkSize)
                        put("chunkHash", chunk.chunkHash)
                        put("telegramFileId", chunk.telegramFileId)
                        put("uploaderBotToken", chunk.uploaderBotToken)
                    })
                }
                put("chunks", chunksArray)
            }
        }
    }
    
    private fun parseChunksFromEntity(file: CloudFileEntity, defaultBotToken: String): List<SharedChunkInfo>? {
        val caption = file.caption ?: return null
        val chunkCount = ChunkedDownloadManager.getChunkCount(caption) ?: return null
        val telegramFileIds = file.fileUniqueId?.split(",") ?: return null
        
        // Get per-chunk bot tokens (stored during upload)
        val uploaderTokens = file.uploaderTokens?.split(",") ?: emptyList()
        
        return telegramFileIds.mapIndexed { index, telegramFileId ->
            // Use stored token for this chunk, or default if not available
            val chunkToken = uploaderTokens.getOrNull(index)?.trim()?.takeIf { it.isNotEmpty() }
                ?: defaultBotToken
            
            SharedChunkInfo(
                chunkNumber = index,
                totalChunks = chunkCount,
                chunkSize = 4 * 1024 * 1024L, // 4MB
                chunkHash = "",
                telegramFileId = telegramFileId.trim(),
                uploaderBotToken = chunkToken
            )
        }
    }
    
    // ==================== JSON Parsing (compatible with desktop) ====================
    
    private fun parseJsonData(jsonString: String): ShareLinkData {
        val json = JSONObject(jsonString)
        val version = json.optString("version", "1.0")
        val type = json.getString("type")
        
        val files = mutableListOf<SharedFileInfo>()
        
        when (type) {
            "single" -> {
                val fileJson = json.getJSONObject("file")
                files.add(parseFileJson(fileJson))
            }
            "batch" -> {
                val filesArray = json.getJSONArray("files")
                for (i in 0 until filesArray.length()) {
                    files.add(parseFileJson(filesArray.getJSONObject(i)))
                }
            }
        }
        
        return ShareLinkData(version, type, files)
    }
    
    private fun parseFileJson(json: JSONObject): SharedFileInfo {
        val chunks = if (json.has("chunks")) {
            val chunksArray = json.getJSONArray("chunks")
            (0 until chunksArray.length()).map { i ->
                val chunkJson = chunksArray.getJSONObject(i)
                SharedChunkInfo(
                    chunkNumber = chunkJson.getInt("chunkNumber"),
                    totalChunks = chunkJson.getInt("totalChunks"),
                    chunkSize = chunkJson.getLong("chunkSize"),
                    chunkHash = chunkJson.optString("chunkHash", ""),
                    telegramFileId = chunkJson.getString("telegramFileId"),
                    uploaderBotToken = chunkJson.optString("uploaderBotToken", "")
                )
            }
        } else null
        
        return SharedFileInfo(
            fileId = json.getString("fileId"),
            fileName = json.getString("fileName"),
            fileSize = json.getLong("fileSize"),
            mimeType = json.optString("mimeType", "application/octet-stream"),
            category = json.optString("category", "file"),
            uploadDate = json.optString("uploadDate", ""),
            telegramFileId = json.getString("telegramFileId"),
            uploaderBotToken = json.optString("uploaderBotToken", ""),
            isEncrypted = json.optBoolean("isEncrypted", false),
            chunks = chunks
        )
    }
    
    // ==================== Encryption (matches desktop exactly) ====================
    
    /**
     * Encrypt data using AES-256-CBC with PBKDF2-HMAC-SHA256
     * Format: salt(16) + iv(16) + ciphertext
     * Compatible with desktop's UniversalLinkGenerator::encryptData
     */
    private fun encryptData(data: String, password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { SecureRandom().nextBytes(it) }
        
        val key = deriveKey(password, salt)
        
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        
        // Combine: salt + iv + encrypted (matches desktop format)
        return salt + iv + encrypted
    }
    
    /**
     * Decrypt data using AES-256-CBC with PBKDF2-HMAC-SHA256
     * Compatible with desktop's UniversalLinkDownloader::decryptData
     */
    private fun decryptData(data: ByteArray, password: String): String {
        if (data.size < SALT_LENGTH + IV_LENGTH) {
            throw IllegalArgumentException("Invalid link file format (file too short)")
        }
        
        val salt = data.copyOfRange(0, SALT_LENGTH)
        val iv = data.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val encrypted = data.copyOfRange(SALT_LENGTH + IV_LENGTH, data.size)
        
        if (encrypted.isEmpty()) {
            throw IllegalArgumentException("Invalid link file format (no encrypted data)")
        }
        
        val key = deriveKey(password, salt)
        
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))
        
        return try {
            val decrypted = cipher.doFinal(encrypted)
            String(decrypted, Charsets.UTF_8)
        } catch (e: Exception) {
            throw IllegalArgumentException("Wrong password or corrupted link file", e)
        }
    }
    
    /**
     * Derive key using PBKDF2-HMAC-SHA256 with 10000 iterations
     * Must match desktop's deriveKey exactly
     */
    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        if (password.isEmpty()) {
            throw IllegalArgumentException("Password cannot be empty")
        }
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH)
        val tmp = factory.generateSecret(spec)
        return SecretKeySpec(tmp.encoded, "AES")
    }
    
    private fun formatDate(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        return sdf.format(java.util.Date(timestamp))
    }
}
