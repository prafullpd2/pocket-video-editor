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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.PhotoLibrary
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
import androidx.compose.ui.semantics.Role
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
    val pickVideo = rememberMediaSourcePicker(arrayOf("video/*")) { selectedUris ->
        val selected = selectedUris.firstOrNull()
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
            Button(onClick = pickVideo) { Text(if (uri == null) "Choose HDR video" else "Choose another video") }
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
    val pickPhoto = rememberMediaSourcePicker(arrayOf("image/*")) { selectedUris ->
        val selected = selectedUris.firstOrNull()
        uri = selected?.also { runCatching { activity.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        preview = selected?.let { decodeImage(activity, it, 1200) }
        isUltraHdr = if (Build.VERSION.SDK_INT >= 34) preview?.hasGainmap() == true else false
        message = if (selected == null) "" else if (isUltraHdr) "Ultra HDR gain map detected." else "This image does not appear to contain an Ultra HDR gain map."
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionCard("Ultra HDR photo → HDR video", "Create a short still-image video in HEVC Main10 HLG from an Ultra HDR JPEG.") {
            if (Build.VERSION.SDK_INT < 34) Text("Requires Android 14 or newer.", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = pickPhoto) { Text(if (uri == null) "Choose Ultra HDR photo" else "Choose another photo") }
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
    onFullScreenChange: (Boolean) -> Unit,
    initialUri: Uri? = null
) {
    var uri by remember(initialUri) { mutableStateOf(initialUri) }
    var bitmap by remember(initialUri) { mutableStateOf<Bitmap?>(null) }
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
    var sourceImageSize by remember { mutableStateOf<IntSize?>(null) }
    var sourceFileSize by remember { mutableStateOf<Long?>(null) }
    var showPhotoDetails by remember { mutableStateOf(false) }
    val picker = rememberMediaSourcePicker(arrayOf("image/*")) { selectedUris ->
        val selected = selectedUris.firstOrNull()
        uri = selected?.also { runCatching { activity.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        bitmap = selected?.let { decodeImage(activity, it, 1800) }
    }
    LaunchedEffect(initialUri) {
        if (initialUri != null) {
            uri = initialUri
            bitmap = withContext(Dispatchers.IO) { decodeImage(activity, initialUri, 1800) }
        } else {
            picker()
        }
    }
    LaunchedEffect(uri) {
        val selected = uri ?: run { sourceImageSize = null; sourceFileSize = null; return@LaunchedEffect }
        val dimensionsAndBytes = withContext(Dispatchers.IO) {
            val dimensions = runCatching {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                activity.contentResolver.openInputStream(selected)?.use { BitmapFactory.decodeStream(it, null, options) }
                if (options.outWidth > 0 && options.outHeight > 0) IntSize(options.outWidth, options.outHeight) else null
            }.getOrNull()
            val bytes = runCatching {
                activity.contentResolver.query(selected, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
                }
            }.getOrNull()
            dimensions to bytes
        }
        sourceImageSize = dimensionsAndBytes.first
        sourceFileSize = dimensionsAndBytes.second
    }
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
    LaunchedEffect(fullScreen, showViewerControls, showHdrAdjustments) {
        if (fullScreen && showViewerControls && !showHdrAdjustments) {
            delay(2600)
            showViewerControls = false
        }
    }
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val toggleOrientation = {
        if (isLandscape) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            if (fullScreen) onFullScreenChange(false)
        } else {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            onFullScreenChange(true)
        }
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
            if (image != null && isLandscape && hasGainMap) {
                HdrPhotoInfoOverlay(
                    gainBoost = bitmap?.gainmap?.ratioMax?.maxOrNull(),
                    hdrPreviewEnabled = hdrPreviewEnabled,
                    displaySupportsHdr = display?.isHdr == true,
                    displayHeadroom = displayHeadroom,
                    onClick = { showPhotoDetails = true },
                    modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
                )
            }
        if (showViewerControls) {
                Surface(
                    Modifier.align(Alignment.BottomEnd).padding(
                        horizontal = if (isLandscape) 24.dp else 12.dp,
                        vertical = if (isLandscape) 10.dp else 18.dp
                    ),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = .58f),
                    tonalElevation = 1.dp
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InfoHelpButton("Photo gestures", "Pinch to zoom. Double-tap the photo to reset zoom. Tap the photo to show or hide the controls.")
                        if (hasGainMap) HdrPreviewSwitch(hdrPreviewEnabled, { hdrPreviewEnabled = it })
                        if (hasGainMap) IconButton(onClick = { showHdrAdjustments = !showHdrAdjustments; showViewerControls = true }) { Icon(Icons.Rounded.Tune, "HDR adjustments") }
                        IconButton(onClick = toggleOrientation) { Icon(Icons.Rounded.ScreenRotation, if (isLandscape) "Rotate to portrait" else "Rotate to landscape") }
                        IconButton(onClick = { onFullScreenChange(false) }) { Icon(Icons.Rounded.Close, "Close full screen") }
                    }
                }
            }
            // Keep the adjustment panel independent of the transient toolbar. Opening it
            // pauses the toolbar's idle timer so the controls do not disappear mid-adjustment.
            if (image != null && hasGainMap && showHdrAdjustments) {
                HdrTuningControls(
                    autoExposure, { autoExposure = it },
                    manualExposureEv, { manualExposureEv = it },
                    highlightGuard, { highlightGuard = it },
                    highlightLimit, { highlightLimit = it },
                    currentExposureEv = if (autoExposure) autoExposureEv else manualExposureEv,
                    errorMessage = adjustmentError,
                    onClose = { showHdrAdjustments = false; showViewerControls = true },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                )
            }
        }
        if (showPhotoDetails && hasGainMap) {
            HdrPhotoDetailsDialog(
                sourceSize = sourceImageSize,
                fileSize = sourceFileSize,
                gainMap = bitmap?.gainmap,
                hdrPreviewEnabled = hdrPreviewEnabled,
                displaySupportsHdr = display?.isHdr == true,
                displayHeadroom = displayHeadroom,
                colorSpace = bitmap?.colorSpace?.name,
                onDismiss = { showPhotoDetails = false }
            )
        }
    } else Box(Modifier.fillMaxSize()) {
        if (bitmap != null) Image(
            (adjustedBitmap ?: bitmap!!).asImageBitmap(), "HDR photo",
            Modifier.fillMaxSize().graphicsLayer { scaleX = imageScale; scaleY = imageScale; translationX = imageOffset.x; translationY = imageOffset.y }.then(photoGestures),
            contentScale = ContentScale.Fit
        )
        else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.Photo, contentDescription = null, modifier = Modifier.size(42.dp), tint = MaterialTheme.colorScheme.primary)
            Text(if (uri == null) "View an HDR photo" else "This photo couldn't be previewed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Choose a photo to see it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FilledTonalButton(onClick = picker) { Icon(Icons.Rounded.FolderOpen, null); Spacer(Modifier.width(6.dp)); Text("Browse photos") }
        }
        if (bitmap != null && showViewerControls) {
            Surface(Modifier.align(Alignment.BottomCenter).padding(6.dp), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface.copy(alpha = .82f), tonalElevation = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    IconButton(onClick = picker, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.PhotoLibrary, "Choose another photo") }
                    if (hasGainMap) HdrPreviewSwitch(hdrPreviewEnabled, { hdrPreviewEnabled = it })
                    if (hasGainMap) IconButton(onClick = { showHdrAdjustments = !showHdrAdjustments }, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.Tune, if (showHdrAdjustments) "Hide HDR adjustments" else "Adjust HDR preview")
                    }
                    IconButton(onClick = toggleOrientation, modifier = Modifier.size(38.dp)) {
                        Icon(Icons.Rounded.ScreenRotation, if (isLandscape) "Rotate to portrait" else "Rotate to landscape")
                    }
                    IconButton(onClick = { onFullScreenChange(true) }, modifier = Modifier.size(38.dp)) { Icon(Icons.Rounded.OpenInFull, "Full screen") }
                    val gainBoost = if (Build.VERSION.SDK_INT >= 34 && hasGainMap) bitmap?.gainmap?.ratioMax?.maxOrNull() else null
                    InfoHelpButton(
                        "Photo preview",
                        when {
                            Build.VERSION.SDK_INT < 34 -> "Android does not expose Ultra HDR detection on this version. Pinch to zoom, double-tap to reset, and tap the photo to hide or show these controls."
                            !hasGainMap -> "Standard dynamic range · no Ultra HDR gain map detected. Pinch to zoom, double-tap to reset, and tap the photo to hide or show these controls."
                            display?.isHdr != true -> "Ultra HDR detected · this display reports no HDR output support. Source boost up to ${String.format("%.1f", gainBoost ?: 1f)}×."
                            !hdrPreviewEnabled -> "SDR preview · source HDR boost up to ${String.format("%.1f", gainBoost ?: 1f)}×."
                            else -> "HDR preview · source boost up to ${String.format("%.1f", gainBoost ?: 1f)}×${displayHeadroom?.let { " · display headroom ${String.format("%.1f", it)}×" } ?: ""}."
                        }
                    )
                }
            }
        }
        if (bitmap != null && isLandscape && hasGainMap) {
            HdrPhotoInfoOverlay(
                gainBoost = bitmap?.gainmap?.ratioMax?.maxOrNull(),
                hdrPreviewEnabled = hdrPreviewEnabled,
                displaySupportsHdr = display?.isHdr == true,
                displayHeadroom = displayHeadroom,
                onClick = { showPhotoDetails = true },
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
            )
        }
        if (showPhotoDetails && hasGainMap) {
            HdrPhotoDetailsDialog(
                sourceSize = sourceImageSize,
                fileSize = sourceFileSize,
                gainMap = bitmap?.gainmap,
                hdrPreviewEnabled = hdrPreviewEnabled,
                displaySupportsHdr = display?.isHdr == true,
                displayHeadroom = displayHeadroom,
                colorSpace = bitmap?.colorSpace?.name,
                onDismiss = { showPhotoDetails = false }
            )
        }
        if (bitmap != null && hasGainMap && showHdrAdjustments) HdrTuningControls(
            autoExposure, { autoExposure = it },
            manualExposureEv, { manualExposureEv = it },
            highlightGuard, { highlightGuard = it },
            highlightLimit, { highlightLimit = it },
            currentExposureEv = if (autoExposure) autoExposureEv else manualExposureEv,
            errorMessage = adjustmentError,
            onClose = { showHdrAdjustments = false },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp)
        )
    }
}

