package com.telegram.cloud.ui

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.telegram.cloud.data.backup.BackupManager
import com.telegram.cloud.data.prefs.BotConfig
import com.telegram.cloud.data.repository.TelegramRepository
import com.telegram.cloud.data.share.ShareLinkManager
import com.telegram.cloud.domain.model.CloudFile
import com.telegram.cloud.domain.model.DownloadRequest
import com.telegram.cloud.domain.model.UploadRequest
import com.telegram.cloud.gallery.GalleryMediaEntity
import com.telegram.cloud.gallery.DownloadWorker
import com.telegram.cloud.gallery.UploadWorker
import com.telegram.cloud.tasks.TaskProgress
import com.telegram.cloud.tasks.TaskQueueManager
import com.telegram.cloud.tasks.TaskStatus
import com.telegram.cloud.tasks.TaskType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import android.webkit.MimeTypeMap
import com.telegram.cloud.utils.moveFileToDownloads
import com.telegram.cloud.R

data class DashboardState(
    val files: List<CloudFile> = emptyList(),
    val isUploading: Boolean = false,
    val isDownloading: Boolean = false,
    val uploadProgress: Float = 0f,
    val downloadProgress: Float = 0f,
    val currentFileName: String? = null,
    val isConfigLoaded: Boolean = false,
    // Gallery sync state
    val isGallerySyncing: Boolean = false,
    val gallerySyncProgress: Float = 0f,
    val gallerySyncFileName: String? = null
)

