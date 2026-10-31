package app.pocketvideotoolkit

import android.content.Context
import android.content.Intent
import android.media.MediaCodecInfo
import android.net.Uri
import android.provider.OpenableColumns
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

private enum class ToolkitPage(val title: String) {
    PROJECT("Project"), EDIT("Edit"), CONVERT("Convert"), MEDIA("Media"), JOBS("Jobs")
}

private enum class MediaRoute { HUB, HDR_CONVERSION, PHOTO_VIEWER }

private data class VideoClip(val uri: Uri, val name: String)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun PocketToolkitPages(activity: ComponentActivity) {
    val prefs = remember { activity.getSharedPreferences("pocket_toolkit_settings", Context.MODE_PRIVATE) }
    var darkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", false)) }
    LaunchedEffect(darkTheme) { prefs.edit().putBoolean("dark_theme", darkTheme).apply() }

    var page by remember { mutableStateOf(ToolkitPage.PROJECT) }
    var mediaRoute by remember { mutableStateOf(MediaRoute.HUB) }
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
    var trimOn by remember { mutableStateOf(false) }
    var startSec by remember { mutableStateOf("0") }
    var endSec by remember { mutableStateOf("30") }
    var notice by remember { mutableStateOf("") }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val additions = uris.mapNotNull { uri -> runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else "Video"
            } ?: "Video"
            VideoClip(uri, name)
        }.getOrNull() }
        clips = (clips + additions).distinctBy { it.uri }
        clips.firstOrNull()?.let { videoInfo = VideoInspector.inspect(activity, it.uri) }
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
    val outputSize = if (customSize) (customWidth.toIntOrNull() ?: 1280) to (customHeight.toIntOrNull() ?: 720) else when (preset) {
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
                .putInt(ExportWorker.KEY_WIDTH, outputSize.first).putInt(ExportWorker.KEY_HEIGHT, outputSize.second)
                .putInt(ExportWorker.KEY_BITRATE, if (targetSizeOn) targetBitrate ?: 0 else (bitrate * 1_000_000).toInt())
                .putString(ExportWorker.KEY_CODEC, codec).putInt(ExportWorker.KEY_FPS, fps)
                .putInt(ExportWorker.KEY_BITRATE_MODE, when (rateControl) { "Variable" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR; "Constant" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR; else -> 0 })
                .putBoolean(ExportWorker.KEY_TRIM, trimOn && clips.size == 1).putString(ExportWorker.KEY_AUDIO, audio)
                .putLong(ExportWorker.KEY_START, (startSec.toLongOrNull() ?: 0) * 1000)
                .putLong(ExportWorker.KEY_END, (endSec.toLongOrNull() ?: Long.MAX_VALUE / 1000) * 1000).build()
            val request = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(data).addTag("pvt-export").build()
            workManager.enqueue(request)
            notice = "Export added. You can keep working in other pages or queue another job."
            page = ToolkitPage.JOBS
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) enqueue() else { notice = "Export queued. Android may hide its progress notification."; enqueue() }
    }
    val colors = if (darkTheme) darkColorScheme(primary = Color(0xFF8CE0B4), onPrimary = Color(0xFF10251A), secondary = Color(0xFF8CE0B4), background = Color(0xFF101714), surface = Color(0xFF1B2823))
        else lightColorScheme(primary = Color(0xFF17633F), secondary = Color(0xFF8CE0B4), background = Color(0xFFF3F6F2), surface = Color.White)

    val mediaTitle = when (mediaRoute) {
        MediaRoute.HUB -> "Media tools"
        MediaRoute.HDR_CONVERSION -> "HDR Conversion"
        MediaRoute.PHOTO_VIEWER -> "HDR Photo Viewer"
    }
    val nestedMediaPage = page == ToolkitPage.MEDIA && mediaRoute != MediaRoute.HUB
    fun navigateBack() {
        mediaRoute = MediaRoute.HUB
    }
    androidx.activity.compose.BackHandler(enabled = nestedMediaPage, onBack = ::navigateBack)

    MaterialTheme(colorScheme = colors) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { if (nestedMediaPage) IconButton(onClick = ::navigateBack) { Icon(Icons.Rounded.ArrowBack, "Back") } },
                    title = { Column { Text("Pocket Video", fontWeight = FontWeight.Bold); Text(if (page == ToolkitPage.MEDIA) mediaTitle else page.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    actions = { IconButton(onClick = { darkTheme = !darkTheme }) { Icon(if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, "Toggle dark theme") } }
                )
            },
            bottomBar = {
                NavigationBar {
                    ToolkitPage.entries.forEach { destination ->
                        val icon = when (destination) { ToolkitPage.PROJECT -> Icons.Rounded.FolderOpen; ToolkitPage.EDIT -> Icons.Rounded.Tune; ToolkitPage.CONVERT -> Icons.Rounded.Movie; ToolkitPage.MEDIA -> Icons.Rounded.Photo; ToolkitPage.JOBS -> Icons.Rounded.ListAlt }
                        val running = jobs.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                        NavigationBarItem(selected = page == destination, onClick = { page = destination; if (destination == ToolkitPage.MEDIA) mediaRoute = MediaRoute.HUB }, icon = { Icon(icon, null) }, label = { Text(if (destination == ToolkitPage.JOBS && running > 0) "Jobs · $running" else destination.title) })
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background
        ) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (page) {
                    ToolkitPage.PROJECT -> ProjectPage(clips, videoInfo, onAdd = { picker.launch(arrayOf("video/*")) }, onNew = { clips = emptyList(); videoInfo = ""; trimOn = false; startSec = "0"; endSec = "30" }, onEdit = { page = ToolkitPage.EDIT }, onConvert = { page = ToolkitPage.CONVERT })
                    ToolkitPage.EDIT -> EditPage(clips, onMove = { from, to -> clips = clips.toMutableList().also { it.add(to, it.removeAt(from)) } }, onRemove = { index ->
                        clips = clips.filterIndexed { i, _ -> i != index }; clips.firstOrNull()?.let { videoInfo = VideoInspector.inspect(activity, it.uri) } ?: run { videoInfo = "" }
                    }, onAdd = { picker.launch(arrayOf("video/*")) }, trimOn = trimOn, onTrimToggle = { trimOn = it }, start = startSec, onStart = { startSec = it.filter(Char::isDigit) }, end = endSec, onEnd = { endSec = it.filter(Char::isDigit) }, durationMs = durationMs)
                    ToolkitPage.CONVERT -> ConvertPage(preset, { preset = it }, customSize, { customSize = it }, customWidth, { customWidth = it.filter(Char::isDigit) }, customHeight, { customHeight = it.filter(Char::isDigit) }, quality, { quality = it }, bitrate, { bitrate = it; quality = "Custom" }, targetSizeOn, { targetSizeOn = it }, targetSize, { targetSize = it.filter { ch -> ch.isDigit() || ch == '.' } }, targetBitrate, fps, { fps = it }, codec, { codec = it }, availableCodecs, rateControl, { rateControl = it }, audio, { audio = it }, codecSupport, clips.isNotEmpty(), onQueue = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else enqueue()
                    })
                    ToolkitPage.MEDIA -> when (mediaRoute) {
                        MediaRoute.HUB -> MediaHubPage(onViewer = { mediaRoute = MediaRoute.PHOTO_VIEWER }, onConversion = { mediaRoute = MediaRoute.HDR_CONVERSION })
                        MediaRoute.HDR_CONVERSION -> HdrConversionPage(activity)
                        MediaRoute.PHOTO_VIEWER -> UltraHdrPhotoViewerPage(activity)
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
    SectionCard("Clip sequence", "Reorder clips to change the join order.") {
        Button(onClick = onAdd) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add clips") }
        if (clips.isEmpty()) Text("No clips yet. Add videos to start editing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    fps: Int, onFps: (Int) -> Unit, codec: String, onCodec: (String) -> Unit, codecOptions: List<String>, rateControl: String, onRateControl: (String) -> Unit, audio: String, onAudio: (String) -> Unit, codecSupport: List<Pair<String, Boolean>>, hasClips: Boolean, onQueue: () -> Unit
) {
    SectionCard("Resolution", "Choose an output size or enter dimensions.") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("240p", "360p", "480p", "720p", "1080p", "1440p", "4K").forEach { FilterChip(preset == it, { onPreset(it) }, label = { Text(it) }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Custom dimensions", Modifier.weight(1f)); Switch(customSize, onCustomSize) }
        if (customSize) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedTextField(width, onWidth, label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true); OutlinedTextField(height, onHeight, label = { Text("Height") }, modifier = Modifier.weight(1f), singleLine = true) }
    }
    SectionCard("Compression", "Balance image quality against file size.") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("High", "Balanced", "Small", "Custom").forEach { FilterChip(quality == it, { onQuality(it) }, label = { Text(it) }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text("Target file size", fontWeight = FontWeight.SemiBold); Text("Estimated from clip duration", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }; Switch(targetSizeOn, onTargetToggle, enabled = hasClips) }
        if (targetSizeOn) {
            OutlinedTextField(targetSize, onTargetSize, label = { Text("Target · MB") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Text(if (targetBitrate == null) "Select a clip with a readable duration and enter a size." else "Requests about ${String.format("%.1f", targetBitrate / 1_000_000f)} Mbps. Actual size depends on content and encoder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Video bitrate", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text(if (targetSizeOn) "Target driven" else "${String.format("%.1f", bitrate)} Mbps", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold) }
        Slider(bitrate, onBitrate, valueRange = .5f..40f, steps = 78, enabled = !targetSizeOn)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { SelectControl("Encoding", codec, codecOptions, onCodec, Modifier.weight(1f)); SelectControl("Audio", audio, listOf("AAC · 128 kbps", "AAC · 192 kbps", "AAC · 256 kbps", "Keep source"), onAudio, Modifier.weight(1f)) }
        SelectControl("Rate control", rateControl, listOf("Device default", "Variable", "Constant"), onRateControl)
        Text("Device encoders may adjust requested bitrate or reject a codec/profile.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SectionCard("Frame rate", "Caps output frame rate; it does not create new frames.") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(0 to "Source", 60 to "60 fps", 30 to "30 fps", 24 to "24 fps").forEach { (value, label) -> FilterChip(fps == value, { onFps(value) }, label = { Text(label) }) } }
    }
    Button(onClick = onQueue, enabled = hasClips && (!targetSizeOn || targetBitrate != null) && codecSupport.any { it.first == codec && it.second }, modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp)) { Icon(Icons.Rounded.Queue, null); Spacer(Modifier.width(8.dp)); Text("Add export to queue", fontWeight = FontWeight.Bold) }
    Text("Exports run in the background. You can queue more work and switch pages while they process.", modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable private fun MediaHubPage(onViewer: () -> Unit, onConversion: () -> Unit) {
    SectionCard("HDR media tools", "Open one tool at a time. Each workflow has only the controls for that task.") {
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
        active.forEachIndexed { index, work ->
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(if (work.state == WorkInfo.State.RUNNING) "Export ${index + 1} · processing" else "Export ${index + 1} · waiting", Modifier.weight(1f), fontWeight = FontWeight.SemiBold); if (work.state == WorkInfo.State.RUNNING) Text("${work.progress.getInt("progress", 0)}%", color = MaterialTheme.colorScheme.primary) }
                if (work.state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { work.progress.getInt("progress", 0) / 100f }, modifier = Modifier.fillMaxWidth()) else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { onCancel(work.id) }) { Text("Cancel this export") }
            }
        }
    }
    if (complete.isNotEmpty()) SectionCard("Recent exports", "") {
        complete.take(8).forEach { work ->
            val title = when (work.state) { WorkInfo.State.SUCCEEDED -> "Finished · saved to Movies / Pocket Video Toolkit"; WorkInfo.State.CANCELLED -> "Cancelled"; else -> "Failed · ${work.outputData.getString("error") ?: "Unsupported setting or source"}" }
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (work.state == WorkInfo.State.SUCCEEDED) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
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
