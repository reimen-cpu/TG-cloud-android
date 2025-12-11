package com.telegram.cloud.utils

import android.content.Context
import android.os.Environment
import java.io.File

@Suppress("DEPRECATION")
private fun getCommonRoot(context: Context): File {
    val root = Environment.getExternalStorageDirectory()
    val target = File(root, "telegram cloud app")
    if (target.exists() || target.mkdirs()) {
        return target
    }
    return context.getExternalFilesDir(null)?.apply { mkdirs() } ?: context.filesDir
}

fun getUserVisibleRootDir(context: Context): File = getCommonRoot(context)

fun getUserVisibleSubDir(context: Context, subDir: String?): File {
    val normalized = subDir?.trim()?.takeIf { it.isNotEmpty() }
    val base = getCommonRoot(context)
    if (normalized == null) {
        return base
    }
    return File(base, normalized).apply { mkdirs() }
}

fun getUserVisibleDownloadsDir(context: Context): File =
    getUserVisibleSubDir(context, "Downloads")

