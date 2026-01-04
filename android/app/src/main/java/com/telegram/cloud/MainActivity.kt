package com.telegram.cloud

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.telegram.cloud.domain.model.CloudFile
import com.telegram.cloud.domain.model.DownloadRequest
import com.telegram.cloud.domain.model.UploadRequest
import com.telegram.cloud.gallery.*
import com.telegram.cloud.data.batch.MultiFileDashboardManager
import com.telegram.cloud.data.remote.TelegramBotClient
import com.telegram.cloud.data.share.LinkDownloadManager
import com.telegram.cloud.data.share.MultiLinkDownloadManager
import com.telegram.cloud.data.share.MultiLinkGenerator
import com.telegram.cloud.data.share.ShareLinkManager
import com.telegram.cloud.ui.MainViewModel
import com.telegram.cloud.ui.MainViewModelFactory
import com.telegram.cloud.ui.UiEvent
import com.telegram.cloud.ui.screen.DashboardScreen
import com.telegram.cloud.ui.screen.SetupScreen
import com.telegram.cloud.ui.screen.SplashScreen
import com.telegram.cloud.ui.theme.TelegramCloudTheme
import com.telegram.cloud.utils.getUserVisibleDownloadsDir
import com.telegram.cloud.utils.getUserVisibleSubDir
import com.telegram.cloud.utils.moveFileToDownloads
import com.telegram.cloud.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import com.telegram.cloud.ui.MainDialogState
import com.telegram.cloud.ui.RestorePasswordDialog
import com.telegram.cloud.ui.CreateBackupPasswordDialog
import com.telegram.cloud.ui.LinkPasswordDialog
import com.telegram.cloud.gallery.GalleryMediaEntity


