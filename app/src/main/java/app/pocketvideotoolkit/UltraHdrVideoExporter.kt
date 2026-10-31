package app.pocketvideotoolkit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.HardwareBufferRenderer
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RenderNode
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.media.ImageWriter
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import io.github.takusan23.akaricore.common.toAkariCoreInputOutputData
import io.github.takusan23.akaricore.graphics.mediacodec.AkariVideoEncoder
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object UltraHdrVideoExporter {
    fun hasMain10HevcEncoder(): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { codec ->
            codec.isEncoder && codec.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) } &&
                codec.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC).profileLevels.any {
                    it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                }
        }
    }.getOrDefault(false)

    @RequiresApi(34)
    suspend fun export(context: Context, imageUri: Uri, durationSeconds: Int = 3): Uri = withContext(Dispatchers.IO) {
        if (!hasMain10HevcEncoder()) throw IOException("This device does not advertise an HEVC Main10 encoder.")
        val bitmap = android.graphics.ImageDecoder.decodeBitmap(
            android.graphics.ImageDecoder.createSource(context.contentResolver, imageUri)
        ) { decoder, imageInfo, _ ->
            val imageSize = imageInfo.size
            val scale = minOf(1f, 2048f / maxOf(imageSize.width, imageSize.height))
            decoder.setTargetSize((imageSize.width * scale).toInt().coerceAtLeast(2), (imageSize.height * scale).toInt().coerceAtLeast(2))
        }
        try {
            if (!bitmap.hasGainmap()) throw IOException("This image has no Ultra HDR gain map. Pick an Ultra HDR JPEG.")
            writeHdrVideo(context, bitmap, durationSeconds.coerceIn(1, 10))
        } finally {
            bitmap.recycle()
        }
    }

    @RequiresApi(34)
    private suspend fun writeHdrVideo(context: Context, bitmap: Bitmap, durationSeconds: Int): Uri = withContext(Dispatchers.IO) {
        val width = (bitmap.width and -2).coerceAtLeast(2)
        val height = (bitmap.height and -2).coerceAtLeast(2)
        val fps = 30
        val output = File(context.cacheDir, "pvt-hdr-${System.nanoTime()}.mp4")
        val encoder = AkariVideoEncoder()
        var imageWriter: ImageWriter? = null
        try {
            encoder.prepare(
                output = output.toAkariCoreInputOutputData(),
                containerFormat = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                outputVideoWidth = width,
                outputVideoHeight = height,
                frameRate = fps,
                bitRate = 8_000_000,
                keyframeInterval = 1,
                codecName = MediaFormat.MIMETYPE_VIDEO_HEVC,
                tenBitHdrParametersOrNullSdr = AkariVideoEncoder.TenBitHdrParameters()
            )
            imageWriter = ImageWriter.Builder(encoder.getInputSurface())
                .setHardwareBufferFormat(HardwareBuffer.RGBA_1010102)
                .setDataSpace(DataSpace.DATASPACE_BT2020_HLG)
                .setMaxImages(4)
                .build()
            val frameCount = durationSeconds * fps
            coroutineScope {
                val encoderJob = launch(Dispatchers.Default) { encoder.start() }
                try {
                    repeat(frameCount) {
                        val image = imageWriter!!.dequeueInputImage()
                        try {
                            val buffer = image.hardwareBuffer ?: throw IOException("10-bit image buffer is unavailable.")
                            val renderer = HardwareBufferRenderer(buffer)
                            try {
                                val node = RenderNode("pvt-ultrahdr-frame")
                                node.setPosition(0, 0, image.width, image.height)
                                val canvas: Canvas = node.beginRecording()
                                canvas.drawColor(Color.BLACK)
                                val scale = minOf(image.width.toFloat() / bitmap.width, image.height.toFloat() / bitmap.height)
                                val drawWidth = (bitmap.width * scale).toInt()
                                val drawHeight = (bitmap.height * scale).toInt()
                                val left = (image.width - drawWidth) / 2
                                val top = (image.height - drawHeight) / 2
                                canvas.drawBitmap(bitmap, null, Rect(left, top, left + drawWidth, top + drawHeight), Paint(Paint.FILTER_BITMAP_FLAG))
                                node.endRecording()
                                renderer.setContentRoot(node)
                                renderer.obtainRenderRequest()
                                    .setColorSpace(ColorSpace.get(ColorSpace.Named.BT2020_HLG))
                                    .draw({ executor -> executor.run() }, { result -> image.fence = result.fence })
                                while (image.fence?.isValid != true) Thread.yield()
                                image.fence?.awaitForever()
                                imageWriter!!.queueInputImage(image)
                            } finally {
                                renderer.close()
                            }
                        } catch (error: Exception) {
                            runCatching { image.close() }
                            throw error
                        }
                    }
                } finally {
                    encoderJob.cancelAndJoin()
                }
            }
            publish(context, output)
        } finally {
            runCatching { imageWriter?.close() }
            output.delete()
        }
    }

    private fun publish(context: Context, file: File): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "PocketToolkit_UltraHDR_${System.currentTimeMillis()}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/Pocket Video Toolkit")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Could not create the HDR video in Movies.")
        try {
            context.contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                ?: throw IOException("Could not open the HDR video output.")
            if (Build.VERSION.SDK_INT >= 29) {
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            }
        } catch (error: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
        return uri
    }
}
