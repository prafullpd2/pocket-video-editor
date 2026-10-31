package app.pocketvideotoolkit

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.SurfaceHolder
import androidx.annotation.RequiresApi
import androidx.media3.common.util.UnstableApi
import io.github.takusan23.akaricore.common.toAkariCoreInputOutputData
import io.github.takusan23.akaricore.graphics.AkariGraphicsProcessor
import io.github.takusan23.akaricore.graphics.AkariGraphicsSurfaceTexture
import io.github.takusan23.akaricore.graphics.data.AkariGraphicsProcessorColorSpaceType
import io.github.takusan23.akaricore.graphics.data.AkariGraphicsProcessorRenderingPrepareData
import io.github.takusan23.akaricore.graphics.mediacodec.AkariVideoDecoder
import io.github.takusan23.libaicaroid.LibUltraHdrBridge
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
class HdrFrameCaptureSession(private val context: Context) {
    data class VideoInfo(val width: Int, val height: Int, val durationMs: Long, val transfer: String)

    private var processor: AkariGraphicsProcessor? = null
    private var decoder: AkariVideoDecoder? = null
    private var videoTexture: AkariGraphicsSurfaceTexture? = null
    private var colorSpace: AkariGraphicsProcessorColorSpaceType? = null
    private var videoInfo: VideoInfo? = null

    @RequiresApi(34)
    suspend fun configure(uri: Uri, holder: SurfaceHolder): VideoInfo = withContext(Dispatchers.IO) {
        closePipeline()
        if (!holder.surface.isValid) throw IOException("Preview surface is not ready yet.")

        val retriever = MediaMetadataRetriever()
        val metadata = try {
            retriever.setDataSource(context, uri)
            val rawWidth = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val rawHeight = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val standard = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)?.toIntOrNull()
            val transfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
            val color = when {
                standard == MediaFormat.COLOR_STANDARD_BT2020 && transfer == MediaFormat.COLOR_TRANSFER_HLG -> AkariGraphicsProcessorColorSpaceType.TEN_BIT_HDR_BT2020_HLG
                standard == MediaFormat.COLOR_STANDARD_BT2020 && transfer == MediaFormat.COLOR_TRANSFER_ST2084 -> AkariGraphicsProcessorColorSpaceType.TEN_BIT_HDR_BT2020_PQ
                else -> null
            } ?: throw IOException("This clip is not identified as BT.2020 HLG or PQ HDR. Choose a 10-bit HDR clip.")
            val (width, height) = if (rotation % 180 != 0) rawHeight to rawWidth else rawWidth to rawHeight
            if (width < 16 || height < 16 || duration < 1) throw IOException("Could not read video dimensions or duration.")
            Triple(VideoInfo(width, height, duration, if (color == AkariGraphicsProcessorColorSpaceType.TEN_BIT_HDR_BT2020_HLG) "HLG" else "PQ"), color, duration)
        } finally {
            retriever.release()
        }

        holder.setFixedSize(metadata.first.width, metadata.first.height)
        val newProcessor = AkariGraphicsProcessor(
            renderingPrepareData = AkariGraphicsProcessorRenderingPrepareData.SurfaceRendering(
                surface = holder.surface,
                width = metadata.first.width,
                height = metadata.first.height
            ),
            colorSpaceType = metadata.second
        ).apply { prepare() }
        val newTexture = newProcessor.genTextureId { id -> AkariGraphicsSurfaceTexture(id) }
        try {
            val newDecoder = AkariVideoDecoder().apply {
                prepare(uri.toAkariCoreInputOutputData(context), newTexture.surface)
            }
            processor = newProcessor
            videoTexture = newTexture
            decoder = newDecoder
            colorSpace = metadata.second
            videoInfo = metadata.first
            metadata.first
        } catch (error: Exception) {
            newProcessor.destroy { newTexture.destroy() }
            throw error
        }
    }

    @RequiresApi(34)
    suspend fun preview(positionMs: Long) {
        renderFrame(positionMs, saveOrientation = false)
    }

    @RequiresApi(34)
    suspend fun saveUltraHdrPhoto(positionMs: Long): Uri = withContext(Dispatchers.IO) {
        val info = videoInfo ?: throw IOException("Select and prepare an HDR video first.")
        val transfer = colorSpace ?: throw IOException("HDR transfer information is unavailable.")
        val pixels = renderFrame(positionMs, saveOrientation = true)
        val raw = File(context.cacheDir, "pvt-${System.nanoTime()}.rgba1010102")
        val encoded = File(context.cacheDir, "pvt-${System.nanoTime()}.jpg")
        try {
            raw.writeBytes(pixels)
            LibUltraHdrBridge.encodeFromRgba1010102(
                width = info.width,
                height = info.height,
                rgba1010102FilePath = raw.absolutePath,
                ultraHdrResultFilePath = encoded.absolutePath,
                colorSpaceType = if (transfer == AkariGraphicsProcessorColorSpaceType.TEN_BIT_HDR_BT2020_HLG) LibUltraHdrBridge.HdrColorSpaceType.HLG else LibUltraHdrBridge.HdrColorSpaceType.PQ
            )
            val bitmap = BitmapFactory.decodeFile(encoded.absolutePath) ?: throw IOException("Ultra HDR JPEG encoding failed.")
            try {
                val name = "PocketToolkit_UltraHDR_${System.currentTimeMillis()}.jpg"
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Pocket Video Toolkit")
                    if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val outputUri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("Could not create the Ultra HDR photo in Pictures.")
                try {
                    val saved = context.contentResolver.openOutputStream(outputUri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) } ?: false
                    if (!saved) throw IOException("Could not write the Ultra HDR photo.")
                    if (Build.VERSION.SDK_INT >= 29) {
                        context.contentResolver.update(outputUri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                    }
                } catch (error: Exception) {
                    context.contentResolver.delete(outputUri, null, null)
                    throw error
                }
                outputUri
            } finally {
                bitmap.recycle()
            }
        } finally {
            raw.delete()
            encoded.delete()
        }
    }

    @RequiresApi(34)
    private suspend fun renderFrame(positionMs: Long, saveOrientation: Boolean): ByteArray {
        val activeDecoder = decoder ?: throw IOException("Select an HDR video first.")
        val activeTexture = videoTexture ?: throw IOException("Video preview is not ready.")
        val activeProcessor = processor ?: throw IOException("HDR rendering is not ready.")
        val duration = videoInfo?.durationMs ?: 0L
        val seek = activeDecoder.seekTo(positionMs.coerceIn(0, (duration - 1).coerceAtLeast(0)))
        if (!seek.isSuccessful) throw IOException("Could not decode a frame at this timestamp.")
        return activeProcessor.drawOneshotAndGlReadPixels {
            drawSurfaceTexture(
                akariSurfaceTexture = activeTexture,
                nullOrTextureUpdateTimeoutMs = 750,
                onTransform = { matrix ->
                    if (saveOrientation) android.opengl.Matrix.scaleM(matrix, 0, 1f, -1f, 1f)
                }
            )
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) { closePipeline() }

    private suspend fun closePipeline() {
        decoder?.destroy()
        decoder = null
        val activeProcessor = processor
        val activeTexture = videoTexture
        if (activeProcessor != null) {
            if (activeTexture != null) activeProcessor.destroy { activeTexture.destroy() }
            else activeProcessor.destroy()
        }
        processor = null
        videoTexture = null
        colorSpace = null
        videoInfo = null
    }
}
