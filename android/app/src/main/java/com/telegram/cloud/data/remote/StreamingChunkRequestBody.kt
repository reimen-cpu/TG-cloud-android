package com.telegram.cloud.data.remote

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okio.BufferedSink
import java.io.IOException
import java.security.MessageDigest

private const val TAG = "StreamingChunkRequestBody"
private const val BUFFER_SIZE = 8192 // 8KB buffer - small memory footprint

/**
 * A streaming RequestBody that reads directly from a ContentResolver InputStream
 * without loading the entire chunk into memory.
 * 
 * Benefits:
 * - Memory usage per chunk: ~8KB (buffer) instead of ~8MB (full chunk + copy)
 * - With 6 parallel uploads: ~48KB total vs ~48MB
 * - No GC pressure from large allocations
 * - Infinitely scalable for any file size
 * 
 * The RequestBody reads from [offset] and writes [length] bytes to the sink,
 * using a small buffer for streaming. Hash is calculated during the first
 * read pass if requested.
 */
class StreamingChunkRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val offset: Long,
    private val length: Int,
    private val mediaType: String = "application/octet-stream"
) : RequestBody() {

    // Cached hash - calculated on first writeTo if needed
    private var calculatedHash: String? = null
    private var hashCalculated = false

    override fun contentType(): MediaType? = mediaType.toMediaTypeOrNull()

    override fun contentLength(): Long = length.toLong()

    /**
     * Writes the chunk to the sink by streaming from the InputStream.
     * Only keeps [BUFFER_SIZE] bytes in memory at any time.
     */
    override fun writeTo(sink: BufferedSink) {
        Log.d(TAG, "writeTo: Starting streaming write, offset=$offset, length=$length")
        
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open input stream for URI: $uri")
        
        inputStream.use { stream ->
            // Skip to the chunk offset using small buffer
            skipToOffset(stream, offset)
            
            // Stream the chunk data to sink
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = length
            var totalWritten = 0
            
            while (remaining > 0) {
                val toRead = minOf(buffer.size, remaining)
                val bytesRead = stream.read(buffer, 0, toRead)
                
                if (bytesRead == -1) {
                    Log.w(TAG, "writeTo: EOF reached early at $totalWritten/$length bytes")
                    break
                }
                
                sink.write(buffer, 0, bytesRead)
                remaining -= bytesRead
                totalWritten += bytesRead
            }
            
            sink.flush()
            Log.d(TAG, "writeTo: Completed, wrote $totalWritten bytes")
        }
    }

    /**
     * Calculates the SHA-256 hash of the chunk using streaming.
     * This reads the chunk once to calculate the hash, using minimal memory.
     * Call this before the actual upload to get the hash for the caption.
     */
    fun calculateHash(): String {
        if (hashCalculated) {
            return calculatedHash ?: ""
        }
        
        Log.d(TAG, "calculateHash: Starting streaming hash calculation")
        
        val inputStream = contentResolver.openInputStream(uri)
            ?: throw IOException("Cannot open input stream for hash calculation")
        
        val hash = inputStream.use { stream ->
            // Skip to offset
            skipToOffset(stream, offset)
            
            // Calculate hash while reading
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER_SIZE)
            var remaining = length
            
            while (remaining > 0) {
                val toRead = minOf(buffer.size, remaining)
                val bytesRead = stream.read(buffer, 0, toRead)
                
                if (bytesRead == -1) break
                
                digest.update(buffer, 0, bytesRead)
                remaining -= bytesRead
            }
            
            // Convert to hex string (first 16 chars like original)
            digest.digest().joinToString("") { "%02x".format(it) }.take(16)
        }
        
        calculatedHash = hash
        hashCalculated = true
        Log.d(TAG, "calculateHash: Hash calculated: $hash")
        
        return hash
    }

    /**
     * Resets the hash cache, allowing recalculation if needed.
     */
    fun resetHashCache() {
        calculatedHash = null
        hashCalculated = false
    }

    companion object {
        /**
         * Skips to the specified offset in the stream using a small buffer.
         * This is needed because some InputStreams don't support skip() well.
         */
        private fun skipToOffset(stream: java.io.InputStream, offset: Long) {
            if (offset <= 0) return
            
            val skipBuffer = ByteArray(BUFFER_SIZE)
            var skipped = 0L
            
            while (skipped < offset) {
                val toRead = minOf(skipBuffer.size.toLong(), offset - skipped).toInt()
                val bytesRead = stream.read(skipBuffer, 0, toRead)
                
                if (bytesRead == -1) {
                    throw IOException("EOF while seeking to offset $offset (at $skipped)")
                }
                
                skipped += bytesRead
            }
            
            Log.d(TAG, "skipToOffset: Skipped $skipped bytes")
        }
    }
}
