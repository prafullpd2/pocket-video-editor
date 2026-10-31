package app.pocketvideotoolkit

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Close
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
import androidx.work.ExistingWorkPolicy
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import android.media.MediaCodecInfo

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PocketToolkit(this) }
    }
}

@Composable
private fun PocketToolkit(activity: ComponentActivity) {
    val preferences = remember { activity.getSharedPreferences("pocket_toolkit_settings", android.content.Context.MODE_PRIVATE) }
    var darkTheme by remember { mutableStateOf(preferences.getBoolean("dark_theme", false)) }
    val ink = if (darkTheme) Color(0xFFE7F0EA) else Color(0xFF101A19)
    val accent = if (darkTheme) Color(0xFF8CE0B4) else Color(0xFF24714B)
    val muted = if (darkTheme) Color(0xFFACB9B1) else Color(0xFF77817D)
    val mutedStrong = if (darkTheme) Color(0xFFC5D0C9) else Color(0xFF53605A)
    val label = if (darkTheme) Color(0xFFA8B6AE) else Color(0xFF72807A)
    val surface = if (darkTheme) Color(0xFF1B2823) else Color.White
    val softAccent = if (darkTheme) Color(0xFF263A30) else Color(0xFFE9F7EE)
    val canvas = if (darkTheme) Color(0xFF101714) else Color(0xFFF3F6F2)
    val brandSurface = if (darkTheme) Color(0xFF8CE0B4) else Color(0xFF101A19)
    val brandContent = if (darkTheme) Color(0xFF101A19) else Color.White
    val hdrSurface = if (darkTheme) Color(0xFF17231F) else Color(0xFF101A19)
    val hdrContent = if (darkTheme) Color(0xFFE7F0EA) else Color.White
    LaunchedEffect(darkTheme) { preferences.edit().putBoolean("dark_theme", darkTheme).apply() }
    var sources by remember { mutableStateOf(listOf<Pair<Uri, String>>()) }
    val selected = sources.firstOrNull()?.first
    val selectedName = sources.firstOrNull()?.second.orEmpty()
    var preset by remember { mutableStateOf("720p") }
    var codec by remember { mutableStateOf("H.264") }
    var audio by remember { mutableStateOf("AAC · 192 kbps") }
    var bitrate by remember { mutableFloatStateOf(8f) }
    var qualityPreset by remember { mutableStateOf("Balanced") }
    var targetSizeEnabled by remember { mutableStateOf(false) }
    var targetSizeMb by remember { mutableStateOf("25") }
    var sourceDurationMs by remember { mutableLongStateOf(0L) }
    var frameRate by remember { mutableIntStateOf(0) }
    var bitrateMode by remember { mutableStateOf("Device default") }
    var customSize by remember { mutableStateOf(false) }
    var customWidth by remember { mutableStateOf("1280") }
    var customHeight by remember { mutableStateOf("720") }
    var trimEnabled by remember { mutableStateOf(false) }
    var startSec by remember { mutableStateOf("0") }
    var endSec by remember { mutableStateOf("30") }
    var info by remember { mutableStateOf("") }
    var imageUri by remember { mutableStateOf<Uri?>(null) }
    var photoPreview by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var notificationNotice by remember { mutableStateOf("") }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val additions = uris.mapNotNull { uri -> runCatching {
            activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val name = activity.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (i >= 0 && c.moveToFirst()) c.getString(i) else "Selected video"
            } ?: "Selected video"
            uri to name
        }.getOrNull() }
        sources = (sources + additions).distinctBy { it.first }
        sourceDurationMs = sources.sumOf { VideoInspector.durationMs(activity, it.first) }
        sources.firstOrNull()?.let { info = VideoInspector.inspect(activity, it.first) }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        imageUri = uri
        photoPreview = uri?.let {
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, it)) { decoder, imageInfo, _ ->
                    val size = imageInfo.size
                    val scale = minOf(1f, 1400f / maxOf(size.width, size.height))
                    decoder.setTargetSize((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
                }
                else activity.contentResolver.openInputStream(it)?.use { stream -> BitmapFactory.decodeStream(stream) }
            }.getOrNull()
        }
    }
    val wm = remember { WorkManager.getInstance(activity) }
    val workInfos by wm.getWorkInfosForUniqueWorkFlow("pvt-export").collectAsStateWithLifecycle(initialValue = emptyList())
    val active = workInfos.firstOrNull { !it.state.isFinished }
    val finished = workInfos.firstOrNull { it.state == WorkInfo.State.SUCCEEDED || it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED }
    val progress = active?.progress?.getInt("progress", 0) ?: 0
    val snackbar = remember { SnackbarHostState() }
    val codecAvailability = remember { listOf("H.264" to CodecSupport.hasVideoEncoder(androidx.media3.common.MimeTypes.VIDEO_H264), "H.265" to CodecSupport.hasVideoEncoder(androidx.media3.common.MimeTypes.VIDEO_H265), "AV1" to CodecSupport.hasVideoEncoder(androidx.media3.common.MimeTypes.VIDEO_AV1)) }
    val codecSummary = "Advertised encoders: ${codecAvailability.filter { it.second }.joinToString(" · ") { it.first }.ifEmpty { "none" }}. Actual support depends on the selected size and profile."
    val codecOptions = codecAvailability.filter { it.second }.map { it.first }.ifEmpty { listOf("H.264") }
    val outputSize = if (customSize) (customWidth.toIntOrNull() ?: 1280) to (customHeight.toIntOrNull() ?: 720) else when (preset) { "240p" -> 426 to 240; "360p" -> 640 to 360; "480p" -> 854 to 480; "720p" -> 1280 to 720; "1080p" -> 1920 to 1080; "1440p" -> 2560 to 1440; else -> 3840 to 2160 }
    val requestedAudioRate = when (audio) { "AAC · 128 kbps" -> 128_000; "AAC · 256 kbps" -> 256_000; "Keep source" -> 192_000; else -> 192_000 }
    val effectiveDurationMs = if (trimEnabled && sources.size == 1) {
        val start = (startSec.toLongOrNull() ?: 0L) * 1000L
        val requestedEnd = (endSec.toLongOrNull() ?: Long.MAX_VALUE / 1000L) * 1000L
        val end = if (sourceDurationMs > 0) requestedEnd.coerceAtMost(sourceDurationMs) else requestedEnd
        (end - start).coerceAtLeast(0L)
    } else sourceDurationMs
    val targetBitrate = targetSizeMb.toFloatOrNull()?.let { targetMb ->
        if (effectiveDurationMs <= 0L || targetMb <= 0f) null
        else ((targetMb * 8_000_000f / (effectiveDurationMs / 1000f)) - requestedAudioRate).toInt().coerceIn(250_000, 40_000_000)
    }
    LaunchedEffect(qualityPreset, outputSize, frameRate, codec) {
        if (qualityPreset != "Custom") {
            val factor = when (qualityPreset) { "High" -> 1.0f; "Small" -> 0.42f; else -> 0.65f }
            val codecFactor = when (codec) { "H.265" -> 0.67f; "AV1" -> 0.55f; else -> 1f }
            val fpsFactor = if (frameRate == 0) 1f else frameRate / 30f
            val pixelFactor = outputSize.first.toFloat() * outputSize.second / (1920f * 1080f)
            bitrate = (10f * factor * codecFactor * fpsFactor * pixelFactor).coerceIn(0.5f, 40f)
        }
    }
    val enqueueExport = {
        val uri = selected
        if (uri != null) {
            val size = outputSize
            val data = Data.Builder()
                .putString(ExportWorker.KEY_URI, uri.toString())
                .putStringArray(ExportWorker.KEY_URIS, sources.map { it.first.toString() }.toTypedArray())
                .putInt(ExportWorker.KEY_WIDTH, size.first)
                .putInt(ExportWorker.KEY_HEIGHT, size.second)
                .putInt(ExportWorker.KEY_BITRATE, if (targetSizeEnabled) targetBitrate ?: (bitrate * 1_000_000).toInt() else (bitrate * 1_000_000).toInt())
                .putString(ExportWorker.KEY_CODEC, codec)
                .putInt(ExportWorker.KEY_FPS, frameRate)
                .putInt(ExportWorker.KEY_BITRATE_MODE, when (bitrateMode) { "Variable" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR; "Constant" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR; else -> 0 })
                .putBoolean(ExportWorker.KEY_TRIM, trimEnabled && sources.size == 1)
                .putString(ExportWorker.KEY_AUDIO, audio)
                .putLong(ExportWorker.KEY_START, startSec.toLongOrNull()?.times(1000) ?: 0L)
                .putLong(ExportWorker.KEY_END, endSec.toLongOrNull()?.times(1000) ?: Long.MAX_VALUE)
                .build()
            val req = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(data).build()
            wm.enqueueUniqueWork("pvt-export", ExistingWorkPolicy.REPLACE, req)
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) enqueueExport() else notificationNotice = "Export will continue, but Android may hide its progress notification."
    }

    val colorScheme = if (darkTheme) darkColorScheme(primary = Color(0xFF8CE0B4), onPrimary = Color(0xFF10251A), secondary = Color(0xFF8CE0B4), background = canvas, surface = surface, onSurface = ink, onBackground = ink) else lightColorScheme(primary = Color(0xFF101A19), secondary = Color(0xFF8CE0B4), background = canvas, surface = surface)
    MaterialTheme(colorScheme = colorScheme) {
        Scaffold(containerColor = canvas, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
            Column(Modifier.fillMaxSize().padding(insets).padding(horizontal = 20.dp).verticalScroll(rememberScrollState())) {
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(42.dp).background(brandSurface, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Movie, null, tint = if (darkTheme) ink else Color(0xFF8CE0B4))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("POCKET VIDEO", fontSize = 11.sp, letterSpacing = 1.4.sp, color = muted, fontWeight = FontWeight.Bold)
                        Text("Toolkit", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = ink)
                    }
                    Text("LOCAL", modifier = Modifier.background(softAccent, RoundedCornerShape(30.dp)).padding(horizontal = 12.dp, vertical = 7.dp), color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dark theme", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = ink)
                        Text("Use a darker interface", fontSize = 12.sp, color = muted)
                    }
                    Switch(checked = darkTheme, onCheckedChange = { darkTheme = it })
                }
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureTile("Convert", "Resolution & bitrate", Icons.Rounded.Bolt, Modifier.weight(1f))
                    FeatureTile("Edit", "Trim & join", Icons.Rounded.Tune, Modifier.weight(1f))
                    FeatureTile("HDR", "Inspect & preserve", Icons.Rounded.Photo, Modifier.weight(1f))
                }
                Spacer(Modifier.height(20.dp))
                Text("START A PROJECT", fontSize = 11.sp, letterSpacing = 1.25.sp, color = label, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).background(softAccent, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Movie, null, tint = accent) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if (selected == null) "Choose video clips" else if (sources.size == 1) selectedName else "${sources.size} clips · ordered join", fontWeight = FontWeight.SemiBold, color = ink, maxLines = 1)
                                Text(if (selected == null) "Import one or more from this device" else info.ifBlank { "Reading video details…" }, fontSize = 12.sp, color = muted, maxLines = 2)
                            }
                            IconButton(onClick = { pickVideo.launch(arrayOf("video/*")) }) { Icon(Icons.Rounded.Add, "Choose video clips") }
                        }
                        sources.forEachIndexed { index, source ->
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("${index + 1}", color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                Text(source.second, color = mutedStrong, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f).padding(horizontal = 8.dp))
                                IconButton(onClick = { if (index > 0) sources = sources.toMutableList().also { it.add(index - 1, it.removeAt(index)) } }, enabled = index > 0) { Icon(Icons.Rounded.ArrowUpward, "Move clip earlier", modifier = Modifier.size(17.dp)) }
                                IconButton(onClick = { if (index < sources.lastIndex) sources = sources.toMutableList().also { it.add(index + 1, it.removeAt(index)) } }, enabled = index < sources.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Move clip later", modifier = Modifier.size(17.dp)) }
                                IconButton(onClick = { sources = sources.filterIndexed { i, _ -> i != index }; sourceDurationMs = sources.sumOf { VideoInspector.durationMs(activity, it.first) }; sources.firstOrNull()?.let { info = VideoInspector.inspect(activity, it.first) } ?: run { info = "" } }) { Icon(Icons.Rounded.Close, "Remove clip", modifier = Modifier.size(17.dp)) }
                            }
                        }
                        if (sources.size > 1) Text("Clips join in this order. Matching codecs and consistent HDR/SDR formats give the most reliable results.", color = muted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 4.dp))
                        if (selected != null && info.isNotBlank()) {
                            Spacer(Modifier.height(12.dp))
                            Text(info, color = mutedStrong, fontSize = 12.sp, lineHeight = 17.sp)
                        }
                    }
                }
                Spacer(Modifier.height(15.dp))
                Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text("Export settings", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = ink); Spacer(Modifier.weight(1f)); Icon(Icons.Rounded.ExpandMore, null, tint = muted) }
                        Spacer(Modifier.height(14.dp))
                        Text("OUTPUT SIZE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color(0xFF78837F))
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("240p", "360p", "480p", "720p", "1080p", "1440p", "4K").forEach { label ->
                                FilterChip(selected = preset == label, onClick = { preset = label }, label = { Text(label) }, shape = RoundedCornerShape(12.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Custom dimensions", fontSize = 13.sp, color = ink, modifier = Modifier.weight(1f))
                            Switch(checked = customSize, onCheckedChange = { customSize = it })
                        }
                        if (customSize) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(customWidth, { customWidth = it.filter(Char::isDigit) }, label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(customHeight, { customHeight = it.filter(Char::isDigit) }, label = { Text("Height") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("COMPRESSION PRESET", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = muted)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("High", "Balanced", "Small", "Custom").forEach { option ->
                                FilterChip(selected = qualityPreset == option, onClick = { qualityPreset = option }, label = { Text(option) }, shape = RoundedCornerShape(12.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Target file size", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ink)
                                Text("Estimate bitrate from clip length", fontSize = 11.sp, color = muted)
                            }
                            Switch(checked = targetSizeEnabled, onCheckedChange = { targetSizeEnabled = it }, enabled = selected != null)
                        }
                        if (targetSizeEnabled) {
                            OutlinedTextField(
                                value = targetSizeMb,
                                onValueChange = { targetSizeMb = it.filter { ch -> ch.isDigit() || ch == '.' }.take(7) },
                                label = { Text("Target · MB") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                when {
                                    sources.isEmpty() -> "Choose a video to estimate its output bitrate."
                                    effectiveDurationMs <= 0L -> "Could not read clip duration; turn this off and set bitrate manually."
                                    targetBitrate == null -> "Enter a target size greater than zero."
                                    else -> "Requested video bitrate ≈ ${String.format("%.1f", targetBitrate / 1_000_000f)} Mbps. Actual file size can vary by encoder and content."
                                },
                                color = muted,
                                fontSize = 11.sp,
                                lineHeight = 15.sp,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Video bitrate", fontWeight = FontWeight.SemiBold, color = ink)
                            Spacer(Modifier.weight(1f))
                            Text(if (targetSizeEnabled) "Target-driven" else "${String.format("%.1f", bitrate)} Mbps", color = accent, fontWeight = FontWeight.Bold)
                        }
                        Slider(value = bitrate, onValueChange = { bitrate = it; qualityPreset = "Custom" }, valueRange = 0.5f..40f, steps = 78, enabled = !targetSizeEnabled, colors = SliderDefaults.colors(thumbColor = accent, activeTrackColor = if (darkTheme) Color(0xFF69C994) else Color(0xFF4CB77D)))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SettingSelect("Encoding", codec, codecOptions, { codec = it }, Modifier.weight(1f))
                            SettingSelect("Audio", audio, listOf("AAC · 128 kbps", "AAC · 192 kbps", "AAC · 256 kbps", "Keep source"), { audio = it }, Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        SettingSelect("Rate control", bitrateMode, listOf("Device default", "Variable", "Constant"), { bitrateMode = it })
                        Text(codecSummary, color = muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("FRAME RATE", fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = muted)
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(0 to "Source", 60 to "60 fps", 30 to "30 fps", 24 to "24 fps").forEach { (fps, fpsLabel) ->
                                FilterChip(selected = frameRate == fps, onClick = { frameRate = fps }, label = { Text(fpsLabel) }, shape = RoundedCornerShape(12.dp))
                            }
                        }
                        Text("Frame rate is capped at the selected value; frames are not added when the source is slower.", color = muted, fontSize = 10.sp, lineHeight = 14.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("Trim clip", fontWeight = FontWeight.SemiBold, color = ink); Text("Set a start and end point", fontSize = 12.sp, color = muted) }
                            Switch(checked = trimEnabled, onCheckedChange = { trimEnabled = it }, enabled = sources.size <= 1)
                        }
                        if (trimEnabled && sources.size <= 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(startSec, { startSec = it.filter(Char::isDigit) }, label = { Text("Start · seconds") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(endSec, { endSec = it.filter(Char::isDigit) }, label = { Text("End · seconds") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                    }
                }
                Spacer(Modifier.height(15.dp))
                Card(colors = CardDefaults.cardColors(containerColor = hdrSurface), shape = RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) { Text("HDR & photos", color = hdrContent, fontWeight = FontWeight.Bold, fontSize = 17.sp); Text("View a still image or inspect HDR support", color = if (darkTheme) muted else Color(0xFFB5C3BC), fontSize = 12.sp) }
                            Icon(Icons.Rounded.Photo, null, tint = if (darkTheme) ink else Color(0xFF8CE0B4))
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { pickPhoto.launch(arrayOf("image/*")) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = hdrContent)) { Text("Open photo") }
                            OutlinedButton(onClick = { activity.startActivity(Intent(activity, HdrToolsActivity::class.java).putExtra("tab", 0)) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = hdrContent)) { Text("Video → Ultra HDR") }
                            OutlinedButton(onClick = { activity.startActivity(Intent(activity, HdrToolsActivity::class.java).putExtra("tab", 1)) }, colors = ButtonDefaults.outlinedButtonColors(contentColor = hdrContent)) { Text("Photo → HDR video") }
                        }
                        if (imageUri != null) Text("Photo selected", color = if (darkTheme) ink else Color(0xFF8CE0B4), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("HDR video editing/export uses device codecs. Media3 may preserve HDR or fall back; dynamic metadata and mixed HDR/SDR behavior vary by device.", color = if (darkTheme) muted else Color(0xFFB5C3BC), fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
                if (active != null) {
                    Spacer(Modifier.height(14.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Row { Text("Exporting…", fontWeight = FontWeight.Bold, color = ink); Spacer(Modifier.weight(1f)); Text("$progress%", color = accent, fontWeight = FontWeight.Bold) }
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp))
                            TextButton(onClick = { wm.cancelUniqueWork("pvt-export") }) { Text("Cancel export") }
                        }
                    }
                } else if (finished != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(when (finished.state) { WorkInfo.State.SUCCEEDED -> "Export saved to Movies / Pocket Video Toolkit"; WorkInfo.State.CANCELLED -> "Export cancelled"; else -> finished.outputData.getString("error") ?: "Export failed. Try a lower resolution or H.264." }, color = if (finished.state == WorkInfo.State.SUCCEEDED) accent else Color(0xFFEF8F83), fontSize = 13.sp)
                }
                if (notificationNotice.isNotBlank()) Text(notificationNotice, color = Color(0xFF9B5A20), fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        } else enqueueExport()
                    }, enabled = selected != null && active == null && codecAvailability.any { it.first == codec && it.second } && (!targetSizeEnabled || targetBitrate != null),
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(17.dp), colors = ButtonDefaults.buttonColors(containerColor = brandSurface, contentColor = brandContent, disabledContainerColor = if (darkTheme) Color(0xFF35443C) else Color(0xFF9AA39F))
                ) { Text(if (active != null) "EXPORTING" else "Export video", color = brandContent, fontWeight = FontWeight.Bold, fontSize = 16.sp) }
                Text("Exports run on this device and can continue with the screen locked.", modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 9.dp, bottom = 22.dp), color = muted, fontSize = 11.sp)
            }
        }
        if (photoPreview != null) AlertDialog(
            onDismissRequest = { photoPreview = null },
            confirmButton = { TextButton(onClick = { photoPreview = null }) { Text("Done") } },
            title = { Text("Photo preview") },
            text = { Image(bitmap = photoPreview!!.asImageBitmap(), contentDescription = "Selected photo", modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp)) }
        )
    }
}

@Composable private fun FeatureTile(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(12.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(21.dp)); Spacer(Modifier.height(9.dp))
        Text(title, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface); Text(subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 13.sp)
    }
}

@Composable private fun SettingSelect(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) { Text(label.uppercase(), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(selected, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1) }
            Icon(Icons.Rounded.ExpandMore, null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { options.forEach { DropdownMenuItem(text = { Text(it) }, onClick = { onSelect(it); open = false }) } }
    }
}