@Composable
private fun HdrPhotoInfoOverlay(
    gainBoost: Float?,
    hdrPreviewEnabled: Boolean,
    displaySupportsHdr: Boolean,
    displayHeadroom: Float?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .78f),
        tonalElevation = 2.dp
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Rounded.HdrOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(17.dp))
            Text(
                "Ultra HDR  ·  gain ${gainBoost?.let { "up to ${String.format(java.util.Locale.getDefault(), "%.1f", it)}×" } ?: "available"}  ·  ${if (hdrPreviewEnabled) "HDR preview" else "SDR preview"}  ·  ${if (displaySupportsHdr) "HDR display${displayHeadroom?.let { " · ${String.format(java.util.Locale.getDefault(), "%.1f", it)}× headroom" }.orEmpty()}" else "SDR display"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Icon(Icons.Rounded.Info, contentDescription = "Show all HDR photo details", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun HdrPhotoDetailsDialog(
    sourceSize: IntSize?,
    fileSize: Long?,
    gainMap: Gainmap?,
    hdrPreviewEnabled: Boolean,
    displaySupportsHdr: Boolean,
    displayHeadroom: Float?,
    colorSpace: String?,
    onDismiss: () -> Unit
) {
    fun range(values: FloatArray?): String = values?.joinToString(" / ") {
        String.format(java.util.Locale.getDefault(), "%.2f", it)
    } ?: "—"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("HDR photo details") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Format · Ultra HDR JPEG with gain map")
                Text("Image size · ${sourceSize?.let { "${it.width} × ${it.height}" } ?: "—"}")
                Text("File size · ${fileSize?.let(::formatPhotoFileSize) ?: "—"}")
                Text("Gain map ratio · min ${range(gainMap?.ratioMin)} · max ${range(gainMap?.ratioMax)}")
                Text("Color space · ${colorSpace ?: "—"}")
                Text("Preview · ${if (hdrPreviewEnabled) "HDR" else "SDR"}")
                Text("Display · ${if (displaySupportsHdr) "HDR capable" else "SDR / HDR output unavailable"}")
                Text("Display headroom · ${displayHeadroom?.let { String.format(java.util.Locale.getDefault(), "%.2f×", it) } ?: "not reported"}")
                Text("Gain-map detection and headroom are reported by Android and may vary by device/display.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

private fun formatPhotoFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.getDefault(), "%.1f MB", bytes / (1024f * 1024f))
    bytes >= 1024L -> String.format(java.util.Locale.getDefault(), "%.0f KB", bytes / 1024f)
    else -> "$bytes B"
}

