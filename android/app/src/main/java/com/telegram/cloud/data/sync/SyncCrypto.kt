package com.telegram.cloud.data.sync

import android.util.Base64
import android.util.Log
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * AES-256-GCM encryption utilities for database sync logs.
 * Uses PBKDF2 for key derivation from the user's password.
 */
object SyncCrypto {
    private const val TAG = "SyncCrypto"
    
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val KEY_ALGORITHM = "AES"
    private const val KEY_SIZE = 256
    private const val IV_SIZE = 12  // 96 bits for GCM
    private const val TAG_SIZE = 128  // Authentication tag size in bits
    private const val SALT_SIZE = 16
    private const val KDF_ITERATIONS = 100000
    private const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"
    
    private val secureRandom = SecureRandom()
    
    /**
     * Derives an AES-256 key from password using PBKDF2.
     * 
     * @param password The user's sync password
     * @param salt Random salt for key derivation
     * @return The derived secret key
     */
    fun deriveKey(password: String, salt: ByteArray): SecretKey {
        val keySpec = PBEKeySpec(
            password.toCharArray(),
            salt,
            KDF_ITERATIONS,
            KEY_SIZE
        )
        val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
        val keyBytes = factory.generateSecret(keySpec).encoded
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }
    
    /**
     * Encrypts data using AES-256-GCM.
     * Output format: [salt (16 bytes)][iv (12 bytes)][ciphertext + auth tag]
     * 
     * @param data The plaintext data to encrypt
     * @param password The encryption password
     * @return Encrypted data with salt and IV prepended
     */
    fun encrypt(data: ByteArray, password: String): ByteArray {
        try {
            // Generate random salt and IV
            val salt = ByteArray(SALT_SIZE).also { secureRandom.nextBytes(it) }
            val iv = ByteArray(IV_SIZE).also { secureRandom.nextBytes(it) }
            
            // Derive key from password
            val key = deriveKey(password, salt)
            
            // Initialize cipher
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_SIZE, iv)
            cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
            
            // Encrypt
            val ciphertext = cipher.doFinal(data)
            
            // Combine salt + iv + ciphertext
            val result = ByteArray(salt.size + iv.size + ciphertext.size)
            System.arraycopy(salt, 0, result, 0, salt.size)
            System.arraycopy(iv, 0, result, salt.size, iv.size)
            System.arraycopy(ciphertext, 0, result, salt.size + iv.size, ciphertext.size)
            
            return result
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            throw SyncCryptoException("Failed to encrypt data", e)
        }
    }
    
    /**
     * Decrypts data that was encrypted with [encrypt].
     * Expects format: [salt (16 bytes)][iv (12 bytes)][ciphertext + auth tag]
     * 
     * @param encryptedData The encrypted data with salt and IV
     * @param password The decryption password
     * @return Decrypted plaintext data
     * @throws SyncCryptoException if decryption fails (wrong password or corrupted data)
     */
    fun decrypt(encryptedData: ByteArray, password: String): ByteArray {
        try {
            if (encryptedData.size < SALT_SIZE + IV_SIZE + TAG_SIZE / 8) {
                throw SyncCryptoException("Encrypted data too short")
            }
            
            // Extract salt, IV, and ciphertext
            val salt = encryptedData.copyOfRange(0, SALT_SIZE)
            val iv = encryptedData.copyOfRange(SALT_SIZE, SALT_SIZE + IV_SIZE)
            val ciphertext = encryptedData.copyOfRange(SALT_SIZE + IV_SIZE, encryptedData.size)
            
            // Derive key from password
            val key = deriveKey(password, salt)
            
            // Initialize cipher
            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_SIZE, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            
            // Decrypt
            return cipher.doFinal(ciphertext)
        } catch (e: javax.crypto.AEADBadTagException) {
            Log.e(TAG, "Decryption failed - wrong password or corrupted data")
            throw SyncCryptoException("Decryption failed - invalid password or corrupted data", e)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            throw SyncCryptoException("Failed to decrypt data", e)
        }
    }
    
    /**
     * Generates a SHA-256 checksum for data integrity verification.
     * 
     * @param data The data to checksum
     * @return Hex-encoded SHA-256 hash
     */
    fun generateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return hash.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Verifies a checksum against data.
     * 
     * @param data The data to verify
     * @param expectedChecksum The expected checksum
     * @return true if checksum matches
     */
    fun verifyChecksum(data: ByteArray, expectedChecksum: String): Boolean {
        return generateChecksum(data) == expectedChecksum
    }
    
    /**
     * Encodes encrypted data to Base64 for text-safe storage/transmission.
     */
    fun encodeToBase64(data: ByteArray): String {
        return Base64.encodeToString(data, Base64.NO_WRAP)
    }
    
    /**
     * Decodes Base64 string back to bytes.
     */
    fun decodeFromBase64(base64: String): ByteArray {
        return Base64.decode(base64, Base64.NO_WRAP)
    }
}

/**
 * Exception thrown when encryption/decryption operations fail.
 */
class SyncCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)
