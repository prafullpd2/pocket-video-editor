package app.pocketvideotoolkit

import android.media.MediaCodecList
import androidx.media3.common.MimeTypes

object CodecSupport {
    fun hasVideoEncoder(mime: String): Boolean = runCatching {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
    }.getOrDefault(false)

    fun summary(): String {
        val h264 = hasVideoEncoder(MimeTypes.VIDEO_H264)
        val h265 = hasVideoEncoder(MimeTypes.VIDEO_H265)
        return "Encoders advertised: ${if (h264) "H.264" else "no H.264"} · ${if (h265) "H.265" else "no H.265"}. Size/profile support can still vary."
    }
}