@Composable
private fun HdrPreviewSwitch(enabled: Boolean, onEnabledChange: (Boolean) -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f)) {
        Row(Modifier.selectableGroup().padding(2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            listOf("SDR" to false, "HDR" to true).forEach { (label, value) ->
                val selected = enabled == value
                Surface(
                    modifier = Modifier.height(28.dp).selectable(selected = selected, role = Role.RadioButton) { onEnabledChange(value) },
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0f)
                ) {
                    Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
                        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
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
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val maxPanelHeight = (LocalConfiguration.current.screenHeightDp * .82f).dp
    Surface(
        modifier = modifier.widthIn(max = 340.dp).heightIn(max = maxPanelHeight),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = .94f),
        tonalElevation = 5.dp
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("HDR adjustments", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                InfoHelpButton("Preview only", "These controls change how this photo looks on your screen. They do not alter or save over the original photo.")
                Text("${if (currentExposureEv >= 0f) "+" else ""}${String.format("%.1f", currentExposureEv)} EV", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close HDR adjustments", modifier = Modifier.size(17.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onAutoExposureChange(!autoExposure) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (autoExposure) "Auto exposure · on" else "Auto exposure · off", style = MaterialTheme.typography.labelMedium)
                }
                InfoHelpButton("Auto exposure", "When on, the app estimates the photo's overall brightness and adjusts the preview. Turn it off to set exposure manually.")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { onHighlightGuardChange(!highlightGuard) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                    Text(if (highlightGuard) "Guard · on" else "Guard · off", style = MaterialTheme.typography.labelMedium)
                }
                InfoHelpButton("Highlight guard", "Limits the strongest HDR highlights to tame glare. Turn it off to keep the image's full highlight boost.")
            }
            if (!autoExposure) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Exposure · ${if (exposureEv >= 0f) "+" else ""}${String.format("%.1f", exposureEv)} EV", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    InfoHelpButton("Exposure", "Exposure changes overall brightness. +1 EV is about twice as bright; −1 EV is about half as bright.")
                }
                Slider(value = exposureEv, onValueChange = onExposureEvChange, valueRange = -2f..2f, modifier = Modifier.fillMaxWidth().height(30.dp))
            }
            if (highlightGuard) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Highlight limit · ${String.format("%.1f", highlightLimit)}×", Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    InfoHelpButton("Highlight limit", "Sets the maximum HDR brightness boost. Lower values reduce bright glare; higher values preserve stronger highlights.")
                }
                Slider(value = highlightLimit, onValueChange = onHighlightLimitChange, valueRange = 1.1f..6f, modifier = Modifier.fillMaxWidth().height(30.dp))
            }
            errorMessage?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun InfoHelpButton(title: String, message: String) {
    var showHelp by remember { mutableStateOf(false) }
    IconButton(onClick = { showHelp = true }, modifier = Modifier.size(30.dp)) {
        Icon(Icons.Rounded.Info, contentDescription = "About $title", modifier = Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (showHelp) AlertDialog(
        onDismissRequest = { showHelp = false },
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Got it") } }
    )
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
