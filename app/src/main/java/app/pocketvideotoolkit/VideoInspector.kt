package app.pocketvideotoolkit

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns

internal data class VideoFileDetails(
    val name: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val modifiedSeconds: Long,
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val hdr: String,
    val videoCodec: String,
    val audioCodec: String,
    val videoBitrate: Int = 0,
    val audioBitrate: Int = 0,
    val audioSampleRate: Int = 0,
    val audioChannels: Int = 0,
    val colorStandard: String = "—",
    val colorTransfer: String = "—"
)

object VideoInspector {
    fun resolutionLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return "—"
        val longEdge = maxOf(width, height)
        val shortEdge = minOf(width, height)
        return when {
            longEdge >= 7680 || shortEdge >= 4320 -> "8K"
            longEdge >= 3840 || shortEdge >= 2160 -> "4K"
            longEdge >= 2560 || shortEdge >= 1440 -> "2K"
            shortEdge >= 1080 -> "1080p"
            shortEdge >= 720 -> "720p"
            shortEdge >= 480 -> "480p"
            shortEdge >= 360 -> "360p"
            shortEdge >= 240 -> "240p"
            else -> "${width}×${height}"
        }
    }

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
            val transfer = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
            val colorStandard = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)?.toIntOrNull()
            val dims = if (rotation % 180 == 0) "${w}×${h}" else "${h}×${w}"
            val codecs = readTrackCodecs(context, uri)
            val hdr = detectHdrFormat(context, uri, transfer, colorStandard)
            "$dims · ${duration / 60_000}:${((duration / 1000) % 60).toString().padStart(2, '0')} · $mime · ${codecs.first} · ${codecs.second} · $hdr"
        } finally { r.release() }
    } catch (_: Exception) { "Could not read video details. The file may use an unsupported format." }

    internal fun details(context: Context, uri: Uri): VideoFileDetails? = runCatching {
        val retriever = MediaMetadataRetriever()
        val base = try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            val transfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
            val standard = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)?.toIntOrNull()
            VideoFileDetails(
                name = null,
                durationMs = duration,
                sizeBytes = 0L,
                modifiedSeconds = 0L,
                width = width,
                height = height,
                frameRate = frameRate,
                hdr = detectHdrFormat(context, uri, transfer, standard),
                videoCodec = "Video —",
                audioCodec = "Audio —"
            )
        } finally { retriever.release() }
        val nameAndSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString)
                    val size = cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let(cursor::getLong)?.takeIf { it > 0L } ?: 0L
                    name to size
                } else null
            }
        }.getOrNull()
        val modified = runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } ?: 0L
        }.getOrDefault(0L)
        val tracks = readTrackDetails(context, uri)
        base.copy(
            name = nameAndSize?.first, sizeBytes = nameAndSize?.second ?: 0L, modifiedSeconds = modified,
            videoCodec = tracks.videoCodec, audioCodec = tracks.audioCodec,
            videoBitrate = tracks.videoBitrate, audioBitrate = tracks.audioBitrate,
            audioSampleRate = tracks.audioSampleRate, audioChannels = tracks.audioChannels,
            colorStandard = tracks.colorStandard, colorTransfer = tracks.colorTransfer
        )
    }.getOrNull()

    private inline fun <T> MediaMetadataRetriever.useRetriever(context: Context, uri: Uri, block: (MediaMetadataRetriever) -> T): T {
        try {
            setDataSource(context, uri)
            return block(this)
        } finally {
            release()
        }
    }
}

private data class TrackDetails(
    val videoCodec: String, val audioCodec: String,
    val videoBitrate: Int, val audioBitrate: Int, val audioSampleRate: Int, val audioChannels: Int,
    val colorStandard: String, val colorTransfer: String
)

private fun readTrackDetails(context: Context, uri: Uri): TrackDetails = runCatching {
    val extractor = android.media.MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        var video: android.media.MediaFormat? = null
        var audio: android.media.MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            when {
                format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true && video == null -> video = format
                format.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true && audio == null -> audio = format
            }
        }
        fun int(format: android.media.MediaFormat?, key: String) = if (format?.containsKey(key) == true) format.getInteger(key) else 0
        val videoMime = video?.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
        val audioMime = audio?.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
        val standard = when (int(video, "color-standard")) { 1 -> "BT.709"; 2 -> "BT.601"; 6 -> "BT.2020"; else -> "—" }
        val transfer = when (int(video, "color-transfer")) { 3 -> "SDR"; 6 -> "HLG"; 7 -> "PQ"; else -> "—" }
        TrackDetails(
            codecName(videoMime, true), codecName(audioMime, false), int(video, android.media.MediaFormat.KEY_BIT_RATE),
            int(audio, android.media.MediaFormat.KEY_BIT_RATE), int(audio, android.media.MediaFormat.KEY_SAMPLE_RATE),
            int(audio, android.media.MediaFormat.KEY_CHANNEL_COUNT), standard, transfer
        )
    } finally { extractor.release() }
}.getOrElse { TrackDetails("Video —", "Audio —", 0, 0, 0, 0, "—", "—") }

private fun codecName(mime: String, video: Boolean): String = when {
    mime.contains("hevc", true) || mime.contains("h265", true) -> "H.265"
    mime.contains("avc", true) || mime.contains("h264", true) -> "H.264"
    mime.contains("av01", true) -> "AV1"
    mime.contains("vp9", true) -> "VP9"
    mime.contains("opus", true) -> "Opus"
    mime.contains("aac", true) || mime.contains("mp4a", true) -> "AAC"
    mime.isBlank() -> if (video) "Video —" else "Audio —"
    else -> mime.substringAfterLast('/').uppercase(java.util.Locale.ROOT)
}
