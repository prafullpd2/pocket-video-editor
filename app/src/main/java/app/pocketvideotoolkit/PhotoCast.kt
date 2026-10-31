package app.pocketvideotoolkit

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.CastContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.Closeable
import java.io.IOException
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

class PhotoCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
        .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

internal class PhotoCastController(
    private val context: Context,
    private val report: (String) -> Unit,
    private val connectionChanged: (Boolean) -> Unit
) : Closeable {
    private val castContext = CastContext.getSharedInstance(context)
    private val sessionManager = castContext.sessionManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var photoUri: Uri? = null
    private var server: LocalPhotoHttpServer? = null
    private var castJob: Job? = null
    private var burnInProtectionJob: Job? = null

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarting(session: CastSession) = Unit
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            connectionChanged(true)
            castCurrentPhoto(session)
        }
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            report("Could not start the Cast session. Check that the TV and phone are on the same Wi-Fi network.")
        }
        override fun onSessionEnding(session: CastSession) = Unit
        override fun onSessionEnded(session: CastSession, error: Int) {
            closeServer()
            connectionChanged(false)
            report("Disconnected from the TV.")
        }
        override fun onSessionResuming(session: CastSession, sessionId: String) = Unit
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {
            connectionChanged(true)
        }
        override fun onSessionResumeFailed(session: CastSession, error: Int) {
            connectionChanged(false)
            report("Could not reconnect to the TV.")
        }
        override fun onSessionSuspended(session: CastSession, reason: Int) = Unit
    }

    init {
        sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
    }

    fun setPhoto(uri: Uri?) {
        if (photoUri != uri) closeServer()
        photoUri = uri
    }

    fun syncConnectionState() {
        connectionChanged(sessionManager.currentCastSession != null)
    }

    fun castCurrentPhotoNow() {
        sessionManager.currentCastSession?.let(::castCurrentPhoto)
            ?: report("Connect to a Cast TV first.")
    }

    private fun castCurrentPhoto(session: CastSession) {
        val uri = photoUri ?: return
        castJob?.cancel()
        burnInProtectionJob?.cancel()
        castJob = scope.launch {
            val mime = context.contentResolver.getType(uri)?.lowercase()?.substringBefore(';')
            val supportedTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp", "image/apng")
            if (mime !in supportedTypes) {
                report("This TV receiver does not support this photo format. JPEG, PNG, WebP, GIF, and BMP are supported.")
                return@launch
            }
            report("Sending photo to the TV…")
            try {
                closeServer()
                val photoServer = withContext(Dispatchers.IO) {
                    LocalPhotoHttpServer(context, uri).also { it.start() }
                }
                if (photoUri != uri || sessionManager.currentCastSession !== session) {
                    photoServer.close()
                    return@launch
                }
                server = photoServer
                val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_PHOTO).apply {
                    putString(MediaMetadata.KEY_TITLE, "Pocket Video Toolkit photo")
                }
                val mediaInfo = MediaInfo.Builder(photoServer.photoUrl)
                    .setStreamType(MediaInfo.STREAM_TYPE_NONE)
                    .setContentType(mime!!)
                    .setMetadata(metadata)
                    .build()
                val remoteClient = session.remoteMediaClient
                    ?: throw IOException("The TV did not provide a photo receiver.")
                remoteClient.load(MediaLoadRequestData.Builder().setMediaInfo(mediaInfo).build())
                    .setResultCallback { result ->
                        if (result.status.isSuccess) {
                            report("Photo sent to the TV. Ultra HDR brightness depends on the TV receiver. It will stop after 10 minutes to protect the display.")
                            burnInProtectionJob = scope.launch {
                                kotlinx.coroutines.delay(10 * 60 * 1000L)
                                if (photoUri == uri && sessionManager.currentCastSession === session) {
                                    session.remoteMediaClient?.stop()
                                    closeServer()
                                    report("Photo sharing stopped to protect the TV display. Cast again to view it.")
                                }
                            }
                        }
                        else report(result.status.statusMessage ?: "The TV could not load this photo.")
                    }
            } catch (error: Exception) {
                closeServer()
                report(error.message ?: "Could not send the photo to the TV.")
            }
        }
    }

    private fun closeServer() {
        server?.close()
        server = null
    }

    override fun close() {
        sessionManager.removeSessionManagerListener(sessionListener, CastSession::class.java)
        castJob?.cancel()
        burnInProtectionJob?.cancel()
        scope.cancel()
        closeServer()
    }
}

private class LocalPhotoHttpServer(context: Context, private val uri: Uri) : Closeable {
    private val resolver = context.contentResolver
    private val token = UUID.randomUUID().toString()
    private val socket = ServerSocket(0, 4, InetAddress.getByName("0.0.0.0"))
    @Volatile private var active = true
    val photoUrl: String

    init {
        val localAddress = findLocalAddress() ?: run {
            socket.close()
            throw IOException("Could not find this phone's Wi-Fi address. Connect it to the same Wi-Fi as the TV.")
        }
        photoUrl = "http://$localAddress:${socket.localPort}/$token"
    }

    fun start() {
        thread(name = "PocketPhotoCastServer", isDaemon = true) {
            while (active) {
                val client = try { socket.accept() } catch (_: IOException) {
                    if (active) close()
                    continue
                }
                try { client.use(::serveRequest) } catch (_: IOException) { /* Receiver may retry a failed request. */ }
            }
        }
    }

    private fun serveRequest(client: Socket) {
        client.soTimeout = 8_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
        val request = reader.readLine()?.split(' ') ?: return
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
        }
        val method = request.getOrNull(0)
        val path = request.getOrNull(1)?.substringBefore('?')
        val output = BufferedOutputStream(client.getOutputStream())
        if ((method != "GET" && method != "HEAD") || path != "/$token") {
            output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            output.flush()
            return
        }
        val mime = resolver.getType(uri)?.substringBefore(';') ?: "image/jpeg"
        val length = contentLength()
        val headers = buildString {
            append("HTTP/1.1 200 OK\r\nContent-Type: $mime\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\n")
            if (length >= 0) append("Content-Length: $length\r\n") else append("Transfer-Encoding: chunked\r\n")
            append("\r\n")
        }
        output.write(headers.toByteArray(StandardCharsets.US_ASCII))
        if (method == "HEAD") {
            output.flush()
            return
        }
        val input = resolver.openInputStream(uri) ?: throw IOException("Could not open the selected photo.")
        input.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (length >= 0) output.write(buffer, 0, count)
                else {
                    output.write("${count.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
                    output.write(buffer, 0, count)
                    output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                }
            }
        }
        if (length < 0) output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun contentLength(): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it >= 0 } else null
        } ?: -1L
    }.getOrDefault(-1L)

    private fun findLocalAddress(): String? = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { Collections.list(it.inetAddresses) }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }.getOrNull()

    override fun close() {
        active = false
        runCatching { socket.close() }
    }
}
