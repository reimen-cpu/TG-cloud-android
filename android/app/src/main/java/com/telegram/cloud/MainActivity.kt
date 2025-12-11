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
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
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
        val factory = MainViewModelFactory(this, container.repository, container.backupManager, container.taskQueueManager)
        val galleryFactory = GalleryViewModelFactory(
            this,
            container.mediaScanner,
            container.gallerySyncManager,
            container.database
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
            var pendingRestoreFile: File? by remember { mutableStateOf(null) }
            var showPasswordDialog by remember { mutableStateOf(false) }
            var restorePassword by rememberSaveable { mutableStateOf("") }
            
            // Share link dialog state
            var showShareDialog by remember { mutableStateOf(false) }
            var sharePassword by rememberSaveable { mutableStateOf("") }
            var fileToShare: CloudFile? by remember { mutableStateOf(null) }
            var filesToShareBatch: List<CloudFile>? by remember { mutableStateOf(null) }
            var mediaToShare: GalleryMediaEntity? by remember { mutableStateOf(null) }
            var mediaListToShare: List<GalleryMediaEntity>? by remember { mutableStateOf(null) }
            var showBackupPasswordDialog by remember { mutableStateOf(false) }
            var backupPassword by rememberSaveable { mutableStateOf("") }
            var pendingBackupFile: File? by remember { mutableStateOf(null) }
            
            val linksDir = remember {
                File(context.getExternalFilesDir(null), "links").apply { mkdirs() }
            }
            val downloadsDir = remember {
                getUserVisibleDownloadsDir(context)
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

            // Single file picker (for backward compatibility)
            val pickFileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri: Uri? ->
                if (uri != null) {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                    val meta = queryDocumentMeta(uri)
                    viewModel.upload(
                        UploadRequest(
                            uri = uri.toString(),
                            displayName = meta.name,
                            caption = null,
                            sizeBytes = meta.size
                        )
                    )
                }
            }
            
            // Multiple files picker
            val pickMultipleFilesLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenMultipleDocuments()
            ) { uris: List<Uri> ->
                if (uris.isNotEmpty()) {
                    val requests = uris.mapNotNull { uri ->
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                            val meta = queryDocumentMeta(uri)
                            UploadRequest(
                                uri = uri.toString(),
                                displayName = meta.name,
                                caption = null,
                                sizeBytes = meta.size
                            )
                        } catch (e: Exception) {
                            Log.e("MainActivity", "Error processing file $uri", e)
                            null
                        }
                    }
                    if (requests.isNotEmpty()) {
                        viewModel.uploadMultiple(requests)
                    }
                }
            }

            val restoreLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val restoreFile = copyToCache(uri, File(context.cacheDir, "restore-${System.currentTimeMillis()}.zip"))
                    scope.launch {
                        val needsPassword = viewModel.requiresPassword(restoreFile)
                        if (needsPassword) {
                            pendingRestoreFile = restoreFile
                            restorePassword = ""
                            showPasswordDialog = true
                        } else {
                            viewModel.restoreBackup(restoreFile, null)
                        }
                    }
                }
            }
            
            // Link file picker and download state
            var pendingLinkFile: File? by remember { mutableStateOf(null) }
            var showLinkPasswordDialog by remember { mutableStateOf(false) }
            var linkPassword by rememberSaveable { mutableStateOf("") }
            
            val linkFileLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument()
            ) { uri ->
                if (uri != null) {
                    val linkFile = copyToCache(uri, File(context.cacheDir, "download-link-${System.currentTimeMillis()}.link"))
                    pendingLinkFile = linkFile
                    linkPassword = ""
                    showLinkPasswordDialog = true
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
                        // Restore password dialog
                        if (showPasswordDialog && pendingRestoreFile != null) {
                            AlertDialog(
                                onDismissRequest = {
                                    showPasswordDialog = false
                                    pendingRestoreFile = null
                                    restorePassword = ""
                                },
                                title = { Text(stringResource(R.string.restore_protected_backup)) },
                                text = {
                                    OutlinedTextField(
                                        value = restorePassword,
                                        onValueChange = { restorePassword = it },
                                        label = { Text(stringResource(R.string.password)) }
                                    )
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = restorePassword.isNotBlank(),
                                        onClick = {
                                            pendingRestoreFile?.let { file ->
                                                viewModel.restoreBackup(file, restorePassword)
                                            }
                                            showPasswordDialog = false
                                            restorePassword = ""
                                            pendingRestoreFile = null
                                        }
                                    ) {
                                        Text(stringResource(R.string.restore))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showPasswordDialog = false
                                        restorePassword = ""
                                        pendingRestoreFile = null
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                        
                        // Download from link password dialog
                        if (showLinkPasswordDialog && pendingLinkFile != null) {
                            AlertDialog(
                                onDismissRequest = {
                                    showLinkPasswordDialog = false
                                    pendingLinkFile = null
                                    linkPassword = ""
                                },
                                title = { Text(stringResource(R.string.download_from_link)) },
                                text = {
                                    Column {
                                        Text(stringResource(R.string.enter_link_password))
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = linkPassword,
                                            onValueChange = { linkPassword = it },
                                            label = { Text(stringResource(R.string.password)) },
                                            singleLine = true
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = linkPassword.isNotBlank(),
                                        onClick = {
                                            pendingLinkFile?.let { linkFile ->
                                                scope.launch {
                                                    isBatchDownloading = true
                                                    batchDownloadTotal = 0 // Unknown initially or could be read from link metadata
                                                    batchDownloadCurrent = 0
                                                    batchDownloadProgress = 0f
                                                    
                                                    val results = multiLinkDownloadManager.downloadFromMultiLink(
                                                        linkFile = linkFile,
                                                        password = linkPassword,
                                                        destDir = linkDownloadTempDir
                                                    ) { progress, phase ->
                                                        batchDownloadProgress = progress
                                                        // Estimate current/total based on progress if possible, or just show percentage
                                                    }
                                                    
                                                    resetBatchState()
                                                    
                                                    if (results.isNotEmpty()) {
                                                        val successCount = results.count { it.success }
                                                        snackbarHostState.showSnackbar(context.getString(R.string.files_downloaded, successCount, results.size))
                                                        
                                                        // Move files to public downloads
                                                        results.forEach { result ->
                                                            result.filePath?.let { rawPath ->
                                                                val file = File(rawPath)
                                                                val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(rawPath).lowercase()
                                                                val resolvedMime = extension.takeIf { it.isNotBlank() }
                                                                    ?.let { android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                                                                    ?: "application/octet-stream"
                                                                    
                                                                moveFileToDownloads(
                                                                    context = context,
                                                                    source = file,
                                                                    displayName = file.name,
                                                                    mimeType = resolvedMime,
                                                                    subfolder = "telegram cloud app/Downloads"
                                                                )
                                                            }
                                                        }
                                                    } else {
                                                        snackbarHostState.showSnackbar(context.getString(R.string.wrong_password_or_corrupt_file))
                                                    }
                                                }
                                            }
                                            showLinkPasswordDialog = false
                                            linkPassword = ""
                                            pendingLinkFile = null
                                        }
                                    ) {
                                        Text(stringResource(R.string.download))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showLinkPasswordDialog = false
                                        linkPassword = ""
                                        pendingLinkFile = null
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                        
                        // Backup password dialog
                        if (showBackupPasswordDialog && pendingBackupFile != null) {
                            AlertDialog(
                                onDismissRequest = {
                                    showBackupPasswordDialog = false
                                    pendingBackupFile = null
                                    backupPassword = ""
                                },
                                title = { Text(stringResource(R.string.create_backup)) },
                                text = {
                                    Column {
                                        Text(stringResource(R.string.define_backup_password))
                                        Spacer(Modifier.height(8.dp))
                                        OutlinedTextField(
                                            value = backupPassword,
                                            onValueChange = { backupPassword = it },
                                            label = { Text(stringResource(R.string.password)) },
                                            singleLine = true
                                        )
                                    }
                                },
                                confirmButton = {
                                    TextButton(
                                        enabled = backupPassword.isNotBlank(),
                                        onClick = {
                                            pendingBackupFile?.let { file ->
                                                viewModel.createBackup(file, backupPassword)
                                            }
                                            showBackupPasswordDialog = false
                                            pendingBackupFile = null
                                            backupPassword = ""
                                        }
                                    ) {
                                        Text(stringResource(R.string.create_backup))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showBackupPasswordDialog = false
                                        backupPassword = ""
                                        pendingBackupFile = null
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                        
                        // Share link password dialog
                        if (showShareDialog && fileToShare != null) {
                            AlertDialog(
                                onDismissRequest = {
                                    showShareDialog = false
                                    fileToShare = null
                                    sharePassword = ""
                                },
                                title = { Text(stringResource(R.string.create_link_file)) },
                                text = {
                                    Column {
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
                                            fileToShare?.let { file ->
                                                val linkFile = File(linksDir, "${sanitizeFileName(file.fileName)}.link")
                                                viewModel.generateLinkFile(file, sharePassword, linkFile) { success ->
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
                                                            putExtra(Intent.EXTRA_SUBJECT, "Telegram Cloud: ${file.fileName}.link")
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        startActivity(Intent.createChooser(shareIntent, shareLabel))
                                                    }
                                                }
                                            }
                                            showShareDialog = false
                                            fileToShare = null
                                            sharePassword = ""
                                        }
                                    ) {
                                        Text(stringResource(R.string.create_and_share))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showShareDialog = false
                                        fileToShare = null
                                        sharePassword = ""
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                        
                        // Share dialog for gallery media (single file)
                        if (showShareDialog && mediaToShare != null) {
                            AlertDialog(
                                onDismissRequest = {
                                    showShareDialog = false
                                    mediaToShare = null
                                    sharePassword = ""
                                },
                                title = { Text(stringResource(R.string.create_link_file)) },
                                text = {
                                    Column {
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
                                            mediaToShare?.let { media ->
                                                val linkFile = File(linksDir, "${sanitizeFileName(media.filename)}.link")
                                                viewModel.generateLinkFileFromGallery(media, sharePassword, linkFile) { success ->
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
                                                            putExtra(Intent.EXTRA_SUBJECT, "Telegram Cloud: ${media.filename}.link")
                                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                        }
                                                        startActivity(Intent.createChooser(shareIntent, shareLabel))
                                                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.link_file_shared_simple)) }
                                                    } else {
                                                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.error_creating_link_file)) }
                                                    }
                                                }
                                            }
                                            showShareDialog = false
                                            mediaToShare = null
                                            sharePassword = ""
                                        }
                                    ) {
                                        Text(stringResource(R.string.create_and_share))
                                    }
                                },
                                dismissButton = {
                                    TextButton(onClick = {
                                        showShareDialog = false
                                        mediaToShare = null
                                        sharePassword = ""
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }
                        
                        // Share dialog for gallery media (multiple files)
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
                                                val linkFile = File(linksDir, "batch_share_${System.currentTimeMillis()}.link")
                                                
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
                                                val linkFile = File(linksDir, "batch_share_${System.currentTimeMillis()}.link")
                                                
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
                        var showTaskQueue by rememberSaveable { mutableStateOf(false) }
                        
                        // Gallery state
                        val galleryMedia by galleryViewModel.mediaList.collectAsState()
                        val gallerySyncState by galleryViewModel.syncState.collectAsState()
                        val gallerySyncProgress by galleryViewModel.syncProgress.collectAsState()
                        val gallerySyncedCount by galleryViewModel.syncedCount.collectAsState()
                        val galleryTotalCount by galleryViewModel.totalCount.collectAsState()
                        val gallerySyncFileName by galleryViewModel.currentSyncFileName.collectAsState()
                        
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
                        
                        // Batch Download Progress Dialog
                        if (isBatchDownloading) {
                            AlertDialog(
                                onDismissRequest = { /* Prevent dismiss */ },
                                title = { Text(stringResource(R.string.downloading_files, batchDownloadTotal)) },
                                text = {
                                    Column {
                                        LinearProgressIndicator(
                                            progress = { batchDownloadProgress },
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        Text(
                                            text = "$batchDownloadCurrent / $batchDownloadTotal",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                },
                                confirmButton = {},
                                dismissButton = {
                                    TextButton(onClick = { 
                                        // TODO: Implement cancellation logic in managers
                                        resetBatchState() 
                                    }) {
                                        Text(stringResource(R.string.cancel))
                                    }
                                }
                            )
                        }

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
                            showSetup -> {
                            SetupScreen(
                                isEditing = config != null,
                                initialTokens = config?.tokens ?: emptyList(),
                                initialChannelId = config?.channelId,
                                initialChatId = config?.chatId,
                                onSave = { tokens, channelId, chatId ->
                                    viewModel.saveConfig(tokens, channelId, chatId)
                                    editingConfig = false
                                },
                                onImportBackup = { restoreLauncher.launch(arrayOf("application/zip")) },
                                onCancel = if (config != null) { { editingConfig = false } } else null
                            )
                            }
                            showGallery && config != null -> {
                                // State for media viewer
                                var selectedMedia by rememberSaveable { mutableStateOf<Long?>(null) }
                                
                                // State for context menu
                                var mediaForContextMenu by remember { mutableStateOf<GalleryMediaEntity?>(null) }
                                var showPropertiesDialog by remember { mutableStateOf(false) }
                                var showRenameDialog by remember { mutableStateOf(false) }
                                var showDeleteDialog by remember { mutableStateOf(false) }
                                
                                // State for batch delete
                                var mediaToDeleteBatch by remember { mutableStateOf<List<GalleryMediaEntity>?>(null) }
                                
                                // Find selected media from list
                                val mediaToView = selectedMedia?.let { id ->
                                    galleryMedia.find { it.id == id }
                                }
                                
                                // Handle system back button
                                BackHandler(enabled = true) {
                                    when {
                                        mediaToView != null -> selectedMedia = null
                                        else -> showGallery = false
                                    }
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
                                                        mediaToShare = media
                                                        mediaListToShare = null
                                                        sharePassword = ""
                                                        showShareDialog = true
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
                                        onDelete = { deleteFromTelegram ->
                                            galleryViewModel.deleteMedia(mediaForContextMenu!!, deleteFromTelegram, config)
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
                                                "Eliminar ${mediaList.size} archivo(s)",
                                                style = MaterialTheme.typography.titleLarge
                                            ) 
                                        },
                                        text = { 
                                            Text(
                                                "¿Estás seguro de que deseas eliminar estos archivos? Esta acción no se puede deshacer.",
                                                style = MaterialTheme.typography.bodyMedium
                                            ) 
                                        },
                                        confirmButton = {
                                            Button(
                                                onClick = {
                                                    scope.launch {
                                                        val result = multiFileGalleryManager.deleteMultiple(mediaList, false, config)
                                                        snackbarHostState.showSnackbar(context.getString(R.string.files_deleted, result.successful))
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                                            ) {
                                                Text("Eliminar")
                                            }
                                        },
                                        dismissButton = {
                                            TextButton(onClick = { mediaToDeleteBatch = null }) {
                                                Text("Cancelar")
                                            }
                                        },
                                        containerColor = MaterialTheme.colorScheme.surface,
                                        shape = MaterialTheme.shapes.large
                                    )
                                }
                                
                                if (mediaToView != null) {
                                    // Check if this media is currently being synced
                                    val isCurrentlySyncing = when (val state = gallerySyncState) {
                                        is com.telegram.cloud.gallery.GallerySyncManager.SyncState.Syncing -> {
                                            state.currentFile == mediaToView.filename
                                        }
                                        else -> false
                                    }
                                    
                                    // Use the progress from GallerySyncManager directly
                                    val currentUploadProgress = if (isCurrentlySyncing) gallerySyncProgress else 0f
                                    
                                    // Show full screen viewer
                                    MediaViewerScreen(
                                        media = mediaToView,
                                        onBack = { selectedMedia = null },
                                        onSync = {
                                            config?.let { cfg ->
                                                galleryViewModel.syncSingleMedia(
                                                    media = mediaToView,
                                                    config = cfg,
                                                    onProgress = null // Progress is handled by GallerySyncManager
                                                )
                                            }
                                        },
                                        onDownloadFromTelegram = { media, onProgress, onSuccess, onError ->
                                            config?.let { cfg ->
                                                galleryViewModel.downloadFromTelegram(
                                                    media = media,
                                                    config = cfg,
                                                    onProgress = onProgress,
                                                    onSuccess = onSuccess,
                                                    onError = onError
                                                )
                                            } ?: onError("Config not available")
                                        },
                                        onFileDownloaded = { localPath: String ->
                                            // Update database with new local path for chunked streaming downloads
                                            galleryViewModel.updateLocalPath(mediaToView.id, localPath)
                                        },
                                        onSyncClick = { progress ->
                                            // Progress is handled by GallerySyncManager
                                        },
                                        isSyncing = isCurrentlySyncing,
                                        uploadProgress = currentUploadProgress,
                                        config = config
                                    )
                                } else {
                                    // Show gallery grid
                                    CloudGalleryScreen(
                                        mediaList = galleryMedia,
                                        syncState = gallerySyncState,
                                        syncProgress = gallerySyncProgress,
                                        syncedCount = gallerySyncedCount,
                                        totalCount = galleryTotalCount,
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
                                                if (selectedMediaList.size == 1) {
                                                    mediaToShare = selectedMediaList.first()
                                                    mediaListToShare = null
                                                    sharePassword = ""
                                                    showShareDialog = true
                                                } else {
                                                    mediaListToShare = selectedMediaList
                                                    mediaToShare = null
                                                    sharePassword = ""
                                                    showShareDialog = true
                                                }
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
                                                    ) { current, total, progress ->
                                                        batchDownloadProgress = progress
                                                        batchDownloadCurrent = current
                                                    }
                                                    
                                                    resetBatchState()
                                                    
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.files_downloaded, result.successful, selectedMediaList.size)
                                                    )
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                            config != null -> {
                            DashboardScreen(
                                config = config!!,
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
                                    // Show dialog to get password and create .link file
                                    fileToShare = file
                                    sharePassword = ""
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
                                    pendingBackupFile = File(
                                        File(context.cacheDir, "backups").apply { mkdirs() },
                                        "tgcloud-backup-${System.currentTimeMillis()}.zip"
                                    )
                                    showBackupPasswordDialog = true
                                },
                                onRestoreBackup = { restoreLauncher.launch(arrayOf("application/zip")) },
                                onOpenConfig = { editingConfig = true },
                                onOpenGallery = { showGallery = true },
                                onOpenTaskQueue = { showTaskQueue = true },
                                // Gallery sync state for progress display
                                isGallerySyncing = isGallerySyncing,
                                gallerySyncProgress = gallerySyncProgress,
                                gallerySyncFileName = gallerySyncFileName,
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
                                    sharePassword = ""
                                    showShareDialog = true
                                }
                            )
                            }
                        }
                    }
                }
            }
        }
    }


    private fun queryDocumentMeta(uri: Uri): DocumentMeta {
        var name = "archivo.bin"
        var size = 0L
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex != -1) name = cursor.getString(nameIndex)
                if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
            }
        }
        return DocumentMeta(name, size)
    }

    private fun copyToCache(uri: Uri, target: File): File {
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        return target
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
        if (!file.exists()) return
        val result = moveFileToDownloads(
            context = this,
            source = file,
            displayName = file.name,
            mimeType = "application/zip",
            subfolder = "telegram cloud app/Backups"
        )
        val uriToShare = result?.uri ?: run {
            FileProvider.getUriForFile(this, "${packageName}.provider", file)
        }
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uriToShare)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_backup_subject))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_backup)))
    }

    private fun buildDownloadFile(dir: File, fileName: String, messageId: Long): File {
        val safeName = sanitizeFileName(fileName.ifBlank { "tg-file-$messageId" })
        return File(dir, safeName)
    }

    private fun sanitizeFileName(name: String): String {
        val sanitized = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return if (sanitized.isBlank()) "tg-file" else sanitized
    }

    private fun restartApp() {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return
        startActivity(launchIntent)
        finishAffinity()
    }
}

data class DocumentMeta(val name: String, val size: Long)

