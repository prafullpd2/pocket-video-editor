@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package app.pocketvideotoolkit

import android.content.Context
import android.content.ContentValues
import android.content.ContentUris
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaCodecInfo
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.RangeSlider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
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
    PROJECT("Project"), EDIT("Edit"), CONVERT("Export"), MEDIA("Media"), JOBS("Exports")
}

private enum class MediaRoute { HUB, HDR_CONVERSION, PHOTO_VIEWER, CAMERA }

private data class VideoClip(val uri: Uri, val name: String, val durationMs: Long = 0L, val trimStartMs: Long = 0L, val trimEndMs: Long = durationMs)
private data class ProjectExportSettings(
    val preset: String = "720p", val socialProfile: String = "General", val customSize: Boolean = false,
    val customWidth: String = "1280", val customHeight: String = "720", val quality: String = "Balanced",
    val bitrate: Float = 8f, val targetSizeOn: Boolean = false, val targetSize: String = "25", val fps: Int = 0,
    val codec: String = "H.264", val rateControl: String = "Device default", val audio: String = "AAC · 192 kbps",
    val copySourceStreams: Boolean = false, val hdrExportMode: String = ExportWorker.HDR_MODE_KEEP,
    val batchExport: Boolean = false, val brightness: Float = 0f, val contrast: Float = 0f,
    val saturation: Float = 0f, val rotation: Float = 0f, val cropToFill: Boolean = false,
    val outputFileName: String = "PocketVideo_export"
)
private data class VideoProject(val id: String, val name: String, val clips: List<VideoClip> = emptyList(), val info: String = "", val settings: ProjectExportSettings = ProjectExportSettings())

private fun loadProjects(prefs: android.content.SharedPreferences): List<VideoProject> = runCatching {
    val root = org.json.JSONArray(prefs.getString("projects", "[]"))
    buildList {
        for (i in 0 until root.length()) {
            val project = root.getJSONObject(i)
            val clipArray = project.optJSONArray("clips") ?: org.json.JSONArray()
            val clips = buildList {
                for (j in 0 until clipArray.length()) {
                    val c = clipArray.getJSONObject(j)
                    val duration = c.optLong("duration", 0L)
                    add(VideoClip(Uri.parse(c.getString("uri")), c.optString("name", "Video"), duration, c.optLong("start", 0L), c.optLong("end", duration)))
                }
            }
            val s = project.optJSONObject("settings") ?: org.json.JSONObject()
            val settings = ProjectExportSettings(
                preset = s.optString("preset", "720p"), socialProfile = s.optString("profile", "General"),
                customSize = s.optBoolean("custom_size", false), customWidth = s.optString("width", "1280"), customHeight = s.optString("height", "720"),
                quality = s.optString("quality", "Balanced"), bitrate = s.optDouble("bitrate", 8.0).toFloat(), targetSizeOn = s.optBoolean("target_on", false),
                targetSize = s.optString("target_size", "25"), fps = s.optInt("fps", 0), codec = s.optString("codec", "H.264"),
                rateControl = s.optString("rate_control", "Device default"), audio = s.optString("audio", "AAC · 192 kbps"),
                copySourceStreams = s.optBoolean("copy_streams", false), hdrExportMode = s.optString("hdr_mode", ExportWorker.HDR_MODE_KEEP),
                batchExport = s.optBoolean("batch", false), brightness = s.optDouble("brightness", 0.0).toFloat(), contrast = s.optDouble("contrast", 0.0).toFloat(),
                saturation = s.optDouble("saturation", 0.0).toFloat(), rotation = s.optDouble("rotation", 0.0).toFloat(), cropToFill = s.optBoolean("crop", false),
                outputFileName = s.optString("output_name", "PocketVideo_export")
            )
            add(VideoProject(project.optString("id", UUID.randomUUID().toString()), project.optString("name", "Project ${i + 1}"), clips, project.optString("info", ""), settings))
        }
    }
}.getOrDefault(emptyList())

private fun saveProjects(prefs: android.content.SharedPreferences, projects: List<VideoProject>) {
    val root = org.json.JSONArray()
    projects.forEach { project ->
        val clipArray = org.json.JSONArray()
        project.clips.forEach { clip -> clipArray.put(org.json.JSONObject().put("uri", clip.uri.toString()).put("name", clip.name).put("duration", clip.durationMs).put("start", clip.trimStartMs).put("end", clip.trimEndMs)) }
        val s = project.settings
        val settings = org.json.JSONObject()
            .put("preset", s.preset).put("profile", s.socialProfile).put("custom_size", s.customSize).put("width", s.customWidth).put("height", s.customHeight)
            .put("quality", s.quality).put("bitrate", s.bitrate.toDouble()).put("target_on", s.targetSizeOn).put("target_size", s.targetSize).put("fps", s.fps)
            .put("codec", s.codec).put("rate_control", s.rateControl).put("audio", s.audio).put("copy_streams", s.copySourceStreams).put("hdr_mode", s.hdrExportMode)
            .put("batch", s.batchExport).put("brightness", s.brightness.toDouble()).put("contrast", s.contrast.toDouble()).put("saturation", s.saturation.toDouble())
            .put("rotation", s.rotation.toDouble()).put("crop", s.cropToFill).put("output_name", s.outputFileName)
        root.put(org.json.JSONObject().put("id", project.id).put("name", project.name).put("info", project.info).put("clips", clipArray).put("settings", settings))
    }
    prefs.edit().putString("projects", root.toString()).apply()
}

private fun formatMediaTime(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    else String.format(java.util.Locale.ROOT, "%d:%02d", minutes, seconds)
}

