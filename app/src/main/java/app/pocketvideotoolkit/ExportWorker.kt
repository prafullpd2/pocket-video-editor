package app.pocketvideotoolkit

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.AudioEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import androidx.work.workDataOf
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.media3.common.util.UnstableApi
class ExportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val KEY_URI = "source_uri"
        const val KEY_URIS = "source_uris"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_BITRATE = "bitrate"
        const val KEY_CODEC = "codec"
        const val KEY_FPS = "fps"
        const val KEY_BITRATE_MODE = "bitrate_mode"
        const val KEY_AUDIO = "audio"
        const val KEY_TRIM = "trim"
        const val KEY_START = "start_ms"
        const val KEY_END = "end_ms"
        const val KEY_COPY_SOURCE_STREAMS = "copy_source_streams"
        const val KEY_OUTPUT_NAME = "output_name"
        private const val CHANNEL = "exports"
        private const val NOTIFICATION_ID = 7214
    }

    private val appContext = applicationContext

    override suspend fun doWork(): Result = withContext(Dispatchers.Main.immediate) {
        val sources = inputData.getStringArray(KEY_URIS)?.map(Uri::parse)
            ?.takeIf { it.isNotEmpty() }
            ?: inputData.getString(KEY_URI)?.let { listOf(Uri.parse(it)) }
            ?: return@withContext Result.failure()
        val source = sources.first()
        val requestedOutputName = inputData.getString(KEY_OUTPUT_NAME).orEmpty()
        val width = inputData.getInt(KEY_WIDTH, 1280)
        val height = inputData.getInt(KEY_HEIGHT, 720)
        val videoRate = inputData.getInt(KEY_BITRATE, 8_000_000)
        val videoMime = when (inputData.getString(KEY_CODEC)) {
            "H.265" -> MimeTypes.VIDEO_H265
            "AV1" -> MimeTypes.VIDEO_AV1
            else -> MimeTypes.VIDEO_H264
        }
        val frameRate = inputData.getInt(KEY_FPS, 0)
        val bitrateMode = inputData.getInt(KEY_BITRATE_MODE, 0)
        val audioChoice = inputData.getString(KEY_AUDIO) ?: "AAC · 192 kbps"
        val audioRate = when (audioChoice) { "AAC · 128 kbps" -> 128_000; "AAC · 256 kbps" -> 256_000; else -> 192_000 }
        val trim = inputData.getBoolean(KEY_TRIM, false)
        val startMs = inputData.getLong(KEY_START, 0L)
        val endMs = inputData.getLong(KEY_END, Long.MAX_VALUE)
        if (width !in 16..7680 || height !in 16..7680) return@withContext Result.failure(workDataOf("error" to "Dimensions must be between 16 and 7680 pixels."))
        if (trim && endMs <= startMs) return@withContext Result.failure(workDataOf("error" to "Trim end must be after start."))

        createChannel()
        setForeground(foregroundInfo(buildNotification("Preparing export", 0)))
        val copySourceStreams = inputData.getBoolean(KEY_COPY_SOURCE_STREAMS, false)
        val sourceNameForContainer = appContext.contentResolver.query(source, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }.orEmpty()
        val sourceMimeForContainer = appContext.contentResolver.getType(source)
        val isWebmCopy = copySourceStreams && sources.size == 1 &&
            sourceNameForContainer.endsWith(".webm", ignoreCase = true) && (sourceMimeForContainer == null || sourceMimeForContainer == "video/webm")
        val outputExtension = if (isWebmCopy) ".webm" else ".mp4"
        val outputMime = if (isWebmCopy) "video/webm" else "video/mp4"
        val output = File(appContext.cacheDir, "pvt-${id}$outputExtension")
        output.delete()
        try {
            if (copySourceStreams) {
                if (trim) throw IOException("Turn off trimming to copy source streams without re-encoding.")
                Hdr10PlusPassthrough.export(appContext, sources, output) { progress ->
                    setProgress(workDataOf("progress" to progress))
                    setForeground(foregroundInfo(buildNotification("Copying original encoded streams", progress)))
                }
                currentCoroutineContext().ensureActive()
                val saved = publish(output, requestedOutputName.ifBlank { sourceNameForContainer.ifBlank { source.lastPathSegment ?: "video" } }, outputMime, outputExtension)
                setProgress(workDataOf("progress" to 100))
                return@withContext Result.success(workDataOf("saved_uri" to saved.toString(), "output_name" to requestedOutputName, "source_uri" to source.toString()))
            }
            val editedItems = sources.mapIndexed { index, uri ->
                val itemBuilder = MediaItem.Builder().setUri(uri)
                if (trim && sources.size == 1) itemBuilder.setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
                EditedMediaItem.Builder(itemBuilder.build())
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(width, height, Presentation.LAYOUT_SCALE_TO_FIT))))
                    .apply { if (frameRate > 0) setFrameRate(frameRate) }
                    .build()
            }
            val sequence = EditedMediaItemSequence.Builder(editedItems).build()
            val composition = Composition.Builder(sequence)
                .setHdrMode(Composition.HDR_MODE_KEEP_HDR)
                .build()
            val videoEncoderSettings = VideoEncoderSettings.Builder().setBitrate(videoRate).apply {
                if (bitrateMode > 0) setBitrateMode(bitrateMode)
            }.build()
            val encoderFactory = DefaultEncoderFactory.Builder(appContext)
                .setRequestedVideoEncoderSettings(videoEncoderSettings)
                .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(audioRate).build())
                .build()
            val transformerBuilder = Transformer.Builder(appContext).setEncoderFactory(encoderFactory)
                .setVideoMimeType(videoMime)
            if (audioChoice != "Keep source") transformerBuilder.setAudioMimeType(MimeTypes.AUDIO_AAC)
            val builder = transformerBuilder.addListener(object : Transformer.Listener {})
            val transformer = builder.build()
            val export = launch {
                while (isActive) {
                    val holder = ProgressHolder()
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        val p = holder.progress.coerceIn(0, 99)
                        setProgress(workDataOf("progress" to p))
                        setForeground(foregroundInfo(buildNotification("Exporting video", p)))
                    }
                    delay(700)
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }
                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(exportException)
                        }
                    })
                    continuation.invokeOnCancellation { transformer.cancel() }
                    transformer.start(composition, output.absolutePath)
                }
            } finally {
                export.cancel()
            }
            currentCoroutineContext().ensureActive()
            val saved = publish(output, requestedOutputName.ifBlank { source.lastPathSegment ?: "video" }, outputMime, outputExtension)
            setProgress(workDataOf("progress" to 100))
            Result.success(workDataOf("saved_uri" to saved.toString(), "output_name" to requestedOutputName, "source_uri" to source.toString()))
        } catch (e: Exception) {
            if (isStopped) Result.failure(workDataOf("error" to "Export cancelled"))
            else Result.failure(workDataOf("error" to (e.message ?: "This format or encoder is not supported on this device.")))
        } finally {
            output.delete()
        }
    }

    private fun publish(file: File, sourceName: String, mimeType: String, extension: String): Uri {
        val safe = sourceName.substringBeforeLast('.', sourceName).trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
            .ifBlank { "PocketVideo_export" }
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "${safe}_export$extension")
                put(MediaStore.Video.Media.MIME_TYPE, mimeType)
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Pocket Video Toolkit")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Could not create output in Movies.")
            try {
                appContext.contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } } ?: throw IOException("Could not open output file.")
                values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
                appContext.contentResolver.update(uri, values, null, null)
            } catch (e: Exception) { appContext.contentResolver.delete(uri, null, null); throw e }
            return uri
        }
        val dir = appContext.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES) ?: appContext.filesDir
        val target = File(dir, "${safe}_export$extension")
        FileInputStream(file).use { input -> target.outputStream().use(input::copyTo) }
        android.media.MediaScannerConnection.scanFile(appContext, arrayOf(target.absolutePath), arrayOf(mimeType), null)
        return Uri.fromFile(target)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = appContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "Video exports", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun buildNotification(message: String, progress: Int): Notification = NotificationCompat.Builder(appContext, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Pocket Video Toolkit")
        .setContentText(message)
        .setProgress(100, progress, progress == 0)
        .setOngoing(true)
        .build()

    private fun foregroundInfo(notification: Notification): ForegroundInfo = if (Build.VERSION.SDK_INT >= 29) {
        ForegroundInfo(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    } else {
        ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
