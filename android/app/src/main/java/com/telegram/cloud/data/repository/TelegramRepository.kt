package com.telegram.cloud.data.repository

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import com.telegram.cloud.data.local.CloudDatabase
import com.telegram.cloud.data.local.CloudFileEntity
import com.telegram.cloud.data.local.DownloadStatus
import com.telegram.cloud.data.local.DownloadTaskEntity
import com.telegram.cloud.data.local.UploadStatus
import com.telegram.cloud.data.local.UploadTaskEntity
import com.telegram.cloud.data.prefs.BotConfig
import com.telegram.cloud.data.prefs.ConfigStore
import com.telegram.cloud.data.remote.CHUNK_THRESHOLD
import com.telegram.cloud.data.remote.ChunkedDownloadManager
import com.telegram.cloud.data.remote.ChunkedUploadManager
import com.telegram.cloud.data.remote.TelegramBotClient
import com.telegram.cloud.domain.model.CloudFile
import com.telegram.cloud.domain.model.DownloadRequest
import com.telegram.cloud.domain.model.UploadRequest
import com.telegram.cloud.utils.getUserVisibleDownloadsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import com.telegram.cloud.utils.moveFileToDownloads
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.telegram.cloud.data.remote.ChunkInfo

private const val TAG = "TelegramRepository"

private const val GALLERY_MATCH_TOLERANCE = 128 * 1024L // 128KB tolerance when matching media entries