import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private var isPaused = false
    private var shouldShowBackgroundDialog = false
    
    override fun onPause() {
        super.onPause()
        isPaused = true
        // Check if sync is active - will be handled in Compose
    }
    
    override fun onResume() {
        super.onResume()
        isPaused = false
        shouldShowBackgroundDialog = false
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TelegramCloudApp).container
        
        val factory = MainViewModelFactory(
            applicationContext,
            container.repository,
            container.backupManager,
            container.taskQueueManager,
            container.localFileRepository
        )
        val galleryFactory = GalleryViewModelFactory(
            this,
            container.mediaScanner,
            container.gallerySyncManager,
            container.database,
            container.galleryRestoreManager
        )

        setContent {
            val viewModel: MainViewModel = viewModel(factory = factory)
            val galleryViewModel: GalleryViewModel = viewModel(factory = galleryFactory)
            
            // Initialize managers
            val shareLinkManager = remember { ShareLinkManager() }
            val multiLinkGenerator = remember { MultiLinkGenerator(shareLinkManager, container.repository) }
            val multiFileGalleryManager = remember { MultiFileGalleryManager(galleryViewModel, multiLinkGenerator) }
            val multiFileDashboardManager = remember { MultiFileDashboardManager(viewModel, multiLinkGenerator) }
            val linkDownloadManager = remember { LinkDownloadManager(TelegramBotClient()) }
            val multiLinkDownloadManager = remember { MultiLinkDownloadManager(linkDownloadManager, shareLinkManager) }
            
            // Connect gallery sync logs to main sync engine
            LaunchedEffect(Unit) {
                container.gallerySyncManager.onPendingSyncLogs = {
                    viewModel.performSync()
                }
            }
            
            val config by viewModel.config.collectAsState()
            val dashboard by viewModel.dashboardState.collectAsState()
            val context = LocalContext.current
            val snackbarHostState = remember { SnackbarHostState() }
            val scope = rememberCoroutineScope()
            val lifecycleOwner = LocalLifecycleOwner.current
            
            // Refrescar feed cuando la app vuelve a primer plano
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) {
                        // Forzar actualización del Flow
                        scope.launch {
                            viewModel.refreshFiles()
                        }
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                }
            }
            var editingConfig by rememberSaveable { mutableStateOf(false) }
            
            // Files removed from here (pendingRestoreFile, showPasswordDialog, etc.) as they are now in ViewModel state

            
            val linksDir = remember {
                File(context.getExternalFilesDir(null), "links").apply { mkdirs() }
            }

            val downloadTempDir = remember {
                File(context.cacheDir, "downloads").apply { mkdirs() }
            }
            val linkDownloadTempDir = remember {
                File(context.cacheDir, "link-downloads").apply { mkdirs() }
            }
            // State for batch operations
            var isBatchDownloading by remember { mutableStateOf(false) }
            var batchDownloadProgress by remember { mutableStateOf(0f) }
            var batchDownloadTotal by remember { mutableStateOf(0) }
            var batchDownloadCurrent by remember { mutableStateOf(0) }
            
            // Helper to reset batch state
            fun resetBatchState() {
                isBatchDownloading = false
                batchDownloadProgress = 0f
                batchDownloadTotal = 0
                batchDownloadCurrent = 0
            }


            
            // Multiple files picker
            val pickMultipleFilesLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    // Take permissions immediately
                    uris.forEach { uri ->
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error taking permission for $uri", e)
                        }
                    }
                    // Hand off to ViewModel for processing (meta query + queueing)
                    viewModel.handleUploads(uris)
                }
            }

            val restoreLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    viewModel.handleRestoreBackupUri(uri)
                }
            }
            
            // Link file picker
            val linkFileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    viewModel.handleLinkFileUri(uri)
                }
            }

            LaunchedEffect(Unit) {
                viewModel.events.collect { event ->
                    when (event) {
                        is UiEvent.ShareFile -> shareBackupFile(event.file)
                        is UiEvent.Message -> snackbarHostState.showSnackbar(event.text)
                        UiEvent.RestartApp -> restartApp()
                    }
                }
            }

            TelegramCloudTheme {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) }
                ) { padding ->
                    Box(modifier = Modifier.padding(padding)) {
                        val dialogState by viewModel.dialogState.collectAsState()
                        
                        when (val state = dialogState) {
                            is MainDialogState.RestorePassword -> {
                                RestorePasswordDialog(
                                    onConfirm = { password ->
                                        viewModel.restoreBackup(state.file, password)
                                        viewModel.dismissDialog()
                                    },
                                    onDismiss = { viewModel.dismissDialog() }
                                )
                            }
                            is MainDialogState.CreateBackupPassword -> {
                                CreateBackupPasswordDialog(
                                    onConfirm = { password ->
                                        viewModel.createBackup(state.targetFile, password)
                                        viewModel.dismissDialog()
                                    },
                                    onDismiss = { viewModel.dismissDialog() }
                                )
                            }
                            is MainDialogState.LinkPassword -> {
                                LinkPasswordDialog(
                                    onConfirm = { password ->
                                        val fileToDownload = state.file
                                        viewModel.dismissDialog()
                                        
                                        scope.launch {
                                            isBatchDownloading = true
                                            batchDownloadTotal = 0
                                            batchDownloadCurrent = 0
                                            batchDownloadProgress = 0f
                                            
                                            Log.i("MainActivity", "Download from link: file=${fileToDownload.absolutePath}, password=${password.length} chars")
                                            
                                            val results = multiLinkDownloadManager.downloadFromMultiLink(
                                                linkFile = fileToDownload,
                                                password = password,
                                                destDir = linkDownloadTempDir
                                            ) { progress, _ ->
                                                batchDownloadProgress = progress
                                            }
                                            
                                            resetBatchState()
                                            
                                            if (results.isNotEmpty()) {
                                                val successCount = results.count { it.success }
                                                snackbarHostState.showSnackbar(context.getString(R.string.files_downloaded, successCount, results.size))
                                                
                                                results.forEach { result ->
                                                    result.filePath?.let { rawPath ->
                                                        val file = File(rawPath)
                                                        val extension = file.extension.lowercase()
                                                        val resolvedMime = extension.takeIf { it.isNotBlank() }
                                                            ?: "application/octet-stream"
                                                        
                                                         shareFile(this@MainActivity, file, resolvedMime)
                                                    }
                                                }
                                            }
                                        }
                                    },
                                    onDismiss = { viewModel.dismissDialog() }
                                )
                            }
                            else -> {} // Handle other dialog states or no dialog
                        }

                        var showShareDialog by rememberSaveable { mutableStateOf(false) }
                        var mediaListToShare by remember { mutableStateOf<List<GalleryMediaEntity>?>(null) }
                        var filesToShareBatch by remember { mutableStateOf<List<CloudFile>?>(null) }
                        var sharePassword by rememberSaveable { mutableStateOf("") }

                        if (showShareDialog && mediaListToShare != null) {
                            AlertDialog(
                                onDismissRequest = {
                                    showShareDialog = false
                                    mediaListToShare = null
                                    sharePassword = ""
                                },
                                title = { Text(stringResource(R.string.create_link_file)) },
                                text = {
                                    Column {
                                        Text("Crear archivo .link para ${mediaListToShare!!.size} archivo(s)")
                                        Spacer(Modifier.height(8.dp))
                                        Text(stringResource(R.string.define_link_password))
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = sharePassword,
                                            onValueChange = { sharePassword = it },
                                            label = { Text(stringResource(R.string.password)) },
                                            singleLine = true
                                        )
                                    }
                                },
                                confirmButton = {
                                    val shareLabel = stringResource(R.string.share)
                                    TextButton(
                                        enabled = sharePassword.isNotBlank(),
                                        onClick = {
                                            mediaListToShare?.let { mediaList ->
                                                // Double-check password is not empty
                                                if (sharePassword.isBlank()) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("Password is required")
                                                    }
                                                    return@TextButton
                                                }
                                                
                                                // Capture password before resetting state
                                                val passwordToUse = sharePassword
                                                
                                                // Generate descriptive filename
                                                val totalSize = mediaList.sumOf { it.sizeBytes }
                                                val shortSize = formatShortSize(totalSize)
                                                val linkFileName = if (mediaList.size == 1) {
                                                    "${sanitizeFileName(mediaList.first().filename)}-${shortSize}.link"
                                                } else {
                                                    "batch_${mediaList.size}files-${shortSize}.link"
                                                }
                                                val linkFile = File(linksDir, linkFileName)
                                                
                                                // Reset state immediately
                                                showShareDialog = false
                                                mediaListToShare = null
                                                sharePassword = ""
                                                
                                                viewModel.generateBatchLinkFileFromGallery(mediaList, passwordToUse, linkFile) { success ->
                                                    if (success) {
                                                        // Share the .link file
                                                        val uri = FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.provider",
                                                            linkFile
                                                        )
                                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "application/octet-stream"
                                                            putExtra(Intent.EXTRA_STREAM, uri)
                                                            putExtra(Intent.EXTRA_SUBJECT, "Telegram Cloud: batch_share.link")
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        startActivity(Intent.createChooser(shareIntent, shareLabel))
                                                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.link_file_shared, mediaList.size)) }
                                                    } else {
                                                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.error_creating_link_file)) }
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.create_and_share))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showShareDialog = false
                                        mediaListToShare = null
                                        sharePassword = ""
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                        
                        // Share dialog for dashboard files (multiple)
                        if (showShareDialog && filesToShareBatch != null) {
                            AlertDialog(
                                onDismissRequest = {
                                    showShareDialog = false
                                    filesToShareBatch = null
                                    sharePassword = ""
                                },
                                title = { Text(stringResource(R.string.create_link_file)) },
                                text = {
                                    Column {
                                        Text("Crear archivo .link para ${filesToShareBatch!!.size} archivo(s)")
                                        Spacer(Modifier.height(8.dp))
                                        Text(stringResource(R.string.define_link_password))
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = sharePassword,
                                            onValueChange = { sharePassword = it },
                                            label = { Text(stringResource(R.string.password)) },
                                            singleLine = true
                                        )
                                    }
                                },
                                confirmButton = {
                                    val shareLabel = stringResource(R.string.share)
                                    TextButton(
                                        enabled = sharePassword.isNotBlank(),
                                        onClick = {
                                            filesToShareBatch?.let { files ->
                                                // Double-check password is not empty
                                                if (sharePassword.isBlank()) {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar("Password is required")
                                                    }
                                                    return@TextButton
                                                }
                                                
                                                // Capture password before resetting state
                                                val passwordToUse = sharePassword
                                                
                                                // Generate descriptive filename
                                                val totalSize = files.sumOf { it.sizeBytes }
                                                val shortSize = formatShortSize(totalSize)
                                                val linkFileName = if (files.size == 1) {
                                                    "${sanitizeFileName(files.first().fileName)}-${shortSize}.link"
                                                } else {
                                                    "batch_${files.size}files-${shortSize}.link"
                                                }
                                                val linkFile = File(linksDir, linkFileName)
                                                
                                                // Reset state immediately
                                                showShareDialog = false
                                                filesToShareBatch = null
                                                sharePassword = ""
                                                
                                                scope.launch {
                                                    val success = multiFileDashboardManager.generateBatchLink(files, passwordToUse, linkFile)
                                                    if (success) {
                                                        // Share the .link file
                                                        val uri = FileProvider.getUriForFile(
                                                            context,
                                                            "${context.packageName}.provider",
                                                            linkFile
                                                        )
                                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                            type = "application/octet-stream"
                                                            putExtra(Intent.EXTRA_STREAM, uri)
                                                            putExtra(Intent.EXTRA_SUBJECT, "Telegram Cloud: batch_share.link")
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        startActivity(Intent.createChooser(shareIntent, shareLabel))
                                                        snackbarHostState.showSnackbar(context.getString(R.string.link_file_shared, files.size))
                                                    } else {
                                                        snackbarHostState.showSnackbar(context.getString(R.string.error_creating_link_file))
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Text(stringResource(R.string.create_and_share))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showShareDialog = false
                                        filesToShareBatch = null
                                        sharePassword = ""
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }

                        // Show setup only when config is loaded AND empty, or when editing
                        val showSetup = dashboard.isConfigLoaded && (editingConfig || config == null)
                        var showGallery by rememberSaveable { mutableStateOf(false) }
                        var selectedMedia by rememberSaveable { mutableStateOf<Long?>(null) }
                        var showTaskQueue by rememberSaveable { mutableStateOf(false) }
                        var showWizard by rememberSaveable { mutableStateOf(false) }
                        
                        // Gallery state

                        val gallerySyncState by galleryViewModel.syncState.collectAsState()
                        val gallerySyncProgress by galleryViewModel.syncProgress.collectAsState()
                        val gallerySyncedCount by galleryViewModel.syncedCount.collectAsState()
                        val galleryTotalCount by galleryViewModel.totalCount.collectAsState()
                        val gallerySyncFileName by galleryViewModel.currentSyncFileName.collectAsState()
                        val galleryUiState by galleryViewModel.uiState.collectAsState()
                        val galleryFilterState by galleryViewModel.filterState.collectAsState()
                        val galleryRestoreState by galleryViewModel.restoreState.collectAsState()
                        val galleryRestoreProgress by galleryViewModel.restoreProgress.collectAsState()
                        val streamingProgress by galleryViewModel.streamingProgress.collectAsState()
                        
                        // Determine if any operation is active
                        val isGallerySyncing = gallerySyncState is com.telegram.cloud.gallery.GallerySyncManager.SyncState.Syncing
                        val isUploading = dashboard.isUploading
                        val isDownloading = dashboard.isDownloading
                        val hasActiveOperation = isGallerySyncing || isUploading || isDownloading
                        
                        // Dialog state for background operation confirmation
                        var showBackgroundOperationDialog by remember { mutableStateOf(false) }
                        
                        // Handle back button when operations are active
                        BackHandler(enabled = hasActiveOperation && !showGallery) {
                            showBackgroundOperationDialog = true
                        }
                        
                        // Show dialog when user tries to leave app while operations are active
                        if (showBackgroundOperationDialog) {
                            val operationType = when {
                                isGallerySyncing -> "sincronización"
                                isUploading -> "carga"
                                isDownloading -> "descarga"
                                else -> "operación"
                            }
                            
                            AlertDialog(
                                onDismissRequest = { showBackgroundOperationDialog = false },
                                title = { Text("Operación en progreso") },
                                text = {
                                    Text("Hay una $operationType en progreso. ¿Deseas continuar en segundo plano?")
                                },
                                confirmButton = {
                                    TextButton(
                                        onClick = {
                                            showBackgroundOperationDialog = false
                                            // Operations will continue in background via WorkManager
                                            finish()
                                        }
                                    ) {
                                        Text("Continuar en segundo plano")
                                    }
                                },
                                dismissButton = {
                                    TextButton(
                                        onClick = {
                                            showBackgroundOperationDialog = false
                                            // Cancel operations
                                            if (isGallerySyncing) {
                                                galleryViewModel.cancelSync()
                                            }
                                            // Upload/Download workers will be cancelled by WorkManager when app is killed
                                            finish()
                                        }
                                    ) {
                                        Text("Cancelar y salir")
                                    }
                                }
                            )
                        }
                        
                        // Batch Download Progress is now shown inline via DashboardScreen ProgressCard

                        val storagePermissions = remember {
                            when {
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
                                    Manifest.permission.READ_MEDIA_IMAGES,
                                    Manifest.permission.READ_MEDIA_VIDEO,
                                    Manifest.permission.READ_MEDIA_AUDIO
                                )
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                )
                                else -> arrayOf(
                                    Manifest.permission.READ_EXTERNAL_STORAGE,
                                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                                )
                            }
                        }
                        
                        val awaitingStoragePermission = remember { mutableStateOf(false) }
                        
                        val storagePermissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) { permissions ->
                            val allGranted = permissions.values.all { it }
                            awaitingStoragePermission.value = false
                            if (!allGranted) {
                                Log.w("MainActivity", "Permisos de almacenamiento denegados")
                            }
                        }
                        
                        val galleryPermissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestMultiplePermissions()
                        ) { permissions ->
                            val allGranted = permissions.values.all { it }
                            if (allGranted) {
                                galleryViewModel.scanMedia()
                            }
                        }
                        
                        val notificationPermissionLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.RequestPermission()
                        ) { granted ->
                            if (granted) {
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.notification_permission_granted))
                                }
                            }
                        }
                        
                        val hasRequestedInitialPermissions = remember { mutableStateOf(false) }
                        
                        LaunchedEffect(hasRequestedInitialPermissions.value) {
                            if (!hasRequestedInitialPermissions.value) {
                                val storageMissing = storagePermissions.any {
                                    ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
                                }
                                if (storageMissing) {
                                    awaitingStoragePermission.value = true
                                    storagePermissionLauncher.launch(storagePermissions)
                                }
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.POST_NOTIFICATIONS
                                    ) != PackageManager.PERMISSION_GRANTED
                                ) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                                hasRequestedInitialPermissions.value = true
                            }
                        }
                        
                        when {
                            !dashboard.isConfigLoaded -> {
                                SplashScreen()
                            }
                            showSetup || showWizard -> {
                                // Wizard ViewModel
                                val wizardViewModel: com.telegram.cloud.ui.wizard.WizardViewModel = viewModel(
                                    factory = com.telegram.cloud.ui.wizard.WizardViewModelFactory(
                                        context,
                                        container.repository
                                    )
                                )
                                
                                com.telegram.cloud.ui.wizard.WizardScreen(
                                    viewModel = wizardViewModel,
                                    onComplete = { completedConfig ->
                                        showWizard = false
                                        editingConfig = false
                                    },
                                    onCancel = if (config != null) {
                                        {
                                            showWizard = false
                                            editingConfig = false
                                        }
                                    } else null,
                                    onImportBackup = { restoreLauncher.launch(arrayOf("application/zip")) },
                                    existingConfig = if (editingConfig) config else null
                                )
                            }
                            // Media Viewer (Overlay) - Decoupled from Gallery
                            selectedMedia != null && config != null -> {
                                val mediaToView = galleryUiState.currentMedia.find { it.id == selectedMedia }
                                
                                if (mediaToView != null) {
                                    val isCurrentlySyncing = when (val state = gallerySyncState) {
                                        is com.telegram.cloud.gallery.GallerySyncManager.SyncState.Syncing -> {
                                            state.currentFile == mediaToView.filename
                                        }
                                        else -> false
                                    }
                                    
                                    val currentUploadProgress = if (isCurrentlySyncing) gallerySyncProgress else 0f
                                    val currentSyncMediaId = if (gallerySyncState is com.telegram.cloud.gallery.GallerySyncManager.SyncState.Syncing) {
                                        galleryUiState.currentMedia.find { 
                                            it.filename == (gallerySyncState as com.telegram.cloud.gallery.GallerySyncManager.SyncState.Syncing).currentFile 
                                        }?.id
                                    } else null
                                    
                                    val isAnySyncing = gallerySyncState is com.telegram.cloud.gallery.GallerySyncManager.SyncState.Syncing
                                    
                                    // Handle Back: Just close viewer, do not force Gallery state
                                    BackHandler(enabled = true) {
                                        selectedMedia = null
                                    }

                                    // Get fresh media entity from state to pick up DB updates (e.g., localPath after download)
                                    val freshMedia = galleryUiState.currentMedia.find { it.id == selectedMedia }
                                    if (freshMedia != null) {
                                        MediaViewerScreen(
                                            initialMediaId = freshMedia.id,
                                            mediaList = listOf(freshMedia), // Use fresh entity that updates with DB
                                        onBack = { selectedMedia = null },
                                        onSync = { mediaToSync ->
                                            config?.let { cfg ->
                                                galleryViewModel.syncSingleMedia(
                                                    media = mediaToSync,
                                                    config = cfg,
                                                    onProgress = null
                                                )
                                            }
                                        },
                                        onDownloadFromTelegram = { mediaToDownload, onProgress, onSuccess, onError ->
                                            config?.let { cfg ->
                                                galleryViewModel.downloadFromTelegram(
                                                    media = mediaToDownload,
                                                    config = cfg,
                                                    onProgress = onProgress,
                                                    onSuccess = onSuccess,
                                                    onError = onError
                                                )
                                            } ?: onError("Config not available")
                                        },
                                        onFileDownloaded = { mediaId, localPath ->
                                            galleryViewModel.updateLocalPath(mediaId, localPath)
                                        },
                                        onSyncClick = { progress -> },
                                        isSyncing = isAnySyncing,
                                        currentSyncMediaId = currentSyncMediaId,
                                        uploadProgress = gallerySyncProgress,
                                        config = config,
                                        getStreamingManager = { media, cfg -> galleryViewModel.getOrInitStreamingManager(media, cfg) }
                                    )
                                    }
                                }
                            }
                            showGallery && config != null -> {
                                // State for media viewer

                                var showTrash by rememberSaveable { mutableStateOf(false) }
                                
                                // State for context menu
                                var mediaForContextMenu by remember { mutableStateOf<GalleryMediaEntity?>(null) }
                                var showPropertiesDialog by remember { mutableStateOf(false) }
                                var showRenameDialog by remember { mutableStateOf(false) }
                                var showDeleteDialog by remember { mutableStateOf(false) }
                                
                                // State for batch delete
                                var mediaToDeleteBatch by remember { mutableStateOf<List<GalleryMediaEntity>?>(null) }
                                
                                // Handle system back button
                                BackHandler(enabled = true) {
                                    showGallery = false
                                }
                                
                                // Context menu dialog
                                mediaForContextMenu?.let { media ->
                                    MediaContextMenu(
                                        media = media,
                                        onDismiss = { 
                                            // Only dismiss if no dialog is showing
                                            if (!showDeleteDialog && !showRenameDialog && !showPropertiesDialog) {
                                                mediaForContextMenu = null
                                            }
                                        },
                                        onAction = { action ->
                                            when (action) {
                                                MediaAction.Share -> {
                                                    // Use .link generation instead of direct file share
                                                    if (media.isSynced) {
                                                        viewModel.showShareGalleryMediaDialog(media)
                                                    } else {
                                                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.file_must_be_synced)) }
                                                    }
                                                    mediaForContextMenu = null
                                                }
                                                MediaAction.Sync -> {
                                                    config?.let { cfg ->
                                                        galleryViewModel.syncSingleMedia(media, cfg)
                                                        scope.launch { 
                                                            snackbarHostState.showSnackbar(context.getString(R.string.syncing_file, media.filename)) 
                                                        }
                                                    } ?: scope.launch { 
                                                        snackbarHostState.showSnackbar(context.getString(R.string.config_not_available)) 
                                                    }
                                                    mediaForContextMenu = null
                                                }
                                                MediaAction.OpenWith -> {
                                                    MediaActionHelper.openWith(context, media)
                                                    mediaForContextMenu = null
                                                }
                                                MediaAction.SetAs -> {
                                                    MediaActionHelper.setAs(context, media)
                                                    mediaForContextMenu = null
                                                }
                                                MediaAction.Properties -> {
                                                    showPropertiesDialog = true
                                                    // Keep mediaForContextMenu for the dialog
                                                }
                                                MediaAction.Rename -> {
                                                    showRenameDialog = true
                                                    // Keep mediaForContextMenu for the dialog
                                                }
                                                MediaAction.Delete -> {
                                                    showDeleteDialog = true
                                                    // Keep mediaForContextMenu for the dialog
                                                }
                                                MediaAction.Favorite -> {
                                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.added_to_favorites)) }
                                                    mediaForContextMenu = null
                                                }
                                                MediaAction.CopyTo -> {
                                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.copy_to_coming_soon)) }
                                                    mediaForContextMenu = null
                                                }
                                                MediaAction.MoveTo -> {
                                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.move_to_coming_soon)) }
                                                    mediaForContextMenu = null
                                                }
                                                MediaAction.FixDate -> {
                                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.fix_date_coming_soon)) }
                                                    mediaForContextMenu = null
                                                }
                                            }
                                        }
                                    )
                                }
                                
                                // Properties dialog
                                if (showPropertiesDialog && mediaForContextMenu != null) {
                                    MediaPropertiesDialog(
                                        media = mediaForContextMenu!!,
                                        onDismiss = { showPropertiesDialog = false }
                                    )
                                }
                                
                                // Rename dialog
                                if (showRenameDialog && mediaForContextMenu != null) {
                                    RenameMediaDialog(
                                        media = mediaForContextMenu!!,
                                        onDismiss = { showRenameDialog = false },
                                        onRename = { newName ->
                                            galleryViewModel.renameMedia(mediaForContextMenu!!, newName)
                                            showRenameDialog = false
                                            mediaForContextMenu = null
                                        }
                                    )
                                }
                                
                                // Delete dialog
                                if (showDeleteDialog && mediaForContextMenu != null) {
                                    DeleteMediaDialog(
                                        media = mediaForContextMenu!!,
                                        onDismiss = { showDeleteDialog = false },
                                        onDelete = {
                                            galleryViewModel.moveToTrash(mediaForContextMenu!!)
                                            showDeleteDialog = false
                                            mediaForContextMenu = null
                                        }
                                    )
                                }
                                
                                // Batch delete dialog
                                mediaToDeleteBatch?.let { mediaList ->
                                    AlertDialog(
                                        onDismissRequest = { mediaToDeleteBatch = null },
                                        title = { 
                                            Text(
                                                "Mover a la papelera ${mediaList.size} archivo(s)",
                                                style = MaterialTheme.typography.titleLarge
                                            ) 
                                        },
                                        text = { 
                                            Text(
                                                "¿Estás seguro de que deseas mover estos archivos a la papelera? Se eliminarán permanentemente después de 30 días.",
                                                style = MaterialTheme.typography.bodyMedium
                                            )  
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        val result = multiFileGalleryManager.moveToTrashMultiple(mediaList)
                                                        snackbarHostState.showSnackbar(context.getString(R.string.files_deleted, result.successful))
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("A Papelera")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { mediaToDeleteBatch = null }) {
                                                Text("Cancelar")
                                            }
                                        },
                                        containerColor = MaterialTheme.colorScheme.surface,
                                    )
                                }
                                
                                if (showTrash) {
                                    val trashItems by galleryViewModel.trashItems.collectAsState()
                                    
                                    BackHandler { showTrash = false }
                                    
                                    TrashScreen(
                                        trashItems = trashItems,
                                        onBack = { showTrash = false },
                                        onRestore = { media -> galleryViewModel.restoreFromTrash(media) },
                                        onDeletePermanently = { media -> 
                                            // Always delete from telegram if we are in trash screen?
                                            galleryViewModel.deletePermanently(media, true, config) 
                                        },
                                        onEmptyTrash = { galleryViewModel.emptyTrash(config) },
                                        onRestoreAll = { galleryViewModel.restoreFromTrash(trashItems) }
                                    )
                                } else {
                                    // Show gallery grid
                                    CloudGalleryScreen(
                                        uiState = galleryUiState,
                                        filterState = galleryFilterState,
                                        onUpdateFilter = { update -> galleryViewModel.updateFilter(update) },
                                        syncState = gallerySyncState,
                                        syncProgress = gallerySyncProgress,
                                        streamingProgress = streamingProgress,
                                        restoreState = galleryRestoreState,
                                        restoreProgress = galleryRestoreProgress,
                                        syncedCount = gallerySyncedCount,
                                        totalCount = galleryTotalCount,
                                        onRestoreAll = { config?.let { galleryViewModel.restoreAllSynced(it) } },
                                        onScanMedia = {
                                            // Check permissions before scanning
                                            val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                arrayOf(
                                                    Manifest.permission.READ_MEDIA_IMAGES,
                                                    Manifest.permission.READ_MEDIA_VIDEO
                                                )
                                            } else {
                                                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                                            }
                                            
                                            val allGranted = permissions.all {
                                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                                            }
                                            
                                            if (allGranted) {
                                                galleryViewModel.scanMedia()
                        } else {
                                                galleryPermissionLauncher.launch(permissions)
                                            }
                                        },
                                        onSyncAll = { config?.let { galleryViewModel.syncAllMedia(it) } },
                                        onMediaClick = { media ->
                                            selectedMedia = media.id
                                        },
                                        onMediaLongClick = { media ->
                                            mediaForContextMenu = media
                                        },
                                        onBack = { showGallery = false },
                                        onCancelSync = {
                                            scope.launch {
                                                galleryViewModel.cancelSync()
                                                snackbarHostState.showSnackbar(context.getString(R.string.sync_stopped))
                                            }
                                        },
                                        onCancelRestore = {
                                            scope.launch {
                                                galleryViewModel.cancelRestore()
                                                snackbarHostState.showSnackbar("Restore cancelled")
                                            }
                                        },
                                        onSelectedSync = { selectedMediaList ->
                                            config?.let { cfg ->
                                                selectedMediaList.forEach { media ->
                                                    galleryViewModel.syncSingleMedia(media, cfg)
                                                }
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(context.getString(R.string.syncing_files, selectedMediaList.size))
                                                }
                                            } ?: scope.launch {
                                                snackbarHostState.showSnackbar(context.getString(R.string.config_not_available))
                                            }
                                        },
                                        onSelectedDelete = { selectedMediaList ->
                                            mediaToDeleteBatch = selectedMediaList
                                            // Dialog logic handles the actual deletion using galleryViewModel.deleteMedia loop
                                            // We should update it to use multiFileGalleryManager.deleteMultiple
                                        },
                                        onSelectedShare = { selectedMediaList ->
                                            // Share multiple files using .link generation
                                            if (selectedMediaList.isNotEmpty()) {
                                                mediaListToShare = selectedMediaList
                                                showShareDialog = true
                                            }
                                        },
                                        onSelectedDownload = { selectedMediaList ->
                                            // Download multiple files from Telegram
                                            config?.let { cfg ->
                                                scope.launch {
                                                    isBatchDownloading = true
                                                    batchDownloadTotal = selectedMediaList.size
                                                    batchDownloadCurrent = 0
                                                    batchDownloadProgress = 0f
                                                    
                                                    val result = multiFileGalleryManager.downloadMultiple(
                                                        mediaList = selectedMediaList,
                                                        config = cfg
                                                    ) { current, _, progress ->
                                                        batchDownloadProgress = progress
                                                        batchDownloadCurrent = current
                                                    }
                                                    
                                                    resetBatchState()
                                                    
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.files_downloaded, result.successful, selectedMediaList.size)
                                                    )
                                                }
                                            }
                                        },
                                        onOpenTrash = { showTrash = true }
                                    )
                                }
                            }
                            config != null -> {
                            DashboardScreen(
                                state = dashboard,
                                onUploadClick = { pickMultipleFilesLauncher.launch(arrayOf("*/*")) },
                                onDownloadFromLinkClick = { linkFileLauncher.launch(arrayOf("*/*")) },
                                onDownloadClick = { file ->
                                    val destination = buildDownloadFile(downloadTempDir, file.fileName, file.messageId)
                                    viewModel.download(
                                        DownloadRequest(
                                            file = file,
                                            targetPath = destination.absolutePath
                                        )
                                    )
                                },
                                onShareClick = { file ->
                                    // Show dialog to get password and create .link file (using batch list for single items too)
                                    filesToShareBatch = listOf(file)
                                    showShareDialog = true
                                },
                                onCopyLink = { file ->
                                    val link = file.shareLink
                                    if (link != null) {
                                        copyToClipboard(link)
                                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.link_copied)) }
                                    } else {
                                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.no_public_link)) }
                                    }
                                },
                                onDeleteLocal = { file -> viewModel.deleteFile(file) },
                                onCreateBackup = {
                                    val backupFile = File(
                                        File(context.cacheDir, "backups").apply { mkdirs() },
                                        "tgcloud-backup-${System.currentTimeMillis()}.zip"
                                    )
                                    viewModel.showCreateBackupPasswordDialog(backupFile)
                                },
                                onRestoreBackup = { restoreLauncher.launch(arrayOf("application/zip")) },
                                onOpenConfig = { editingConfig = true },
                                onOpenGallery = { showGallery = true },
                                onOpenTaskQueue = { showTaskQueue = true },
                                // Gallery sync state for progress display
                                isGallerySyncing = isGallerySyncing,
                                gallerySyncProgress = gallerySyncProgress,
                                gallerySyncFileName = gallerySyncFileName,
                                // Link download state for progress display
                                isLinkDownloading = isBatchDownloading,
                                linkDownloadProgress = batchDownloadProgress,
                                linkDownloadFileName = null, // Will show generic message
                                // Pull-to-refresh
                                onRefresh = {
                                    scope.launch {
                                        viewModel.refreshFiles()
                                    }
                                },
                                onDownloadMultiple = { files ->
                                    multiFileDashboardManager.downloadMultiple(files, downloadTempDir)
                                    scope.launch {
                                        snackbarHostState.showSnackbar(context.getString(R.string.downloading_files, files.size))
                                    }
                                },
                                onDeleteMultiple = { files ->
                                    scope.launch {
                                        multiFileDashboardManager.deleteMultiple(files)
                                        snackbarHostState.showSnackbar(context.getString(R.string.files_deleted, files.size))
                                    }
                                },
                                onShareMultiple = { files ->
                                    filesToShareBatch = files
                                    showShareDialog = true
                                },
                                onViewMedia = { file ->
                                    // Navigate to gallery and open media viewer for this file
                                    // Find matching gallery media by filename or message ID
                                    scope.launch {
                                        val matchingMedia = galleryViewModel.findMediaByFile(
                                            fileMessageId = file.messageId, 
                                            fileName = file.fileName,
                                            fileId = file.fileId,
                                            fileSize = file.sizeBytes,
                                            date = file.uploadedAt
                                        )
                                        if (matchingMedia != null) {
                                            selectedMedia = matchingMedia.id
                                        } else {
                                            snackbarHostState.showSnackbar(context.getString(R.string.file_not_found_in_gallery))
                                        }
                                    }
                                },
                                onCancelTask = { taskId ->
                                    scope.launch {
                                        container.taskQueueManager.cancelTask(taskId)
                                        snackbarHostState.showSnackbar(context.getString(R.string.operation_cancelled))
                                    }
                                }
                            )
                            }
                        }
                    }
                }
            }
        }
    }




    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("link", text)
        clipboard.setPrimaryClip(clip)
        scheduleClipboardClear(text)
    }

    private fun scheduleClipboardClear(expectedText: CharSequence, delayMs: Long = 5000L) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        Handler(Looper.getMainLooper()).postDelayed({
            val currentClip = clipboard.primaryClip
            val matches =
                currentClip?.getItemAt(0)?.coerceToText(this)?.toString() == expectedText.toString()
            if (matches) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        }, delayMs)
    }

    private fun shareBackupFile(file: File) {
        shareFile(this, file, "application/zip", getString(R.string.share_backup_subject), getString(R.string.share_backup))
    }
    
    private fun shareFile(context: Context, file: File, mimeType: String = "application/octet-stream", subject: String = "", chooserTitle: String = "Share") {
         if (!file.exists()) return
         val result = moveFileToDownloads(
             context = context,
             source = file,
             displayName = file.name,
             mimeType = mimeType,
             subfolder = "telegram cloud app/Shared"
         )
         val uriToShare = result?.uri ?: run {
             FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
         }
         val shareIntent = Intent(Intent.ACTION_SEND).apply {
             type = mimeType
             putExtra(Intent.EXTRA_STREAM, uriToShare)
             if (subject.isNotEmpty()) putExtra(Intent.EXTRA_SUBJECT, subject)
             addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
         }
         context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    }

    private fun buildDownloadFile(dir: File, fileName: String, messageId: Long): File {
        val safeName = sanitizeFileName(fileName.ifBlank { "tg-file-$messageId" })
        return File(dir, safeName)
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (sanitized.isBlank()) "tg-file" else sanitized
    }
    
    private fun formatShortSize(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> "${bytes / 1_000_000_000}gb"
            bytes >= 1_000_000 -> "${bytes / 1_000_000}mb"
            bytes >= 1_000 -> "${bytes / 1_000}kb"
            else -> "${bytes}b"
        }
    }

    private fun restartApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return
        startActivity(launchIntent)
        finishAffinity()
    }
}



