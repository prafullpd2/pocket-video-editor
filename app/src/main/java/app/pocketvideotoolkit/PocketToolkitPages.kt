package app.pocketvideotoolkit

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaCodecInfo
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class ToolkitPage(val title: String) {
    PROJECT("Project"), EDIT("Edit"), CONVERT("Convert"), MEDIA("Media"), JOBS("Jobs")
}

private enum class MediaRoute { HUB, HDR_CONVERSION, PHOTO_VIEWER, CAMERA }

private data class VideoClip(val uri: Uri, val name: String)

private fun incomingMediaUris(intent: Intent): List<Uri> {
    val uris = buildList {
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let(::add)
        if (intent.action == Intent.ACTION_SEND) {
            incomingSingleStream(intent)?.let(::add)
        }
        if (intent.action == Intent.ACTION_SEND_MULTIPLE) addAll(incomingMultipleStreams(intent))
        intent.clipData?.let { clip ->
            for (index in 0 until clip.itemCount) clip.getItemAt(index).uri?.let(::add)
        }
    }
    return uris.distinct()
}

@Suppress("DEPRECATION")
private fun incomingSingleStream(intent: Intent): Uri? = if (android.os.Build.VERSION.SDK_INT >= 33) {
    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
} else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri

@Suppress("DEPRECATION")
private fun incomingMultipleStreams(intent: Intent): List<Uri> = if (android.os.Build.VERSION.SDK_INT >= 33) {
    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
} else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PocketToolkitPages(activity: ComponentActivity, externalOpenIntent: Intent? = null) {
    val prefs = remember { activity.getSharedPreferences("pocket_toolkit_settings", Context.MODE_PRIVATE) }
    var darkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", false)) }
    LaunchedEffect(darkTheme) { prefs.edit().putBoolean("dark_theme", darkTheme).apply() }

    var page by remember { mutableStateOf(ToolkitPage.PROJECT) }
    var mediaRoute by remember { mutableStateOf(MediaRoute.HUB) }
    var photoFullScreen by rememberSaveable { mutableStateOf(false) }
    var clips by remember { mutableStateOf(emptyList<VideoClip>()) }
    var videoInfo by remember { mutableStateOf("") }
    var preset by remember { mutableStateOf("720p") }
    var customSize by remember { mutableStateOf(false) }
    var customWidth by remember { mutableStateOf("1280") }
    var customHeight by remember { mutableStateOf("720") }
    var quality by remember { mutableStateOf("Balanced") }
    var bitrate by remember { mutableFloatStateOf(8f) }
    var targetSizeOn by remember { mutableStateOf(false) }
    var targetSize by remember { mutableStateOf("25") }
    var fps by remember { mutableIntStateOf(0) }
    var codec by remember { mutableStateOf("H.264") }
    var rateControl by remember { mutableStateOf("Device default") }
    var audio by remember { mutableStateOf("AAC · 192 kbps") }
    var copySourceStreams by remember { mutableStateOf(false) }
    var exportFileName by remember { mutableStateOf("") }
    var trimOn by remember { mutableStateOf(false) }
    var startSec by remember { mutableStateOf("0") }
    var endSec by remember { mutableStateOf("30") }
    var notice by remember { mutableStateOf("") }
    var photoViewerInitialUri by remember { mutableStateOf<Uri?>(null) }

    val picker = rememberMediaSourcePicker(arrayOf("video/*"), allowMultiple = true) { uris ->
        val additions = uris.mapNotNull { uri -> runCatching {
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else "Video"
            } ?: "Video"
            VideoClip(uri, name)
        }.getOrNull() }
        clips = (clips + additions).distinctBy { it.uri }
        clips.firstOrNull()?.let { videoInfo = VideoInspector.inspect(activity, it.uri) }
    }
    LaunchedEffect(clips.firstOrNull()?.uri) {
        clips.firstOrNull()?.let { clip ->
            exportFileName = clip.name.substringBeforeLast('.', clip.name).takeIf(String::isNotBlank)?.let { "${it}_export" } ?: "PocketVideo_export"
        }
    }
    LaunchedEffect(externalOpenIntent) {
        val incomingUris = externalOpenIntent?.let(::incomingMediaUris).orEmpty()
        if (incomingUris.isEmpty()) return@LaunchedEffect
        val items = incomingUris.map { uri ->
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val mime = runCatching { activity.contentResolver.getType(uri) }.getOrNull() ?: externalOpenIntent?.type.orEmpty()
            val name = runCatching {
                activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
                }
            }.getOrNull() ?: (uri.lastPathSegment ?: "Media")
            Triple(uri, mime, name)
        }
        val incomingVideos = items.filter { it.second.startsWith("video/") }
        val incomingPhotos = items.filter { it.second.startsWith("image/") }
        when {
            incomingVideos.isNotEmpty() -> {
                clips = incomingVideos.map { VideoClip(it.first, it.third) }
                videoInfo = clips.firstOrNull()?.let { VideoInspector.inspect(activity, it.uri) }.orEmpty()
                trimOn = false
                startSec = "0"
                endSec = "30"
                page = ToolkitPage.PROJECT
                mediaRoute = MediaRoute.HUB
                photoFullScreen = false
            }
            incomingPhotos.isNotEmpty() -> {
                photoViewerInitialUri = incomingPhotos.first().first
                page = ToolkitPage.MEDIA
                mediaRoute = MediaRoute.PHOTO_VIEWER
                photoFullScreen = false
            }
        }
    }

    val workManager = remember { WorkManager.getInstance(activity) }
    val jobs by workManager.getWorkInfosByTagFlow("pvt-export").collectAsStateWithLifecycle(initialValue = emptyList())
    val codecSupport = remember {
        listOf(
            "H.264" to CodecSupport.hasVideoEncoder(androidx.media3.common.MimeTypes.VIDEO_H264),
            "H.265" to CodecSupport.hasVideoEncoder(androidx.media3.common.MimeTypes.VIDEO_H265),
            "AV1" to CodecSupport.hasVideoEncoder(androidx.media3.common.MimeTypes.VIDEO_AV1)
        )
    }
    val availableCodecs = codecSupport.filter { it.second }.map { it.first }.ifEmpty { listOf("H.264") }
    val sourceDimensions = remember(videoInfo) {
        Regex("(\\d+)×(\\d+)").find(videoInfo)?.let { match ->
            (match.groupValues[1].toIntOrNull() ?: 0) to (match.groupValues[2].toIntOrNull() ?: 0)
        }?.takeIf { it.first > 0 && it.second > 0 }
    }
    val outputSize = if (preset == "Source" && sourceDimensions != null) sourceDimensions else if (customSize) (customWidth.toIntOrNull() ?: 1280) to (customHeight.toIntOrNull() ?: 720) else when (preset) {
        "240p" -> 426 to 240; "360p" -> 640 to 360; "480p" -> 854 to 480; "1080p" -> 1920 to 1080; "1440p" -> 2560 to 1440; "4K" -> 3840 to 2160; else -> 1280 to 720
    }
    val durationMs = remember(clips) { clips.sumOf { VideoInspector.durationMs(activity, it.uri) } }
    val trimmedDuration = if (trimOn && clips.size == 1) {
        val start = (startSec.toLongOrNull() ?: 0L) * 1000
        val end = ((endSec.toLongOrNull() ?: 0L) * 1000).let { if (durationMs > 0) it.coerceAtMost(durationMs) else it }
        (end - start).coerceAtLeast(0L)
    } else durationMs
    val audioRate = when (audio) { "AAC · 128 kbps" -> 128_000; "AAC · 256 kbps" -> 256_000; else -> 192_000 }
    val targetBitrate = targetSize.toFloatOrNull()?.takeIf { it > 0 && trimmedDuration > 0 }?.let {
        ((it * 8_000_000f / (trimmedDuration / 1000f)) - audioRate).toInt().coerceIn(250_000, 40_000_000)
    }
    val sourceSizeBytes = remember(clips) {
        clips.sumOf { clip ->
            runCatching {
                activity.contentResolver.query(clip.uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (column >= 0 && cursor.moveToFirst()) cursor.getLong(column).coerceAtLeast(0L) else 0L
                } ?: 0L
            }.getOrDefault(0L)
        }
    }
    val estimatedOutputBytes = when {
        clips.isEmpty() -> null
        copySourceStreams -> sourceSizeBytes.takeIf { it > 0L }
        targetSizeOn -> targetSize.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 1_000_000).toLong() }
        trimmedDuration > 0L -> ((bitrate * 1_000_000f + audioRate) * trimmedDuration / 8_000f).toLong().coerceAtLeast(0L)
        else -> null
    }
    val estimateNote = when {
        copySourceStreams -> "Source size · no re-encoding"
        targetSizeOn -> "Requested target · actual size may vary"
        else -> "Approx. from duration and selected bitrates"
    }
    LaunchedEffect(quality, outputSize, fps, codec) {
        if (quality != "Custom") {
            val q = when (quality) { "High" -> 1f; "Small" -> .42f; else -> .65f }
            val c = when (codec) { "H.265" -> .67f; "AV1" -> .55f; else -> 1f }
            val f = if (fps == 0) 1f else fps / 30f
            bitrate = (10f * q * c * f * outputSize.first * outputSize.second / (1920f * 1080f)).coerceIn(.5f, 40f)
        }
    }

    val enqueue = {
        if (clips.isNotEmpty()) {
            val data = Data.Builder()
                .putString(ExportWorker.KEY_URI, clips.first().uri.toString())
                .putStringArray(ExportWorker.KEY_URIS, clips.map { it.uri.toString() }.toTypedArray())
                .putString(ExportWorker.KEY_OUTPUT_NAME, exportFileName)
                .putInt(ExportWorker.KEY_WIDTH, outputSize.first).putInt(ExportWorker.KEY_HEIGHT, outputSize.second)
                .putInt(ExportWorker.KEY_BITRATE, if (targetSizeOn) targetBitrate ?: 0 else (bitrate * 1_000_000).toInt())
                .putString(ExportWorker.KEY_CODEC, codec).putInt(ExportWorker.KEY_FPS, fps)
                .putInt(ExportWorker.KEY_BITRATE_MODE, when (rateControl) { "Variable" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR; "Constant" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR; else -> 0 })
                .putBoolean(ExportWorker.KEY_TRIM, trimOn && clips.size == 1).putString(ExportWorker.KEY_AUDIO, audio)
                .putLong(ExportWorker.KEY_START, (startSec.toLongOrNull() ?: 0) * 1000)
                .putLong(ExportWorker.KEY_END, (endSec.toLongOrNull() ?: Long.MAX_VALUE / 1000) * 1000)
                .putBoolean(ExportWorker.KEY_COPY_SOURCE_STREAMS, copySourceStreams && !trimOn).build()
            val request = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(data)
                .addTag("pvt-export")
                .addTag("pvt-output-name:${Uri.encode(exportFileName)}")
                .addTag("pvt-source-uri:${Uri.encode(clips.first().uri.toString())}")
                .build()
            workManager.enqueue(request)
            notice = "Export added. You can keep working in other pages or queue another job."
            page = ToolkitPage.JOBS
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) enqueue() else { notice = "Export queued. Android may hide its progress notification."; enqueue() }
    }
    val colors = if (darkTheme) darkColorScheme(primary = Color(0xFFB2C5B8), onPrimary = Color(0xFF202A24), secondary = Color(0xFFBBC4B8), background = Color(0xFF191C1A), surface = Color(0xFF242825), surfaceVariant = Color(0xFF303632))
        else lightColorScheme(primary = Color(0xFF53685C), onPrimary = Color.White, secondary = Color(0xFF78877B), background = Color(0xFFF1F1EC), surface = Color(0xFFFAFAF6), surfaceVariant = Color(0xFFE5E7E1))

    val mediaTitle = when (mediaRoute) {
        MediaRoute.HUB -> "Media tools"
        MediaRoute.HDR_CONVERSION -> "HDR Conversion"
        MediaRoute.PHOTO_VIEWER -> "HDR Photo Viewer"
        MediaRoute.CAMERA -> "Ultra HDR Camera"
    }
    val nestedMediaPage = page == ToolkitPage.MEDIA && mediaRoute != MediaRoute.HUB
    val cameraFullScreen = page == ToolkitPage.MEDIA && mediaRoute == MediaRoute.CAMERA
    fun navigateBack() {
        mediaRoute = MediaRoute.HUB
    }
    androidx.activity.compose.BackHandler(enabled = nestedMediaPage, onBack = ::navigateBack)
    androidx.activity.compose.BackHandler(enabled = photoFullScreen, onBack = { photoFullScreen = false })

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            contentWindowInsets = if (photoFullScreen || cameraFullScreen) WindowInsets(0.dp, 0.dp, 0.dp, 0.dp) else ScaffoldDefaults.contentWindowInsets,
            topBar = {
                if (!photoFullScreen && !cameraFullScreen) TopAppBar(
                    navigationIcon = { if (nestedMediaPage) IconButton(onClick = ::navigateBack) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                    title = { Column { Text("Pocket Video", fontWeight = FontWeight.Bold); Text(if (page == ToolkitPage.MEDIA) mediaTitle else page.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    actions = { IconButton(onClick = { darkTheme = !darkTheme }) { Icon(if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, "Toggle dark theme") } }
                )
            },
            bottomBar = {
                if (!photoFullScreen && !cameraFullScreen) NavigationBar {
                    ToolkitPage.entries.forEach { destination ->
                        val icon = when (destination) { ToolkitPage.PROJECT -> Icons.Rounded.FolderOpen; ToolkitPage.EDIT -> Icons.Rounded.Tune; ToolkitPage.CONVERT -> Icons.Rounded.Movie; ToolkitPage.MEDIA -> Icons.Rounded.Photo; ToolkitPage.JOBS -> Icons.Rounded.ListAlt }
                        val running = jobs.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                        NavigationBarItem(selected = page == destination, onClick = { page = destination; if (destination == ToolkitPage.MEDIA) mediaRoute = MediaRoute.HUB }, icon = { Icon(icon, null) }, label = { Text(if (destination == ToolkitPage.JOBS && running > 0) "Jobs · $running" else destination.title) })
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { insets ->
            val photoViewerPage = page == ToolkitPage.MEDIA && mediaRoute == MediaRoute.PHOTO_VIEWER && !photoFullScreen
            val bodyModifier = when {
                photoFullScreen || cameraFullScreen -> Modifier.fillMaxSize()
                photoViewerPage -> Modifier.fillMaxSize().padding(insets).padding(horizontal = 12.dp, vertical = 8.dp)
                else -> Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp)
            }
            Column(bodyModifier, verticalArrangement = if (photoFullScreen || cameraFullScreen) Arrangement.Center else Arrangement.spacedBy(14.dp)) {
                when (page) {
                    ToolkitPage.PROJECT -> ProjectPage(clips, videoInfo, onAdd = picker, onNew = { clips = emptyList(); videoInfo = ""; trimOn = false; startSec = "0"; endSec = "30" }, onEdit = { page = ToolkitPage.EDIT }, onConvert = { page = ToolkitPage.CONVERT })
                    ToolkitPage.EDIT -> EditPage(clips, onMove = { from, to -> clips = clips.toMutableList().also { it.add(to, it.removeAt(from)) } }, onRemove = { index ->
                        clips = clips.filterIndexed { i, _ -> i != index }; clips.firstOrNull()?.let { videoInfo = VideoInspector.inspect(activity, it.uri) } ?: run { videoInfo = "" }
                    }, onAdd = picker, trimOn = trimOn, onTrimToggle = { trimOn = it; if (it) copySourceStreams = false }, start = startSec, onStart = { startSec = it.filter(Char::isDigit) }, end = endSec, onEnd = { endSec = it.filter(Char::isDigit) }, durationMs = durationMs)
                    ToolkitPage.CONVERT -> ConvertPage(preset, { preset = it; customSize = false }, customSize, { customSize = it }, customWidth, { customWidth = it.filter(Char::isDigit) }, customHeight, { customHeight = it.filter(Char::isDigit) }, quality, { quality = it }, bitrate, { bitrate = it; quality = "Custom" }, targetSizeOn, { targetSizeOn = it }, targetSize, { targetSize = it.filter { ch -> ch.isDigit() || ch == '.' } }, targetBitrate, fps, { fps = it }, codec, { codec = it }, availableCodecs, rateControl, { rateControl = it }, audio, { audio = it }, codecSupport, clips.isNotEmpty(), sourceDimensions, copySourceStreams, { copySourceStreams = it }, trimOn, exportFileName, { exportFileName = it }, estimatedOutputBytes?.let(::formatFileSize), estimateNote, onQueue = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else enqueue()
                    })
                    ToolkitPage.MEDIA -> when (mediaRoute) {
                        MediaRoute.HUB -> MediaHubPage(onViewer = { mediaRoute = MediaRoute.PHOTO_VIEWER }, onConversion = { mediaRoute = MediaRoute.HDR_CONVERSION }, onCamera = { mediaRoute = MediaRoute.CAMERA })
                        MediaRoute.HDR_CONVERSION -> HdrConversionPage(activity)
                        MediaRoute.PHOTO_VIEWER -> UltraHdrPhotoViewerPage(activity, initialUri = photoViewerInitialUri, fullScreen = photoFullScreen, onFullScreenChange = { photoFullScreen = it })
                        MediaRoute.CAMERA -> UltraHdrCameraPage(activity, onExit = { mediaRoute = MediaRoute.HUB })
                    }
                    ToolkitPage.JOBS -> JobsPage(jobs, onCancel = { id -> workManager.cancelWorkById(id) }, notice = notice)
                }
            }
        }
    }
}

@Composable private fun ProjectPage(clips: List<VideoClip>, info: String, onAdd: () -> Unit, onNew: () -> Unit, onEdit: () -> Unit, onConvert: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Start a project", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Choose videos, arrange your edit, then set up an export.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text(if (clips.isEmpty()) "Choose video clips" else "Add more clips") }
        if (clips.isNotEmpty()) {
            Text(if (clips.size == 1) clips.first().name else "${clips.size} clips in this project", fontWeight = FontWeight.SemiBold)
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit clips") }; Button(onClick = onConvert, modifier = Modifier.weight(1f)) { Text("Set up export") } }
            OutlinedButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) { Text("Start a new project") }
        }
    } }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Work in parallel", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Queue an export, then keep editing or prepare another project. Background jobs remain visible in Jobs and can continue when the screen is locked.", style = MaterialTheme.typography.bodyMedium)
    } }
}

