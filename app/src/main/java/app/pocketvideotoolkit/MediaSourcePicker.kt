package app.pocketvideotoolkit

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaExtractor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.CalendarToday
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.*
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens a thumbnail file browser for device videos, a document picker for other files, or Gallery. */
@Composable
internal fun rememberMediaSourcePicker(
    mimeTypes: Array<String>,
    allowMultiple: Boolean = false,
    darkTheme: Boolean? = null,
    onSelected: (List<Uri>) -> Unit
): () -> Unit {
    val context = LocalContext.current
    val inheritedColors = MaterialTheme.colorScheme
    val pickerIsDark = darkTheme ?: (inheritedColors.background.luminance() < .5f)
    val pickerColors = if (pickerIsDark) darkColorScheme(
        primary = androidx.compose.ui.graphics.Color(0xFFA6D2C5),
        onPrimary = androidx.compose.ui.graphics.Color(0xFF0D2720),
        primaryContainer = androidx.compose.ui.graphics.Color(0xFF29433D),
        onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFD1E9E2),
        secondary = androidx.compose.ui.graphics.Color(0xFFAABED5),
        onSecondary = androidx.compose.ui.graphics.Color(0xFF182430),
        background = androidx.compose.ui.graphics.Color(0xFF101418),
        surface = androidx.compose.ui.graphics.Color(0xFF181E23),
        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF252D34),
        onSurface = androidx.compose.ui.graphics.Color(0xFFE0E7EA),
        onSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFAFB9BF),
        outline = androidx.compose.ui.graphics.Color(0xFF65717A),
        outlineVariant = androidx.compose.ui.graphics.Color(0xFF414C54),
        secondaryContainer = androidx.compose.ui.graphics.Color(0xFF33434A),
        onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFD6E2E8)
    ) else inheritedColors
    var choosingSource by remember { mutableStateOf(false) }
    var showVideoBrowser by remember { mutableStateOf(false) }
    val filesSingle = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onSelected(listOf(uri))
    }
    val filesMultiple = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) onSelected(uris)
    }
    val gallerySingle = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) onSelected(listOf(uri))
    }
    val galleryMultiple = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
        if (uris.isNotEmpty()) onSelected(uris)
    }
    val videoPermissions = if (Build.VERSION.SDK_INT >= 34) arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        else if (Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    val videoPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it } || hasVideoReadAccess(context)) showVideoBrowser = true
        else Toast.makeText(context, "Allow video access to browse device folders with thumbnails. Gallery is still available.", Toast.LENGTH_LONG).show()
    }
    val pickImages = mimeTypes.any { it.startsWith("image/") }
    val isVideoRequest = mimeTypes.any { it.startsWith("video/") }
    val galleryType = if (pickImages) ActivityResultContracts.PickVisualMedia.ImageOnly else ActivityResultContracts.PickVisualMedia.VideoOnly
    val openGallery = {
        val request = PickVisualMediaRequest(galleryType)
        if (allowMultiple) galleryMultiple.launch(request) else gallerySingle.launch(request)
    }

    if (choosingSource) AlertDialog(
        onDismissRequest = { choosingSource = false },
        title = { Text("Choose a source") },
        text = { Text(if (isVideoRequest) "Choose Gallery, browse video thumbnails, or open the file manager." else "Choose Gallery or browse device folders in the file manager.") },
        confirmButton = {
            if (isVideoRequest) Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                TextButton(onClick = { choosingSource = false; openGallery() }) { Text("Gallery") }
                TextButton(onClick = {
                    choosingSource = false
                    if (hasVideoReadAccess(context)) showVideoBrowser = true
                    else videoPermissionLauncher.launch(videoPermissions)
                }) { Text("Videos") }
                TextButton(onClick = {
                    choosingSource = false
                    if (allowMultiple) filesMultiple.launch(mimeTypes) else filesSingle.launch(mimeTypes)
                }) { Text("File manager") }
            } else TextButton(onClick = {
                choosingSource = false
                if (allowMultiple) filesMultiple.launch(mimeTypes) else filesSingle.launch(mimeTypes)
            }) { Text("File manager") }
        },
        dismissButton = if (isVideoRequest) null else ({ TextButton(onClick = { choosingSource = false; openGallery() }) { Text("Gallery") } })
    )

    if (showVideoBrowser) VideoFileBrowser(
        allowMultiple = allowMultiple,
        onDismiss = { showVideoBrowser = false },
        onOpenGallery = { showVideoBrowser = false; openGallery() },
        onSelected = { selected -> showVideoBrowser = false; onSelected(selected) },
        pickerColors = pickerColors
    )
    return remember { { choosingSource = true } }
}

