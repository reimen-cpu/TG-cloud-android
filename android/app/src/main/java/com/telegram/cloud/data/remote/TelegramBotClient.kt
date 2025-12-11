package com.telegram.cloud.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.json.JSONArray
import java.io.InputStream
import java.io.OutputStream
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "TelegramBotClient"
private const val RATE_LIMIT_WINDOW_MS = 60_000L
private const val MAX_MESSAGES_PER_CHAT_PER_MINUTE = 20
private const val RATE_LIMIT_INTERVAL_MS = RATE_LIMIT_WINDOW_MS / MAX_MESSAGES_PER_CHAT_PER_MINUTE // minimal interval cap (~3s)

private class TokenRateLimiter(
    private val maxMessagesPerWindow: Int = MAX_MESSAGES_PER_CHAT_PER_MINUTE,
    private val windowMillis: Long = RATE_LIMIT_WINDOW_MS
) {
    private val messageTimestamps = ConcurrentHashMap<String, ArrayDeque<Long>>()
    private val channelLocks = ConcurrentHashMap<String, Mutex>()

    private fun buildKey(token: String, channelId: String) = "$token::$channelId"

    suspend fun <T> execute(token: String, channelId: String, block: suspend () -> T): T {
        val key = buildKey(token, channelId)
        val mutex = channelLocks.computeIfAbsent(key) { Mutex() }
        return mutex.withLock {
            val queue = messageTimestamps.computeIfAbsent(key) { ArrayDeque() }
            while (true) {
            val now = System.currentTimeMillis()
                while (queue.isNotEmpty() && now - queue.first >= windowMillis) {
                    queue.removeFirst()
                }
                if (queue.size < maxMessagesPerWindow) {
                    queue.addLast(now)
                    break
                }
                val waitMillis = windowMillis - (now - queue.first)
                Log.w(
                    TAG,
                    "TokenRateLimiter: limit reached for $channelId (token=${token.take(20)}); waiting ${waitMillis}ms"
                )
                delay(waitMillis)
            }
            try {
                block()
            } finally {
                // Ensure we don't accumulate stale queues
                if (queue.isEmpty()) {
                    messageTimestamps.remove(key)
                }
    }
        }
    }

}

data class TelegramMessage(
    val messageId: Long,
    val document: TelegramDocument?
)

data class TelegramDocument(
    val fileId: String,
    val fileUniqueId: String,
    val fileName: String?,
    val mimeType: String?,
    val fileSize: Long?
)

data class TelegramFile(
    val fileId: String,
    val fileUniqueId: String,
    val filePath: String,
    val fileSize: Long?
)

