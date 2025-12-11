package com.telegram.cloud.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.request.RequestOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Scans device media (photos and videos) from MediaStore
 */
class MediaScanner(private val context: Context) {
    
    companion object {
        private const val TAG = "MediaScanner"
        private const val THUMBNAIL_SIZE = 256
    }
    
    private val contentResolver: ContentResolver get() = context.contentResolver
    
    /**
     * Scan all photos and videos from device
     */
    suspend fun scanAllMedia(): List<GalleryMediaEntity> = withContext(Dispatchers.IO) {
        val media = mutableListOf<GalleryMediaEntity>()
        
        // Scan images
        media.addAll(scanImages())
        
        // Scan videos
        media.addAll(scanVideos())
        
        // Sort by date taken descending
        media.sortedByDescending { it.dateTaken }
    }
    
    private fun scanImages(): List<GalleryMediaEntity> {
        val images = mutableListOf<GalleryMediaEntity>()
        
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT
        )
        
        val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
        
        try {
            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(dataColumn) ?: continue
                    val name = cursor.getString(nameColumn) ?: File(path).name
                    val mime = cursor.getString(mimeColumn) ?: "image/*"
                    val size = cursor.getLong(sizeColumn)
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn) * 1000
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    
                    // Skip if file doesn't exist
                    if (!File(path).exists()) continue
                    
                    images.add(
                        GalleryMediaEntity(
                            localPath = path,
                            filename = name,
                            mimeType = mime,
                            sizeBytes = size,
                            dateTaken = if (dateTaken > 0) dateTaken else dateModified,
                            dateModified = dateModified,
                            width = width,
                            height = height
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning images", e)
        }
        
        return images
    }
    
    private fun scanVideos(): List<GalleryMediaEntity> {
        val videos = mutableListOf<GalleryMediaEntity>()
        
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DURATION
        )
        
        val sortOrder = "${MediaStore.Video.Media.DATE_TAKEN} DESC"
        
        try {
            contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(dataColumn) ?: continue
                    val name = cursor.getString(nameColumn) ?: File(path).name
                    val mime = cursor.getString(mimeColumn) ?: "video/*"
                    val size = cursor.getLong(sizeColumn)
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn) * 1000
                    val width = cursor.getInt(widthColumn)
                    val height = cursor.getInt(heightColumn)
                    val duration = cursor.getLong(durationColumn)
                    
                    // Skip if file doesn't exist
                    if (!File(path).exists()) continue
                    
                    videos.add(
                        GalleryMediaEntity(
                            localPath = path,
                            filename = name,
                            mimeType = mime,
                            sizeBytes = size,
                            dateTaken = if (dateTaken > 0) dateTaken else dateModified,
                            dateModified = dateModified,
                            width = width,
                            height = height,
                            durationMs = duration
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning videos", e)
        }
        
        return videos
    }
    
    /**
     * Generate thumbnail for a media file using Glide (like Gallery app)
     * Glide handles both images and videos reliably
     */
    suspend fun generateThumbnail(media: GalleryMediaEntity): String? = withContext(Dispatchers.IO) {
        try {
            val thumbDir = File(context.cacheDir, "gallery_thumbs").apply { mkdirs() }
            val thumbFile = File(thumbDir, "${media.id}_thumb.jpg")
            
            if (thumbFile.exists() && thumbFile.length() > 0) {
                return@withContext thumbFile.absolutePath
            }
            
            // Use Glide to generate thumbnail - works for both images and videos
            val bitmap = try {
                Glide.with(context)
                    .asBitmap()
                    .load(File(media.localPath))
                    .apply(
                        RequestOptions()
                            .override(THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                            .centerCrop()
                            .diskCacheStrategy(DiskCacheStrategy.NONE)
                            .skipMemoryCache(true)
                    )
                    .submit()
                    .get(30, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.d(TAG, "Glide failed for ${media.filename}, trying fallback: ${e.message}")
                null
            }
            
            // Fallback methods if Glide fails
            val finalBitmap = bitmap 
                ?: (if (media.isVideo) generateVideoThumbnailFallback(media) else generateImageThumbnailFallback(media))
            
            if (finalBitmap != null) {
                FileOutputStream(thumbFile).use { out ->
                    finalBitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
                if (finalBitmap != bitmap) {
                    finalBitmap.recycle()
                }
                Log.d(TAG, "Generated thumbnail for ${media.filename}")
                return@withContext thumbFile.absolutePath
            }
            
            Log.w(TAG, "Could not generate thumbnail for ${media.filename}")
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error generating thumbnail for ${media.localPath}", e)
            null
        }
    }
    
    /**
     * Fallback: Generate thumbnail for video using MediaMetadataRetriever
     */
    private fun generateVideoThumbnailFallback(media: GalleryMediaEntity): Bitmap? {
        // Method 1: Use MediaMetadataRetriever
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(media.localPath)
            
            // Get frame at 1 second or first frame
            val frame = retriever.getFrameAtTime(1000000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            
            retriever.release()
            
            if (frame != null) {
                val scaled = ThumbnailUtils.extractThumbnail(frame, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                if (scaled != frame) {
                    frame.recycle()
                }
                Log.d(TAG, "Video thumbnail via MediaMetadataRetriever: ${media.filename}")
                return scaled
            }
        } catch (e: Exception) {
            Log.d(TAG, "MediaMetadataRetriever failed: ${e.message}")
        }
        
        // Method 2: Use MediaStore.loadThumbnail (Android Q+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val contentUri = getContentUriFromPath(media.localPath, true)
                if (contentUri != null) {
                    return contentResolver.loadThumbnail(contentUri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
                }
            } catch (e: Exception) {
                Log.d(TAG, "loadThumbnail failed: ${e.message}")
            }
        }
        
        // Method 3: Use deprecated ThumbnailUtils
        try {
            @Suppress("DEPRECATION")
            return ThumbnailUtils.createVideoThumbnail(media.localPath, MediaStore.Video.Thumbnails.MINI_KIND)
        } catch (e: Exception) {
            Log.d(TAG, "createVideoThumbnail failed: ${e.message}")
        }
        
        return null
    }
    
    /**
     * Fallback: Generate thumbnail for image
     */
    private fun generateImageThumbnailFallback(media: GalleryMediaEntity): Bitmap? {
        // Method 1: Use MediaStore.loadThumbnail (Android Q+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val contentUri = getContentUriFromPath(media.localPath, false)
                if (contentUri != null) {
                    return contentResolver.loadThumbnail(contentUri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
                }
            } catch (e: Exception) {
                Log.d(TAG, "loadThumbnail failed for image: ${e.message}")
            }
        }
        
        // Method 2: Decode and scale manually
        try {
            val options = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            android.graphics.BitmapFactory.decodeFile(media.localPath, options)
            
            val scale = maxOf(options.outWidth, options.outHeight) / THUMBNAIL_SIZE
            options.inSampleSize = maxOf(1, scale)
            options.inJustDecodeBounds = false
            
            val bitmap = android.graphics.BitmapFactory.decodeFile(media.localPath, options)
            if (bitmap != null) {
                val scaled = ThumbnailUtils.extractThumbnail(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE)
                if (scaled != bitmap) {
                    bitmap.recycle()
                }
                return scaled
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error decoding image: ${e.message}")
        }
        
        return null
    }
    
    private fun getContentUriFromPath(path: String, isVideo: Boolean): Uri? {
        val uri = if (isVideo) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DATA} = ?"
        val selectionArgs = arrayOf(path)
        
        return contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID))
                ContentUris.withAppendedId(uri, id)
            } else {
                null
            }
        }
    }
}

