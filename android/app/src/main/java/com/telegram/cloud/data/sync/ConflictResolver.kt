package com.telegram.cloud.data.sync

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Handles conflict detection and resolution when applying remote sync logs.
 * Implements timestamp-based ordering and field-level merge when possible.
 */
class ConflictResolver {
    companion object {
        private const val TAG = "ConflictResolver"
    }
    
    private val gson = Gson()
    
    /**
     * Detects if applying a log would cause a conflict with existing logs.
     * 
     * @param incomingLog The remote log to apply
     * @param existingLogs Recent logs affecting the same record
     * @return The type of conflict detected
     */
    fun detectConflict(
        incomingLog: SyncLogEntity,
        existingLogs: List<SyncLogEntity>
    ): ConflictType {
        if (existingLogs.isEmpty()) {
            return ConflictType.NONE
        }
        
        // Find logs that are newer than the incoming log
        val newerLogs = existingLogs.filter { it.timestamp > incomingLog.timestamp }
        if (newerLogs.isEmpty()) {
            return ConflictType.NONE
        }
        
        // Check for delete conflicts
        val hasLocalDelete = newerLogs.any { it.operation == SyncOperation.DELETE }
        val isIncomingDelete = incomingLog.operation == SyncOperation.DELETE
        
        if (hasLocalDelete || isIncomingDelete) {
            Log.w(TAG, "Delete conflict detected for ${incomingLog.tableName}[${incomingLog.primaryKey}]")
            return ConflictType.DELETE_CONFLICT
        }
        
        // For updates, check if the same fields are modified
        if (incomingLog.operation == SyncOperation.UPDATE) {
            val incomingFields = getModifiedFields(incomingLog)
            for (localLog in newerLogs) {
                if (localLog.operation == SyncOperation.UPDATE) {
                    val localFields = getModifiedFields(localLog)
                    val conflictingFields = incomingFields.intersect(localFields)
                    if (conflictingFields.isNotEmpty()) {
                        Log.w(TAG, "Field conflict on: ${conflictingFields.joinToString()}")
                        return ConflictType.FIELD_CONFLICT
                    }
                }
            }
        }
        
        return ConflictType.NONE
    }
    
    /**
     * Attempts to automatically resolve a conflict.
     * 
     * @param incomingLog The remote log
     * @param localLog The conflicting local log
     * @return The resolution result
     */
    fun resolveConflict(
        incomingLog: SyncLogEntity,
        localLog: SyncLogEntity
    ): ResolutionResult {
        // Strategy: Last-write-wins based on timestamp
        return if (incomingLog.timestamp > localLog.timestamp) {
            Log.d(TAG, "Resolving conflict: using remote (newer)")
            ResolutionResult.UseRemote(incomingLog)
        } else {
            Log.d(TAG, "Resolving conflict: keeping local (newer)")
            ResolutionResult.UseLocal(localLog)
        }
    }
    
    /**
     * Attempts to merge two UPDATE operations when they modify different fields.
     * 
     * @param incomingLog The remote update log
     * @param localLog The local update log
     * @return Merged data if possible, null if fields conflict
     */
    fun tryMerge(
        incomingLog: SyncLogEntity,
        localLog: SyncLogEntity
    ): Map<String, Any?>? {
        if (incomingLog.operation != SyncOperation.UPDATE || 
            localLog.operation != SyncOperation.UPDATE) {
            return null
        }
        
        val incomingData = parseJson(incomingLog.dataJson) ?: return null
        val localData = parseJson(localLog.dataJson) ?: return null
        val incomingPrevious = parseJson(incomingLog.previousDataJson) ?: emptyMap()
        val localPrevious = parseJson(localLog.previousDataJson) ?: emptyMap()
        
        // Find fields that changed in each log
        val incomingChanges = incomingData.filter { (k, v) -> incomingPrevious[k] != v }
        val localChanges = localData.filter { (k, v) -> localPrevious[k] != v }
        
        // Check for overlapping changes
        val conflictingKeys = incomingChanges.keys.intersect(localChanges.keys)
        if (conflictingKeys.isNotEmpty()) {
            // Can't merge if same fields were changed
            Log.w(TAG, "Cannot merge: conflicting fields $conflictingKeys")
            return null
        }
        
        // Merge: start with local data, apply incoming changes
        val merged = localData.toMutableMap()
        merged.putAll(incomingChanges)
        
        Log.d(TAG, "Successfully merged changes")
        return merged
    }
    
    /**
     * Orders logs for sequential application based on timestamp.
     */
    fun orderByTimestamp(logs: List<SyncLogEntity>): List<SyncLogEntity> {
        return logs.sortedBy { it.timestamp }
    }
    
    /**
     * Groups logs by table and primary key.
     */
    fun groupByRecord(logs: List<SyncLogEntity>): Map<String, List<SyncLogEntity>> {
        return logs.groupBy { "${it.tableName}:${it.primaryKey}" }
    }
    
    /**
     * Gets the fields that were modified in an UPDATE log.
     */
    private fun getModifiedFields(log: SyncLogEntity): Set<String> {
        if (log.operation != SyncOperation.UPDATE) return emptySet()
        
        val current = parseJson(log.dataJson) ?: return emptySet()
        val previous = parseJson(log.previousDataJson) ?: return current.keys
        
        return current.filter { (k, v) -> previous[k] != v }.keys
    }
    
    private fun parseJson(json: String?): Map<String, Any?>? {
        if (json == null) return null
        return try {
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            gson.fromJson(json, type)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse JSON", e)
            null
        }
    }
}

/**
 * Types of conflicts that can occur during sync.
 */
enum class ConflictType {
    /** No conflict detected */
    NONE,
    /** Same field was modified by both local and remote */
    FIELD_CONFLICT,
    /** One side deleted while other modified */
    DELETE_CONFLICT,
    /** Conflict that cannot be automatically resolved */
    IRRECONCILABLE
}

/**
 * Result of conflict resolution.
 */
sealed class ResolutionResult {
    /** Successfully merged both changes */
    data class Merged(val mergedData: Map<String, Any?>) : ResolutionResult()
    /** Use the remote log */
    data class UseRemote(val log: SyncLogEntity) : ResolutionResult()
    /** Keep the local log */
    data class UseLocal(val log: SyncLogEntity) : ResolutionResult()
    /** Conflict rejected - requires manual resolution */
    object Rejected : ResolutionResult()
}