private data class DeviceVideo(
    val uri: Uri,
    val name: String,
    val folderPath: String,
    val modified: Long,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val frameRate: Float?,
    val hdr: String,
    val videoCodec: String,
    val audioCodec: String
)

@Composable
private fun VideoFileBrowser(
    allowMultiple: Boolean,
    onDismiss: () -> Unit,
    onOpenGallery: () -> Unit,
    onSelected: (List<Uri>) -> Unit,
    pickerColors: ColorScheme
) {
    val context = LocalContext.current
    var videos by remember { mutableStateOf<List<DeviceVideo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var search by remember { mutableStateOf("") }
    var currentPath by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<Uri>() }

    LaunchedEffect(Unit) {
        videos = withContext(Dispatchers.IO) { queryDeviceVideos(context) }
        loading = false
    }
    val pathParts = currentPath.split('/').filter(String::isNotBlank)
    val childFolders = remember(videos, currentPath, search) {
        if (search.isNotBlank()) emptyList() else videos.mapNotNull { video ->
            val parts = video.folderPath.split('/').filter(String::isNotBlank)
            if (parts.size > pathParts.size && parts.take(pathParts.size) == pathParts) parts[pathParts.size] else null
        }.distinct().sorted()
    }
    val visibleVideos = remember(videos, currentPath, search) {
        videos.filter { video ->
            if (search.isNotBlank()) video.name.contains(search, true) || video.folderPath.contains(search, true)
            else video.folderPath.split('/').filter(String::isNotBlank) == pathParts
        }
    }
    BackHandler(enabled = currentPath.isNotBlank()) { currentPath = pathParts.dropLast(1).joinToString("/") }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
      MaterialTheme(colorScheme = pickerColors) {
        Box(Modifier.fillMaxSize().background(pickerColors.background), contentAlignment = Alignment.Center) {
          Surface(
            modifier = Modifier.fillMaxSize().padding(8.dp),
            shape = RoundedCornerShape(24.dp), color = pickerColors.surface,
            tonalElevation = 2.dp
          ) {
            // The fullscreen dialog on Samsung can report no navigation-bar inset to Compose.
            // Reserve space directly so the footer stays above the gesture-navigation area.
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp).padding(bottom = 64.dp)) {
                Row(Modifier.statusBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FolderOpen, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Videos on this device", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(if (allowMultiple) "Tap videos to select more than one." else "Choose a video to open." , style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onOpenGallery) { Icon(Icons.Rounded.PhotoLibrary, null); Spacer(Modifier.width(4.dp)); Text("Gallery") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = search, onValueChange = { search = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Search videos") },
                    leadingIcon = { Icon(Icons.Rounded.Movie, null) }
                )
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { currentPath = "" }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text("Internal storage") }
                    pathParts.forEachIndexed { index, part ->
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { currentPath = pathParts.take(index + 1).joinToString("/") }, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp)) { Text(part, maxLines = 1) }
                    }
                }
                when {
                    loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    videos.isEmpty() -> EmptyVideoBrowser(onOpenGallery, Modifier.weight(1f).fillMaxWidth())
                    childFolders.isEmpty() && visibleVideos.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Text(if (search.isBlank()) "No videos in this folder." else "No videos match the search.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    else -> LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(childFolders, key = { "folder:$currentPath/$it" }) { folder ->
                            val childPath = listOf(currentPath, folder).filter(String::isNotBlank).joinToString("/")
                            val count = videos.count { it.folderPath == childPath || it.folderPath.startsWith("$childPath/") }
                            Surface(
                                modifier = Modifier.fillMaxWidth().clickable { currentPath = childPath },
                                shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surface,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Icon(Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.secondary)
                                    Text(folder, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("$count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        items(visibleVideos, key = { it.uri.toString() }) { video ->
                            VideoFileRow(video, checked = video.uri in selected, multiSelect = allowMultiple, onClick = {
                                if (!allowMultiple) onSelected(listOf(video.uri))
                                else if (video.uri in selected) selected.remove(video.uri) else selected.add(video.uri)
                            })
                        }
                    }
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${visibleVideos.size} video${if (visibleVideos.size == 1) "" else "s"}", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    if (allowMultiple) Button(onClick = { if (selected.isNotEmpty()) onSelected(selected.toList()) }, enabled = selected.isNotEmpty()) { Text("Add ${selected.size}") }
                }
            }
          }
        }
      }
    }
}

