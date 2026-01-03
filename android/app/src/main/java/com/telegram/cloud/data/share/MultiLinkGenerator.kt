package com.telegram.cloud.data.share

import android.util.Log
import com.telegram.cloud.data.local.CloudFileEntity
import com.telegram.cloud.data.repository.TelegramRepository
import com.telegram.cloud.domain.model.CloudFile
import com.telegram.cloud.gallery.GalleryMediaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Wrapper for generating multi-file .link files
 */
class MultiLinkGenerator(
    private val shareLinkManager: ShareLinkManager,
    private val repository: TelegramRepository
) {
    companion object {
        private const val TAG = "MultiLinkGenerator"
    }

    /**
     * Generate .link for Gallery items
     */
    suspend fun generateForGallery(
        mediaList: List<GalleryMediaEntity>,
        password: String,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (password.isBlank()) {
                Log.e(TAG, "Password cannot be empty")
                return@withContext false
            }

            val cfg = repository.config.first()
            if (cfg == null) {
                Log.e(TAG, "No config available")
                return@withContext false
            }

            val entities = mutableListOf<CloudFileEntity>()
            val botTokens = mutableListOf<String>()

            for (media in mediaList) {
                if (!media.isSynced) continue

                val entity = repository.getFileEntityFromGallery(media)
                if (entity != null) {
                    entities.add(entity)
                    val token = media.telegramUploaderTokens?.split(",")?.firstOrNull()
                        ?: cfg.tokens.firstOrNull() ?: ""
                    botTokens.add(token)
                }
            }

            if (entities.isEmpty()) return@withContext false

            if (entities.size == 1) {
                Log.i(TAG, "Generating single .link file for gallery item")
                val success = shareLinkManager.generateLinkFile(entities.first(), botTokens.first(), password, outputFile)
                if (success) {
                    try {
                        val debugRead = shareLinkManager.readLinkFile(outputFile, password)
                        if (debugRead == null) {
                            Log.e(TAG, "DEBUG: Immediate readback FAILED for single link (Gallery)!")
                        } else {
                            Log.i(TAG, "DEBUG: Immediate readback SUCCESS. Type=${debugRead.type}, Files=${debugRead.files.size}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "DEBUG: Immediate readback EXCEPTION", e)
                    }
                }
                success
            } else {
                shareLinkManager.generateBatchLinkFile(entities, botTokens, password, outputFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating gallery link", e)
            false
        }
    }

    /**
     * Generate .link for Dashboard items
     */
    suspend fun generateForDashboard(
        files: List<CloudFile>,
        password: String,
        outputFile: File
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "generateForDashboard: Starting generation for ${files.size} files, output=${outputFile.name}")
            
            if (password.isBlank()) {
                Log.e(TAG, "generateForDashboard: Password cannot be empty")
                return@withContext false
            }

            val cfg = repository.config.first()
            if (cfg == null) {
                Log.e(TAG, "generateForDashboard: No config available")
                return@withContext false
            }

            val entities = mutableListOf<CloudFileEntity>()
            val botTokens = mutableListOf<String>()

            for (file in files) {
                val entity = repository.getFileEntity(file.id)
                if (entity != null) {
                    entities.add(entity)
                    // For dashboard files, we might need to check where the token comes from.
                    // Usually it's in the entity or we use default.
                    // CloudFileEntity has uploaderTokens? Yes, let's check.
                    val token = entity.uploaderTokens?.split(",")?.firstOrNull()
                        ?.takeIf { it.isNotBlank() }
                        ?: cfg.tokens.firstOrNull() 
                        ?: ""
                    
                    if (token.isBlank()) {
                         Log.w(TAG, "generateForDashboard: No token found for file ${entity.fileName}, using empty string")
                    }
                    botTokens.add(token)
                } else {
                    Log.e(TAG, "generateForDashboard: Entity not found for file ID=${file.id}, name=${file.fileName}")
                }
            }

            if (entities.isEmpty()) {
                Log.e(TAG, "generateForDashboard: No valid entities found (all calls to getFileEntity failed)")
                return@withContext false
            }

            Log.d(TAG, "generateForDashboard: password length = ${password.length}, password = '$password'")
            Log.d(TAG, "generateForDashboard: found ${entities.size} entities")
            
            if (entities.size == 1) {
                Log.i(TAG, "Generating single .link file for dashboard item: ${entities.first().fileName}")
                val success = shareLinkManager.generateLinkFile(entities.first(), botTokens.first(), password, outputFile)
                if (success) {
                    try {
                        val debugRead = shareLinkManager.readLinkFile(outputFile, password)
                        if (debugRead == null) {
                            Log.e(TAG, "DEBUG: Immediate readback FAILED for single link (Dashboard)!")
                        } else {
                            Log.i(TAG, "DEBUG: Immediate readback SUCCESS. Type=${debugRead.type}, Files=${debugRead.files.size}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "DEBUG: Immediate readback EXCEPTION", e)
                    }
                } else {
                    Log.e(TAG, "generateForDashboard: shareLinkManager.generateLinkFile returned false")
                }
                success
            } else {
                Log.i(TAG, "Generating batch .link file for ${entities.size} items")
                val success = shareLinkManager.generateBatchLinkFile(entities, botTokens, password, outputFile)
                if (!success) {
                    Log.e(TAG, "generateForDashboard: shareLinkManager.generateBatchLinkFile returned false")
                }
                success
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error generating dashboard link", e)
            false
        }
    }
}