class TelegramBotClient(
    private val token: String? = null,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(600, TimeUnit.SECONDS) // 10 minutes for large file uploads
        .connectTimeout(120, TimeUnit.SECONDS) // 2 minutes for connection
        .readTimeout(600, TimeUnit.SECONDS) // 10 minutes for reading large responses
        .writeTimeout(600, TimeUnit.SECONDS) // 10 minutes for writing large files
        .build(),
    // Separate client for chunks with 1 minute timeout
    private val chunkHttpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS) // 1 minute for chunk uploads
        .connectTimeout(30, TimeUnit.SECONDS) // 30 seconds for connection
        .readTimeout(60, TimeUnit.SECONDS) // 1 minute for reading
        .writeTimeout(60, TimeUnit.SECONDS) // 1 minute for writing chunks
        .build()
) {

    private val rateLimiter = TokenRateLimiter()

    suspend fun sendDocument(
        token: String,
        channelId: String,
        caption: String?,
        fileName: String,
        mimeType: String?,
        streamProvider: () -> InputStream,
        totalBytes: Long = -1L,
        onProgress: ((Long, Long) -> Unit)? = null
    ): TelegramMessage =
        rateLimiter.execute(token, channelId) {
            withContext(Dispatchers.IO) {
                val url = "https://api.telegram.org/bot$token/sendDocument"
                Log.i(TAG, "sendDocument: Starting upload")
                Log.i(TAG, "sendDocument: token=${token.take(20)}..., channelId=$channelId")
                Log.i(TAG, "sendDocument: fileName=$fileName, mimeType=$mimeType")
                Log.i(TAG, "sendDocument: totalBytes=$totalBytes")

                val documentBody = object : RequestBody() {
                    override fun contentType() = mimeType?.toMediaTypeOrNull()
                    override fun contentLength(): Long = if (totalBytes > 0) totalBytes else -1L
                    override fun writeTo(sink: okio.BufferedSink) {
                        Log.i(TAG, "sendDocument: writeTo called, opening stream...")
                        try {
                            streamProvider().use { input ->
                                Log.i(TAG, "sendDocument: Stream opened, copying to sink...")
                                // Do NOT close the sink's outputStream - OkHttp manages it
                                val buffer = ByteArray(8192)
                                var bytesWritten = 0L
                                var bytesRead: Int
                                var lastProgressUpdate = 0L
                                val progressUpdateInterval = if (totalBytes > 0) maxOf(100_000L, totalBytes / 100) else 100_000L
                                
                                while (input.read(buffer).also { bytesRead = it } != -1) {
                                    sink.write(buffer, 0, bytesRead)
                                    bytesWritten += bytesRead
                                    
                                    // Reportar progreso cada ~1% o cada 100KB para evitar sobrecarga
                                    if (onProgress != null && (bytesWritten - lastProgressUpdate >= progressUpdateInterval || bytesRead == -1)) {
                                        val effectiveTotal = if (totalBytes > 0) totalBytes else bytesWritten
                                        onProgress(bytesWritten, effectiveTotal)
                                        lastProgressUpdate = bytesWritten
                                    }
                                }
                                sink.flush()
                                
                                // Reportar progreso final
                                if (onProgress != null) {
                                    val effectiveTotal = if (totalBytes > 0) totalBytes else bytesWritten
                                    onProgress(bytesWritten, effectiveTotal)
                                }
                                
                                Log.i(TAG, "sendDocument: Copied $bytesWritten bytes to sink")
                            }
                            Log.i(TAG, "sendDocument: writeTo completed successfully")
                        } catch (e: Exception) {
                            Log.e(TAG, "sendDocument: writeTo failed", e)
                            throw e
                        }
                    }
                }

                Log.i(TAG, "sendDocument: Building multipart request...")
                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", channelId)
                    .addFormDataPart(
                        "document",
                        fileName,
                        documentBody
                    )
                    .apply {
                        if (!caption.isNullOrBlank()) addFormDataPart("caption", caption)
                    }
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(multipart)
                    .build()

                Log.i(TAG, "sendDocument: Executing HTTP request...")
                var attempt = 0
                var message: TelegramMessage? = null
                retry@ while (message == null) {
                    attempt++
                    var waitMillisForRetry: Long? = null
                try {
                    httpClient.newCall(request).execute().use { response ->
                        Log.i(TAG, "sendDocument: Response code=${response.code}")
                        val body = response.body?.string() ?: error("Respuesta vacía de Telegram")
                        Log.i(TAG, "sendDocument: Response body (first 500 chars): ${body.take(500)}")
                            if (response.isSuccessful) {
                                val parsed = parseMessage(body)
                                Log.i(TAG, "sendDocument: Success! messageId=${parsed.messageId}")
                                message = parsed
                                return@use
                            }

                            if (response.code == 429) {
                                val retryAfterMillis = registerRetryAfterDelay(token, body)
                                val waitMillis = maxOf(retryAfterMillis, RATE_LIMIT_INTERVAL_MS)
                                Log.w(
                                    TAG,
                                    "sendDocument: rate limited; retrying after ${waitMillis}ms (attempt $attempt)"
                                )
                                waitMillisForRetry = waitMillis
                                return@use
                            }

                            registerRetryAfterDelay(token, body)
                            Log.e(TAG, "sendDocument: HTTP error ${response.code}: $body")
                            error("Telegram error: $body")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "sendDocument: Exception during upload", e)
                    throw e
                }
                    val waitMillis = waitMillisForRetry
                    if (waitMillis != null) {
                        delay(waitMillis)
                        continue@retry
                    }
                }
                return@withContext message!!
            }
        }

    suspend fun getFile(token: String, fileId: String): TelegramFile = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/bot$token/getFile"
        val request = Request.Builder()
            .url(url)
            .post(
                "file_id=$fileId"
                    .toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
            )
            .build()

        // Use chunkHttpClient for chunk operations (1 minute timeout)
        chunkHttpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: error("Respuesta vacía de Telegram")
            if (!response.isSuccessful) error("Telegram error: $body")
            val json = JSONObject(body)
            if (!json.optBoolean("ok")) {
                error(json.optString("description", "Error getFile"))
            }
            val result = json.getJSONObject("result")
            TelegramFile(
                fileId = result.getString("file_id"),
                fileUniqueId = result.getString("file_unique_id"),
                filePath = result.getString("file_path"),
                fileSize = result.optLong("file_size")
            )
        }
    }
    
    /**
     * Get file info using the instance's token
     */
    suspend fun getFile(fileId: String): TelegramFile {
        requireNotNull(token) { "Token must be set for this operation" }
        return getFile(token, fileId)
    }

    suspend fun downloadFile(
        token: String,
        filePath: String,
        outputStream: OutputStream,
        totalBytes: Long = -1L,
        onProgress: ((Long, Long) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val url = "https://api.telegram.org/file/bot$token/$filePath"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Error al descargar: ${response.code}")
            
            val body = response.body ?: error("Cuerpo vacío en descarga")
            val contentLength = body.contentLength()
            val effectiveTotal = if (totalBytes > 0) totalBytes else if (contentLength > 0) contentLength else -1L
            
            body.byteStream().use { input ->
                outputStream.use { out ->
                    val buffer = ByteArray(8192)
                    var bytesDownloaded = 0L
                    var bytesRead: Int
                    var lastProgressUpdate = 0L
                    // Actualizar progreso más frecuentemente: cada ~0.5% o cada 50KB para mejor feedback visual
                    val progressUpdateInterval = if (effectiveTotal > 0) maxOf(50_000L, effectiveTotal / 200) else 50_000L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        out.write(buffer, 0, bytesRead)
                        bytesDownloaded += bytesRead
                        
                        // Reportar progreso cada ~1% o cada 100KB para evitar sobrecarga
                        if (onProgress != null && (bytesDownloaded - lastProgressUpdate >= progressUpdateInterval || bytesRead == -1)) {
                            onProgress(bytesDownloaded, effectiveTotal)
                            lastProgressUpdate = bytesDownloaded
                        }
                    }
                    
                    // Reportar progreso final
                    if (onProgress != null) {
                        onProgress(bytesDownloaded, effectiveTotal)
                    }
                }
            }
        }
    }

    /**
     * Deletes a message from Telegram.
     * @param token Bot token
     * @param channelId Channel/chat ID
     * @param messageId Message ID to delete
     * @return true if deleted successfully
     */
    suspend fun deleteMessage(token: String, channelId: String, messageId: Long): Boolean =
        withContext(Dispatchers.IO) {
            val url = "https://api.telegram.org/bot$token/deleteMessage"
            Log.i(TAG, "deleteMessage: Deleting message $messageId from channel $channelId")
            
            val formBody = okhttp3.FormBody.Builder()
                .add("chat_id", channelId)
                .add("message_id", messageId.toString())
                .build()
            
            val request = Request.Builder()
                .url(url)
                .post(formBody)
                .build()
            
            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: ""
                    Log.i(TAG, "deleteMessage: Response code=${response.code}, body=$body")
                    
                    if (body.contains("\"ok\":true")) {
                        Log.i(TAG, "deleteMessage: Message $messageId deleted successfully")
                        true
                    } else {
                        Log.e(TAG, "deleteMessage: Failed to delete message $messageId: $body")
                        false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "deleteMessage: Exception", e)
                false
            }
        }

    /**
     * Sends a chunk of a file as a document.
     * Used for chunked uploads of large files.
     */
    suspend fun sendChunk(
        token: String,
        channelId: String,
        chunkData: ByteArray,
        chunkFileName: String,
        chunkIndex: Int,
        totalChunks: Int,
        originalFileName: String,
        fileId: String,
        chunkHash: String
    ): TelegramMessage =
        rateLimiter.execute(token, channelId) {
            withContext(Dispatchers.IO) {
                val url = "https://api.telegram.org/bot$token/sendDocument"
                Log.i(TAG, "sendChunk: Uploading chunk $chunkIndex/$totalChunks for $originalFileName")

                // Caption with chunk metadata for reconstruction
                val caption = buildString {
                    append("[CHUNK]")
                    append("|fileId:$fileId")
                    append("|chunk:$chunkIndex")
                    append("|total:$totalChunks")
                    append("|name:$originalFileName")
                    append("|hash:$chunkHash")
                }

                val documentBody = object : RequestBody() {
                    override fun contentType() = "application/octet-stream".toMediaTypeOrNull()
                    override fun contentLength() = chunkData.size.toLong()
                    override fun writeTo(sink: okio.BufferedSink) {
                        sink.write(chunkData)
                    }
                }

                val multipart = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", channelId)
                    .addFormDataPart("document", chunkFileName, documentBody)
                    .addFormDataPart("caption", caption)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .post(multipart)
                    .build()

                // Use chunkHttpClient for chunk uploads (1 minute timeout)
                var attempt = 0
                var message: TelegramMessage? = null
                retryChunk@ while (message == null) {
                    attempt++
                    var waitMillisForRetry: Long? = null
                chunkHttpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: error("Empty response")
                        if (response.isSuccessful) {
                            val parsed = parseMessage(body)
                            Log.i(TAG, "sendChunk: Chunk $chunkIndex uploaded, messageId=${parsed.messageId}")
                            message = parsed
                            return@use
                        }

                        if (response.code == 429) {
                            val retryAfterMillis = registerRetryAfterDelay(token, body)
                            val waitMillis = maxOf(retryAfterMillis, RATE_LIMIT_INTERVAL_MS)
                            Log.w(
                                TAG,
                                "sendChunk: rate limited; retrying after ${waitMillis}ms (attempt $attempt)"
                            )
                            waitMillisForRetry = waitMillis
                            return@use
                        }
                        registerRetryAfterDelay(token, body)
                        Log.e(TAG, "sendChunk: Failed: $body")
                        error("Telegram error: $body")
                    }
                    val waitMillis = waitMillisForRetry
                    if (waitMillis != null) {
                        delay(waitMillis)
                        continue@retryChunk
                    }
                }
                return@withContext message!!
            }
        }

    /**
     * Downloads a file directly to a byte array (for chunks).
     */
    suspend fun downloadFileToBytes(token: String, filePath: String): ByteArray =
        withContext(Dispatchers.IO) {
            val url = "https://api.telegram.org/file/bot$token/$filePath"
            Log.i(TAG, "downloadFileToBytes: Downloading $filePath")
            
            val request = Request.Builder().url(url).build()
            // Use chunkHttpClient for chunk downloads (1 minute timeout)
            chunkHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Download failed: ${response.code}")
                response.body?.bytes() ?: error("Empty body")
            }
        }
    
    /**
     * Downloads a file to byte array with progress callback.
     * Uses the instance's token.
     */
    suspend fun downloadFileToBytes(filePath: String, onProgress: (Float) -> Unit): ByteArray? =
        withContext(Dispatchers.IO) {
            requireNotNull(token) { "Token must be set for this operation" }
            val url = "https://api.telegram.org/file/bot$token/$filePath"
            Log.i(TAG, "downloadFileToBytes: Downloading $filePath with progress")
            
            val request = Request.Builder().url(url).build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Download failed: ${response.code}")
                        return@withContext null
                    }
                    
                    val body = response.body ?: return@withContext null
                    val contentLength = body.contentLength()
                    
                    if (contentLength <= 0) {
                        // Unknown size, just read all
                        return@withContext body.bytes()
                    }
                    
                    // Read with progress tracking
                    val buffer = ByteArray(8192)
                    val output = java.io.ByteArrayOutputStream()
                    var totalRead = 0L
                    
                    body.byteStream().use { input ->
                        var bytesRead: Int
                        while (input.read(buffer).also { bytesRead = it } != -1) {
                            output.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                            onProgress(totalRead.toFloat() / contentLength)
                        }
                    }
                    
                    output.toByteArray()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading file", e)
                null
            }
        }

    /**
     * Gets a message by ID from a channel and extracts fileId.
     * Uses forwardMessage to a temporary chat, then extracts document info.
     */
    suspend fun getMessageFileId(
        token: String,
        channelId: String,
        messageId: Int
    ): String? = withContext(Dispatchers.IO) {
        try {
            // Use getUpdates to find the message, or better: use getChatMember to verify access
            // Then try to get the message directly
            // Since Telegram Bot API doesn't have direct getMessage, we'll try a workaround:
            // Attempt to download using messageId as reference
            // For now, return null and let the caller handle it
            // The real solution is to ensure fileId is always saved during upload
            Log.w(TAG, "getMessageFileId: Cannot recover fileId from messageId without additional API calls")
            null
        } catch (e: Exception) {
            Log.e(TAG, "getMessageFileId: Error", e)
            null
        }
    }

    private fun registerRetryAfterDelay(token: String, body: String): Long {
        val retryAfterSec = runCatching {
            JSONObject(body)
                .optJSONObject("parameters")
                ?.optLong("retry_after", 0L) ?: 0L
        }.getOrNull() ?: 0L

        val delayMillis = TimeUnit.SECONDS.toMillis(retryAfterSec)
        if (delayMillis > 0) {
            Log.w(
                TAG,
                "registerRetryAfterDelay: delaying ${retryAfterSec}s for token=${token.take(20)}"
            )
            return delayMillis
        }
        return 0L
    }

    private fun parseMessage(body: String): TelegramMessage {
        val json = JSONObject(body)
        if (!json.optBoolean("ok")) {
            error(json.optString("description", "Error sendDocument"))
        }
        val result = json.getJSONObject("result")
        val messageId = result.getLong("message_id")
        
        // Try document first (for files sent as document)
        val doc = result.optJSONObject("document")
        if (doc != null) {
            return TelegramMessage(
                messageId = messageId,
                document = TelegramDocument(
                    fileId = doc.getString("file_id"),
                    fileUniqueId = doc.getString("file_unique_id"),
                    fileName = doc.optString("file_name", null),
                    mimeType = doc.optString("mime_type", null),
                    fileSize = doc.optLong("file_size")
                )
            )
        }
        
        // Try video (for videos sent as video)
        val video = result.optJSONObject("video")
        if (video != null) {
            return TelegramMessage(
                messageId = messageId,
                document = TelegramDocument(
                    fileId = video.getString("file_id"),
                    fileUniqueId = video.getString("file_unique_id"),
                    fileName = video.optString("file_name", null),
                    mimeType = video.optString("mime_type", null),
                    fileSize = video.optLong("file_size")
                )
            )
        }
        
        // Try photo (for photos sent as photo - get largest size)
        val photos = result.optJSONArray("photo")
        if (photos != null && photos.length() > 0) {
            val largestPhoto = photos.getJSONObject(photos.length() - 1) // Last is largest
            return TelegramMessage(
                messageId = messageId,
                document = TelegramDocument(
                    fileId = largestPhoto.getString("file_id"),
                    fileUniqueId = largestPhoto.getString("file_unique_id"),
                    fileName = null,
                    mimeType = "image/jpeg",
                    fileSize = largestPhoto.optLong("file_size")
                )
            )
        }
        
        // Log the full response for debugging
        Log.e(TAG, "parseMessage: No document/video/photo found in response: ${body.take(1000)}")
        error("No document, video, or photo found in Telegram response")
    }
}