@Composable
private fun EmptyVideoBrowser(onOpenGallery: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(42.dp))
        Spacer(Modifier.height(8.dp))
        Text("No device videos found", style = MaterialTheme.typography.titleMedium)
        Text("Try Gallery or check a different folder.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onOpenGallery) { Text("Open Gallery") }
    }
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun VideoFileRow(video: DeviceVideo, checked: Boolean, multiSelect: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val thumbnail by produceState<Bitmap?>(null, video.uri) {
        value = withContext(Dispatchers.IO) { loadVideoThumbnail(context, video.uri) }
    }
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = if (checked) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(Modifier.size(width = 84.dp, height = 58.dp), shape = RoundedCornerShape(9.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    if (thumbnail != null) Image(thumbnail!!.asImageBitmap(), null, contentScale = ContentScale.Crop)
                    else Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.Movie, null, tint = MaterialTheme.colorScheme.primary) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(video.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(video.folderPath.ifBlank { "Internal storage" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (multiSelect && checked) Icon(Icons.Rounded.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                VideoDetail(Icons.Rounded.Schedule, formatDuration(video.durationMs))
                VideoDetail(Icons.Rounded.HighQuality, VideoInspector.resolutionLabel(video.width, video.height))
                VideoDetail(Icons.Rounded.Storage, formatFileSize(video.sizeBytes))
                VideoDetail(Icons.Rounded.Speed, video.frameRate?.let { "${it.toInt()} fps" } ?: "— fps")
                VideoDetail(Icons.Rounded.HdrOn, video.hdr)
                VideoDetail(Icons.Rounded.Movie, video.videoCodec)
                VideoDetail(Icons.Rounded.GraphicEq, video.audioCodec)
                VideoDetail(Icons.Rounded.CalendarToday, formatModified(video.modified))
            }
        }
    }
}

private fun hasVideoReadAccess(context: Context): Boolean {
    val permission = if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO else android.Manifest.permission.READ_EXTERNAL_STORAGE
    return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED ||
        (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
}

private fun queryDeviceVideos(context: Context): List<DeviceVideo> {
    val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    val projection = buildList {
        add(MediaStore.Video.Media._ID)
        add(MediaStore.Video.Media.DISPLAY_NAME)
        add(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        if (Build.VERSION.SDK_INT >= 29) add(MediaStore.Video.Media.RELATIVE_PATH)
        add(MediaStore.Video.Media.DATE_MODIFIED)
        add(MediaStore.Video.Media.SIZE)
        add(MediaStore.Video.Media.DURATION)
        add(MediaStore.Video.Media.WIDTH)
        add(MediaStore.Video.Media.HEIGHT)
    }.toTypedArray()
    return runCatching {
        context.contentResolver.query(collection, projection, null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val bucketIndex = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val pathIndex = if (Build.VERSION.SDK_INT >= 29) cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH) else -1
            val dateIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
            val sizeIndex = cursor.getColumnIndex(MediaStore.Video.Media.SIZE)
            val durationIndex = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
            val widthIndex = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
            val heightIndex = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val folder = if (pathIndex >= 0) cursor.getString(pathIndex)?.trim('/') else null
                    val bucket = if (bucketIndex >= 0) cursor.getString(bucketIndex) else null
                    val uri = ContentUris.withAppendedId(collection, id)
                    val retriever = MediaMetadataRetriever()
                    val metadata = runCatching {
                        retriever.setDataSource(context, uri)
                        val frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
                        val colorStandard = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_STANDARD)?.toIntOrNull()
                        val transfer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COLOR_TRANSFER)?.toIntOrNull()
                        val codecs = readTrackCodecs(context, uri)
                        val hdr = detectHdrFormat(context, uri, transfer, colorStandard)
                        listOf(frameRate, hdr, codecs.first, codecs.second)
                    }.getOrDefault(listOf(null, "HDR —", "Video —", "Audio —"))
                    runCatching { retriever.release() }
                    add(DeviceVideo(
                        uri = uri,
                        name = cursor.getString(nameIndex) ?: "Video",
                        folderPath = folder?.takeIf { it.isNotBlank() } ?: bucket?.takeIf { it.isNotBlank() } ?: "",
                        modified = if (dateIndex >= 0) cursor.getLong(dateIndex) else 0L,
                        sizeBytes = if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L,
                        durationMs = if (durationIndex >= 0) cursor.getLong(durationIndex) else 0L,
                        width = if (widthIndex >= 0) cursor.getInt(widthIndex) else 0,
                        height = if (heightIndex >= 0) cursor.getInt(heightIndex) else 0,
                        frameRate = metadata[0] as? Float,
                        hdr = metadata[1] as String,
                        videoCodec = metadata[2] as String,
                        audioCodec = metadata[3] as String
                    ))
                }
            }
        }.orEmpty()
    }.getOrDefault(emptyList())
}

