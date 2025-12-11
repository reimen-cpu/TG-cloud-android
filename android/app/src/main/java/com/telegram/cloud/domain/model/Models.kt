package com.telegram.cloud.domain.model

data class CloudFile(
    val id: Long,
    val messageId: Long,
    val fileId: String,
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val uploadedAt: Long,
    val caption: String?,
    val shareLink: String?,
    val checksum: String?
)

data class UploadRequest(
    val uri: String,
    val displayName: String,
    val caption: String?,
    val sizeBytes: Long
)

data class DownloadRequest(
    val file: CloudFile,
    val targetPath: String
)

