package app.pocketvideotoolkit

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HdrToolsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { HdrToolsScreen(this) }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun HdrToolsScreen(activity: HdrToolsActivity) {
    var tab by remember { mutableIntStateOf(activity.intent.getIntExtra("tab", 0)) }
    var video by remember { mutableStateOf<Uri?>(null) }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var holder by remember { mutableStateOf<SurfaceHolder?>(null) }
    var session by remember { mutableStateOf<HdrFrameCaptureSession?>(null) }
    var duration by remember { mutableLongStateOf(0L) }
    var position by remember { mutableFloatStateOf(0f) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { video = it }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { photo = it }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) scope.launch { session?.close() } }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); scope.launch { session?.close() } }
    }
    LaunchedEffect(video, holder) {
        val uri = video; val surface = holder
        if (uri != null && surface?.surface?.isValid == true && Build.VERSION.SDK_INT >= 34) {
            busy = true
            val active = HdrFrameCaptureSession(activity)
            session = active
            runCatching { withContext(Dispatchers.IO) { active.configure(uri, surface) } }
                .onSuccess { duration = it.durationMs; message = "${it.width} × ${it.height} · ${it.transfer} · Android 14+" }
                .onFailure { message = it.message ?: "Could not open this HDR video." }
            busy = false
        }
    }

    MaterialTheme {
        Scaffold(topBar = { TopAppBar(title = { Text("HDR tools") }, navigationIcon = { TextButton(onClick = { activity.finish() }) { Text("Back") } }) }) { padding ->
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                TabRow(selectedTabIndex = tab.coerceIn(0, 1)) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Video → photo") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Photo → video") })
                }
                Spacer(Modifier.height(16.dp))
                if (Build.VERSION.SDK_INT < 34) Text("These andAicaroid-based HDR conversions require Android 14 or newer.", color = MaterialTheme.colorScheme.error)
                if (tab == 0) {
                    Text("Save an HDR video frame as an Ultra HDR JPEG", style = MaterialTheme.typography.titleMedium)
                    Text("Requires BT.2020 HLG or PQ 10-bit HDR video. Uses the video decoder and HDR rendering path.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text(if (video == null) "Choose HDR video" else "Choose another video") }
                    if (video != null) {
                        AndroidView(factory = { context -> SurfaceView(context).also { view ->
                            view.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(h: SurfaceHolder) { holder = h }
                                override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hgt: Int) { holder = h }
                                override fun surfaceDestroyed(h: SurfaceHolder) { if (holder === h) holder = null }
                            })
                        } }, modifier = Modifier.fillMaxWidth().height(220.dp))
                    }
                    if (duration > 0) {
                        Text("Frame position · ${(position / 1000).toInt()} sec")
                        Slider(position, { position = it; scope.launch { runCatching { session?.preview(it.toLong()) } } }, valueRange = 0f..duration.toFloat())
                    }
                    Button(enabled = duration > 0 && !busy, onClick = { scope.launch {
                        busy = true
                        runCatching { session?.saveUltraHdrPhoto(position.toLong()) ?: error("HDR session is not ready") }
                            .onSuccess { message = "Ultra HDR photo saved to Pictures / Pocket Video Toolkit" }
                            .onFailure { message = it.message ?: "Could not save Ultra HDR photo." }
                        busy = false
                    } }) { Text("Save Ultra HDR photo") }
                } else {
                    Text("Turn an Ultra HDR photo into HDR video", style = MaterialTheme.typography.titleMedium)
                    Text("Creates a 3-second still-image video in HEVC Main10 HLG. Requires an Ultra HDR JPEG with a gain map and a compatible encoder.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { pickPhoto.launch(arrayOf("image/jpeg", "image/*")) }) { Text(if (photo == null) "Choose Ultra HDR photo" else "Choose another photo") }
                    Text(if (UltraHdrVideoExporter.hasMain10HevcEncoder()) "HEVC Main10 encoder detected" else "No HEVC Main10 encoder advertised on this device", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                    Button(enabled = photo != null && !busy && Build.VERSION.SDK_INT >= 34 && UltraHdrVideoExporter.hasMain10HevcEncoder(), onClick = { scope.launch {
                        busy = true
                        runCatching { UltraHdrVideoExporter.export(activity, photo!!) }
                            .onSuccess { message = "HDR video saved to Movies / Pocket Video Toolkit" }
                            .onFailure { message = it.message ?: "Could not create HDR video." }
                        busy = false
                    } }) { Text("Create HDR video") }
                }
                if (busy) { Spacer(Modifier.height(12.dp)); LinearProgressIndicator(Modifier.fillMaxWidth()) }
                if (message.isNotBlank()) Text(message, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
