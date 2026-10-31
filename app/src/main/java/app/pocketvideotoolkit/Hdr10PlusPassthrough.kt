package app.pocketvideotoolkit

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Copies encoded samples without decoding or re-encoding them, preserving in-band HDR10+ SEI. */
internal object Hdr10PlusPassthrough {
    suspend fun export(context: Context, sources: List<Uri>, output: File, onProgress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "Choose at least one video." }
        if (sources.size == 1) copySourceExactly(context, sources.single(), output, onProgress)
        else muxCompatibleHevcClips(context, sources, output, onProgress)
    }

    private suspend fun copySourceExactly(context: Context, uri: Uri, output: File, onProgress: suspend (Int) -> Unit) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        val mime = context.contentResolver.getType(uri)
        val mp4 = name.endsWith(".mp4", ignoreCase = true) && (mime == null || mime == "video/mp4")
        val webm = name.endsWith(".webm", ignoreCase = true) && (mime == null || mime == "video/webm")
        if (!mp4 && !webm) {
            throw IOException("Exact stream copy supports MP4 and WebM files. VP9 and Opus tracks can be kept in WebM without re-encoding.")
        }
        val total = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }
            .getOrNull().takeIf { it != null && it > 0L } ?: -1L
        context.contentResolver.openInputStream(uri)?.use { input ->
            output.outputStream().buffered().use { out ->
                val buffer = ByteArray(1024 * 1024)
                var copied = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    out.write(buffer, 0, count)
                    copied += count
                    if (total > 0) onProgress(((copied * 100 / total).toInt()).coerceIn(0, 99))
                }
            }
        } ?: throw IOException("Could not read the selected video.")
        if (output.length() == 0L) throw IOException("The selected video was empty.")
    }

    private data class Clip(
        val extractor: MediaExtractor,
        val videoIndex: Int,
        val audioIndex: Int?,
        val videoFormat: MediaFormat,
        val audioFormat: MediaFormat?,
        val durationUs: Long
    )

    private suspend fun muxCompatibleHevcClips(context: Context, sources: List<Uri>, output: File, onProgress: suspend (Int) -> Unit) {
        if (Build.VERSION.SDK_INT < 28) throw IOException("Joining without re-encoding requires Android 9 or newer.")
        val clips = mutableListOf<Clip>()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            sources.forEach { uri ->
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(context, uri, null)
                    val videos = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                    val audios = (0 until extractor.trackCount).filter { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                    if (videos.size != 1 || audios.size > 1) throw IOException("Each clip must have one video track and at most one audio track.")
                    if (videos.size + audios.size != extractor.trackCount) throw IOException("Copy-mode joining cannot keep subtitle or extra metadata tracks. Choose clips with only video and optional audio.")
                    val videoIndex = videos.single()
                    val videoFormat = extractor.getTrackFormat(videoIndex)
                    if (videoFormat.getString(MediaFormat.KEY_MIME) != "video/hevc") throw IOException("Joining in copy mode currently requires HEVC clips. No video is re-encoded.")
                    val audioIndex = audios.singleOrNull()
                    val audioFormat = audioIndex?.let(extractor::getTrackFormat)
                    extractor.selectTrack(videoIndex)
                    audioIndex?.let(extractor::selectTrack)
                    val durationUs = sequenceOf(videoFormat, audioFormat).filterNotNull()
                        .mapNotNull { if (it.containsKey(MediaFormat.KEY_DURATION)) it.getLong(MediaFormat.KEY_DURATION) else null }
                        .maxOrNull() ?: 0L
                    clips += Clip(extractor, videoIndex, audioIndex, videoFormat, audioFormat, durationUs)
                } catch (error: Exception) {
                    extractor.release()
                    throw error
                }
            }
            val first = clips.first()
            clips.drop(1).forEachIndexed { index, clip ->
                if (!sameTrackFormat(first.videoFormat, clip.videoFormat, video = true)) {
                    throw IOException("Clip ${index + 2} has different HEVC dimensions, color format, or codec configuration. Copy-mode joining requires matching clips.")
                }
                if ((first.audioFormat == null) != (clip.audioFormat == null) ||
                    (first.audioFormat != null && clip.audioFormat != null && !sameTrackFormat(first.audioFormat, clip.audioFormat, video = false))) {
                    throw IOException("Clip ${index + 2} has a different audio track. Copy-mode joining requires matching audio formats.")
                }
                if (clip.durationUs <= 0L) throw IOException("Could not read the duration of every clip.")
            }
            if (first.durationUs <= 0L) throw IOException("Could not read the duration of the first clip.")

            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outputVideo = muxer.addTrack(first.videoFormat)
            val outputAudio = first.audioFormat?.let(muxer::addTrack)
            val rotation = if (first.videoFormat.containsKey("rotation-degrees")) first.videoFormat.getInteger("rotation-degrees") else 0
            if (rotation != 0) muxer.setOrientationHint(rotation)
            muxer.start()
            started = true

            var timelineOffsetUs = 0L
            clips.forEachIndexed { clipIndex, clip ->
                val buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024)
                val info = android.media.MediaCodec.BufferInfo()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val sourceTrack = clip.extractor.sampleTrackIndex
                    if (sourceTrack < 0) break
                    val sampleSize = clip.extractor.sampleSize
                    if (sampleSize < 0) break
                    if (sampleSize > 64L * 1024 * 1024) throw IOException("A source frame is too large for safe copy-mode joining.")
                    val sampleBuffer = if (sampleSize <= buffer.capacity()) buffer else ByteBuffer.allocateDirect(sampleSize.toInt())
                    sampleBuffer.clear()
                    val read = clip.extractor.readSampleData(sampleBuffer, 0)
                    if (read < 0) break
                    val outputTrack = when (sourceTrack) {
                        clip.videoIndex -> outputVideo
                        clip.audioIndex -> outputAudio
                        else -> null
                    }
                    if (outputTrack != null) {
                        info.set(0, read, clip.extractor.sampleTime + timelineOffsetUs, clip.extractor.sampleFlags)
                        sampleBuffer.position(0)
                        sampleBuffer.limit(read)
                        muxer.writeSampleData(outputTrack, sampleBuffer, info)
                    }
                    clip.extractor.advance()
                }
                timelineOffsetUs += clip.durationUs
                onProgress(((clipIndex + 1) * 100 / clips.size).coerceAtMost(99))
            }
            muxer.stop()
            started = false
        } finally {
            if (started) runCatching { muxer?.stop() }
            muxer?.release()
            clips.forEach { it.extractor.release() }
        }
        if (!output.isFile || output.length() == 0L) throw IOException("The joined file could not be finalized.")
    }

    private fun sameTrackFormat(first: MediaFormat, second: MediaFormat, video: Boolean): Boolean {
        val keys = if (video) listOf(
            MediaFormat.KEY_MIME, MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT,
            MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL, MediaFormat.KEY_COLOR_STANDARD,
            MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.KEY_COLOR_RANGE, "rotation-degrees"
        ) else listOf(MediaFormat.KEY_MIME, MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_PROFILE)
        if (keys.any { key -> value(first, key) != value(second, key) }) return false
        for (key in listOf("csd-0", "csd-1", "csd-2")) {
            if (!sameBytes(first.getByteBuffer(key), second.getByteBuffer(key))) return false
        }
        return true
    }

    private fun value(format: MediaFormat, key: String): Any? = runCatching {
        if (!format.containsKey(key)) return@runCatching null
        when (key) {
            MediaFormat.KEY_MIME -> format.getString(key)
            else -> format.getInteger(key)
        }
    }.getOrNull()

    private fun sameBytes(first: ByteBuffer?, second: ByteBuffer?): Boolean {
        if (first == null || second == null) return first == null && second == null
        val a = first.duplicate()
        val b = second.duplicate()
        if (a.remaining() != b.remaining()) return false
        while (a.hasRemaining()) if (a.get() != b.get()) return false
        return true
    }
}