class MainViewModel(
    private val context: Context,
    private val repository: TelegramRepository,
    private val backupManager: BackupManager,
    private val taskQueueManager: TaskQueueManager
) : ViewModel() {

    private val shareLinkManager = ShareLinkManager()
    
    val config: StateFlow<BotConfig?> = repository.config
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _uploading = MutableStateFlow(false)
    private val _downloading = MutableStateFlow(false)
    private val _uploadProgress = MutableStateFlow(0f)
    private val _downloadProgress = MutableStateFlow(0f)
    private val _currentFileName = MutableStateFlow<String?>(null)
    private val _configLoaded = MutableStateFlow(false)
    private val _events = MutableSharedFlow<UiEvent>()

    val events = _events.asSharedFlow()
    
    init {
        // Mark config as loaded after first emission
        viewModelScope.launch {
            repository.config.first()
            _configLoaded.value = true
            
            // Detect incomplete uploads/downloads on app startup
            try {
                val incompleteUploads = repository.getIncompleteUploads()
                val incompleteDownloads = repository.getIncompleteDownloads()
                
                if (incompleteUploads.isNotEmpty() || incompleteDownloads.isNotEmpty()) {
                    Log.i("MainViewModel", "Found ${incompleteUploads.size} incomplete uploads and ${incompleteDownloads.size} incomplete downloads on startup")
                    // Tasks will be automatically resumed when workers are re-scheduled
                    // The workers will detect the incomplete state and resume
                }
            } catch (e: Exception) {
                Log.e("MainViewModel", "Error detecting incomplete tasks", e)
            }
        }
        // Track task queue progress to keep dashboard progress bars updated
        viewModelScope.launch {
            taskQueueManager.progressUpdates.collect { progress ->
                when (progress.type) {
                    TaskType.UPLOAD -> {
                        _uploadProgress.value = progress.progress
                        _currentFileName.value = progress.fileName
                    }
                    TaskType.DOWNLOAD -> {
                        _downloadProgress.value = progress.progress
                        _currentFileName.value = progress.fileName
                    }
                    else -> Unit
                }
            }
        }
        // Track task queue status
        var wasUploading = false
        viewModelScope.launch {
            taskQueueManager.getUploadQueue().queueItems.collect { tasks ->
                val activeTasks = tasks.any { !it.isCompleted }
                Log.d("MainViewModel", "Upload queue update: ${tasks.size} tasks, active=$activeTasks")
                _uploading.value = activeTasks
                
                // Si todas las tareas se completaron (dejó de estar activo), recargar archivos
                if (wasUploading && !activeTasks) {
                    Log.d("MainViewModel", "All upload tasks completed, reloading files")
                    // Pequeño delay para asegurar que todas las inserciones en la base de datos se completen
                    kotlinx.coroutines.delay(200)
                    repository.reloadFilesFromDatabase()
                    _uploadProgress.value = 0f
                }
                wasUploading = activeTasks
            }
        }
        viewModelScope.launch {
            taskQueueManager.getDownloadQueue().queueItems.collect { tasks ->
                // Considerar activas las tareas que están RUNNING o QUEUED (no solo las que no están completadas)
                val activeTasks = tasks.any { it.status == TaskStatus.RUNNING || it.status == TaskStatus.QUEUED }
                Log.d("MainViewModel", "Download queue update: ${tasks.size} tasks, active=$activeTasks")
                _downloading.value = activeTasks
                if (!activeTasks) {
                    _downloadProgress.value = 0f
                }
            }
        }
    }

    val dashboardState: StateFlow<DashboardState> = combine(
        repository.files,
        _uploading,
        _downloading,
        _uploadProgress,
        _downloadProgress,
        _currentFileName,
        _configLoaded
    ) { array ->
        @Suppress("UNCHECKED_CAST")
        DashboardState(
            files = array[0] as List<CloudFile>,
            isUploading = array[1] as Boolean,
            isDownloading = array[2] as Boolean,
            uploadProgress = array[3] as Float,
            downloadProgress = array[4] as Float,
            currentFileName = array[5] as String?,
            isConfigLoaded = array[6] as Boolean
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardState())
    
    init {
        // Cargar archivos iniciales cuando el ViewModel se crea
        viewModelScope.launch {
            repository.reloadFilesFromDatabase()
        }
    }

    fun saveConfig(tokens: List<String>, channelId: String, chatId: String?) {
        viewModelScope.launch {
            repository.saveConfig(
                BotConfig(tokens = tokens.filter { it.isNotBlank() }, channelId = channelId, chatId = chatId)
            )
        }
    }

    fun upload(request: UploadRequest) {
        viewModelScope.launch {
            taskQueueManager.addUploadTasks(listOf(request))
            // _uploading state is controlled by the queue collector
            _currentFileName.value = request.displayName
        }
    }
    
    fun uploadMultiple(requests: List<UploadRequest>) {
        viewModelScope.launch {
            taskQueueManager.addUploadTasks(requests)
            _uploading.value = true
            _currentFileName.value = if (requests.size == 1) requests.first().displayName else "${requests.size} files"
        }
    }

    fun download(request: DownloadRequest) {
        viewModelScope.launch {
            taskQueueManager.addDownloadTasks(listOf(request))
            _downloading.value = true
            _currentFileName.value = request.file.fileName
        }
    }
    
    fun downloadMultiple(requests: List<DownloadRequest>) {
        viewModelScope.launch {
            taskQueueManager.addDownloadTasks(requests)
            _downloading.value = true
            _currentFileName.value = if (requests.size == 1) requests.first().file.fileName else "${requests.size} files"
        }
    }

    fun deleteFile(file: CloudFile) {
        viewModelScope.launch {
            try {
                repository.deleteFile(file)
                _events.emit(UiEvent.Message(context.getString(R.string.file_deleted)))
            } catch (ex: Exception) {
                _events.emit(UiEvent.Message(context.getString(R.string.error_deleting, ex.message ?: context.getString(R.string.unknown_error))))
            }
        }
    }

    fun createBackup(targetFile: File, password: String?) {
        viewModelScope.launch {
            try {
                backupManager.createBackup(targetFile, password)
                _events.emit(UiEvent.Message(context.getString(R.string.backup_saved, targetFile.absolutePath)))
                _events.emit(UiEvent.ShareFile(targetFile))
            } catch (ex: Exception) {
                _events.emit(UiEvent.Message(context.getString(R.string.error_creating_backup, ex.message ?: context.getString(R.string.unknown_error))))
            }
        }
    }

    fun restoreBackup(sourceFile: File, password: String?) {
        viewModelScope.launch {
            try {
                backupManager.restoreBackup(sourceFile, password)
                _events.emit(UiEvent.Message(context.getString(R.string.backup_restored_restarting)))
                _events.emit(UiEvent.RestartApp)
            } catch (ex: Exception) {
                _events.emit(UiEvent.Message(context.getString(R.string.error_restoring, ex.message ?: context.getString(R.string.unknown_error))))
            }
        }
    }

    suspend fun requiresPassword(file: File): Boolean =
        backupManager.requiresPassword(file)
    
    /**
     * Refrescar la lista de archivos forzando actualización del Flow
     */
    fun refreshFiles() {
        viewModelScope.launch {
            repository.refreshFiles()
        }
    }
    
    /**
     * Generate .link file for sharing (compatible with desktop)
     * @param file The file to share
     * @param password User-defined password for encryption
     * @param outputFile The .link file to create
     * @param onResult Callback with success status
     */
    fun generateLinkFile(
        file: CloudFile,
        password: String,
        outputFile: File,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val cfg = repository.config.first()
                if (cfg == null) {
                    Log.e("MainViewModel", "generateLinkFile: No config available")
                    onResult(false)
                    return@launch
                }
                
                val entity = repository.getFileEntity(file.id)
                if (entity == null) {
                    Log.e("MainViewModel", "generateLinkFile: File not found in database")
                    onResult(false)
                    return@launch
                }
                
                val botToken = cfg.tokens.firstOrNull() ?: ""
                
                val success = shareLinkManager.generateLinkFile(entity, botToken, password, outputFile)
                
                if (success) {
                    Log.i("MainViewModel", "generateLinkFile: Created ${outputFile.absolutePath}")
                    _events.emit(UiEvent.Message("Archivo .link creado"))
                } else {
                    _events.emit(UiEvent.Message("Error al crear archivo .link"))
                }
                
                onResult(success)
                
            } catch (e: Exception) {
                Log.e("MainViewModel", "generateLinkFile: Error", e)
                _events.emit(UiEvent.Message(context.getString(R.string.error_with_message, e.message ?: context.getString(R.string.unknown_error))))
                onResult(false)
            }
        }
    }
    
    /**
     * Generate .link file from GalleryMediaEntity (for gallery share)
     */
    fun generateLinkFileFromGallery(
        media: GalleryMediaEntity,
        password: String,
        outputFile: File,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val cfg = repository.config.first()
                if (cfg == null) {
                    Log.e("MainViewModel", "generateLinkFileFromGallery: No config available")
                    onResult(false)
                    return@launch
                }
                
                if (!media.isSynced) {
                    Log.e("MainViewModel", "generateLinkFileFromGallery: Media not synced")
                    _events.emit(UiEvent.Message(context.getString(R.string.file_not_synced)))
                    onResult(false)
                    return@launch
                }
                
                // Buscar CloudFileEntity por telegramMessageId o telegramFileId
                val entity = repository.getFileEntityFromGallery(media)
                
                if (entity == null) {
                    Log.e("MainViewModel", "generateLinkFileFromGallery: File not found in database (messageId=${media.telegramMessageId}, fileId=${media.telegramFileId?.take(20)})")
                    _events.emit(UiEvent.Message(context.getString(R.string.file_not_found_in_database)))
                    onResult(false)
                    return@launch
                }
                
                val botToken = cfg.tokens.firstOrNull() ?: ""
                
                val success = shareLinkManager.generateLinkFile(entity, botToken, password, outputFile)
                
                if (success) {
                    Log.i("MainViewModel", "generateLinkFileFromGallery: Created ${outputFile.absolutePath}")
                    _events.emit(UiEvent.Message("Archivo .link creado"))
                } else {
                    _events.emit(UiEvent.Message("Error al crear archivo .link"))
                }
                
                onResult(success)
                
            } catch (e: Exception) {
                Log.e("MainViewModel", "generateLinkFileFromGallery: Error", e)
                _events.emit(UiEvent.Message(context.getString(R.string.error_with_message, e.message ?: context.getString(R.string.unknown_error))))
                onResult(false)
            }
        }
    }
    
    /**
     * Generate batch .link file from multiple GalleryMediaEntity (for gallery share)
     */
    fun generateBatchLinkFileFromGallery(
        mediaList: List<GalleryMediaEntity>,
        password: String,
        outputFile: File,
        onResult: (Boolean) -> Unit
    ) {
        viewModelScope.launch {
            try {
                val cfg = repository.config.first()
                if (cfg == null) {
                    Log.e("MainViewModel", "generateBatchLinkFileFromGallery: No config available")
                    onResult(false)
                    return@launch
                }
                
                val entities = mutableListOf<com.telegram.cloud.data.local.CloudFileEntity>()
                val botTokens = mutableListOf<String>()
                
                for (media in mediaList) {
                    if (!media.isSynced) {
                        Log.w("MainViewModel", "generateBatchLinkFileFromGallery: Skipping unsynced media: ${media.filename}")
                        continue
                    }
                    
                    val entity = repository.getFileEntityFromGallery(media)
                    
                    if (entity != null) {
                        entities.add(entity)
                        // Use the token from media or default
                        val token = media.telegramUploaderTokens?.split(",")?.firstOrNull() 
                            ?: cfg.tokens.firstOrNull() ?: ""
                        botTokens.add(token)
                    } else {
                        Log.w("MainViewModel", "generateBatchLinkFileFromGallery: File not found for ${media.filename}")
                    }
                }
                
                if (entities.isEmpty()) {
                    Log.e("MainViewModel", "generateBatchLinkFileFromGallery: No valid files found")
                    _events.emit(UiEvent.Message(context.getString(R.string.no_valid_files_found)))
                    onResult(false)
                    return@launch
                }
                
                val success = shareLinkManager.generateBatchLinkFile(entities, botTokens, password, outputFile)
                
                if (success) {
                    Log.i("MainViewModel", "generateBatchLinkFileFromGallery: Created ${outputFile.absolutePath} for ${entities.size} files")
                    _events.emit(UiEvent.Message(context.getString(R.string.link_file_created_for_files, entities.size)))
                } else {
                    _events.emit(UiEvent.Message("Error al crear archivo .link"))
                }
                
                onResult(success)
                
            } catch (e: Exception) {
                Log.e("MainViewModel", "generateBatchLinkFileFromGallery: Error", e)
                _events.emit(UiEvent.Message(context.getString(R.string.error_with_message, e.message ?: context.getString(R.string.unknown_error))))
                onResult(false)
            }
        }
    }
    
    /**
     * Download files from a .link file (simple wrapper)
     */
    fun downloadFromLink(
        linkFile: File,
        password: String,
        tempDir: File
    ) {
        downloadFromLinkFile(linkFile, password, tempDir, null)
    }
    
    /**
     * Download files from a .link file (compatible with desktop)
     */
    fun downloadFromLinkFile(
        linkFile: File,
        linkPassword: String,
        tempDir: File,
        filePassword: String? = null
    ) {
        viewModelScope.launch {
            try {
                _downloading.value = true
                _downloadProgress.value = 0f
                
                val linkData = shareLinkManager.readLinkFile(linkFile, linkPassword)
                if (linkData == null) {
                    _events.emit(UiEvent.Message(context.getString(R.string.wrong_password_or_corrupt_file)))
                    return@launch
                }
                
                // Establecer nombre del archivo inicial
                _currentFileName.value = linkData.files.firstOrNull()?.fileName ?: context.getString(R.string.file_fallback)
                
                val cfg = repository.config.first()
                val linkDownloadManager = com.telegram.cloud.data.share.LinkDownloadManager(
                    com.telegram.cloud.data.remote.TelegramBotClient()
                )
                
                val results = linkDownloadManager.downloadFromLink(
                    linkData = linkData,
                    destinationDir = tempDir,
                    filePassword = filePassword
                ) { completed: Int, total: Int, phase: String, percent: Double ->
                    // Actualizar progreso directamente (StateFlow es thread-safe)
                    // Convertir porcentaje (0-100) a Float (0.0-1.0) para la barra de progreso
                    val progressFloat = (percent / 100.0).coerceIn(0.0, 1.0).toFloat()
                    _downloadProgress.value = progressFloat
                    
                    // Extraer nombre del archivo de la fase si está incluido
                    // Formato: "[N/M] phase: fileName" o "phase: fileName"
                    val fileNameMatch = Regex(": ([^:]+)$").find(phase)
                    if (fileNameMatch != null) {
                        val fileName = fileNameMatch.groupValues[1]
                        _currentFileName.value = fileName
                    }
                    
                    Log.d("MainViewModel", "Link download progress: $phase - $completed/$total (${percent.toInt()}%)")
                }

                results.forEach { result ->
                    result.filePath?.let { rawPath ->
                        val file = File(rawPath)
                        val extension = MimeTypeMap.getFileExtensionFromUrl(rawPath).lowercase()
                        val resolvedMime = extension.takeIf { it.isNotBlank() }
                            ?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                            ?: "application/octet-stream"
                        moveFileToDownloads(
                            context = context,
                            source = file,
                            displayName = file.name,
                            mimeType = resolvedMime,
                            subfolder = "telegram cloud app/Downloads"
                        )?.let { moved ->
                            Log.i("MainViewModel", "Link download moved to ${moved.uri}")
                        }
                    }
                }

                val successCount = results.count { it.success }
                _events.emit(UiEvent.Message(context.getString(R.string.files_downloaded, successCount, results.size)))
                
            } catch (e: Exception) {
                Log.e("MainViewModel", "downloadFromLinkFile: Error", e)
                _events.emit(UiEvent.Message(context.getString(R.string.error_with_message, e.message ?: context.getString(R.string.unknown_error))))
            } finally {
                _downloading.value = false
                _downloadProgress.value = 0f
                _currentFileName.value = null
            }
        }
    }
}

sealed interface UiEvent {
    data class Message(val text: String) : UiEvent
    data class ShareFile(val file: File) : UiEvent
    object RestartApp : UiEvent
}

class MainViewModelFactory(
    private val context: Context,
    private val repository: TelegramRepository,
    private val backupManager: BackupManager,
    private val taskQueueManager: TaskQueueManager
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(context, repository, backupManager, taskQueueManager) as T
        }
        throw IllegalArgumentException("Tipo de ViewModel no soportado")
    }
}