@Composable private fun EditPage(clips: List<VideoClip>, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onAdd: () -> Unit, trimOn: Boolean, onTrimToggle: (Boolean) -> Unit, start: String, onStart: (String) -> Unit, end: String, onEnd: (String) -> Unit, durationMs: Long) {
    SectionCard("Clip sequence", "Add multiple videos and drag them into the order you want; export joins them end to end.") {
        Button(onClick = onAdd) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add clips") }
        if (clips.isEmpty()) Text("No clips yet. Add videos to start editing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (clips.size > 1) Text("Re-encoding a joined HDR video may keep HDR10/HLG but loses HDR10+ dynamic metadata. For a compatible HEVC join without re-encoding, choose Copy source streams on the Convert page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        clips.forEachIndexed { index, clip ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(clip.name, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1)
                IconButton(onClick = { if (index > 0) onMove(index, index - 1) }, enabled = index > 0) { Icon(Icons.Rounded.KeyboardArrowUp, "Move earlier") }
                IconButton(onClick = { if (index < clips.lastIndex) onMove(index, index + 1) }, enabled = index < clips.lastIndex) { Icon(Icons.Rounded.KeyboardArrowDown, "Move later") }
                IconButton(onClick = { onRemove(index) }) { Icon(Icons.Rounded.Close, "Remove clip") }
            }
        }
        if (clips.size > 1) Text("Clips will be joined in this order. Consistent format and HDR/SDR sources are more reliable.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard("Trim", "Set an in and out point for a single clip.") {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Trim this clip", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Switch(trimOn, onTrimToggle, enabled = clips.size == 1) }
        if (trimOn && clips.size == 1) {
            Text("Clip length: ${durationMs / 1000}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(start, onStart, label = { Text("Start · seconds") }, modifier = Modifier.weight(1f), singleLine = true)
                OutlinedTextField(end, onEnd, label = { Text("End · seconds") }, modifier = Modifier.weight(1f), singleLine = true)
            }
        }
        if (clips.size > 1) Text("Choose one clip to enable trimming.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard("Editing tools", "") { Text("Trim and ordered joining are available now. Split, transitions, and timeline effects are not part of this build yet.", style = MaterialTheme.typography.bodyMedium) }
}

@Composable private fun ConvertPage(
    preset: String, onPreset: (String) -> Unit, customSize: Boolean, onCustomSize: (Boolean) -> Unit, width: String, onWidth: (String) -> Unit, height: String, onHeight: (String) -> Unit,
    quality: String, onQuality: (String) -> Unit, bitrate: Float, onBitrate: (Float) -> Unit, targetSizeOn: Boolean, onTargetToggle: (Boolean) -> Unit, targetSize: String, onTargetSize: (String) -> Unit, targetBitrate: Int?,
    fps: Int, onFps: (Int) -> Unit, codec: String, onCodec: (String) -> Unit, codecOptions: List<String>, rateControl: String, onRateControl: (String) -> Unit, audio: String, onAudio: (String) -> Unit, codecSupport: List<Pair<String, Boolean>>, hasClips: Boolean, sourceDimensions: Pair<Int, Int>?, copySourceStreams: Boolean, onCopySourceStreams: (Boolean) -> Unit, trimOn: Boolean, outputFileName: String, onOutputFileName: (String) -> Unit, estimatedSize: String?, estimateNote: String, onQueue: () -> Unit
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AspectRatio, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Output size", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    CompactInfo("Resolution", "Choose the output frame size. Source keeps the first clip's dimensions; custom lets you enter exact dimensions.")
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    FilterChip(preset == "Source" && !customSize, { onPreset("Source") }, enabled = sourceDimensions != null, label = { Text("Source") })
                    listOf("240p", "360p", "480p", "720p", "1080p", "1440p", "4K").forEach { FilterChip(preset == it && !customSize, { onPreset(it) }, label = { Text(it) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sourceDimensions?.let { "${it.first} × ${it.second} source" } ?: "Select a video to see source size", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Custom", style = MaterialTheme.typography.labelMedium)
                    Switch(customSize, onCustomSize, modifier = Modifier.padding(start = 6.dp))
                }
                if (customSize) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(width, onWidth, label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(height, onHeight, label = { Text("Height") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        }

        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Compression", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    CompactInfo("Compression", "High keeps more detail and creates larger files. Small reduces file size at the cost of detail. Balanced is a middle setting.")
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("High", "Balanced", "Small", "Custom").forEach { FilterChip(quality == it, { onQuality(it) }, label = { Text(it) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Target file size", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        if (targetSizeOn && targetBitrate != null) Text("Video rate about ${String.format("%.1f", targetBitrate / 1_000_000f)} Mbps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(targetSizeOn, onTargetToggle, enabled = hasClips)
                }
                if (targetSizeOn) OutlinedTextField(
                    value = targetSize,
                    onValueChange = onTargetSize,
                    label = { Text("Target size · MB") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .6f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Storage, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Estimated file size", style = MaterialTheme.typography.labelMedium)
                            Text(estimateNote, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text(estimatedSize ?: "—", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp)) {
            Column {
                TextButton(onClick = { advancedExpanded = !advancedExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (advancedExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (advancedExpanded) "Hide advanced settings" else "Advanced settings", Modifier.weight(1f))
                    Text("${codec} · ${fps.takeIf { it > 0 }?.let { "$it fps" } ?: "Source fps"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (advancedExpanded && !copySourceStreams) Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Video bitrate · ${String.format("%.1f", bitrate)} Mbps", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        CompactInfo("Video bitrate", "Higher bitrate keeps more detail and usually creates larger files. The encoder may adjust the requested rate.")
                    }
                    Slider(bitrate, { onBitrate(it); onQuality("Custom") }, valueRange = .5f..40f, steps = 78, enabled = !targetSizeOn, modifier = Modifier.height(26.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactSelectControl(
                            "Video encoding", codec, codecOptions, onCodec,
                            when (codec) {
                                "H.265" -> "Smaller files; older players may not support it."
                                "AV1" -> "Efficient, but slower and less widely supported."
                                else -> "Most compatible; may create larger files."
                            }, Modifier.weight(1f)
                        )
                        CompactSelectControl(
                            "Audio", audio, listOf("AAC · 128 kbps", "AAC · 192 kbps", "AAC · 256 kbps", "Keep source"), onAudio,
                            when (audio) {
                                "Keep source" -> "Keep the original audio track."
                                "AAC · 128 kbps" -> "Smaller audio files."
                                "AAC · 256 kbps" -> "Higher audio quality; larger file."
                                else -> "Balanced audio quality and size."
                            }, Modifier.weight(1f)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("HDR · HDR10/HLG when supported", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        CompactInfo("HDR export", "Re-encoding can retain HDR10 or HLG when the source and encoder support it. It does not preserve HDR10+ dynamic metadata. Use Copy source streams to keep the original encoded video and in-band HDR10+ metadata.")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactSelectControl("Frame rate", when (fps) { 60 -> "60 fps"; 30 -> "30 fps"; 24 -> "24 fps"; else -> "Source" }, listOf("Source", "60 fps", "30 fps", "24 fps"), { value -> onFps(value.substringBefore(' ').toIntOrNull() ?: 0) }, "Source keeps the clip's original pace.", Modifier.weight(1f))
                        CompactSelectControl("Rate control", rateControl, listOf("Device default", "Variable", "Constant"), onRateControl, when (rateControl) { "Variable" -> "Adapts rate to scene detail."; "Constant" -> "Steadier data rate."; else -> "Let the encoder choose." }, Modifier.weight(1f))
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ContentCopy, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Copy source streams", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text("Keep original video and HDR10+ metadata", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CompactInfo("Copy source streams", if (trimOn) "Turn off trimming to use stream copy." else "Copies without re-encoding. Resolution, bitrate, frame rate, codec and audio settings do not apply. Joining clips requires matching HEVC video and audio formats.")
                Switch(copySourceStreams, onCopySourceStreams, enabled = hasClips && !trimOn, modifier = Modifier.padding(start = 4.dp))
            }
        }
        if (copySourceStreams) Text("Stream copy keeps the original encoding. Output size shows the total source file size.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        OutlinedTextField(
            value = outputFileName,
            onValueChange = onOutputFileName,
            label = { Text("Save as") },
            supportingText = { Text("Saved in Movies / Pocket Video Toolkit as an MP4 video.") },
            singleLine = true,
            enabled = hasClips,
            modifier = Modifier.fillMaxWidth()
        )

        Button(
            onClick = onQueue,
            enabled = hasClips && (!targetSizeOn || targetBitrate != null) && (copySourceStreams || codecSupport.any { it.first == codec && it.second }),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(if (copySourceStreams) Icons.Rounded.ContentCopy else Icons.Rounded.Queue, null)
            Spacer(Modifier.width(8.dp))
            Text(if (copySourceStreams) "Copy to queue" else "Add export to queue", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun CompactInfo(title: String, message: String) {
    var showInfo by remember { mutableStateOf(false) }
    IconButton(onClick = { showInfo = true }, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Rounded.Info, "About $title", modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showInfo) AlertDialog(
        onDismissRequest = { showInfo = false },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { showInfo = false }) { Text("Got it") } }
    )
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> "${String.format("%.1f", bytes / 1_000_000_000.0)} GB"
    bytes >= 1_000_000L -> "${String.format("%.0f", bytes / 1_000_000.0)} MB"
    else -> "${String.format("%.0f", bytes / 1_000.0)} KB"
}

@Composable private fun MediaHubPage(onViewer: () -> Unit, onConversion: () -> Unit, onCamera: () -> Unit) {
    SectionCard("HDR media tools", "Open one tool at a time. Each workflow has only the controls for that task.") {
        Button(onClick = onCamera, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.CameraAlt, null); Spacer(Modifier.width(8.dp)); Text("Ultra HDR Camera") }
        Button(onClick = onConversion, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.HdrOn, null); Spacer(Modifier.width(8.dp)); Text("HDR Conversion") }
        OutlinedButton(onClick = onViewer, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Photo, null); Spacer(Modifier.width(8.dp)); Text("HDR Photo Viewer") }
    }
}

@Composable private fun HdrConversionPage(activity: ComponentActivity) {
    var direction by remember { mutableStateOf("Video → Photo") }
    SectionCard("HDR Conversion", "Choose a direction. Its controls appear here without leaving this page.") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = direction == "Video → Photo", onClick = { direction = "Video → Photo" }, label = { Text("Video → Photo") })
            FilterChip(selected = direction == "Photo → Video", onClick = { direction = "Photo → Video" }, label = { Text("Photo → Video") })
        }
        Text("Requires Android 14+ and compatible source formats and hardware codecs.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (direction == "Video → Photo") VideoToUltraHdrPage(activity) else UltraHdrToVideoPage(activity)
}

@Composable private fun JobsPage(jobs: List<WorkInfo>, onCancel: (UUID) -> Unit, notice: String) {
    val active = jobs.filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
    val complete = jobs.filter { it.state.isFinished }.sortedByDescending { it.id.toString() }
    SectionCard("Export queue", if (active.isEmpty()) "No exports are running." else "${active.size} export${if (active.size == 1) "" else "s"} queued or processing.") {
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
        if (jobs.isEmpty()) Text("Exports you add from Convert will appear here. You can continue editing while they run.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        active.forEach { work -> ExportJobRow(work, onCancel) }
    }
    if (complete.isNotEmpty()) SectionCard("Recent exports", "") {
        complete.take(20).forEach { work -> ExportJobRow(work, onCancel) }
    }
}

@Composable private fun ExportJobRow(work: WorkInfo, onCancel: (UUID) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val outputUri = work.outputData.getString("saved_uri")
    val sourceUri = work.outputData.getString("source_uri")
        ?: work.tags.firstOrNull { it.startsWith("pvt-source-uri:") }?.removePrefix("pvt-source-uri:")?.let(Uri::decode)
    val previewUri = outputUri ?: sourceUri
    val thumbnail by produceState<Bitmap?>(null, work.id, previewUri) {
        value = withContext(Dispatchers.IO) {
            val uri = previewUri?.let(Uri::parse) ?: return@withContext null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                val frame = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                if (frame != null && (frame.width > 360 || frame.height > 240)) {
                    val ratio = minOf(360f / frame.width, 240f / frame.height)
                    Bitmap.createScaledBitmap(frame, (frame.width * ratio).toInt().coerceAtLeast(1), (frame.height * ratio).toInt().coerceAtLeast(1), true).also { if (it !== frame) frame.recycle() }
                } else frame
            } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
        }
    }
    val outputName = (work.outputData.getString("output_name")
        ?: work.tags.firstOrNull { it.startsWith("pvt-output-name:") }?.removePrefix("pvt-output-name:")?.let(Uri::decode)).orEmpty()
        .ifBlank { "Video export" }.substringBeforeLast('.')
    val status = when (work.state) {
        WorkInfo.State.RUNNING -> "Saving · ${work.progress.getInt("progress", 0)}%"
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting in queue"
        WorkInfo.State.SUCCEEDED -> "Saved to Movies / Pocket Video Toolkit"
        WorkInfo.State.CANCELLED -> "Cancelled"
        else -> "Failed · ${work.outputData.getString("error") ?: "Unsupported format or encoder"}"
    }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f))) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Surface(
                onClick = {
                    val uri = previewUri?.let(Uri::parse)
                    if (uri == null) Toast.makeText(context, "Video is not available to play yet.", Toast.LENGTH_SHORT).show()
                    else runCatching {
                        val viewIntent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(viewIntent, "Play video with"))
                    }.onFailure { Toast.makeText(context, "No video player is available for this file.", Toast.LENGTH_SHORT).show() }
                },
                modifier = Modifier.size(width = 88.dp, height = 62.dp),
                shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surface
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (thumbnail != null) Image(thumbnail!!.asImageBitmap(), "Play $outputName with another video app", contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.primary)
                    Surface(Modifier.align(Alignment.BottomEnd).padding(3.dp), shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.scrim.copy(alpha = .62f)) {
                        Icon(Icons.Rounded.PlayArrow, "Play video", tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(19.dp).padding(2.dp))
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("$outputName.mp4", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(status, style = MaterialTheme.typography.labelSmall, color = if (work.state == WorkInfo.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                if (work.state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { work.progress.getInt("progress", 0).coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
            }
            if (!work.state.isFinished) IconButton(onClick = { onCancel(work.id) }) { Icon(Icons.Rounded.Close, "Cancel export") }
        }
    }
}

@Composable internal fun SectionCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable private fun SelectControl(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(label.uppercase(), fontSize = 9.sp); Text(selected, fontSize = 12.sp, maxLines = 1) }; Icon(Icons.Rounded.ExpandMore, null) }
        DropdownMenu(expanded, { expanded = false }) { options.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onSelect(it); expanded = false }) } }
    }
}

@Composable private fun CompactSelectControl(
    label: String,
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    helper: String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        Surface(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(selected, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Icon(Icons.Rounded.ExpandMore, null, modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option, style = MaterialTheme.typography.bodyMedium) }, onClick = { onSelect(option); expanded = false }) }
        }
        if (helper.isNotBlank()) Text(helper, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