@Composable
private fun VideoDetail(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.widthIn(max = 170.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun formatDuration(durationMs: Long): String {
    if (durationMs <= 0) return "—:—"
    val totalSeconds = durationMs / 1000
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

private fun formatFileSize(bytes: Long): String = when {
    bytes <= 0 -> "Size —"
    bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format("%.0f MB", bytes / 1_000_000.0)
    else -> String.format("%.0f KB", bytes / 1_000.0)
}

private fun formatModified(seconds: Long): String {
    if (seconds <= 0) return "Date —"
    return java.text.SimpleDateFormat("MMM d, yy", java.util.Locale.getDefault()).format(java.util.Date(seconds * 1000))
}

private fun codecLabel(mime: String): String = when {
    mime.contains("hevc", true) || mime.contains("h265", true) -> "H.265"
    mime.contains("avc", true) || mime.contains("h264", true) -> "H.264"
    mime.contains("av01", true) || mime.contains("av1", true) -> "AV1"
    mime.contains("vp9", true) -> "VP9"
    mime.contains("aac", true) -> "AAC"
    mime.contains("opus", true) -> "Opus"
    mime.contains("mp4a", true) -> "AAC"
    mime.isBlank() -> "—"
    else -> mime.substringAfterLast('/').uppercase(java.util.Locale.ROOT)
}

/** Labels only formats that can be identified from track metadata; generic PQ is not assumed to be HDR10+. */
internal fun detectHdrFormat(context: Context, uri: Uri, transfer: Int?, colorStandard: Int?): String = runCatching {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        val videoFormat = (0 until extractor.trackCount)
            .map(extractor::getTrackFormat)
            .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        val profile = videoFormat?.let { format ->
            if (format.containsKey(android.media.MediaFormat.KEY_PROFILE)) format.getInteger(android.media.MediaFormat.KEY_PROFILE) else null
        }
        val hasStaticHdrMetadata = videoFormat?.containsKey("hdr-static-info") == true
        val hdr10Profile = runCatching { android.media.MediaCodecInfo.CodecProfileLevel::class.java.getField("HEVCProfileMain10HDR10").getInt(null) }.getOrNull()
        val hdr10PlusProfile = runCatching { android.media.MediaCodecInfo.CodecProfileLevel::class.java.getField("HEVCProfileMain10HDR10Plus").getInt(null) }.getOrNull()
        val main10Profile = runCatching { android.media.MediaCodecInfo.CodecProfileLevel::class.java.getField("HEVCProfileMain10").getInt(null) }.getOrNull()
        when {
            transfer == android.media.MediaFormat.COLOR_TRANSFER_HLG -> "HDR HLG"
            transfer == android.media.MediaFormat.COLOR_TRANSFER_ST2084 && profile != null && profile == hdr10PlusProfile -> "HDR10+ · PQ"
            transfer == android.media.MediaFormat.COLOR_TRANSFER_ST2084 && profile != null && profile == hdr10Profile -> "HDR10 · PQ"
            transfer == android.media.MediaFormat.COLOR_TRANSFER_ST2084 && hasStaticHdrMetadata -> "HDR10 · PQ"
            transfer == android.media.MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR PQ"
            transfer == null && colorStandard == android.media.MediaFormat.COLOR_STANDARD_BT2020 && profile != null && profile == main10Profile -> "HDR · transfer unspecified"
            transfer == null && colorStandard == android.media.MediaFormat.COLOR_STANDARD_BT2020 && hasStaticHdrMetadata -> "HDR · transfer unspecified"
            else -> "SDR"
        }
    } finally { extractor.release() }
}.getOrElse {
    when (transfer) {
        android.media.MediaFormat.COLOR_TRANSFER_HLG -> "HDR HLG"
        android.media.MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR PQ"
        else -> "SDR"
    }
}

internal fun readTrackCodecs(context: Context, uri: Uri): Pair<String, String> = runCatching {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(context, uri, null)
        var video = "Video —"
        var audio = "Audio —"
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).orEmpty()
            when {
                mime.startsWith("video/") -> video = codecLabel(mime)
                mime.startsWith("audio/") -> audio = codecLabel(mime)
            }
        }
        video to audio
    } finally {
        extractor.release()
    }
}.getOrDefault("Video —" to "Audio —")

private fun loadVideoThumbnail(context: Context, uri: Uri): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 29) context.contentResolver.loadThumbnail(uri, android.util.Size(420, 280), null)
    else {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally { runCatching { retriever.release() } }
    }
}.getOrNull()