private fun defaultExportName(fileName: String): String = fileName.substringBeforeLast('.', fileName)
    .takeIf(String::isNotBlank)?.let { "${it}_export" } ?: "PocketVideo_export"

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
    val initialProjects = remember { loadProjects(prefs).ifEmpty { listOf(VideoProject(UUID.randomUUID().toString(), "Project 1")) } }
    var darkTheme by remember { mutableStateOf(prefs.getBoolean("dark_theme", false)) }
    LaunchedEffect(darkTheme) { prefs.edit().putBoolean("dark_theme", darkTheme).apply() }

    val initialActiveId = remember { prefs.getString("active_project_id", initialProjects.first().id)?.takeIf { id -> initialProjects.any { it.id == id } } ?: initialProjects.first().id }
    val initialActiveProject = initialProjects.first { it.id == initialActiveId }
    var page by remember { mutableStateOf(ToolkitPage.PROJECT) }
    var mediaRoute by remember { mutableStateOf(MediaRoute.HUB) }
    var photoFullScreen by rememberSaveable { mutableStateOf(false) }
    var projects by remember { mutableStateOf(initialProjects) }
    var activeProjectId by remember { mutableStateOf(initialActiveId) }
    var clips by remember { mutableStateOf(initialActiveProject.clips) }
    var videoInfo by remember { mutableStateOf(initialActiveProject.info) }
    val activeProjectName = projects.firstOrNull { it.id == activeProjectId }?.name ?: "Project"
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var renameProjectDialog by remember { mutableStateOf(false) }
    var projectNameDraft by remember(activeProjectId) { mutableStateOf(activeProjectName) }
    var preset by remember { mutableStateOf(initialActiveProject.settings.preset) }
    var socialProfile by remember { mutableStateOf(initialActiveProject.settings.socialProfile) }
    var customSize by remember { mutableStateOf(initialActiveProject.settings.customSize) }
    var customWidth by remember { mutableStateOf(initialActiveProject.settings.customWidth) }
    var customHeight by remember { mutableStateOf(initialActiveProject.settings.customHeight) }
    var quality by remember { mutableStateOf(initialActiveProject.settings.quality) }
    var bitrate by remember { mutableFloatStateOf(initialActiveProject.settings.bitrate) }
    var targetSizeOn by remember { mutableStateOf(initialActiveProject.settings.targetSizeOn) }
    var targetSize by remember { mutableStateOf(initialActiveProject.settings.targetSize) }
    var fps by remember { mutableIntStateOf(initialActiveProject.settings.fps) }
    var codec by remember { mutableStateOf(initialActiveProject.settings.codec) }
    var rateControl by remember { mutableStateOf(initialActiveProject.settings.rateControl) }
    var audio by remember { mutableStateOf(initialActiveProject.settings.audio) }
    var copySourceStreams by remember { mutableStateOf(initialActiveProject.settings.copySourceStreams) }
    var hdrExportMode by remember { mutableStateOf(initialActiveProject.settings.hdrExportMode) }
    var batchExport by remember { mutableStateOf(initialActiveProject.settings.batchExport) }
    var brightness by remember { mutableFloatStateOf(initialActiveProject.settings.brightness) }
    var contrast by remember { mutableFloatStateOf(initialActiveProject.settings.contrast) }
    var saturation by remember { mutableFloatStateOf(initialActiveProject.settings.saturation) }
    var rotation by remember { mutableFloatStateOf(initialActiveProject.settings.rotation) }
    var cropToFill by remember { mutableStateOf(initialActiveProject.settings.cropToFill) }
    var exportFileName by remember { mutableStateOf(initialActiveProject.settings.outputFileName) }
    var notice by remember { mutableStateOf("") }
    var photoViewerInitialUri by remember { mutableStateOf<Uri?>(null) }
    var exportListRefreshKey by remember { mutableIntStateOf(0) }

    fun currentExportSettings() = ProjectExportSettings(
        preset, socialProfile, customSize, customWidth, customHeight, quality, bitrate, targetSizeOn, targetSize, fps,
        codec, rateControl, audio, copySourceStreams, hdrExportMode, batchExport, brightness, contrast, saturation,
        rotation, cropToFill, exportFileName
    )

    fun applyExportSettings(settings: ProjectExportSettings) {
        preset = settings.preset; socialProfile = settings.socialProfile; customSize = settings.customSize
        customWidth = settings.customWidth; customHeight = settings.customHeight; quality = settings.quality
        bitrate = settings.bitrate; targetSizeOn = settings.targetSizeOn; targetSize = settings.targetSize; fps = settings.fps
        codec = settings.codec; rateControl = settings.rateControl; audio = settings.audio
        copySourceStreams = settings.copySourceStreams; hdrExportMode = settings.hdrExportMode; batchExport = settings.batchExport
        brightness = settings.brightness; contrast = settings.contrast; saturation = settings.saturation
        rotation = settings.rotation; cropToFill = settings.cropToFill; exportFileName = settings.outputFileName
    }

    LaunchedEffect(clips, videoInfo, activeProjectId, projects.map { it.id to it.name }, preset, socialProfile, customSize, customWidth, customHeight, quality, bitrate, targetSizeOn, targetSize, fps, codec, rateControl, audio, copySourceStreams, hdrExportMode, batchExport, brightness, contrast, saturation, rotation, cropToFill, exportFileName) {
        val snapshot = projects.map { project ->
            if (project.id == activeProjectId) project.copy(clips = clips, info = videoInfo, settings = currentExportSettings()) else project
        }
        if (snapshot != projects) projects = snapshot
        saveProjects(prefs, snapshot)
    }

    fun saveActiveProjectSnapshot(): List<VideoProject> = projects.map { project ->
        if (project.id == activeProjectId) project.copy(clips = clips, info = videoInfo, settings = currentExportSettings()) else project
    }.also { snapshot -> projects = snapshot; saveProjects(prefs, snapshot) }

    fun selectProject(selectedId: String) {
        val snapshot = saveActiveProjectSnapshot()
        val selected = snapshot.firstOrNull { it.id == selectedId } ?: return
        activeProjectId = selected.id
        prefs.edit().putString("active_project_id", selected.id).apply()
        clips = selected.clips
        videoInfo = selected.info
        applyExportSettings(selected.settings)
    }

    fun createProject() {
        val snapshot = saveActiveProjectSnapshot()
        val created = VideoProject(UUID.randomUUID().toString(), "Project ${snapshot.size + 1}")
        projects = snapshot + created
        saveProjects(prefs, projects)
        activeProjectId = created.id
        prefs.edit().putString("active_project_id", created.id).apply()
        clips = emptyList()
        videoInfo = ""
        applyExportSettings(created.settings)
        page = ToolkitPage.PROJECT
        mediaRoute = MediaRoute.HUB
    }

    val picker = rememberMediaSourcePicker(arrayOf("video/*"), allowMultiple = true, darkTheme = darkTheme) { uris ->
        val additions = uris.mapNotNull { uri -> runCatching {
            runCatching { activity.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val name = activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val col = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (col >= 0 && cursor.moveToFirst()) cursor.getString(col) else "Video"
            } ?: "Video"
            val duration = VideoInspector.durationMs(activity, uri)
            VideoClip(uri, name, duration, 0L, duration)
        }.getOrNull() }
        val wasEmpty = clips.isEmpty()
        clips = (clips + additions).distinctBy { it.uri }
        clips.firstOrNull()?.let { videoInfo = VideoInspector.inspect(activity, it.uri) }
        if (wasEmpty && additions.isNotEmpty()) exportFileName = defaultExportName(additions.first().name)
    }
    LaunchedEffect(externalOpenIntent) {
        if (externalOpenIntent?.getBooleanExtra("open_ultra_hdr_camera", false) == true) {
            page = ToolkitPage.MEDIA
            mediaRoute = MediaRoute.CAMERA
            photoFullScreen = false
            return@LaunchedEffect
        }
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
                val incomingClips = incomingVideos.map { item ->
                    runCatching { activity.contentResolver.takePersistableUriPermission(item.first, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                    val duration = VideoInspector.durationMs(activity, item.first)
                    VideoClip(item.first, item.third, duration, 0L, duration)
                }
                val snapshot = projects.map { project -> if (project.id == activeProjectId) project.copy(clips = clips, info = videoInfo, settings = currentExportSettings()) else project }
                val settings = ProjectExportSettings().copy(outputFileName = defaultExportName(incomingClips.firstOrNull()?.name.orEmpty()))
                val project = VideoProject(UUID.randomUUID().toString(), "Project ${snapshot.size + 1}", incomingClips, incomingClips.firstOrNull()?.let { VideoInspector.inspect(activity, it.uri) }.orEmpty(), settings)
                projects = snapshot + project
                saveProjects(prefs, snapshot + project)
                activeProjectId = project.id
                prefs.edit().putString("active_project_id", project.id).apply()
                clips = incomingClips
                videoInfo = project.info
                applyExportSettings(project.settings)
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
    val trashRequestLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            exportListRefreshKey++
            Toast.makeText(activity, "Moved to device trash", Toast.LENGTH_SHORT).show()
        } else Toast.makeText(activity, "Move to trash was cancelled", Toast.LENGTH_SHORT).show()
    }
    LaunchedEffect(Unit) {
        if (android.os.Build.VERSION.SDK_INT >= 30 && !prefs.getBoolean("legacy_trash_migrated", false)) withContext(Dispatchers.IO) {
            val restored = runCatching {
                val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val queryArgs = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
                activity.contentResolver.query(
                    collection,
                    arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.RELATIVE_PATH),
                    queryArgs,
                    null
                )?.use { cursor ->
                    val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.RELATIVE_PATH)
                    val hiddenExportUris = buildList {
                        while (cursor.moveToNext()) {
                            if (cursor.getString(pathColumn).orEmpty().trimEnd('/') == "Movies/Pocket Video Toolkit") {
                                add(ContentUris.withAppendedId(collection, cursor.getLong(idColumn)))
                            }
                        }
                    }
                    hiddenExportUris.forEach { uri ->
                        val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY) }
                        activity.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_TRASHED, 0) }, args)
                    }
                }
                true
            }.getOrDefault(false)
            if (restored) prefs.edit().putBoolean("legacy_trash_migrated", true).apply()
        }
    }
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
    val outputSize = if (preset in setOf("Reels / Shorts", "TikTok")) 1080 to 1920 else if (preset == "Square social") 1080 to 1080 else if (preset == "Source" && sourceDimensions != null) sourceDimensions else if (customSize) (customWidth.toIntOrNull() ?: 1280) to (customHeight.toIntOrNull() ?: 720) else when (preset) {
        "240p" -> 426 to 240; "360p" -> 640 to 360; "480p" -> 854 to 480; "1080p" -> 1920 to 1080; "1440p" -> 2560 to 1440; "4K" -> 3840 to 2160; else -> 1280 to 720
    }
    val durationMs = remember(clips) { clips.sumOf { it.durationMs } }
    val trimmedDuration = remember(clips) { clips.sumOf { (it.trimEndMs - it.trimStartMs).coerceIn(0L, it.durationMs.takeIf { d -> d > 0L } ?: Long.MAX_VALUE) } }
    val hasTrim = clips.any { it.trimStartMs > 0L || (it.durationMs > 0L && it.trimEndMs < it.durationMs) }
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
            val jobsToQueue = if (batchExport && clips.size > 1) clips else listOf(VideoClip(clips.first().uri, clips.joinToString(" + ") { it.name }))
            jobsToQueue.forEachIndexed { jobIndex, exportClip ->
            val data = Data.Builder()
                .putString(ExportWorker.KEY_URI, clips.first().uri.toString())
                .putStringArray(ExportWorker.KEY_URIS, if (batchExport && clips.size > 1) arrayOf(exportClip.uri.toString()) else clips.map { it.uri.toString() }.toTypedArray())
                .putString(ExportWorker.KEY_OUTPUT_NAME, if (batchExport && clips.size > 1) "${exportFileName}_${jobIndex + 1}" else exportFileName)
                .putInt(ExportWorker.KEY_WIDTH, outputSize.first).putInt(ExportWorker.KEY_HEIGHT, outputSize.second)
                .putInt(ExportWorker.KEY_BITRATE, if (targetSizeOn) targetBitrate ?: 0 else (bitrate * 1_000_000).toInt())
                .putString(ExportWorker.KEY_CODEC, codec).putInt(ExportWorker.KEY_FPS, fps)
                .putInt(ExportWorker.KEY_BITRATE_MODE, when (rateControl) { "Variable" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR; "Constant" -> MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR; else -> 0 })
                .putBoolean(ExportWorker.KEY_TRIM, hasTrim).putString(ExportWorker.KEY_AUDIO, audio)
                .putLong(ExportWorker.KEY_START, exportClip.trimStartMs)
                .putLong(ExportWorker.KEY_END, exportClip.trimEndMs.takeIf { it > 0L } ?: Long.MAX_VALUE)
                .putLongArray(ExportWorker.KEY_TRIM_STARTS, if (batchExport && clips.size > 1) longArrayOf(exportClip.trimStartMs) else clips.map { it.trimStartMs }.toLongArray())
                .putLongArray(ExportWorker.KEY_TRIM_ENDS, if (batchExport && clips.size > 1) longArrayOf(exportClip.trimEndMs) else clips.map { it.trimEndMs }.toLongArray())
                .putBoolean(ExportWorker.KEY_COPY_SOURCE_STREAMS, copySourceStreams && !hasTrim)
                .putFloat(ExportWorker.KEY_BRIGHTNESS, brightness).putFloat(ExportWorker.KEY_CONTRAST, contrast).putFloat(ExportWorker.KEY_SATURATION, saturation)
                .putFloat(ExportWorker.KEY_ROTATION, rotation).putBoolean(ExportWorker.KEY_CROP, cropToFill).build()
            val mutableData = Data.Builder().putAll(data).putString(ExportWorker.KEY_HDR_MODE, hdrExportMode).build()
            val request = OneTimeWorkRequestBuilder<ExportWorker>().setInputData(mutableData)
                .addTag("pvt-export")
                .addTag("pvt-output-name:${Uri.encode(if (batchExport && clips.size > 1) "${exportFileName}_${jobIndex + 1}" else exportFileName)}")
                .addTag("pvt-source-uri:${Uri.encode(exportClip.uri.toString())}")
                .build()
            workManager.enqueue(request)
            }
            notice = "Export added. You can keep working in other pages or queue another job."
            page = ToolkitPage.JOBS
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) enqueue() else { notice = "Export queued. Android may hide its progress notification."; enqueue() }
    }
    val colors = if (darkTheme) darkColorScheme(
        primary = Color(0xFFA6D2C5), onPrimary = Color(0xFF0D2720),
        primaryContainer = Color(0xFF29433D), onPrimaryContainer = Color(0xFFD1E9E2),
        secondary = Color(0xFFAABED5), onSecondary = Color(0xFF182430),
        background = Color(0xFF101418), surface = Color(0xFF181E23),
        surfaceVariant = Color(0xFF252D34), onSurface = Color(0xFFE0E7EA),
        onSurfaceVariant = Color(0xFFAFB9BF), outline = Color(0xFF65717A),
        outlineVariant = Color(0xFF414C54), secondaryContainer = Color(0xFF33434A),
        onSecondaryContainer = Color(0xFFD6E2E8)
    )
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
                    title = {
                        Column {
                            if (page in setOf(ToolkitPage.PROJECT, ToolkitPage.EDIT, ToolkitPage.CONVERT)) {
                                Box {
                                    TextButton(onClick = { projectMenuExpanded = true }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
                                        Text(activeProjectName, maxLines = 1, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Icon(Icons.Rounded.ExpandMore, "Choose project", modifier = Modifier.size(20.dp))
                                    }
                                    DropdownMenu(expanded = projectMenuExpanded, onDismissRequest = { projectMenuExpanded = false }) {
                                        projects.forEach { project ->
                                            DropdownMenuItem(
                                                text = { Text(if (project.id == activeProjectId) "✓  ${project.name}" else project.name) },
                                                onClick = { projectMenuExpanded = false; selectProject(project.id) }
                                            )
                                        }
                                        HorizontalDivider()
                                        DropdownMenuItem(text = { Text("Rename current project") }, onClick = { projectMenuExpanded = false; projectNameDraft = activeProjectName; renameProjectDialog = true })
                                        DropdownMenuItem(text = { Text("New project") }, leadingIcon = { Icon(Icons.Rounded.Add, null) }, onClick = { projectMenuExpanded = false; createProject() })
                                    }
                                }
                                Text(page.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text("Pocket Video", fontWeight = FontWeight.Bold)
                                Text(if (page == ToolkitPage.MEDIA) mediaTitle else page.title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    },
                    actions = { IconButton(onClick = { darkTheme = !darkTheme }) { Icon(if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode, "Toggle dark theme") } }
                )
            },
            bottomBar = {
                if (!photoFullScreen && !cameraFullScreen) {
                    Column {
                        // Keep the project workspace tabs visible on the Media hub so users can
                        // return to their project without relying on the system Back gesture.
                        if (page in setOf(ToolkitPage.PROJECT, ToolkitPage.EDIT, ToolkitPage.CONVERT) ||
                            (page == ToolkitPage.MEDIA && mediaRoute == MediaRoute.HUB)
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(ToolkitPage.PROJECT, ToolkitPage.EDIT, ToolkitPage.CONVERT).forEach { destination ->
                                    val selected = page == destination
                                    val icon = when (destination) {
                                        ToolkitPage.PROJECT -> Icons.Rounded.FolderOpen
                                        ToolkitPage.EDIT -> Icons.Rounded.Tune
                                        else -> Icons.Rounded.Movie
                                    }
                                    Surface(
                                        onClick = { page = destination },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f)
                                    ) {
                                        Row(
                                            Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(icon, null, modifier = Modifier.size(18.dp), tint = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(6.dp))
                                            Text(destination.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                        NavigationBar {
                            val running = jobs.count { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.BLOCKED }
                            listOf(ToolkitPage.MEDIA, ToolkitPage.JOBS).forEach { destination ->
                                val icon = if (destination == ToolkitPage.MEDIA) Icons.Rounded.Photo else Icons.Rounded.ListAlt
                                NavigationBarItem(
                                    selected = page == destination,
                                    onClick = { page = destination; if (destination == ToolkitPage.MEDIA) mediaRoute = MediaRoute.HUB },
                                    icon = { Icon(icon, null) },
                                    label = { Text(if (destination == ToolkitPage.JOBS && running > 0) "Recent · $running" else if (destination == ToolkitPage.JOBS) "Recent exports" else "Media tools", maxLines = 1) }
                                )
                            }
                        }
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
                    ToolkitPage.PROJECT -> ProjectPage(
                        projectName = activeProjectName,
                        clips = clips,
                        info = videoInfo,
                        settings = currentExportSettings(),
                        sourceSizeBytes = sourceSizeBytes,
                        estimatedSize = estimatedOutputBytes?.let(::formatFileSize),
                        onAdd = picker,
                        onEdit = { page = ToolkitPage.EDIT },
                        onConvert = { page = ToolkitPage.CONVERT },
                        onRemoveProject = {
                            val snapshot = saveActiveProjectSnapshot()
                            val remaining = snapshot.filterNot { it.id == activeProjectId }
                            val next = remaining.firstOrNull() ?: VideoProject(UUID.randomUUID().toString(), "Project 1")
                            projects = if (remaining.isEmpty()) listOf(next) else remaining
                            saveProjects(prefs, projects)
                            activeProjectId = next.id
                            prefs.edit().putString("active_project_id", next.id).apply()
                            clips = next.clips
                            videoInfo = next.info
                            applyExportSettings(next.settings)
                            Toast.makeText(activity, "Project removed", Toast.LENGTH_SHORT).show()
                        }
                    )
                    ToolkitPage.EDIT -> EditPage(clips, onMove = { from, to -> clips = clips.toMutableList().also { it.add(to, it.removeAt(from)) } }, onRemove = { index ->
                        clips = clips.filterIndexed { i, _ -> i != index }; clips.firstOrNull()?.let { videoInfo = VideoInspector.inspect(activity, it.uri) } ?: run { videoInfo = "" }
                    }, onAdd = picker, onTrimRangeChange = { index, startMs, endMs ->
                        clips = clips.mapIndexed { clipIndex, clip -> if (clipIndex == index) clip.copy(trimStartMs = startMs, trimEndMs = endMs) else clip }
                        if (startMs > 0L || (clips.getOrNull(index)?.durationMs ?: 0L) > endMs) copySourceStreams = false
                    })
                    ToolkitPage.CONVERT -> ConvertPage(socialProfile, { selected ->
                        socialProfile = selected
                        if (selected != "General") {
                            customSize = false; targetSizeOn = false; copySourceStreams = false
                            codec = "H.264"; quality = "Balanced"; fps = 30; hdrExportMode = ExportWorker.HDR_MODE_SDR
                            when (selected) {
                                "Instagram Reels" -> { preset = "Reels / Shorts"; cropToFill = true; audio = "AAC · 128 kbps"; bitrate = 8f }
                                "TikTok" -> { preset = "TikTok"; cropToFill = true; audio = "AAC · 192 kbps"; bitrate = 8f }
                                "YouTube" -> { preset = "1080p"; cropToFill = false; fps = 0; audio = "AAC · 192 kbps"; bitrate = 8f }
                                "X" -> { preset = "1080p"; cropToFill = false; audio = "AAC · 128 kbps"; bitrate = 8f }
                                "Snapchat Ad" -> { preset = "Reels / Shorts"; cropToFill = true; audio = "AAC · 192 kbps"; bitrate = 10f }
                                "LinkedIn" -> { preset = "1080p"; cropToFill = false; audio = "AAC · 192 kbps"; bitrate = 8f }
                            }
                        }
                    }, preset, { preset = it; socialProfile = "General" }, customSize, { customSize = it }, customWidth, { customWidth = it.filter(Char::isDigit) }, customHeight, { customHeight = it.filter(Char::isDigit) }, quality, { quality = it }, bitrate, { bitrate = it; quality = "Custom" }, targetSizeOn, { targetSizeOn = it }, targetSize, { targetSize = it.filter { ch -> ch.isDigit() || ch == '.' } }, targetBitrate, fps, { fps = it }, codec, { codec = it }, availableCodecs, rateControl, { rateControl = it }, audio, { audio = it }, codecSupport, clips.isNotEmpty(), sourceDimensions, clips.firstOrNull()?.uri, outputSize.first, outputSize.second, copySourceStreams, { copySourceStreams = it }, hasTrim, activeProjectName, exportFileName, { exportFileName = it }, sourceSizeBytes, estimatedOutputBytes?.let(::formatFileSize), estimateNote, hdrExportMode, { hdrExportMode = it; if (it == ExportWorker.HDR_MODE_SDR) copySourceStreams = false }, batchExport, { batchExport = it }, clips.size, brightness, { brightness = it }, contrast, { contrast = it }, saturation, { saturation = it }, rotation, { rotation = it }, cropToFill, { cropToFill = it }, onQueue = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 && activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS) else enqueue()
                    })
                    ToolkitPage.MEDIA -> when (mediaRoute) {
                        MediaRoute.HUB -> MediaHubPage(onViewer = { mediaRoute = MediaRoute.PHOTO_VIEWER }, onConversion = { mediaRoute = MediaRoute.HDR_CONVERSION }, onCamera = { mediaRoute = MediaRoute.CAMERA })
                        MediaRoute.HDR_CONVERSION -> HdrConversionPage(activity)
                        MediaRoute.PHOTO_VIEWER -> UltraHdrPhotoViewerPage(activity, initialUri = photoViewerInitialUri, fullScreen = photoFullScreen, onFullScreenChange = { photoFullScreen = it })
                        MediaRoute.CAMERA -> UltraHdrCameraPage(activity, onExit = { mediaRoute = MediaRoute.HUB })
                    }
                    ToolkitPage.JOBS -> JobsPage(
                        jobs,
                        refreshKey = exportListRefreshKey,
                        onCancel = { id -> workManager.cancelWorkById(id) },
                        onRename = { uri, name -> runCatching { activity.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, name) }, null, null) > 0 }.getOrDefault(false) },
                        onDelete = { id, uri ->
                            val deleted = runCatching { activity.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)
                            if (deleted) exportListRefreshKey++
                            deleted
                        },
                        onTrash = { uri ->
                            runCatching {
                                if (android.os.Build.VERSION.SDK_INT < 30) error("Trash requires Android 11 or newer")
                                val request = MediaStore.createTrashRequest(activity.contentResolver, listOf(uri), true)
                                trashRequestLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                            }.onFailure { error ->
                                Toast.makeText(activity, error.message ?: "Could not open the device trash confirmation", Toast.LENGTH_LONG).show()
                            }
                        },
                        notice = notice
                    )
                }
            }
        }
        if (renameProjectDialog) AlertDialog(
            onDismissRequest = { renameProjectDialog = false },
            title = { Text("Rename project") },
            text = { OutlinedTextField(value = projectNameDraft, onValueChange = { projectNameDraft = it.take(48) }, singleLine = true, label = { Text("Project name") }) },
            confirmButton = {
                TextButton(onClick = {
                    val cleanName = projectNameDraft.trim()
                    if (cleanName.isNotEmpty()) {
                        projects = projects.map { if (it.id == activeProjectId) it.copy(name = cleanName) else it }
                        saveProjects(prefs, projects)
                    }
                    renameProjectDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { renameProjectDialog = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ProjectPage(
    projectName: String,
    clips: List<VideoClip>,
    info: String,
    settings: ProjectExportSettings,
    sourceSizeBytes: Long,
    estimatedSize: String?,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onConvert: () -> Unit,
    onRemoveProject: () -> Unit
) {
    var projectMenuExpanded by remember { mutableStateOf(false) }
    var confirmRemoveProject by remember { mutableStateOf(false) }
    val duration = clips.sumOf { (it.trimEndMs - it.trimStartMs).coerceAtLeast(0L) }
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .58f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Icon(Icons.Rounded.VideoLibrary, contentDescription = null, modifier = Modifier.padding(12.dp).size(24.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text("PROJECT WORKSPACE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(projectName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    Text("${clips.size} ${if (clips.size == 1) "clip" else "clips"}  ·  ${formatMediaTime(duration)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onAdd) { Icon(Icons.Rounded.Add, "Add video") }
                Box {
                    IconButton(onClick = { projectMenuExpanded = true }) { Icon(Icons.Rounded.MoreVert, "Project options") }
                    DropdownMenu(expanded = projectMenuExpanded, onDismissRequest = { projectMenuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Remove project") },
                            leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                            onClick = { projectMenuExpanded = false; confirmRemoveProject = true }
                        )
                    }
                }
            }
            if (clips.isEmpty()) {
                Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .72f)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.Movie, null, modifier = Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("Start this project", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Add one or more videos. Clips, edit points, and export choices will stay with this project.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onAdd) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add videos") }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SOURCE CLIPS", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                    TextButton(onClick = onAdd, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Icon(Icons.Rounded.Add, null, modifier = Modifier.size(17.dp)); Text("Add") }
                }
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    clips.forEachIndexed { index, clip -> ProjectSourceCard(clip, index, if (index == 0) info else null, onClick = onEdit) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Tune, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Edit clips") }
                    Button(onClick = onConvert, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Movie, null, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Export") }
                }
            }
        }
    }

    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("Export setup", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onConvert, contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text("Adjust") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectSetting("PROFILE", if (settings.socialProfile == "General") "Custom" else settings.socialProfile, Modifier.weight(1f))
                val frameSize = if (settings.customSize) "${settings.customWidth} × ${settings.customHeight}" else settings.preset
                ProjectSetting("FRAME", frameSize, Modifier.weight(1f))
                ProjectSetting("QUALITY", if (settings.targetSizeOn) "${settings.targetSize} MB target" else "${settings.quality} · ${settings.bitrate.toInt()} Mbps", Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProjectSetting("VIDEO", settings.codec, Modifier.weight(1f))
                ProjectSetting("AUDIO", settings.audio.substringBefore(" · "), Modifier.weight(1f))
                ProjectSetting("DYNAMIC RANGE", when (settings.hdrExportMode) { ExportWorker.HDR_MODE_SDR -> "SDR"; else -> "Keep source" }, Modifier.weight(1f))
            }
            if (clips.isNotEmpty()) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Storage, null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Source ${if (sourceSizeBytes > 0) formatFileSize(sourceSizeBytes) else "size unavailable"}", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(estimatedSize?.let { "Estimate $it" } ?: "Add video for estimate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
            }
        }
    }

    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .72f))) {
        Row(Modifier.fillMaxWidth().padding(15.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Rounded.Layers, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f)) {
                Text("Keep working while exports run", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text("Switch projects or open other tools. Progress and saved files are in Recent exports.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }

    if (confirmRemoveProject) AlertDialog(
        onDismissRequest = { confirmRemoveProject = false },
        title = { Text("Remove $projectName?") },
        text = { Text("This removes the project, its clip list, and its export settings. Exported video files are kept.") },
        confirmButton = {
            TextButton(onClick = { confirmRemoveProject = false; onRemoveProject() }) { Text("Remove project") }
        },
        dismissButton = { TextButton(onClick = { confirmRemoveProject = false }) { Text("Cancel") } }
    )
}

@Composable
private fun ProjectSourceCard(clip: VideoClip, index: Int, firstClipInfo: String?, onClick: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val details by produceState<VideoFileDetails?>(null, clip.uri) {
        value = withContext(Dispatchers.IO) { VideoInspector.details(context, clip.uri) }
    }
    val thumbnail by produceState<Bitmap?>(null, clip.uri) {
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, clip.uri)
                retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
        }
    }
    Card(onClick = onClick, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .72f))) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(width = 86.dp, height = 62.dp).clip(RoundedCornerShape(11.dp)), contentAlignment = Alignment.Center) {
                if (thumbnail != null) Image(thumbnail!!.asImageBitmap(), "Video thumbnail", modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                else Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant) { Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) } }
                Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp), shape = RoundedCornerShape(5.dp), color = Color.Black.copy(alpha = .68f)) {
                    Text(formatMediaTime(clip.durationMs), Modifier.padding(horizontal = 5.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("${index + 1}. ${clip.name}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val sourceLine = details?.let {
                    listOf(VideoInspector.resolutionLabel(it.width, it.height), it.hdr, it.videoCodec).filter(String::isNotBlank).joinToString(" · ")
                } ?: if (index == 0) firstClipInfo?.take(55).orEmpty() else "Loading video details…"
                Text(sourceLine, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                val selectedDuration = (clip.trimEndMs - clip.trimStartMs).coerceAtLeast(0L)
                Text(if (clip.trimStartMs > 0L || clip.trimEndMs < clip.durationMs) "Trim · ${formatMediaTime(selectedDuration)}" else "Full clip · ${details?.sizeBytes?.takeIf { it > 0 }?.let(::formatFileSize) ?: "Size unavailable"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProjectSetting(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .62f)).padding(horizontal = 9.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

@Composable private fun EditPage(clips: List<VideoClip>, onMove: (Int, Int) -> Unit, onRemove: (Int) -> Unit, onAdd: () -> Unit, onTrimRangeChange: (Int, Long, Long) -> Unit) {
    SectionCard("Clip sequence", "Add multiple videos and drag them into the order you want; export joins them end to end.") {
        Button(onClick = onAdd) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Add clips") }
        if (clips.isEmpty()) Text("No clips yet. Add videos to start editing.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (clips.size > 1) Text("Re-encoding a joined HDR video may keep HDR10/HLG but loses HDR10+ dynamic metadata. For a compatible HEVC join without re-encoding, choose Copy source streams on the Export page.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    SectionCard("Trim each clip", "Drag the handles to choose each clip’s in and out points. Times use minutes and seconds.") {
        if (clips.isEmpty()) Text("Add a video to set its trim range.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        clips.forEachIndexed { index, clip ->
            val duration = clip.durationMs.coerceAtLeast(0L)
            val rangeStart = if (duration > 0L) (clip.trimStartMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val rangeEnd = if (duration > 0L) (clip.trimEndMs.toFloat() / duration).coerceIn(rangeStart, 1f) else 1f
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(clip.name, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    Text("${formatMediaTime(clip.trimEndMs - clip.trimStartMs)} / ${formatMediaTime(duration)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                RangeSlider(
                    value = rangeStart..rangeEnd,
                    onValueChange = { range ->
                        val minGap = minOf(500L, duration.coerceAtLeast(1L))
                        var startMs = (range.start * duration).toLong().coerceIn(0L, duration)
                        var endMs = (range.endInclusive * duration).toLong().coerceIn(0L, duration)
                        if (endMs - startMs < minGap) {
                            if (range.start != rangeStart) startMs = (endMs - minGap).coerceAtLeast(0L)
                            else endMs = (startMs + minGap).coerceAtMost(duration)
                        }
                        onTrimRangeChange(index, startMs, endMs)
                    },
                    enabled = duration > 0L
                )
                Text("${formatMediaTime(clip.trimStartMs)}  –  ${formatMediaTime(clip.trimEndMs)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    SectionCard("Editing tools", "") { Text("Trim and ordered joining are available now. Split, transitions, and timeline effects are not part of this build yet.", style = MaterialTheme.typography.bodyMedium) }
}

@Composable private fun ConvertPage(
    socialProfile: String, onSocialProfile: (String) -> Unit, preset: String, onPreset: (String) -> Unit, customSize: Boolean, onCustomSize: (Boolean) -> Unit, width: String, onWidth: (String) -> Unit, height: String, onHeight: (String) -> Unit,
    quality: String, onQuality: (String) -> Unit, bitrate: Float, onBitrate: (Float) -> Unit, targetSizeOn: Boolean, onTargetToggle: (Boolean) -> Unit, targetSize: String, onTargetSize: (String) -> Unit, targetBitrate: Int?,
    fps: Int, onFps: (Int) -> Unit, codec: String, onCodec: (String) -> Unit, codecOptions: List<String>, rateControl: String, onRateControl: (String) -> Unit, audio: String, onAudio: (String) -> Unit, codecSupport: List<Pair<String, Boolean>>, hasClips: Boolean, sourceDimensions: Pair<Int, Int>?, previewUri: Uri?, outputWidth: Int, outputHeight: Int, copySourceStreams: Boolean, onCopySourceStreams: (Boolean) -> Unit, trimOn: Boolean, projectName: String, outputFileName: String, onOutputFileName: (String) -> Unit, sourceSizeBytes: Long, estimatedSize: String?, estimateNote: String, hdrExportMode: String, onHdrExportMode: (String) -> Unit, batchExport: Boolean, onBatchExport: (Boolean) -> Unit, clipCount: Int, brightness: Float, onBrightness: (Float) -> Unit, contrast: Float, onContrast: (Float) -> Unit, saturation: Float, onSaturation: (Float) -> Unit, rotation: Float, onRotation: (Float) -> Unit, cropToFill: Boolean, onCropToFill: (Boolean) -> Unit, onQueue: () -> Unit
) {
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var adjustmentsExpanded by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .46f))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Export preset", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    CompactInfo("Platform profiles", "Profiles apply a practical starting setup for common upload workflows. Check the selected platform, account, and posting method before publishing; upload rules can change and this app cannot guarantee acceptance.")
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("General", "Instagram Reels", "TikTok", "YouTube", "X", "Snapchat Ad", "LinkedIn").forEach { option ->
                        FilterChip(selected = socialProfile == option, onClick = { onSocialProfile(option) }, label = { Text(option, maxLines = 1) })
                    }
                }
                Text(
                    when (socialProfile) {
                        "Instagram Reels" -> "Vertical · H.264 · 30 fps · AAC 128 kbps · SDR"
                        "TikTok" -> "Vertical · H.264 · 30 fps · AAC 192 kbps · SDR"
                        "YouTube" -> "1080p · source frame rate · H.264 · AAC · SDR"
                        "X" -> "1080p · H.264 · AAC 128 kbps · SDR"
                        "Snapchat Ad" -> "Vertical ad canvas · H.264 · stereo AAC · SDR"
                        "LinkedIn" -> "1080p · H.264 · AAC · SDR"
                        else -> "Choose a platform starting point or set every export option yourself."
                    }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AspectRatio, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Frame size", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    CompactInfo("Resolution", "Source matches the first clip. Choose a preset or enter custom dimensions. Platform presets choose a frame shape; use Fill frame below to crop into it.")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (customSize) "Custom dimensions" else "Choose output size", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ExportPreviewButton(previewUri, outputWidth, outputHeight, cropToFill, rotation)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Source", "240p", "360p", "480p", "720p", "1080p", "1440p", "4K", "Reels / Shorts", "TikTok", "Square social").forEach { option ->
                        FilterChip(selected = !customSize && preset == option, onClick = { onCustomSize(false); onPreset(option) }, label = { Text(option, maxLines = 1) }, enabled = option != "Source" || sourceDimensions != null)
                    }
                    FilterChip(selected = customSize, onClick = { onCustomSize(true) }, label = { Text("Custom") })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sourceDimensions?.let { "${it.first} × ${it.second} source" } ?: "Select a video to see source size", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (customSize) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(width, onWidth, label = { Text("Width") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(height, onHeight, label = { Text("Height") }, modifier = Modifier.weight(1f), singleLine = true)
                }
            }
        }

        Card(shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Tune, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Quality and size", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    CompactInfo("Compression", "High keeps more detail and creates larger files. Small reduces file size at the cost of detail. Balanced is a middle setting.")
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf("High", "Balanced", "Small", "Custom").forEach { FilterChip(quality == it, { onQuality(it) }, label = { Text(it) }) }
                }
                Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .55f)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(19.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Original file size", style = MaterialTheme.typography.labelMedium)
                            Text(if (clipCount > 1) "$clipCount source clips · $projectName" else projectName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        Text(if (sourceSizeBytes > 0L) formatFileSize(sourceSizeBytes) else "Unavailable", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Target MB", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Switch(targetSizeOn, onTargetToggle, enabled = hasClips)
                    if (targetSizeOn) OutlinedTextField(value = targetSize, onValueChange = onTargetSize, modifier = Modifier.width(108.dp), singleLine = true, suffix = { Text("MB") })
                    if (targetSizeOn && targetBitrate != null) Text("≈${String.format("%.1f", targetBitrate / 1_000_000f)} Mbps", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
                        CompactSelectControl("HDR output", if (hdrExportMode == ExportWorker.HDR_MODE_SDR) "Tone-map to SDR" else "Keep HDR", listOf("Keep HDR", "Tone-map to SDR"), { onHdrExportMode(if (it == "Tone-map to SDR") ExportWorker.HDR_MODE_SDR else ExportWorker.HDR_MODE_KEEP) }, "Tone mapping converts HDR brightness to standard display range.")
                        CompactInfo("HDR output", if (hdrExportMode == ExportWorker.HDR_MODE_SDR) "Converts HDR to standard dynamic range using Media3 tone mapping. This re-encodes the video; HDR metadata is not retained." else "Keeps HDR when source, Android, and the selected encoder support it. Re-encoding does not preserve HDR10+ dynamic metadata. Copy source streams to preserve original metadata.")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompactSelectControl("Frame rate", when (fps) { 60 -> "60 fps"; 30 -> "30 fps"; 24 -> "24 fps"; else -> "Source" }, listOf("Source", "60 fps", "30 fps", "24 fps"), { value -> onFps(value.substringBefore(' ').toIntOrNull() ?: 0) }, "Source keeps the clip's original pace.", Modifier.weight(1f))
                        CompactSelectControl("Rate control", rateControl, listOf("Device default", "Variable", "Constant"), onRateControl, when (rateControl) { "Variable" -> "Adapts rate to scene detail."; "Constant" -> "Steadier data rate."; else -> "Let the encoder choose." }, Modifier.weight(1f))
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(18.dp)) {
            Column {
                TextButton(onClick = { adjustmentsExpanded = !adjustmentsExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Icon(if (adjustmentsExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    Spacer(Modifier.width(6.dp))
                    Text("Picture adjustments", Modifier.weight(1f))
                    Text("${if (cropToFill) "Crop" else "Fit"} · ${rotation.toInt()}°", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (adjustmentsExpanded && !copySourceStreams) Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Color and framing", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        CompactInfo("Picture adjustments", "Brightness, contrast, saturation, crop, and rotation are applied during export. HDR sources are kept in HDR mode when supported; look and brightness can vary by device encoder.")
                    }
                    AdjustmentSlider("Brightness", brightness, -0.5f..0.5f, onBrightness, "Neutral")
                    AdjustmentSlider("Contrast", contrast, -0.5f..0.5f, onContrast, "Neutral")
                    AdjustmentSlider("Saturation", saturation, -100f..100f, onSaturation, "Neutral")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Rotate", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                        listOf(0f, 90f, 180f, 270f).forEach { angle -> FilterChip(selected = rotation == angle, onClick = { onRotation(angle) }, label = { Text("${angle.toInt()}°") }) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Fill frame", style = MaterialTheme.typography.labelLarge); Text("Crop edges to match output ratio", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Switch(cropToFill, onCropToFill)
                    }
                }
            }
        }

        Card(shape = RoundedCornerShape(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Copy original streams", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text(if (copySourceStreams) "No re-encoding · keeps source metadata" else "Re-encode with selected settings", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    CompactInfo("Copy source streams", if (trimOn) "Turn off all clip trimming to use stream copy." else "Copies without re-encoding. A single MP4 or WebM keeps its original video and audio tracks, including VP9 or Opus in WebM and HDR10+ metadata. Re-encoding to VP9/Opus and joining WebM are not supported yet. Joining in copy mode requires matching HEVC MP4 clips.")
                    Switch(copySourceStreams, onCopySourceStreams, enabled = hasClips && !trimOn)
                }
                if (clipCount > 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                if (clipCount > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Export clips separately", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        Text("One output for each selected video", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(batchExport, onBatchExport)
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            OutlinedTextField(value = outputFileName, onValueChange = onOutputFileName, label = { Text("Save as") }, singleLine = true, enabled = hasClips, modifier = Modifier.fillMaxWidth())
            Text("Movies / Pocket Video Toolkit · MP4", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 12.dp))
        }

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

@Composable private fun AdjustmentSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit, helper: String) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
            Text(if (value == 0f) helper else String.format("%.2f", value), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value, onValue, valueRange = range, modifier = Modifier.height(24.dp))
    }
}

@Composable private fun ExportPreviewButton(uri: Uri?, width: Int, height: Int, crop: Boolean, rotation: Float) {
    var showPreview by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val frame by produceState<Bitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) {
            if (uri == null) return@withContext null
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } catch (_: Exception) { null } finally { runCatching { retriever.release() } }
        }
    }
    TextButton(onClick = { showPreview = true }, enabled = uri != null) {
        Icon(Icons.Rounded.Visibility, null, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(4.dp))
        Text("Preview", style = MaterialTheme.typography.labelMedium)
    }
    if (showPreview) AlertDialog(
        onDismissRequest = { showPreview = false },
        title = { Text("Export preview") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (frame != null) {
                    Image(
                        bitmap = frame!!.asImageBitmap(),
                        contentDescription = "Preview of the selected video framed for export",
                        contentScale = if (crop) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().aspectRatio((width.toFloat() / height).coerceIn(.45f, 2.4f)).graphicsLayer { rotationZ = rotation }.clip(RoundedCornerShape(12.dp))
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        Text(if (uri == null) "Add a video to preview its framing." else "Could not load a frame from this video.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text("${width} × ${height} px · ${if (crop) "Fill frame (edges cropped)" else "Fit full image (may add bars)"}", style = MaterialTheme.typography.labelMedium)
                Text("Preview shows framing only. Export colors, HDR, and final quality depend on the source and device encoder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { showPreview = false }) { Text("Done") } }
    )
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

@Composable private fun JobsPage(
    jobs: List<WorkInfo>, refreshKey: Int, onCancel: (UUID) -> Unit, onRename: (Uri, String) -> Boolean,
    onDelete: (UUID, Uri) -> Boolean, onTrash: (Uri) -> Unit, notice: String
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val visibleIds by produceState<Set<String>>(emptySet(), jobs, refreshKey) {
        value = withContext(Dispatchers.IO) {
            jobs.filter { work ->
                when (work.state) {
                    WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> true
                    WorkInfo.State.SUCCEEDED -> work.outputData.getString("saved_uri")?.let { exportIsAvailable(context, Uri.parse(it)) } == true
                    else -> false
                }
            }.map { it.id.toString() }.toSet()
        }
    }
    val visibleJobs = jobs.filter { it.id.toString() in visibleIds }
    SectionCard("Recent exports", "Saved to Movies / Pocket Video Toolkit") {
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
        if (visibleJobs.isEmpty()) Text("Exports will appear here with their progress and final result.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        visibleJobs.take(30).forEach { work -> ExportJobRow(work, onCancel, onRename, onDelete, onTrash) }
    }
}

private fun exportIsAvailable(context: Context, uri: Uri): Boolean = runCatching {
    val projection = if (android.os.Build.VERSION.SDK_INT >= 30) {
        arrayOf(MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.IS_TRASHED)
    } else arrayOf(MediaStore.Video.Media.RELATIVE_PATH)
    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@runCatching false
        val pathIndex = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
        val path = pathIndex.takeIf { it >= 0 }?.let(cursor::getString).orEmpty().trimEnd('/')
        if (path != "Movies/Pocket Video Toolkit") return@runCatching false
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val trashIndex = cursor.getColumnIndex(MediaStore.Video.Media.IS_TRASHED)
            trashIndex < 0 || cursor.getInt(trashIndex) == 0
        } else true
    } ?: false
}.getOrDefault(false)

@Composable private fun ExportJobRow(
    work: WorkInfo, onCancel: (UUID) -> Unit, onRename: (Uri, String) -> Boolean,
    onDelete: (UUID, Uri) -> Boolean, onTrash: (Uri) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val outputUri = work.outputData.getString("saved_uri")
    val sourceUri = work.outputData.getString("source_uri")
        ?: work.tags.firstOrNull { it.startsWith("pvt-source-uri:") }?.removePrefix("pvt-source-uri:")?.let(Uri::decode)
    val previewUri = outputUri ?: sourceUri
    val sourceDetails by produceState<VideoFileDetails?>(null, work.id, sourceUri) {
        value = withContext(Dispatchers.IO) { sourceUri?.let(Uri::parse)?.let { VideoInspector.details(context, it) } }
    }
    val videoDetails by produceState<VideoFileDetails?>(null, work.id, previewUri) {
        value = withContext(Dispatchers.IO) {
            previewUri?.let(Uri::parse)?.let { VideoInspector.details(context, it) }
        }
    }
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
    val outputBaseName = (work.outputData.getString("output_name")
        ?: work.tags.firstOrNull { it.startsWith("pvt-output-name:") }?.removePrefix("pvt-output-name:")?.let(Uri::decode)).orEmpty()
        .ifBlank { "Video export" }.substringBeforeLast('.')
    val outputName = videoDetails?.name?.takeIf { it.isNotBlank() }
        ?: "$outputBaseName.${work.outputData.getString("output_extension")?.removePrefix(".") ?: "mp4"}"
    val isShowingSourceDetails = outputUri == null && sourceUri != null
    val status = when (work.state) {
        WorkInfo.State.RUNNING -> "Saving · ${work.progress.getInt("progress", 0)}%"
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> "Waiting in queue"
        WorkInfo.State.SUCCEEDED -> "Export complete"
        WorkInfo.State.CANCELLED -> "Cancelled"
        else -> "Failed · ${work.outputData.getString("error") ?: "Unsupported format or encoder"}"
    }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .48f))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Text(middleEllipsis(outputName, 36), modifier = Modifier.weight(1f).align(Alignment.CenterVertically), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (!work.state.isFinished) {
                    IconButton(onClick = { onCancel(work.id) }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Cancel export", modifier = Modifier.size(20.dp)) }
                } else if (outputUri != null) {
                    ExportFileMenu(
                        outputUri = Uri.parse(outputUri), outputName = outputName, workId = work.id,
                        onRename = onRename, onDelete = onDelete, onTrash = onTrash
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
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
                if (work.state != WorkInfo.State.SUCCEEDED) Text(status, style = MaterialTheme.typography.labelSmall, color = if (work.state == WorkInfo.State.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (work.state == WorkInfo.State.RUNNING) LinearProgressIndicator(progress = { work.progress.getInt("progress", 0).coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                videoDetails?.let { details ->
                    if (isShowingSourceDetails) Text("Source details · output details appear when saved", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ExportVideoDetails(details)
                    if (outputUri != null && sourceDetails != null) ExportComparison(sourceDetails!!, details)
                }
            }
            }
        }
    }
}

@Composable private fun ExportFileMenu(
    outputUri: Uri, outputName: String, onRename: (Uri, String) -> Boolean,
    workId: UUID, onDelete: (UUID, Uri) -> Boolean, onTrash: (Uri) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var actionToConfirm by remember { mutableStateOf<String?>(null) }
    var filename by remember(outputUri, outputName) { mutableStateOf(outputName.substringBeforeLast('.', outputName)) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.MoreVert, "File options", modifier = Modifier.size(20.dp)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { expanded = false; renameDialog = true })
            DropdownMenuItem(text = { Text("Move to trash") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) }, onClick = { expanded = false; onTrash(outputUri) })
            DropdownMenuItem(text = { Text("Delete file") }, leadingIcon = { Icon(Icons.Rounded.DeleteForever, null) }, onClick = { expanded = false; actionToConfirm = "delete" })
        }
    }
    if (renameDialog) AlertDialog(
        onDismissRequest = { renameDialog = false },
        title = { Text("Rename export") },
        text = { OutlinedTextField(value = filename, onValueChange = { filename = it }, singleLine = true, label = { Text("File name") }) },
        confirmButton = { TextButton(onClick = {
            val extension = outputName.substringAfterLast('.', "mp4").let { ".$it" }
            val safeName = filename.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trimEnd('.')
            if (safeName.isNotBlank()) {
                val success = onRename(outputUri, safeName + extension)
                Toast.makeText(context, if (success) "File renamed" else "Could not rename this file", Toast.LENGTH_SHORT).show()
            }
            renameDialog = false
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { renameDialog = false }) { Text("Cancel") } }
    )
    actionToConfirm?.let { action -> AlertDialog(
        onDismissRequest = { actionToConfirm = null },
        title = { Text("Delete file?") },
        text = { Text("This permanently deletes the exported video.") },
        confirmButton = { TextButton(onClick = {
            val success = onDelete(workId, outputUri)
            Toast.makeText(context, if (success) "File deleted" else "Could not delete this file", Toast.LENGTH_SHORT).show()
            actionToConfirm = null
        }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { actionToConfirm = null }) { Text("Cancel") } }
    ) }
}

private fun middleEllipsis(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    val extension = value.substringAfterLast('.', "").takeIf { it.length <= 8 }?.let { ".$it" }.orEmpty()
    val stem = value.removeSuffix(extension)
    val available = (maxChars - extension.length - 1).coerceAtLeast(2)
    val leading = (available + 1) / 2
    val trailing = available / 2
    return stem.take(leading) + "…" + stem.takeLast(trailing) + extension
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun ExportVideoDetails(details: VideoFileDetails) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        ExportVideoDetail(Icons.Rounded.Schedule, formatExportDuration(details.durationMs))
        ExportVideoDetail(Icons.Rounded.HighQuality, VideoInspector.resolutionLabel(details.width, details.height))
        ExportVideoDetail(Icons.Rounded.Storage, formatExportFileSize(details.sizeBytes))
        ExportVideoDetail(Icons.Rounded.Speed, details.frameRate?.let { "${it.toInt()} fps" } ?: "— fps")
        ExportVideoDetail(Icons.Rounded.HdrOn, details.hdr)
        ExportVideoDetail(Icons.Rounded.Movie, details.videoCodec)
        ExportVideoDetail(Icons.Rounded.GraphicEq, details.audioCodec)
        ExportVideoDetail(Icons.Rounded.CalendarToday, formatExportDate(details.modifiedSeconds))
    }
}

@Composable
private fun ExportVideoDetail(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.widthIn(max = 170.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}

private fun formatExportDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "—:—"
    val seconds = durationMs / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private fun formatExportFileSize(bytes: Long): String = when {
    bytes <= 0L -> "Size —"
    bytes >= 1_000_000_000L -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format("%.0f MB", bytes / 1_000_000.0)
    else -> String.format("%.0f KB", bytes / 1_000.0)
}

private fun formatExportDate(seconds: Long): String = if (seconds <= 0L) "Date —" else
    java.text.SimpleDateFormat("MMM d, yy", java.util.Locale.getDefault()).format(java.util.Date(seconds * 1000))

@Composable private fun ExportComparison(source: VideoFileDetails, output: VideoFileDetails) {
    val sourceBytes = source.sizeBytes
    val outputBytes = output.sizeBytes
    val percent = if (sourceBytes > 0) ((1.0 - outputBytes.toDouble() / sourceBytes) * 100.0).toInt() else 0
    val label = when {
        sourceBytes <= 0 || outputBytes <= 0 -> "Source/result comparison unavailable"
        percent > 0 -> "${formatExportFileSize(sourceBytes)} → ${formatExportFileSize(outputBytes)} · ${percent}% smaller"
        percent < 0 -> "${formatExportFileSize(sourceBytes)} → ${formatExportFileSize(outputBytes)} · ${-percent}% larger"
        else -> "${formatExportFileSize(sourceBytes)} → ${formatExportFileSize(outputBytes)} · similar size"
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
