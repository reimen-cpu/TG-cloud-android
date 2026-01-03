package com.telegram.cloud.ui

import com.telegram.cloud.domain.model.CloudFile

/**
 * Encapsulates all actions that can be triggered from the DashboardScreen.
 * This simplifies the composable signature and makes testing easier.
 */
interface DashboardActions {
    fun onUploadClick()
    fun onDownloadFromLinkClick()
    fun onDownloadClick(file: CloudFile)
    fun onShareClick(file: CloudFile)
    fun onCopyLink(file: CloudFile)
    fun onDeleteLocal(file: CloudFile)
    fun onCreateBackup()
    fun onRestoreBackup()
    fun onOpenConfig()
    fun onOpenGallery()
    fun onOpenTaskQueue()
    fun onRefresh()
    fun onDownloadMultiple(files: List<CloudFile>)
    fun onShareMultiple(files: List<CloudFile>)
    fun onDeleteMultiple(files: List<CloudFile>)
    fun onCancelTask(taskId: String)
}
