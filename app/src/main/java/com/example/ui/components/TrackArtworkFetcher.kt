package com.example.ui.components

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Size
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.key.Keyer
import coil.request.Options
import coil.size.Dimension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Resolves per-track embedded artwork via custom `trackart://` URIs so tracks grouped under
 * a shared Android MediaStore `album_id` (e.g. "Unknown Album" / Download folder) never collide
 * on a single cached cover image.
 */
object TrackArtworkLoader {
    const val SCHEME = "trackart"

    fun buildTrackArtUri(
        songId: Long,
        albumId: Long,
        album: String,
        contentUri: String,
        dataPath: String
    ): String {
        val allowAlbumFallback = shouldAllowAlbumFallback(album)
        return Uri.Builder()
            .scheme(SCHEME)
            .authority(songId.toString())
            .appendQueryParameter("albumId", albumId.toString())
            .appendQueryParameter("contentUri", contentUri)
            .appendQueryParameter("path", dataPath)
            .appendQueryParameter("fallback", if (allowAlbumFallback) "1" else "0")
            .build()
            .toString()
    }

    fun shouldAllowAlbumFallback(album: String): Boolean {
        val clean = album.trim()
        if (clean.isEmpty()) return false
        return !clean.equals("Unknown Album", ignoreCase = true) &&
            !clean.equals("<unknown>", ignoreCase = true) &&
            !clean.equals("Music", ignoreCase = true) &&
            !clean.equals("Download", ignoreCase = true) &&
            !clean.equals("Downloads", ignoreCase = true) &&
            !clean.equals("Audio", ignoreCase = true)
    }

    fun extractArtworkBitmap(
        context: Context,
        uri: Uri,
        maxDimPx: Int = 512,
        allowRgb565: Boolean = false
    ): Bitmap? {
        val contentUriStr = uri.getQueryParameter("contentUri").orEmpty()
        val dataPath = uri.getQueryParameter("path").orEmpty()
        val albumId = uri.getQueryParameter("albumId")?.toLongOrNull() ?: 0L
        val allowAlbumFallback = uri.getQueryParameter("fallback") == "1"

        // 1. Always extract per-file embedded picture directly from the audio track
        val embeddedBytes = extractEmbeddedPictureBytes(context, dataPath, contentUriStr)
        if (embeddedBytes != null && embeddedBytes.isNotEmpty()) {
            decodeSampledBitmap(embeddedBytes, maxDimPx, allowRgb565)?.let { return it }
        }

        // 2. Only fall back to shared album art when the track belongs to a real named album
        if (!allowAlbumFallback) return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && contentUriStr.isNotBlank()) {
            try {
                return context.contentResolver.loadThumbnail(
                    Uri.parse(contentUriStr),
                    Size(maxDimPx, maxDimPx),
                    null
                )
            } catch (_: Exception) {
            }
        }

        if (albumId > 0L) {
            try {
                val albumArtUri = ContentUris.withAppendedId(
                    Uri.parse("content://media/external/audio/albumart"),
                    albumId
                )
                context.contentResolver.openInputStream(albumArtUri)?.use { input ->
                    val bytes = input.readBytes()
                    if (bytes.isNotEmpty()) {
                        return decodeSampledBitmap(bytes, maxDimPx, allowRgb565)
                    }
                }
            } catch (_: Exception) {
            }
        }

        return null
    }

    private fun extractEmbeddedPictureBytes(
        context: Context,
        dataPath: String,
        contentUriStr: String
    ): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            val fileOk = dataPath.isNotBlank() && runCatching {
                val f = File(dataPath)
                f.isFile && f.canRead()
            }.getOrDefault(false)

            if (fileOk) {
                retriever.setDataSource(dataPath)
                retriever.embeddedPicture
            } else if (contentUriStr.isNotBlank()) {
                retriever.setDataSource(context, Uri.parse(contentUriStr))
                retriever.embeddedPicture
            } else {
                null
            }
        } catch (_: Throwable) {
            if (contentUriStr.isNotBlank()) {
                runCatching {
                    retriever.setDataSource(context, Uri.parse(contentUriStr))
                    retriever.embeddedPicture
                }.getOrNull()
            } else {
                null
            }
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun decodeSampledBitmap(
        bytes: ByteArray,
        maxDimPx: Int,
        allowRgb565: Boolean
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val rawW = bounds.outWidth
        val rawH = bounds.outHeight
        if (rawW <= 0 || rawH <= 0) return null

        var inSampleSize = 1
        val target = maxDimPx.coerceIn(64, 1024)
        while ((rawW / inSampleSize) > target * 2 || (rawH / inSampleSize) > target * 2) {
            inSampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = if (allowRgb565) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }
}

class TrackArtworkKeyer : Keyer<Uri> {
    override fun key(data: Uri, options: Options): String? {
        return if (data.scheme == TrackArtworkLoader.SCHEME) {
            data.toString()
        } else {
            null
        }
    }
}

class TrackArtworkFetcher(
    private val context: Context,
    private val uri: Uri,
    private val options: Options
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val targetW = (options.size.width as? Dimension.Pixels)?.px ?: 512
        val targetH = (options.size.height as? Dimension.Pixels)?.px ?: 512
        val maxDim = maxOf(targetW, targetH).coerceIn(64, 1024)

        val bitmap = TrackArtworkLoader.extractArtworkBitmap(
            context = context,
            uri = uri,
            maxDimPx = maxDim,
            allowRgb565 = options.allowRgb565
        ) ?: return@withContext null

        DrawableResult(
            drawable = BitmapDrawable(context.resources, bitmap),
            isSampled = true,
            dataSource = DataSource.DISK
        )
    }

    class Factory(private val context: Context) : Fetcher.Factory<Uri> {
        override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != TrackArtworkLoader.SCHEME) return null
            return TrackArtworkFetcher(context.applicationContext, data, options)
        }
    }
}
