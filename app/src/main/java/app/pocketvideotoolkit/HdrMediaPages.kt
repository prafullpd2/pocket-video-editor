package app.pocketvideotoolkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@androidx.media3.common.util.UnstableApi
internal fun VideoToUltraHdrPage(activity: ComponentActivity) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    var holder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var session by remember { mutableStateOf<HdrFrameCaptureSession?>(null) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        uri = selected?.also { runCatching { activity.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        duration = 0L
        position = 0f
        message = ""
    }

    DisposableEffect(session) {
        val oldSession = session
        onDispose { oldSession?.let { current -> scope.launch { current.close() } } }
    }
    LaunchedEffect(uri, holder) {
        val selectedUri = uri
        val surfaceHolder = holder
        if (selectedUri != null && surfaceHolder?.surface?.isValid == true) {
            if (Build.VERSION.SDK_INT < 34) { message = "HDR frame conversion requires Android 14 or newer."; return@LaunchedEffect }
            busy = true
            val fresh = HdrFrameCaptureSession(activity)
            session = fresh
            runCatching { withContext(Dispatchers.IO) { fresh.configure(selectedUri, surfaceHolder) } }
                .onSuccess { video -> duration = video.durationMs; message = "${video.width} × ${video.height} · ${video.transfer} · Choose a frame to save." }
                .onFailure { message = it.message ?: "Could not open this HDR video." }
            busy = false
        }
    }
    LaunchedEffect(position, duration, session) {
        if (duration > 0 && session != null && Build.VERSION.SDK_INT >= 34) {
            delay(160)
            runCatching { session?.preview(position.toLong()) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("HDR video → Ultra HDR photo", "Pick one HLG or PQ video, seek to a frame, and save that frame as an Ultra HDR JPEG.") {
            if (Build.VERSION.SDK_INT < 34) Text("Requires Android 14 or newer.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text(if (uri == null) "Choose HDR video" else "Choose another video") }
            if (uri != null) {
                AndroidView(
                    factory = { context -> SurfaceView(context).also { view ->
                        view.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(surface: SurfaceHolder) { holder = surface }
                            override fun surfaceChanged(surface: SurfaceHolder, format: Int, width: Int, height: Int) { holder = surface }
                            override fun surfaceDestroyed(surface: SurfaceHolder) { if (holder === surface) holder = null }
                        })
                    } },
                    modifier = Modifier.fillMaxWidth().height(230.dp)
                )
            }
            if (duration > 0) {
                Text("Frame position · ${(position / 1000).toInt()} sec", fontWeight = FontWeight.SemiBold)
                Slider(value = position, onValueChange = { position = it }, valueRange = 0f..duration.toFloat())
                Text("Move the playhead to choose which frame becomes the photo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(enabled = duration > 0 && !busy, onClick = { scope.launch {
                busy = true
                runCatching { session?.saveUltraHdrPhoto(position.toLong()) ?: error("HDR video is not ready") }
                    .onSuccess { message = "Ultra HDR photo saved to Pictures / Pocket Video Toolkit." }
                    .onFailure { message = it.message ?: "Could not save the Ultra HDR photo." }
                busy = false
            } }, modifier = Modifier.fillMaxWidth()) { Text("Save frame as Ultra HDR photo") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun UltraHdrToVideoPage(activity: ComponentActivity) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var isUltraHdr by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        uri = selected?.also { runCatching { activity.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        preview = selected?.let { decodeImage(activity, it, 1200) }
        isUltraHdr = if (Build.VERSION.SDK_INT >= 34) preview?.hasGainmap() == true else false
        message = if (selected == null) "" else if (isUltraHdr) "Ultra HDR gain map detected." else "This image does not appear to contain an Ultra HDR gain map."
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("Ultra HDR photo → HDR video", "Create a short still-image video in HEVC Main10 HLG from an Ultra HDR JPEG.") {
            if (Build.VERSION.SDK_INT < 34) Text("Requires Android 14 or newer.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }) { Text(if (uri == null) "Choose Ultra HDR photo" else "Choose another photo") }
            preview?.let { Image(it.asImageBitmap(), "Selected Ultra HDR photo", Modifier.fillMaxWidth().heightIn(max = 340.dp)) }
            Text(if (UltraHdrVideoExporter.hasMain10HevcEncoder()) "HEVC Main10 encoder available" else "This device does not advertise an HEVC Main10 encoder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Output is a 3-second video of the selected still image. It requires an Ultra HDR gain map and an HEVC Main10 encoder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(enabled = uri != null && isUltraHdr && !busy && Build.VERSION.SDK_INT >= 34 && UltraHdrVideoExporter.hasMain10HevcEncoder(), onClick = { scope.launch {
                busy = true
                runCatching { UltraHdrVideoExporter.export(activity, uri!!) }
                    .onSuccess { message = "HDR video saved to Movies / Pocket Video Toolkit." }
                    .onFailure { message = it.message ?: "Could not create HDR video." }
                busy = false
            } }, modifier = Modifier.fillMaxWidth()) { Text("Create HDR video") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun UltraHdrPhotoViewerPage(activity: ComponentActivity) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { selected ->
        uri = selected?.also { runCatching { activity.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        bitmap = selected?.let { decodeImage(activity, it, 1800) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("HDR Photo Viewer", "View Ultra HDR and other images separately from conversion tools.") {
            Button(onClick = { picker.launch(arrayOf("image/*")) }) { Text(if (uri == null) "Open photo" else "Open another photo") }
            bitmap?.let { Image(it.asImageBitmap(), "HDR photo", Modifier.fillMaxWidth().heightIn(max = 600.dp)) }
            if (bitmap == null) Text("Choose an image to preview it.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Ultra HDR brightness depends on the Android version, display, and image format.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun decodeImage(activity: ComponentActivity, uri: Uri, maxDimension: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri)) { decoder, details, _ ->
        val size = details.size
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(size.width, size.height))
        decoder.setTargetSize((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
    } else activity.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
}.getOrNull()
