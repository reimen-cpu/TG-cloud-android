package com.telegram.cloud.gallery

import java.io.File

/**
 * Represents a gallery album/folder
 */
data class GalleryAlbum(
    val path: String,
    val name: String,
    val thumbnailPath: String?,
    val mediaCount: Int,
    val syncedCount: Int,
    val totalSize: Long,
    val lastModified: Long,
    val mediaTypes: Set<MediaType> // Images, Videos, or both
) {
    val displayName: String
        get() = when {
            path == RECENTS_PATH -> "Recents"
            path == FAVORITES_PATH -> "Favorites"
            path == SYNCED_PATH -> "Synced to Cloud"
            path == ALL_MEDIA_PATH -> "All Media"
            else -> name
        }
    
    val isSpecialAlbum: Boolean
        get() = path in listOf(RECENTS_PATH, FAVORITES_PATH, SYNCED_PATH, ALL_MEDIA_PATH)
    
    companion object {
        const val RECENTS_PATH = "##RECENTS##"
        const val FAVORITES_PATH = "##FAVORITES##"
        const val SYNCED_PATH = "##SYNCED##"
        const val ALL_MEDIA_PATH = "##ALL##"
    }
}

enum class MediaType {
    IMAGE, VIDEO
}

/**
 * Groups media by folder/album
 */
object AlbumGrouper {
    
    /**
     * Groups media into albums based on their folder paths
     */
    fun groupByAlbums(mediaList: List<GalleryMediaEntity>): List<GalleryAlbum> {
        if (mediaList.isEmpty()) return emptyList()
        
        val albums = mutableListOf<GalleryAlbum>()
        
        // Add special albums first
        
        // Recents - last 7 days
        val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        val recentMedia = mediaList.filter { it.dateTaken > sevenDaysAgo || it.dateModified > sevenDaysAgo }
        if (recentMedia.isNotEmpty()) {
            albums.add(createAlbum(
                path = GalleryAlbum.RECENTS_PATH,
                name = "Recents",
                media = recentMedia
            ))
        }
        
        // Synced to Cloud
        val syncedMedia = mediaList.filter { it.isSynced }
        if (syncedMedia.isNotEmpty()) {
            albums.add(createAlbum(
                path = GalleryAlbum.SYNCED_PATH,
                name = "Synced to Cloud",
                media = syncedMedia
            ))
        }
        
        // All Media
        albums.add(createAlbum(
            path = GalleryAlbum.ALL_MEDIA_PATH,
            name = "All Media",
            media = mediaList
        ))
        
        // Group by folder
        val folderGroups = mediaList.groupBy { File(it.localPath).parent ?: "Unknown" }
        
        folderGroups.forEach { (folderPath, media) ->
            val folderName = File(folderPath).name
            albums.add(createAlbum(
                path = folderPath,
                name = folderName,
                media = media
            ))
        }
        
        // Sort: special albums first, then by media count descending
        return albums.sortedWith(
            compareBy<GalleryAlbum> { !it.isSpecialAlbum }
                .thenByDescending { it.mediaCount }
        )
    }
    
    private fun createAlbum(
        path: String,
        name: String,
        media: List<GalleryMediaEntity>
    ): GalleryAlbum {
        // Find best thumbnail (prefer synced, then most recent)
        val thumbnail = media
            .sortedByDescending { it.dateTaken }
            .firstOrNull { it.thumbnailPath != null }
            ?.thumbnailPath
            ?: media.firstOrNull()?.thumbnailPath
            ?: media.firstOrNull()?.localPath
        
        val mediaTypes = mutableSetOf<MediaType>()
        if (media.any { it.isImage }) mediaTypes.add(MediaType.IMAGE)
        if (media.any { it.isVideo }) mediaTypes.add(MediaType.VIDEO)
        
        return GalleryAlbum(
            path = path,
            name = name,
            thumbnailPath = thumbnail,
            mediaCount = media.size,
            syncedCount = media.count { it.isSynced },
            totalSize = media.sumOf { it.sizeBytes },
            lastModified = media.maxOfOrNull { it.dateModified } ?: 0L,
            mediaTypes = mediaTypes
        )
    }
    
    /**
     * Get media for a specific album
     */
    fun getMediaForAlbum(
        allMedia: List<GalleryMediaEntity>,
        albumPath: String
    ): List<GalleryMediaEntity> {
        return when (albumPath) {
            GalleryAlbum.RECENTS_PATH -> {
                val sevenDaysAgo = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
                allMedia.filter { it.dateTaken > sevenDaysAgo || it.dateModified > sevenDaysAgo }
                    .sortedByDescending { maxOf(it.dateTaken, it.dateModified) }
            }
            GalleryAlbum.SYNCED_PATH -> {
                allMedia.filter { it.isSynced }.sortedByDescending { it.dateTaken }
            }
            GalleryAlbum.ALL_MEDIA_PATH -> {
                allMedia.sortedByDescending { it.dateTaken }
            }
            GalleryAlbum.FAVORITES_PATH -> {
                // TODO: Implement favorites
                emptyList()
            }
            else -> {
                allMedia.filter { File(it.localPath).parent == albumPath }
                    .sortedByDescending { it.dateTaken }
            }
        }
    }
}


