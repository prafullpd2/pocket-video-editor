package app.pocketvideotoolkit

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri

object VideoInspector {
    fun durationMs(context: Context, uri: Uri): Long = runCatching {
        MediaMetadataRetriever().useRetriever(context, uri) {
            it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    fun inspect(context: Context, uri: Uri): String = try {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(context, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val mime = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: "video"
            val dims = if (rotation % 180 == 0) "${w}×${h}" else "${h}×${w}"
            "$dims · ${duration / 60_000}:${((duration / 1000) % 60).toString().padStart(2, '0')} · $mime"
        } finally { r.release() }
    } catch (_: Exception) { "Could not read video details. The file may use an unsupported format." }

    private inline fun <T> MediaMetadataRetriever.useRetriever(context: Context, uri: Uri, block: (MediaMetadataRetriever) -> T): T {
        try {
            setDataSource(context, uri)
            return block(this)
        } finally {
            release()
        }
    }
}
