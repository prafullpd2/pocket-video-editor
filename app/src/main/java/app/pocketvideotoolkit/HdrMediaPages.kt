package app.pocketvideotoolkit

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.graphics.Paint
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    var displayAspect by remember { mutableFloatStateOf(16f / 9f) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected ->
        uri = selected?.also { runCatching { activity.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        duration = 0L
        position = 0f
        message = ""
    }
    LaunchedEffect(uri) {
        val selected = uri ?: return@LaunchedEffect
        displayAspect = withContext(Dispatchers.IO) {
            runCatching {
                MediaMetadataRetriever().let { retriever ->
                    try {
                        retriever.setDataSource(activity, selected)
                        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: 0f
                        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: 0f
                        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                        if (width <= 0 || height <= 0) 16f / 9f else if (rotation % 180 != 0) height / width else width / height
                    } finally { retriever.release() }
                }
            }.getOrDefault(16f / 9f)
        }
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
            Button(onClick = { pickVideo.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }) { Text(if (uri == null) "Choose HDR video" else "Choose another video") }
            if (uri != null) {
                BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val previewWidth = minOf(maxWidth, 320.dp * displayAspect)
                    val previewHeight = previewWidth / displayAspect
                    AndroidView(
                        factory = { context -> SurfaceView(context).also { view ->
                            view.holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(surface: SurfaceHolder) { holder = surface }
                                override fun surfaceChanged(surface: SurfaceHolder, format: Int, width: Int, height: Int) { holder = surface }
                                override fun surfaceDestroyed(surface: SurfaceHolder) { if (holder === surface) holder = null }
                            })
                        } },
                        modifier = Modifier.width(previewWidth).height(previewHeight)
                    )
                }
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
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected ->
        uri = selected?.also { runCatching { activity.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        preview = selected?.let { decodeImage(activity, it, 1200) }
        isUltraHdr = if (Build.VERSION.SDK_INT >= 34) preview?.hasGainmap() == true else false
        message = if (selected == null) "" else if (isUltraHdr) "Ultra HDR gain map detected." else "This image does not appear to contain an Ultra HDR gain map."
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("Ultra HDR photo → HDR video", "Create a short still-image video in HEVC Main10 HLG from an Ultra HDR JPEG.") {
            if (Build.VERSION.SDK_INT < 34) Text("Requires Android 14 or newer.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = { pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (uri == null) "Choose Ultra HDR photo" else "Choose another photo") }
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
internal fun UltraHdrPhotoViewerPage(
    activity: ComponentActivity,
    fullScreen: Boolean,
    onFullScreenChange: (Boolean) -> Unit
) {
    var uri by remember { mutableStateOf<Uri?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var adjustedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var hdrPreviewEnabled by remember { mutableStateOf(true) }
    var autoExposure by remember { mutableStateOf(true) }
    var highlightGuard by remember { mutableStateOf(true) }
    var manualExposureEv by remember { mutableFloatStateOf(0f) }
    var highlightLimit by remember { mutableFloatStateOf(1.7f) }
    var showHdrAdjustments by remember { mutableStateOf(false) }
    var adjustmentError by remember { mutableStateOf<String?>(null) }
    var autoExposureEv by remember { mutableFloatStateOf(0f) }
    var showViewerControls by remember { mutableStateOf(true) }
    var imageScale by remember { mutableFloatStateOf(1f) }
    var imageOffset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var imageViewport by remember { mutableStateOf(IntSize.Zero) }
    val hasGainMap = Build.VERSION.SDK_INT >= 34 && bitmap?.hasGainmap() == true
    val display = activity.window.decorView.display
    val configuration = LocalConfiguration.current
    val originalOrientation = remember(activity) { activity.requestedOrientation }
    var displayHeadroom by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(bitmap) {
        val source = bitmap
        autoExposureEv = if (source != null && Build.VERSION.SDK_INT >= 34 && source.hasGainmap()) {
            withContext(Dispatchers.Default) { estimateViewerExposureEv(source) }
        } else 0f
    }
    LaunchedEffect(bitmap, autoExposure, autoExposureEv, highlightGuard, manualExposureEv, highlightLimit) {
        val source = bitmap
        adjustedBitmap = source
        adjustmentError = null
        if (source == null || Build.VERSION.SDK_INT < 34 || !source.hasGainmap()) return@LaunchedEffect
        delay(100)
        val result = withContext(Dispatchers.Default) {
            runCatching {
                val originalGainmap = source.gainmap ?: return@runCatching source
                val exposureEv = if (autoExposure) autoExposureEv else manualExposureEv
                val exposureScale = Math.pow(2.0, exposureEv.toDouble()).toFloat()
                val oldMax = originalGainmap.ratioMax
                val oldMin = originalGainmap.ratioMin
                val newMax = FloatArray(3) { index ->
                    val original = oldMax.getOrElse(index) { oldMax.lastOrNull() ?: 1f }
                    if (highlightGuard) original.coerceAtMost(highlightLimit) else original
                }
                val newMin = FloatArray(3) { index -> minOf(oldMin.getOrElse(index) { oldMin.lastOrNull() ?: 1f }, newMax[index]) }
                val config = if (source.colorSpace?.isWideGamut == true) Bitmap.Config.RGBA_F16 else Bitmap.Config.ARGB_8888
                val sourceColorSpace = source.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
                val baseCopy = Bitmap.createBitmap(source.width, source.height, config, true, sourceColorSpace)
                val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                    colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(exposureScale, exposureScale, exposureScale, 1f) })
                }
                Canvas(baseCopy).drawBitmap(source, 0f, 0f, paint)
                val changedGainmap = Gainmap(originalGainmap, originalGainmap.gainmapContents)
                changedGainmap.setRatioMin(newMin[0], newMin[1], newMin[2])
                changedGainmap.setRatioMax(newMax[0], newMax[1], newMax[2])
                baseCopy.gainmap = changedGainmap
                baseCopy
            }
        }
        adjustedBitmap = result.getOrElse { adjustmentError = it.message ?: "Could not apply the HDR preview controls."; source }
    }
    LaunchedEffect(hasGainMap, hdrPreviewEnabled) {
        activity.window.colorMode = if (hasGainMap && hdrPreviewEnabled && display?.isHdr == true) android.content.pm.ActivityInfo.COLOR_MODE_HDR else android.content.pm.ActivityInfo.COLOR_MODE_DEFAULT
        displayHeadroom = if (Build.VERSION.SDK_INT >= 34 && display?.isHdrSdrRatioAvailable == true) display.hdrSdrRatio else null
    }
    SideEffect {
        WindowCompat.setDecorFitsSystemWindows(activity.window, !fullScreen)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            if (fullScreen) {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    DisposableEffect(activity) {
        onDispose {
            activity.window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = originalOrientation
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { selected ->
        uri = selected?.also { runCatching { activity.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        bitmap = selected?.let { decodeImage(activity, it, 1800) }
    }
    val image = adjustedBitmap ?: bitmap
    LaunchedEffect(uri) { imageScale = 1f; imageOffset = androidx.compose.ui.geometry.Offset.Zero }
    fun boundedImageOffset(scale: Float, offset: androidx.compose.ui.geometry.Offset): androidx.compose.ui.geometry.Offset {
        val currentImage = image ?: return androidx.compose.ui.geometry.Offset.Zero
        if (imageViewport == IntSize.Zero || scale <= 1f) return androidx.compose.ui.geometry.Offset.Zero
        val fit = minOf(imageViewport.width.toFloat() / currentImage.width, imageViewport.height.toFloat() / currentImage.height)
        val maxX = ((currentImage.width * fit * scale - imageViewport.width) / 2f).coerceAtLeast(0f)
        val maxY = ((currentImage.height * fit * scale - imageViewport.height) / 2f).coerceAtLeast(0f)
        return androidx.compose.ui.geometry.Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
    val photoGestures = Modifier
        .onSizeChanged { imageViewport = it }
        .pointerInput(image) {
            detectTransformGestures { centroid, pan, zoom, _ ->
                val nextScale = (imageScale * zoom).coerceIn(1f, 6f)
                val center = androidx.compose.ui.geometry.Offset(imageViewport.width / 2f, imageViewport.height / 2f)
                val nextOffset = (imageOffset + centroid - center) * zoom + pan - (centroid - center)
                imageScale = nextScale
                imageOffset = boundedImageOffset(nextScale, nextOffset)
            }
        }
        .pointerInput(image) {
            detectTapGestures(
                onTap = { showViewerControls = !showViewerControls },
                onDoubleTap = { imageScale = 1f; imageOffset = androidx.compose.ui.geometry.Offset.Zero }
            )
        }
    LaunchedEffect(fullScreen, showViewerControls) {
        if (fullScreen && showViewerControls && !showHdrAdjustments) {
            delay(2600)
            showViewerControls = false
        }
    }
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val toggleOrientation = {
        activity.requestedOrientation = if (isLandscape) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    }
    if (fullScreen) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (image != null) Image(
                image.asImageBitmap(), "HDR photo",
                Modifier.fillMaxSize()
                    .graphicsLayer { scaleX = imageScale; scaleY = imageScale; translationX = imageOffset.x; translationY = imageOffset.y }
                    .then(photoGestures),
                contentScale = ContentScale.Fit
            )
            else Text("Open a photo to view it full screen", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (showViewerControls) {
                Surface(Modifier.align(Alignment.TopEnd).padding(10.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface.copy(alpha = .78f), tonalElevation = 3.dp) {
                    Row {
                        IconButton(onClick = toggleOrientation) { Icon(Icons.Rounded.ScreenRotation, if (isLandscape) "Rotate to portrait" else "Rotate to landscape") }
                        if (hasGainMap) IconButton(onClick = { showHdrAdjustments = !showHdrAdjustments; showViewerControls = true }) { Icon(Icons.Rounded.Tune, "HDR adjustments") }
                        IconButton(onClick = { onFullScreenChange(false) }) { Icon(Icons.Rounded.Close, "Close full screen") }
                    }
                }
                if (image != null && hasGainMap && showHdrAdjustments) {
                    HdrTuningControls(
                        autoExposure, { autoExposure = it },
                        manualExposureEv, { manualExposureEv = it },
                        highlightGuard, { highlightGuard = it },
                        highlightLimit, { highlightLimit = it },
                        currentExposureEv = if (autoExposure) autoExposureEv else manualExposureEv,
                        errorMessage = adjustmentError,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                    )
                } else if (image != null && hasGainMap) {
                    Surface(Modifier.align(Alignment.BottomCenter).padding(12.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface.copy(alpha = .78f), tonalElevation = 3.dp) {
                        Row(Modifier.padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(selected = hdrPreviewEnabled, onClick = { hdrPreviewEnabled = true }, label = { Text("HDR") })
                            FilterChip(selected = !hdrPreviewEnabled, onClick = { hdrPreviewEnabled = false }, label = { Text("SDR") })
                        }
                    }
                }
            }
        }
    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text(if (uri == null) "Open photo" else "Open another") }
            OutlinedButton(onClick = { onFullScreenChange(true) }, enabled = bitmap != null) { Text("Full screen") }
            OutlinedButton(onClick = toggleOrientation) { Text(if (isLandscape) "Portrait" else "Landscape") }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    (adjustedBitmap ?: bitmap!!).asImageBitmap(), "HDR photo",
                    Modifier.fillMaxSize().graphicsLayer { scaleX = imageScale; scaleY = imageScale; translationX = imageOffset.x; translationY = imageOffset.y }.then(photoGestures),
                    contentScale = ContentScale.Fit
                )
            } else Text("Choose a photo to preview it", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (bitmap != null) {
            if (hasGainMap) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Ultra HDR photo", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FilterChip(selected = hdrPreviewEnabled, onClick = { hdrPreviewEnabled = true }, label = { Text("HDR") })
                    FilterChip(selected = !hdrPreviewEnabled, onClick = { hdrPreviewEnabled = false }, label = { Text("SDR") })
                    TextButton(onClick = { showHdrAdjustments = !showHdrAdjustments }) {
                        Icon(Icons.Rounded.Tune, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (showHdrAdjustments) "Hide" else "Adjust")
                    }
                }
                if (showHdrAdjustments) HdrTuningControls(
                    autoExposure, { autoExposure = it },
                    manualExposureEv, { manualExposureEv = it },
                    highlightGuard, { highlightGuard = it },
                    highlightLimit, { highlightLimit = it },
                    currentExposureEv = if (autoExposure) autoExposureEv else manualExposureEv,
                    errorMessage = adjustmentError
                )
            }
            val gainBoost = if (Build.VERSION.SDK_INT >= 34 && hasGainMap) bitmap?.gainmap?.ratioMax?.maxOrNull() else null
            Text(
                when {
                    Build.VERSION.SDK_INT < 34 -> "Android does not expose Ultra HDR detection on this version."
                    !hasGainMap -> "Standard dynamic range · no Ultra HDR gain map detected."
                    display?.isHdr != true -> "Ultra HDR detected · display reports no HDR output support."
                    !hdrPreviewEnabled -> "SDR preview · source boost up to ${String.format("%.1f", gainBoost ?: 1f)}×."
                    else -> "HDR preview · source boost up to ${String.format("%.1f", gainBoost ?: 1f)}×${displayHeadroom?.let { " · display headroom ${String.format("%.1f", it)}×" } ?: ""}."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (hasGainMap) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (hasGainMap) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2
            )
        }
        Text("Pinch to zoom · double-tap in full screen to reset.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HdrTuningControls(
    autoExposure: Boolean,
    onAutoExposureChange: (Boolean) -> Unit,
    exposureEv: Float,
    onExposureEvChange: (Float) -> Unit,
    highlightGuard: Boolean,
    onHighlightGuardChange: (Boolean) -> Unit,
    highlightLimit: Float,
    onHighlightLimitChange: (Float) -> Unit,
    currentExposureEv: Float,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.widthIn(max = 340.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
        tonalElevation = 5.dp
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HDR adjustments", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("${if (currentExposureEv >= 0f) "+" else ""}${String.format("%.1f", currentExposureEv)} EV", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            Text("These controls change the on-screen preview only; they do not edit or save over your photo.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAutoExposureChange(!autoExposure) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (autoExposure) "Auto exposure · on" else "Auto exposure · off", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onHighlightGuardChange(!highlightGuard) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (highlightGuard) "Guard · on" else "Guard · off", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(if (autoExposure) "Auto estimates the photo's overall brightness and adjusts the preview. Turn it off to set exposure yourself." else "Exposure changes overall brightness: +1 EV is about twice as bright; −1 EV is about half.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!autoExposure) {
                Text("Exposure · ${if (exposureEv >= 0f) "+" else ""}${String.format("%.1f", exposureEv)} EV", style = MaterialTheme.typography.labelSmall)
                Slider(value = exposureEv, onValueChange = onExposureEvChange, valueRange = -2f..2f, modifier = Modifier.fillMaxWidth().height(30.dp))
            }
            if (highlightGuard) Text("Highlight limit caps the brightest HDR areas. Lower values tame glare; higher values keep stronger highlights.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (highlightGuard) {
                Text("Highlight limit · ${String.format("%.1f", highlightLimit)}×", style = MaterialTheme.typography.labelSmall)
                Slider(value = highlightLimit, onValueChange = onHighlightLimitChange, valueRange = 1.1f..6f, modifier = Modifier.fillMaxWidth().height(30.dp))
            }
            errorMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@androidx.annotation.RequiresApi(34)
private fun estimateViewerExposureEv(bitmap: Bitmap): Float {
    val stepX = (bitmap.width / 64).coerceAtLeast(1)
    val stepY = (bitmap.height / 64).coerceAtLeast(1)
    var sum = 0.0
    var count = 0
    var y = 0
    while (y < bitmap.height) {
        var x = 0
        while (x < bitmap.width) {
            val color = bitmap.getPixel(x, y)
            val luminance = (0.2126 * android.graphics.Color.red(color) + 0.7152 * android.graphics.Color.green(color) + 0.0722 * android.graphics.Color.blue(color)) / 255.0
            sum += luminance
            count++
            x += stepX
        }
        y += stepY
    }
    val average = (sum / count.coerceAtLeast(1)).coerceAtLeast(0.03)
    return (kotlin.math.ln(0.42 / average) / kotlin.math.ln(2.0)).toFloat().coerceIn(-1f, 1f)
}

private fun decodeImage(activity: ComponentActivity, uri: Uri, maxDimension: Int): Bitmap? = runCatching {
    if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(activity.contentResolver, uri)) { decoder, details, _ ->
        // Software pixels are needed to meter exposure; the default hardware allocator is not CPU-readable.
        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
        val size = details.size
        val scale = minOf(1f, maxDimension.toFloat() / maxOf(size.width, size.height))
        decoder.setTargetSize((size.width * scale).toInt().coerceAtLeast(1), (size.height * scale).toInt().coerceAtLeast(1))
    } else activity.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
}.getOrNull()