class TelegramRepository(
    private val context: Context,
    private val configStore: ConfigStore,
    private val database: CloudDatabase,
    private val botClient: TelegramBotClient
) {
    private val chunkedUploadManager = ChunkedUploadManager(botClient, context.contentResolver)
    private val chunkedDownloadManager = ChunkedDownloadManager(botClient)
    private val gson = Gson()
    
    // Cache de archivos similar a la app desktop - se actualiza manualmente después de cada inserción
    private val _filesCache = MutableStateFlow<List<CloudFile>>(emptyList())
    
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    // Inicializar cache al crear el repository
    init {
        // Cargar archivos iniciales en background
        repositoryScope.launch {
            reloadFilesFromDatabase()
        }
    }

    val config: Flow<BotConfig?> = configStore.configFlow

    // Usar directamente el cache que se actualiza manualmente (similar a LoadFiles en desktop)
    val files: StateFlow<List<CloudFile>> = _filesCache.asStateFlow()
    
    // Recargar archivos directamente desde la base de datos (similar a LoadFiles() en desktop)
    suspend fun reloadFilesFromDatabase() {
        withContext(Dispatchers.IO) {
            val entities = database.cloudFileDao().getAllFiles()
            val files = entities.map { entity ->
                CloudFile(
                    id = entity.id,
                    messageId = entity.telegramMessageId,
                    fileId = entity.fileId,
                    fileName = entity.fileName,
                    mimeType = entity.mimeType,
                    sizeBytes = entity.sizeBytes,
                    uploadedAt = entity.uploadedAt,
                    caption = entity.caption,
                    shareLink = entity.shareLink,
                    checksum = entity.checksum
                )
            }
            // Actualizar el cache en el hilo principal para que el StateFlow emita correctamente
            _filesCache.value = files
            Log.d(TAG, "reloadFilesFromDatabase: Reloaded ${files.size} files")
        }
    }
    
    // Método para forzar actualización del cache (similar a OnRefresh en desktop)
    suspend fun refreshFiles() {
        reloadFilesFromDatabase()
    }

    suspend fun saveConfig(config: BotConfig) = configStore.save(config)

    suspend fun upload(request: UploadRequest, onProgress: ((Float) -> Unit)? = null) {
        Log.i(TAG, "upload: Starting upload for ${request.displayName} (${request.sizeBytes} bytes)")
        Log.i(TAG, "upload: URI=${request.uri}")
        
        val cfg = config.first()
        if (cfg == null) {
            Log.e(TAG, "upload: No config found!")
            error("Configura tokens y canal antes de subir")
        }
        
        Log.i(TAG, "upload: Config loaded - ${cfg.tokens.size} tokens, channelId=${cfg.channelId}")
        
        val taskId = database.uploadTaskDao().upsert(
            UploadTaskEntity(
                uri = request.uri,
                displayName = request.displayName,
                sizeBytes = request.sizeBytes,
                status = UploadStatus.QUEUED,
                progress = 0,
                error = null,
                createdAt = System.currentTimeMillis()
            )
        )
        Log.i(TAG, "upload: Task created with id=$taskId")

        try {
            database.uploadTaskDao().getById(taskId)?.let {
                database.uploadTaskDao().update(it.copy(status = UploadStatus.RUNNING, progress = 5))
            }

            val uri = Uri.parse(request.uri)
            Log.i(TAG, "upload: Parsed URI=$uri")
            
            // Verify we can open the stream
            val testStream = context.contentResolver.openInputStream(uri)
            if (testStream == null) {
                Log.e(TAG, "upload: Cannot open input stream for URI!")
                error("No se pudo abrir el archivo a subir")
            }
            testStream.close()
            Log.i(TAG, "upload: Stream test passed")
            
            val checksum = checksum(context.contentResolver, uri)
            Log.i(TAG, "upload: Checksum calculated=${checksum?.take(16)}...")
            
            val mimeType = context.contentResolver.getType(uri)
            Log.i(TAG, "upload: MimeType=$mimeType")

            // Check if file needs chunked upload (> 4MB)
            if (request.sizeBytes > CHUNK_THRESHOLD) {
                Log.i(TAG, "upload: File exceeds ${CHUNK_THRESHOLD / 1024 / 1024}MB, using chunked upload")
                uploadChunked(request, cfg, taskId, uri, checksum, mimeType, onProgress)
            } else {
                Log.i(TAG, "upload: File is small, using direct upload")
                uploadDirect(request, cfg, taskId, uri, checksum, mimeType, onProgress)
            }

            database.uploadTaskDao().getById(taskId)?.let {
                database.uploadTaskDao().update(it.copy(status = UploadStatus.COMPLETED, progress = 100))
            }
            Log.i(TAG, "upload: Upload completed successfully!")
            
        } catch (ex: Exception) {
            Log.e(TAG, "upload: Upload failed!", ex)
            Log.e(TAG, "upload: Exception type=${ex.javaClass.simpleName}, message=${ex.message}")
            database.uploadTaskDao().getById(taskId)?.let {
                database.uploadTaskDao().update(
                    it.copy(
                        status = UploadStatus.FAILED,
                        error = ex.message
                    )
                )
            }
            throw ex
        }
    }
    
    private suspend fun uploadDirect(
        request: UploadRequest,
        cfg: BotConfig,
        taskId: Long,
        uri: Uri,
        checksum: String?,
        mimeType: String?,
        onProgress: ((Float) -> Unit)? = null
    ) {
        val selectedToken = cfg.tokens.randomOrNull() ?: cfg.tokens.first()
        Log.i(TAG, "uploadDirect: Selected token=${selectedToken.take(20)}...")

        val totalBytes = request.sizeBytes
        Log.i(TAG, "uploadDirect: Total bytes=$totalBytes")

        Log.i(TAG, "uploadDirect: Calling botClient.sendDocument...")
        val message = botClient.sendDocument(
            token = selectedToken,
            channelId = cfg.channelId,
            caption = request.caption,
            fileName = request.displayName,
            mimeType = mimeType,
            streamProvider = {
                Log.i(TAG, "uploadDirect: streamProvider called, opening stream...")
                context.contentResolver.openInputStream(uri)
                    ?: error("No se pudo abrir el archivo a subir")
            },
            totalBytes = totalBytes,
            onProgress = { bytesWritten, total ->
                val progress = if (total > 0) {
                    (bytesWritten.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val progressPercent = (progress * 100f).toInt()
                
                // Actualizar progreso en base de datos de forma asíncrona
                repositoryScope.launch {
                    database.uploadTaskDao().getById(taskId)?.let {
                        database.uploadTaskDao().update(it.copy(progress = progressPercent))
                    }
                }
                
                // Emitir progreso al callback
                onProgress?.invoke(progress)
                
                Log.d(TAG, "uploadDirect: Progress $bytesWritten/$total bytes (${progressPercent}%)")
            }
        )
        Log.i(TAG, "uploadDirect: sendDocument completed, messageId=${message.messageId}")

        val document = message.document
        if (document == null) {
            Log.e(TAG, "uploadDirect: Response has no document!")
            error("Respuesta sin documento")
        }
        Log.i(TAG, "uploadDirect: Document fileId=${document.fileId}")
        
        val shareLink = buildShareLink(cfg.channelId, message.messageId)
        Log.i(TAG, "uploadDirect: ShareLink=$shareLink")

        database.cloudFileDao().insert(
            CloudFileEntity(
                telegramMessageId = message.messageId,
                fileId = document.fileId,
                fileUniqueId = document.fileUniqueId,
                fileName = document.fileName ?: request.displayName,
                mimeType = document.mimeType ?: mimeType,
                sizeBytes = document.fileSize ?: request.sizeBytes,
                uploadedAt = System.currentTimeMillis(),
                caption = request.caption,
                shareLink = shareLink,
                checksum = checksum
            )
        )
        // Recargar archivos desde la base de datos inmediatamente (similar a LoadFiles en desktop)
        reloadFilesFromDatabase()
        Log.i(TAG, "uploadDirect: File saved to database and cache reloaded")
        
        markGalleryMediaSyncedDirect(
            request = request,
            mimeType = document.mimeType ?: mimeType,
            fileId = document.fileId,
            messageId = message.messageId,
            uploaderToken = selectedToken
        )
    }
    
    private suspend fun uploadChunked(
        request: UploadRequest,
        cfg: BotConfig,
        taskId: Long,
        uri: Uri,
        checksum: String?,
        mimeType: String?,
        onProgress: ((Float) -> Unit)? = null
    ) {
        Log.i(TAG, "uploadChunked: Starting chunked upload for ${request.displayName}")
        
        // Check if we can resume from previous attempt
        val task = database.uploadTaskDao().getById(taskId)
        val canResume = task?.fileId != null && task.completedChunksJson != null
        
        val result = if (canResume) {
            Log.i(TAG, "uploadChunked: Resuming from previous attempt")
            val fileId = task!!.fileId!!
            val completedChunksJson = task.completedChunksJson!!
            val tokenOffset = task.tokenOffset
            
            // Deserialize completed chunks
            val completedChunks: List<ChunkInfo> = try {
                val type = object : TypeToken<List<ChunkInfo>>() {}.type
                gson.fromJson(completedChunksJson, type)
            } catch (e: Exception) {
                Log.e(TAG, "uploadChunked: Failed to parse completed chunks JSON", e)
                emptyList()
            }
            
            Log.i(TAG, "uploadChunked: Resuming with ${completedChunks.size} completed chunks, offset=$tokenOffset")
            
            chunkedUploadManager.resumeChunkedUpload(
                uri = uri,
                fileName = request.displayName,
                fileSize = request.sizeBytes,
                fileId = fileId,
                completedChunks = completedChunks,
                tokens = cfg.tokens,
                channelId = cfg.channelId,
                tokenOffset = tokenOffset,
                onProgress = { completed, total, percent ->
                    // Convertir porcentaje (0-100) a Float (0.0-1.0)
                    val progressFloat = (percent / 100f).coerceIn(0f, 1f)
                    
                    // Actualizar progreso en base de datos de forma asíncrona
                    repositoryScope.launch {
                        database.uploadTaskDao().getById(taskId)?.let {
                            database.uploadTaskDao().update(it.copy(progress = percent.toInt()))
                        }
                    }
                    
                    // Emitir progreso como Float entre 0.0 y 1.0
                    onProgress?.invoke(progressFloat)
                    
                    Log.d(TAG, "uploadChunked: Progress $completed/$total chunks (${percent.toInt()}%)")
                },
                onChunkCompleted = { chunkInfo ->
                    // Save progress after each chunk
                    kotlinx.coroutines.runBlocking {
                        database.uploadTaskDao().getById(taskId)?.let { currentTask ->
                            val existingChunks: List<ChunkInfo> = try {
                                val type = object : TypeToken<List<ChunkInfo>>() {}.type
                                gson.fromJson(currentTask.completedChunksJson ?: "[]", type)
                            } catch (e: Exception) {
                                emptyList()
                            }
                            val allChunks = existingChunks + chunkInfo
                            val chunksJson = gson.toJson(allChunks)
                            database.uploadTaskDao().update(
                                currentTask.copy(completedChunksJson = chunksJson)
                            )
                        }
                    }
                }
            )
        } else {
            // New upload - generate file ID and save it
            val fileId = java.util.UUID.randomUUID().toString()
            database.uploadTaskDao().getById(taskId)?.let {
                database.uploadTaskDao().update(it.copy(fileId = fileId, tokenOffset = 0))
            }
            
            chunkedUploadManager.uploadChunked(
                uri = uri,
                fileName = request.displayName,
                fileSize = request.sizeBytes,
                tokens = cfg.tokens,
                channelId = cfg.channelId,
                onProgress = { completed, total, percent ->
                    // Convertir porcentaje (0-100) a Float (0.0-1.0)
                    val progressFloat = (percent / 100f).coerceIn(0f, 1f)
                    
                    // Actualizar progreso en base de datos de forma asíncrona
                    repositoryScope.launch {
                        database.uploadTaskDao().getById(taskId)?.let {
                            database.uploadTaskDao().update(it.copy(progress = percent.toInt()))
                        }
                    }
                    
                    // Emitir progreso como Float entre 0.0 y 1.0
                    onProgress?.invoke(progressFloat)
                    
                    Log.d(TAG, "uploadChunked: Progress $completed/$total chunks (${percent.toInt()}%)")
                },
                onChunkCompleted = { chunkInfo ->
                    // Save progress after each chunk
                    kotlinx.coroutines.runBlocking {
                        database.uploadTaskDao().getById(taskId)?.let { currentTask ->
                            val existingChunks: List<ChunkInfo> = try {
                                val type = object : TypeToken<List<ChunkInfo>>() {}.type
                                gson.fromJson(currentTask.completedChunksJson ?: "[]", type)
                            } catch (e: Exception) {
                                emptyList()
                            }
                            val allChunks = existingChunks + chunkInfo
                            val chunksJson = gson.toJson(allChunks)
                            database.uploadTaskDao().update(
                                currentTask.copy(completedChunksJson = chunksJson)
                            )
                        }
                    }
                }
            )
        }
        
        if (!result.success) {
            error("Chunked upload failed: ${result.error}")
        }
        
        Log.i(TAG, "uploadChunked: All ${result.totalChunks} chunks uploaded")
        
        // Store as chunked file - use first message ID as reference
        val firstMessageId = result.messageIds.firstOrNull() ?: 0L
        val shareLink = buildShareLink(cfg.channelId, firstMessageId)
        
        // Store telegram file IDs for download (comma-separated)
        // These are needed to download the chunks later
        val allTelegramFileIds = result.telegramFileIds.joinToString(",")
        
        // Store bot tokens used for each chunk (for sharing)
        val allUploaderTokens = result.uploaderBotTokens.joinToString(",")
        
        // Store message IDs in caption for deletion (after [CHUNKED:N])
        val allMessageIds = result.messageIds.joinToString(",")
        
        database.cloudFileDao().insert(
            CloudFileEntity(
                telegramMessageId = firstMessageId,
                fileId = result.fileId, // UUID for chunked file
                fileUniqueId = allTelegramFileIds, // Store telegram file IDs for download
                fileName = request.displayName,
                mimeType = mimeType,
                sizeBytes = request.sizeBytes,
                uploadedAt = System.currentTimeMillis(),
                caption = "[CHUNKED:${result.totalChunks}|$allMessageIds] ${request.caption ?: ""}",
                shareLink = shareLink,
                checksum = checksum,
                uploaderTokens = allUploaderTokens // Store bot tokens per chunk for sharing
            )
        )
        // Recargar archivos desde la base de datos inmediatamente (similar a LoadFiles en desktop)
        reloadFilesFromDatabase()
        Log.i(TAG, "uploadChunked: Chunked file record saved to database and cache reloaded")
        
        markGalleryMediaSyncedChunked(
            request = request,
            mimeType = mimeType,
            messageId = firstMessageId,
            telegramFileIds = result.telegramFileIds,
            uploaderTokens = result.uploaderBotTokens
        )
        
        // Clear progress tracking after successful upload
        database.uploadTaskDao().getById(taskId)?.let {
            database.uploadTaskDao().update(
                it.copy(
                    fileId = null,
                    completedChunksJson = null,
                    tokenOffset = 0
                )
            )
        }
    }

    suspend fun download(request: DownloadRequest, onProgress: ((Float) -> Unit)? = null) {
        Log.i(TAG, "download: Starting download for ${request.file.fileName}")
        
        val cfg = config.first() ?: error("Config necesaria")
        
        // Obtener entidad desde la base de datos - buscar por id, messageId o fileId
        var entity: CloudFileEntity? = null
        
        if (request.file.id > 0) {
            // Intentar buscar por ID primero
            entity = database.cloudFileDao().getById(request.file.id)
            Log.d(TAG, "download: Searched by id=${request.file.id}, found=${entity != null}")
        }
        
        if (entity == null && request.file.messageId > 0) {
            // Si no se encontró por ID, buscar por messageId
            entity = database.cloudFileDao().getByTelegramMessageId(request.file.messageId)
            Log.d(TAG, "download: Searched by messageId=${request.file.messageId}, found=${entity != null}")
        }
        
        if (entity == null && request.file.fileId.isNotBlank()) {
            // Si aún no se encontró, buscar por fileId
            entity = database.cloudFileDao().getByFileId(request.file.fileId)
            Log.d(TAG, "download: Searched by fileId=${request.file.fileId.take(50)}..., found=${entity != null}")
        }
        
        if (entity == null) {
            Log.e(TAG, "download: File not found in database (id=${request.file.id}, messageId=${request.file.messageId}, fileId=${request.file.fileId.take(50)}...)")
            error("Archivo no encontrado en la base de datos para ${request.file.fileName}")
        }
        
        val actualFileId = entity!!.fileId
        if (actualFileId.isBlank()) {
            Log.e(TAG, "download: fileId is empty in database for ${entity.fileName} (id=${request.file.id})")
            error("file_id no especificado en la base de datos para ${entity.fileName}")
        }
        
        val taskId = database.downloadTaskDao().upsert(
            DownloadTaskEntity(
                fileId = actualFileId,
                targetPath = request.targetPath,
                status = DownloadStatus.QUEUED,
                progress = 0,
                error = null,
                createdAt = System.currentTimeMillis()
            )
        )

        try {
            database.downloadTaskDao().getById(taskId)?.let {
                database.downloadTaskDao().update(it.copy(status = DownloadStatus.RUNNING, progress = 5))
            }

        var destination = File(request.targetPath)
            destination.parentFile?.mkdirs()
            
            // Usar la entidad ya obtenida anteriormente (no necesitamos obtenerla de nuevo)
            // actualFileId ya está disponible desde arriba
            
            // Check if it's a chunked file usando metadatos de la base de datos
            val isChunkedByCaption = ChunkedDownloadManager.isChunkedFile(entity.caption)
            
            // También verificar si fileId parece un UUID (formato: xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx)
            // Los UUIDs tienen 36 caracteres con guiones en posiciones específicas
            val looksLikeUUID = actualFileId.matches(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE))
            
            // Si parece UUID, verificar si tiene fileUniqueId (que contiene los file_id de Telegram para chunked)
            val hasFileUniqueId = looksLikeUUID && entity.fileUniqueId?.isNotBlank() == true
            
            val isChunked = isChunkedByCaption || hasFileUniqueId
            
            if (isChunked) {
                Log.i(TAG, "download: Chunked file detected (caption=$isChunkedByCaption, uuid=$looksLikeUUID), using chunked download")
                downloadChunked(request, cfg, taskId, destination, onProgress)
            } else {
                Log.i(TAG, "download: Direct download for fileId=${actualFileId.take(50)}...")
                // Crear un nuevo DownloadRequest con el fileId correcto desde la base de datos
                val correctedRequest = DownloadRequest(
                    file = request.file.copy(fileId = actualFileId),
                    targetPath = request.targetPath
                )
                downloadDirect(correctedRequest, cfg, taskId, destination, onProgress)
            }

            database.downloadTaskDao().getById(taskId)?.let {
                database.downloadTaskDao().update(it.copy(status = DownloadStatus.COMPLETED, progress = 100))
            }
            Log.i(TAG, "download: Download completed successfully")
            
        } catch (ex: Exception) {
            Log.e(TAG, "download: Failed", ex)
            database.downloadTaskDao().getById(taskId)?.let {
                database.downloadTaskDao().update(
                    it.copy(
                        status = DownloadStatus.FAILED,
                        error = ex.message
                    )
                )
            }
            throw ex
        }
    }
    
    private suspend fun downloadDirect(
        request: DownloadRequest,
        cfg: BotConfig,
        taskId: Long,
        destination: File,
        onProgress: ((Float) -> Unit)? = null
    ) {
        // SIEMPRE obtener fileId desde la base de datos (fuente de verdad)
        // Buscar por id, messageId o fileId como fallback
        var entity: CloudFileEntity? = null
        
        if (request.file.id > 0) {
            entity = database.cloudFileDao().getById(request.file.id)
        }
        
        if (entity == null && request.file.messageId > 0) {
            entity = database.cloudFileDao().getByTelegramMessageId(request.file.messageId)
        }
        
        if (entity == null && request.file.fileId.isNotBlank()) {
            entity = database.cloudFileDao().getByFileId(request.file.fileId)
        }
        
        if (entity == null) {
            Log.e(TAG, "downloadDirect: File not found in database (id=${request.file.id}, messageId=${request.file.messageId}, fileId=${request.file.fileId.take(50)}...)")
            error("Archivo no encontrado en la base de datos para ${request.file.fileName}")
        }
        
        val fileId = entity!!.fileId
        if (fileId.isBlank()) {
            Log.e(TAG, "downloadDirect: fileId is empty in database for ${entity.fileName} (id=${entity.id})")
            error("file_id no especificado en la base de datos para ${entity.fileName}")
        }
        Log.i(TAG, "downloadDirect: Using fileId from database: ${fileId.take(50)}...")
        
        val token = cfg.tokens.randomOrNull() ?: cfg.tokens.first()
        Log.i(TAG, "downloadDirect: Getting file info for fileId=${fileId.take(50)}...")
        val fileResponse = botClient.getFile(token, fileId)
        
        val totalBytes = fileResponse.fileSize ?: request.file.sizeBytes
        Log.i(TAG, "downloadDirect: Total bytes=$totalBytes")
        
        openDestinationOutputStream(destination, request.file.mimeType).use { output ->
            botClient.downloadFile(
                token = token,
                filePath = fileResponse.filePath,
                outputStream = output,
                totalBytes = totalBytes,
                onProgress = { bytesDownloaded, total ->
                    val progress = if (total > 0) {
                        (bytesDownloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                    } else {
                        0f
                    }
                    val progressPercent = (progress * 100f).toInt()
                    
                    // Actualizar progreso en base de datos de forma asíncrona
                    repositoryScope.launch {
                        database.downloadTaskDao().getById(taskId)?.let {
                            database.downloadTaskDao().update(it.copy(progress = progressPercent))
                        }
                    }
                    
                    // Emitir progreso al callback
                    onProgress?.invoke(progress)
                    
                    Log.d(TAG, "downloadDirect: Progress $bytesDownloaded/$total bytes (${progressPercent}%)")
                }
            )
        }
        Log.i(TAG, "downloadDirect: File saved to ${destination.absolutePath}")
        moveFileToDownloads(
            context = context,
            source = destination,
            displayName = destination.name,
            mimeType = request.file.mimeType ?: "application/octet-stream",
            subfolder = "telegram cloud app/Downloads"
        )?.let { result ->
            Log.i(TAG, "downloadDirect: Moved to user downloads: ${result.uri}")
        }
    }
    
    private suspend fun downloadChunked(
        request: DownloadRequest,
        cfg: BotConfig,
        taskId: Long,
        destination: File,
        onProgress: ((Float) -> Unit)? = null
    ) {
        // Get entity to retrieve chunk file IDs - buscar por id, messageId o fileId
        var entity: CloudFileEntity? = null
        
        if (request.file.id > 0) {
            entity = database.cloudFileDao().getById(request.file.id)
        }
        
        if (entity == null && request.file.messageId > 0) {
            entity = database.cloudFileDao().getByTelegramMessageId(request.file.messageId)
        }
        
        if (entity == null && request.file.fileId.isNotBlank()) {
            entity = database.cloudFileDao().getByFileId(request.file.fileId)
        }
        
        if (entity == null) {
            Log.e(TAG, "downloadChunked: File not found in database (id=${request.file.id}, messageId=${request.file.messageId}, fileId=${request.file.fileId.take(50)}...)")
            error("Archivo no encontrado en la base de datos para ${request.file.fileName}")
        }
        
        val chunkCount = ChunkedDownloadManager.getChunkCount(entity.caption) ?: 1
        Log.i(TAG, "downloadChunked: File has $chunkCount chunks, caption=${entity.caption?.take(50)}")
        
        // fileUniqueId contains comma-separated telegram file IDs for chunked files
        val storedFileIds = entity.fileUniqueId?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        
        Log.i(TAG, "downloadChunked: Found ${storedFileIds.size} stored file IDs")
        
        if (storedFileIds.isEmpty()) {
            Log.e(TAG, "downloadChunked: No file IDs stored!")
            error("No chunk file IDs stored for this file")
        }
        
        if (storedFileIds.size != chunkCount) {
            Log.w(TAG, "downloadChunked: Chunk count mismatch (stored=${storedFileIds.size}, expected=$chunkCount)")
        }
        
        // Verify these look like Telegram file IDs (contain letters)
        val firstId = storedFileIds.firstOrNull() ?: ""
        if (!firstId.any { it.isLetter() }) {
            Log.e(TAG, "downloadChunked: Stored IDs don't look like Telegram file IDs: $firstId")
            error("Invalid file IDs stored - expected Telegram file IDs")
        }
        
        // Save download metadata for resumption
        val task = database.downloadTaskDao().getById(taskId)
        if (task?.totalChunks == 0) {
            // First time downloading - save metadata
            val tempDir = File(destination.parentFile, ".chunks_${System.currentTimeMillis()}")
            database.downloadTaskDao().update(
                task.copy(
                    chunkFileIds = storedFileIds.joinToString(","),
                    tempChunkDir = tempDir.absolutePath,
                    totalChunks = chunkCount
                )
            )
            Log.i(TAG, "downloadChunked: Saved download metadata for resumption")
        }
        
        Log.i(TAG, "downloadChunked: Starting parallel download of ${storedFileIds.size} chunks")
        
        // Download chunks in parallel
        val result = chunkedDownloadManager.downloadChunked(
            chunkFileIds = storedFileIds,
            tokens = cfg.tokens,
            outputFile = destination,
            totalSize = entity.sizeBytes,
            onProgress = { completed, total, percent ->
                // Convertir porcentaje (0-100) a Float (0.0-1.0)
                val progressFloat = (percent / 100f).coerceIn(0f, 1f)
                
                // Actualizar progreso en base de datos de forma asíncrona
                repositoryScope.launch {
                    database.downloadTaskDao().getById(taskId)?.let {
                        database.downloadTaskDao().update(it.copy(progress = percent.toInt()))
                    }
                }
                
                // Emitir progreso como Float entre 0.0 y 1.0
                onProgress?.invoke(progressFloat)
                
                Log.d(TAG, "downloadChunked: Progress $completed/$total chunks (${percent.toInt()}%)")
            },
            onChunkDownloaded = { index, file ->
                // Save progress after each chunk
                kotlinx.coroutines.runBlocking {
                    database.downloadTaskDao().getById(taskId)?.let { currentTask ->
                        val existingIndices: Set<Int> = try {
                            val type = object : TypeToken<Set<Int>>() {}.type
                            gson.fromJson(currentTask.completedChunksJson ?: "[]", type) ?: emptySet()
                        } catch (e: Exception) {
                            emptySet()
                        }
                        val allIndices = existingIndices + index
                        val indicesJson = gson.toJson(allIndices)
                        database.downloadTaskDao().update(
                            currentTask.copy(completedChunksJson = indicesJson)
                        )
                    }
                }
            }
        )
        
        if (!result.success) {
            error("Chunked download failed: ${result.error}")
        }
        
        Log.i(TAG, "downloadChunked: Successfully reassembled file at ${destination.absolutePath}")
        moveFileToDownloads(
            context = context,
            source = destination,
            displayName = destination.name,
            mimeType = request.file.mimeType ?: "application/octet-stream",
            subfolder = "telegram cloud app/Downloads"
        )?.let { moveResult ->
            Log.i(TAG, "downloadChunked: Moved to user downloads: ${moveResult.uri}")
        }
        
        // Clear progress tracking and delete temp directory after successful download
        database.downloadTaskDao().getById(taskId)?.let { currentTask ->
            currentTask.tempChunkDir?.let { tempDirPath ->
                File(tempDirPath).deleteRecursively()
            }
            database.downloadTaskDao().update(
                currentTask.copy(
                    completedChunksJson = null,
                    chunkFileIds = null,
                    tempChunkDir = null,
                    totalChunks = 0
                )
            )
        }
    }

    suspend fun clearConfig() {
        configStore.clear()
        database.cloudFileDao().clear()
    }

    /**
     * Deletes a file from both the local database and Telegram.
     * For chunked files, deletes all chunk messages.
     */
    suspend fun deleteFile(file: CloudFile) {
        Log.i(TAG, "deleteFile: Deleting file ${file.fileName} (id=${file.id})")
        
        val cfg = config.first()
        if (cfg == null) {
            Log.e(TAG, "deleteFile: No config, deleting only from local DB")
            database.cloudFileDao().deleteById(file.id)
            return
        }
        
        // Get the entity to check for chunked file
        val entity = database.cloudFileDao().getById(file.id)
        
        if (entity != null) {
            val token = cfg.tokens.firstOrNull() ?: ""
            
            // Check if it's a chunked file (caption starts with [CHUNKED:])
            val isChunked = entity.caption?.startsWith("[CHUNKED:") == true
            
            if (isChunked) {
                // Extract message IDs from caption format: [CHUNKED:N|msgId1,msgId2,...] 
                val messageIds = extractMessageIdsFromCaption(entity.caption)
                    .takeIf { it.isNotEmpty() }
                    ?: listOf(entity.telegramMessageId)
                
                Log.i(TAG, "deleteFile: Chunked file, deleting ${messageIds.size} messages from Telegram")
                
                var deletedCount = 0
                for (msgId in messageIds) {
                    if (botClient.deleteMessage(token, cfg.channelId, msgId)) {
                        deletedCount++
                    }
                }
                Log.i(TAG, "deleteFile: Deleted $deletedCount/${messageIds.size} messages from Telegram")
                
            } else {
                // Single file - delete the one message
                Log.i(TAG, "deleteFile: Deleting message ${entity.telegramMessageId} from Telegram")
                val deleted = botClient.deleteMessage(token, cfg.channelId, entity.telegramMessageId)
                Log.i(TAG, "deleteFile: Telegram delete result: $deleted")
            }
        }
        
        // Always delete from local database
        database.cloudFileDao().deleteById(file.id)
        Log.i(TAG, "deleteFile: Deleted from local database")
    }
    
    /**
     * @deprecated Use deleteFile instead which also deletes from Telegram
     */
    suspend fun deleteLocalFile(file: CloudFile) {
        deleteFile(file)
    }
    
    /**
     * Get file entity by ID for share link generation
     */
    suspend fun getFileEntity(fileId: Long): CloudFileEntity? {
        return database.cloudFileDao().getById(fileId)
    }
    
    /**
     * Get file entity from GalleryMediaEntity for share link generation
     */
    suspend fun getFileEntityFromGallery(media: com.telegram.cloud.gallery.GalleryMediaEntity): CloudFileEntity? {
        // Try by telegramMessageId first
        if (media.telegramMessageId != null) {
            val entity = database.cloudFileDao().getByTelegramMessageId(media.telegramMessageId.toLong())
            if (entity != null) return entity
        }
        
        // Try by telegramFileId
        if (media.telegramFileId != null) {
            return database.cloudFileDao().getByFileId(media.telegramFileId)
        }
        
        return null
    }

    private fun buildShareLink(channelId: String, messageId: Long): String? {
        return if (channelId.startsWith("-100")) {
            val internalId = channelId.removePrefix("-100")
            "https://t.me/c/$internalId/$messageId"
        } else null
    }
    
    /**
     * Extracts message IDs from chunked file caption.
     * Format: [CHUNKED:N|msgId1,msgId2,...] caption text
     */
    private fun extractMessageIdsFromCaption(caption: String?): List<Long> {
        if (caption == null) return emptyList()
        
        // Match [CHUNKED:N|msgId1,msgId2,...]
        val match = Regex("\\[CHUNKED:\\d+\\|([^\\]]+)\\]").find(caption)
        val idsString = match?.groupValues?.get(1) ?: return emptyList()
        
        return idsString.split(",").mapNotNull { it.trim().toLongOrNull() }
    }

    private fun openDestinationOutputStream(destination: File, mimeType: String?): OutputStream {
        return try {
            destination.parentFile?.mkdirs()
            FileOutputStream(destination)
        } catch (e: FileNotFoundException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createMediaStoreDownloadStream(destination, mimeType)
                    ?: throw e
            } else {
                throw e
            }
        }
    }

    private fun createMediaStoreDownloadStream(destination: File, mimeType: String?): OutputStream? {
        val relativePath = computeRelativeDownloadPath(destination)
        val displayName = destination.name
        val resolvedMime = mimeType ?: guessMimeType(displayName) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, resolvedMime)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            }
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            Log.e(TAG, "createMediaStoreDownloadStream: Failed to insert row for $displayName")
            return null
        }
        Log.i(TAG, "createMediaStoreDownloadStream: Using MediaStore for $displayName at $relativePath")
        return resolver.openOutputStream(uri, "w")
    }

    private fun computeRelativeDownloadPath(destination: File): String {
        val downloadsRoot = getUserVisibleDownloadsDir(context).absolutePath
        val parentPath = destination.parentFile?.absolutePath ?: downloadsRoot
        if (parentPath.startsWith(downloadsRoot)) {
            val suffix = parentPath.removePrefix(downloadsRoot).trimStart(File.separatorChar)
            return if (suffix.isBlank()) {
                Environment.DIRECTORY_DOWNLOADS
            } else {
                "${Environment.DIRECTORY_DOWNLOADS}/${suffix}"
            }
        }
        return Environment.DIRECTORY_DOWNLOADS
    }

    private suspend fun checksum(resolver: ContentResolver, uri: Uri): String? =
        withContext(Dispatchers.IO) {
            resolver.openInputStream(uri)?.use { stream ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read = stream.read(buffer)
                while (read != -1) {
                    digest.update(buffer, 0, read)
                    read = stream.read(buffer)
                }
                digest.digest().joinToString("") { "%02x".format(it) }
            }
        }

    private suspend fun markGalleryMediaSyncedDirect(
        request: UploadRequest,
        mimeType: String?,
        fileId: String,
        messageId: Long,
        uploaderToken: String
    ) {
        val galleryDao = database.galleryMediaDao()
        val media = findGalleryMediaForUpload(request, mimeType)
        if (media != null) {
            galleryDao.markSynced(media.id, fileId, messageId.toInt())
            galleryDao.updateUploaderToken(media.id, uploaderToken)
            Log.i(TAG, "markGalleryMediaSyncedDirect: Marked ${media.filename} as synced")
            return
        }
        
        val updated = galleryDao.markSyncedByNameAndSize(
            filename = request.displayName,
            sizeBytes = request.sizeBytes,
            tolerance = GALLERY_MATCH_TOLERANCE,
            fileId = fileId,
            messageId = messageId.toInt()
        )
        if (updated > 0) {
            galleryDao.updateUploaderTokenByName(
                filename = request.displayName,
                sizeBytes = request.sizeBytes,
                tolerance = GALLERY_MATCH_TOLERANCE,
                token = uploaderToken
            )
            Log.i(TAG, "markGalleryMediaSyncedDirect: Matched by filename ${request.displayName}")
        } else {
            Log.d(TAG, "markGalleryMediaSyncedDirect: No gallery entry matched for ${request.displayName}")
        }
    }

    private suspend fun markGalleryMediaSyncedChunked(
        request: UploadRequest,
        mimeType: String?,
        messageId: Long,
        telegramFileIds: List<String>,
        uploaderTokens: List<String>
    ) {
        if (telegramFileIds.isEmpty()) return
        val galleryDao = database.galleryMediaDao()
        val media = findGalleryMediaForUpload(request, mimeType)
        val fileId = telegramFileIds.first()
        val fileIdsJoined = telegramFileIds.joinToString(",")
        val tokensJoined = uploaderTokens.joinToString(",")
        if (media != null) {
            galleryDao.markSyncedChunked(
                id = media.id,
                fileId = fileId,
                messageId = messageId.toInt(),
                telegramFileIds = fileIdsJoined,
                uploaderTokens = tokensJoined
            )
            Log.i(TAG, "markGalleryMediaSyncedChunked: Marked ${media.filename} as synced (${telegramFileIds.size} chunks)")
            return
        }
        
        val updated = galleryDao.markSyncedChunkedByNameAndSize(
            filename = request.displayName,
            sizeBytes = request.sizeBytes,
            tolerance = GALLERY_MATCH_TOLERANCE,
            fileId = fileId,
            messageId = messageId.toInt(),
            telegramFileIds = fileIdsJoined,
            uploaderTokens = tokensJoined
        )
        if (updated > 0) {
            Log.i(TAG, "markGalleryMediaSyncedChunked: Matched by filename ${request.displayName}")
        } else {
            Log.d(TAG, "markGalleryMediaSyncedChunked: No gallery entry matched for ${request.displayName}")
        }
    }

    private suspend fun findGalleryMediaForUpload(
        request: UploadRequest,
        mimeType: String?
    ): com.telegram.cloud.gallery.GalleryMediaEntity? {
        if (!isMediaUpload(request.displayName, mimeType)) {
            return null
        }
        val uri = Uri.parse(request.uri)
        val localPath = resolveLocalPath(uri)
        val galleryDao = database.galleryMediaDao()
        val media = when {
            localPath != null -> galleryDao.getByPath(localPath)
            else -> galleryDao.getByNameAndSize(request.displayName, request.sizeBytes)
        }
        if (media == null) {
            Log.d(TAG, "findGalleryMediaForUpload: No gallery entry for ${request.displayName}")
        }
        return media
    }

    private fun resolveLocalPath(uri: Uri): String? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            return uri.path
        }
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return uri.path
        }
        val projection = arrayOf(
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val dataIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                if (dataIndex >= 0) {
                    cursor.getString(dataIndex)?.let { if (it.isNotBlank()) return it }
                }
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                val relativeIndex = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                val displayName = if (nameIndex >= 0) cursor.getString(nameIndex) else null
                val relativePath = if (relativeIndex >= 0) cursor.getString(relativeIndex) else null
                if (!relativePath.isNullOrBlank() && !displayName.isNullOrBlank()) {
                    val base = "/storage/emulated/0/"
                    val normalized = if (relativePath.endsWith("/")) relativePath else "$relativePath/"
                    return base + normalized + displayName
                }
                displayName
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveLocalPath: Unable to resolve path for $uri", e)
            null
        }
    }

    private fun isMediaUpload(displayName: String, mimeType: String?): Boolean {
        val resolvedMime = mimeType ?: guessMimeType(displayName)
        return resolvedMime?.startsWith("image/") == true || resolvedMime?.startsWith("video/") == true
    }

    private fun guessMimeType(displayName: String): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(displayName)?.lowercase()
        return if (!extension.isNullOrEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        } else {
            null
        }
    }
    
    /**
     * Get list of incomplete upload tasks that can be resumed.
     * Returns tasks with RUNNING or FAILED status that have fileId set (indicating chunked upload in progress).
     */
    suspend fun getIncompleteUploads(): List<UploadTaskEntity> {
        return withContext(Dispatchers.IO) {
            val incompleteTasks = database.uploadTaskDao().getIncompleteUploads()
            incompleteTasks.filter { task ->
                // Only return tasks that have progress data
                !task.fileId.isNullOrBlank() && !task.completedChunksJson.isNullOrBlank()
            }
        }
    }
    
    /**
     * Get list of incomplete download tasks that can be resumed.
     * Returns tasks with RUNNING or FAILED status that have chunk data (indicating chunked download in progress).
     */
    suspend fun getIncompleteDownloads(): List<DownloadTaskEntity> {
        return withContext(Dispatchers.IO) {
            val incompleteTasks = database.downloadTaskDao().getIncompleteDownloads()
            incompleteTasks.filter { task ->
                // Only return tasks that have progress data and temp directory exists
                task.totalChunks > 0 && 
                !task.chunkFileIds.isNullOrBlank() &&
                !task.tempChunkDir.isNullOrBlank() &&
                File(task.tempChunkDir).exists()
            }
        }
    }
    
    /**
     * Resume an upload task from where it left off.
     */
    suspend fun resumeUpload(taskId: Long, onProgress: ((Float) -> Unit)? = null) {
        Log.i(TAG, "resumeUpload: Resuming upload task $taskId")
        
        val task = database.uploadTaskDao().getById(taskId)
        if (task == null) {
            Log.e(TAG, "resumeUpload: Task $taskId not found")
            error("Upload task not found")
        }
        
        if (task.fileId == null || task.completedChunksJson == null) {
            Log.e(TAG, "resumeUpload: Task $taskId has no progress data")
            error("No progress data to resume from")
        }
        
        val cfg = config.first()
        if (cfg == null) {
            Log.e(TAG, "resumeUpload: No config found")
            error("Configura tokens y canal antes de subir")
        }
        
        // Reconstruct upload request from task
        val request = UploadRequest(
            uri = task.uri,
            displayName = task.displayName,
            caption = null,
            sizeBytes = task.sizeBytes
        )
        
        try {
            database.uploadTaskDao().update(task.copy(status = UploadStatus.RUNNING, error = null))
            
            val uri = Uri.parse(request.uri)
            val checksum = checksum(context.contentResolver, uri)
            val mimeType = context.contentResolver.getType(uri)
            
            // Call uploadChunked which will detect and resume from existing progress
            uploadChunked(request, cfg, taskId, uri, checksum, mimeType, onProgress)
            
            database.uploadTaskDao().update(task.copy(status = UploadStatus.COMPLETED, progress = 100))
            Log.i(TAG, "resumeUpload: Upload resumed and completed successfully")
            
        } catch (ex: Exception) {
            Log.e(TAG, "resumeUpload: Failed", ex)
            database.uploadTaskDao().update(
                task.copy(
                    status = UploadStatus.FAILED,
                    error = ex.message
                )
            )
            throw ex
        }
    }
    
    /**
     * Resume a download task from where it left off.
     */
    suspend fun resumeDownload(taskId: Long, onProgress: ((Float) -> Unit)? = null) {
        Log.i(TAG, "resumeDownload: Resuming download task $taskId")
        
        val task = database.downloadTaskDao().getById(taskId)
        if (task == null) {
            Log.e(TAG, "resumeDownload: Task $taskId not found")
            error("Download task not found")
        }
        
        if (task.totalChunks <= 0 || task.chunkFileIds == null || task.tempChunkDir == null) {
            Log.e(TAG, "resumeDownload: Task $taskId has no progress data")
            error("No progress data to resume from")
        }
        
        val tempDir = File(task.tempChunkDir)
        if (!tempDir.exists()) {
            Log.e(TAG, "resumeDownload: Temp directory does not exist: ${task.tempChunkDir}")
            error("Temp directory not found")
        }
        
        val cfg = config.first() ?: error("Config necesaria")
        
        try {
            database.downloadTaskDao().update(task.copy(status = DownloadStatus.RUNNING, error = null))
            
            val destination = File(task.targetPath)
            destination.parentFile?.mkdirs()
            
            // Get chunk file IDs
            val chunkFileIds = task.chunkFileIds.split(",").filter { it.isNotBlank() }
            
            // Parse completed chunks
            val completedChunkIndices: Set<Int> = try {
                val type = object : TypeToken<Set<Int>>() {}.type
                gson.fromJson(task.completedChunksJson ?: "[]", type) ?: emptySet()
            } catch (e: Exception) {
                Log.e(TAG, "resumeDownload: Failed to parse completed chunks JSON", e)
                emptySet()
            }
            
            Log.i(TAG, "resumeDownload: Resuming with ${completedChunkIndices.size} completed chunks")
            
            // Resume download
            val result = chunkedDownloadManager.resumeChunkedDownload(
                chunkFileIds = chunkFileIds,
                tokens = cfg.tokens,
                outputFile = destination,
                completedChunkIndices = completedChunkIndices,
                tempChunkDir = tempDir,
                onProgress = { completed, total, percent ->
                    kotlinx.coroutines.runBlocking {
                        database.downloadTaskDao().getById(taskId)?.let {
                            database.downloadTaskDao().update(it.copy(progress = percent.toInt()))
                        }
                    }
                    onProgress?.invoke(percent)
                },
                onChunkDownloaded = { index, file ->
                    // Save progress after each chunk
                    kotlinx.coroutines.runBlocking {
                        database.downloadTaskDao().getById(taskId)?.let { currentTask ->
                            val existingIndices: Set<Int> = try {
                                val type = object : TypeToken<Set<Int>>() {}.type
                                gson.fromJson(currentTask.completedChunksJson ?: "[]", type) ?: emptySet()
                            } catch (e: Exception) {
                                emptySet()
                            }
                            val allIndices = existingIndices + index
                            val indicesJson = gson.toJson(allIndices)
                            database.downloadTaskDao().update(
                                currentTask.copy(completedChunksJson = indicesJson)
                            )
                        }
                    }
                }
            )
            
            if (!result.success) {
                error("Chunked download failed: ${result.error}")
            }
            
            Log.i(TAG, "resumeDownload: Successfully reassembled file at ${destination.absolutePath}")
            moveFileToDownloads(
                context = context,
                source = destination,
                displayName = destination.name,
                mimeType = "application/octet-stream",
                subfolder = "telegram cloud app/Downloads"
            )?.let { moveResult ->
                Log.i(TAG, "resumeDownload: Moved to user downloads: ${moveResult.uri}")
            }
            
            database.downloadTaskDao().update(task.copy(status = DownloadStatus.COMPLETED, progress = 100))
            
            // Clear progress tracking and delete temp directory after successful download
            tempDir.deleteRecursively()
            database.downloadTaskDao().update(
                task.copy(
                    completedChunksJson = null,
                    chunkFileIds = null,
                    tempChunkDir = null,
                    totalChunks = 0
                )
            )
            
            Log.i(TAG, "resumeDownload: Download resumed and completed successfully")
            
        } catch (ex: Exception) {
            Log.e(TAG, "resumeDownload: Failed", ex)
            database.downloadTaskDao().update(
                task.copy(
                    status = DownloadStatus.FAILED,
                    error = ex.message
                )
            )
            throw ex
        }
    }
}

