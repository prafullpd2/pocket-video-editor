@file:androidx.annotation.OptIn(androidx.camera.camera2.interop.ExperimentalCamera2Interop::class)

package app.pocketvideotoolkit

import android.Manifest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.heifwriter.HeifWriter
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.AspectRatio
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCaptureCapabilities
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.HdrOn
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class CameraLensOption(val id: String, val label: String, val focalLengthMm: Float, val zoomRatio: Float)
private enum class CameraPhotoFormat { JPEG, HEIC }

@Composable
internal fun UltraHdrCameraPage(activity: ComponentActivity, onExit: () -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var lensOptions by remember { mutableStateOf(emptyList<CameraLensOption>()) }
    var selectedPhysicalLens by remember { mutableStateOf<String?>(null) }
    var usingVendorHdr by remember { mutableStateOf(false) }
    var ultraHdrSupported by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var photoFormat by remember { mutableStateOf(CameraPhotoFormat.JPEG) }
    var showFormatInfo by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var zoomState by remember { mutableStateOf<androidx.camera.core.ZoomState?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var lastCameraInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var cameraPausedForIdle by remember { mutableStateOf(false) }
    var cameraRestartKey by remember { mutableIntStateOf(0) }
    val latestCameraProvider by rememberUpdatedState(cameraProvider)

    SideEffect {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).hide(WindowInsetsCompat.Type.systemBars())
    }
    DisposableEffect(activity) {
        onDispose {
            latestCameraProvider?.unbindAll()
            WindowCompat.setDecorFitsSystemWindows(activity.window, true)
            WindowInsetsControllerCompat(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler(onBack = onExit)

    LaunchedEffect(hasCameraPermission, previewView, useFrontCamera, selectedPhysicalLens, photoFormat, cameraRestartKey) {
        if (!hasCameraPermission) return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        runCatching {
            val mainExecutor = ContextCompat.getMainExecutor(activity)
            val provider = ProcessCameraProvider.getInstance(activity).await(mainExecutor)
            cameraProvider = provider
            val extensions = runCatching { ExtensionsManager.getInstanceAsync(activity, provider).await(mainExecutor) }.getOrNull()
            val facing = if (useFrontCamera) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
            val logicalSelector = CameraSelector.Builder().requireLensFacing(facing).build()

            if (!useFrontCamera && selectedPhysicalLens == null) {
                lensOptions = discoverPhysicalLenses(provider.getCameraInfo(logicalSelector))
            } else if (useFrontCamera) {
                lensOptions = emptyList()
            }

            val hdrAvailable = selectedPhysicalLens == null && !useFrontCamera && extensions?.let {
                runCatching { it.isExtensionAvailable(logicalSelector, ExtensionMode.HDR) }.getOrDefault(false)
            } == true
            val selector = when {
                hdrAvailable && selectedPhysicalLens == null && extensions != null -> runCatching {
                    extensions.getExtensionEnabledCameraSelector(logicalSelector, ExtensionMode.HDR)
                }.getOrDefault(logicalSelector)
                else -> logicalSelector
            }
            val cameraInfo = provider.getCameraInfo(selector)
            val capabilities: ImageCaptureCapabilities = ImageCapture.getImageCaptureCapabilities(cameraInfo)
            val canUltraHdr = photoFormat == CameraPhotoFormat.JPEG && Build.VERSION.SDK_INT >= 34 && ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in capabilities.supportedOutputFormats
            val captureBuilder = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                // Match the native camera's standard full-frame still-photo ratio.
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .setOutputFormat(if (canUltraHdr) ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR else ImageCapture.OUTPUT_FORMAT_JPEG)
            val previewBuilder = Preview.Builder()
            val capture = captureBuilder.build()
            val preview = previewBuilder.build().also { it.setSurfaceProvider(view.surfaceProvider) }
            provider.unbindAll()
            val boundCamera = try {
                provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            } catch (error: Exception) {
                if (selectedPhysicalLens == null) throw error
                selectedPhysicalLens = null
                message = "That physical lens could not be opened with this camera session. Returning to automatic lens selection."
                return@runCatching
            }
            camera = boundCamera
            imageCapture = capture
            usingVendorHdr = hdrAvailable
            ultraHdrSupported = canUltraHdr
            zoomState = boundCamera.cameraInfo.zoomState.value
            val selectedLens = lensOptions.firstOrNull { it.id == selectedPhysicalLens }
            val availableZoom = zoomState
            val requestedZoom = selectedLens?.zoomRatio
            if (requestedZoom != null && availableZoom != null) {
                val appliedZoom = requestedZoom.coerceIn(availableZoom.minZoomRatio, availableZoom.maxZoomRatio)
                // Samsung's CameraX session returned the main sensor for physical
                // selector IDs, so request the selected field of view through the
                // logical camera and let its HAL switch sensors at optical cutovers.
                boundCamera.cameraControl.setZoomRatio(appliedZoom).await(mainExecutor)
                zoom = appliedZoom
            } else {
                zoom = availableZoom?.minZoomRatio ?: 1f
            }
            message = when {
                selectedLens != null -> "${selectedLens.label} framing · camera chooses sensor"
                hdrAvailable && canUltraHdr -> "Multi-frame HDR + Ultra HDR ready"
                hdrAvailable -> "Multi-frame HDR photo mode ready"
                canUltraHdr -> "Ultra HDR capture ready"
                else -> "Standard photo capture ready"
            }
        }.onFailure {
            camera = null
            imageCapture = null
            message = it.message ?: "Could not start this camera. Try another lens or camera."
        }
    }

    LaunchedEffect(camera, lastCameraInteraction, busy) {
        if (camera == null || busy) return@LaunchedEffect
        val remaining = (30_000L - (System.currentTimeMillis() - lastCameraInteraction)).coerceAtLeast(0L)
        kotlinx.coroutines.delay(remaining)
        if (camera != null && !busy) {
            cameraProvider?.unbindAll()
            camera = null
            imageCapture = null
            cameraPausedForIdle = true
            message = "Camera paused after 30 seconds without activity."
        }
    }

    val currentZoomState = zoomState
    val fallbackZoomStops = currentZoomState?.let { state ->
        listOf(state.minZoomRatio, 1f, 2f, 3f, 5f).distinct().filter { it >= state.minZoomRatio && it <= state.maxZoomRatio }
    }.orEmpty()

    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.any { it.pressed }) lastCameraInteraction = System.currentTimeMillis()
            }
        }
    }) {
        if (!hasCameraPermission) {
            Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Camera access is needed to take photos.", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) { Text("Allow camera access") }
                TextButton(onClick = onExit) { Text("Back", color = Color.White) }
            }
        } else {
            if (cameraPausedForIdle) {
                Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Camera paused to save power", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text("It closed after 30 seconds without activity.", color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = {
                        cameraPausedForIdle = false
                        lastCameraInteraction = System.currentTimeMillis()
                        cameraRestartKey++
                    }) { Text("Resume camera") }
                    TextButton(onClick = onExit) { Text("Close camera", color = Color.White) }
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
                // Keep the live framing identical to the saved 4:3 still instead of
                // filling the tall phone display and cropping the sides of the preview.
                val portrait = maxHeight >= maxWidth
                val previewWidth = if (portrait) minOf(maxWidth, maxHeight * .75f) else minOf(maxWidth, maxHeight * (4f / 3f))
                val previewHeight = if (portrait) previewWidth * (4f / 3f) else previewWidth * .75f
                AndroidView(
                    factory = { context -> PreviewView(context).also { it.scaleType = PreviewView.ScaleType.FILL_CENTER; previewView = it } },
                    modifier = Modifier.align(Alignment.Center).size(previewWidth, previewHeight).pointerInput(camera) {
                        detectTapGestures { point ->
                            val preview = previewView ?: return@detectTapGestures
                            val meteringPoint = preview.meteringPointFactory.createPoint(point.x, point.y)
                            camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(meteringPoint).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
                        }
                    },
                    update = { previewView = it }
                )
            }

            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CameraRoundButton(onClick = onExit, description = "Back") { Icon(Icons.Rounded.ArrowBack, null, tint = Color.White) }
                    Surface(color = Color.Black.copy(alpha = .52f), shape = MaterialTheme.shapes.large) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Rounded.HdrOn, null, tint = Color.White)
                    Text(when { ultraHdrSupported -> "Ultra HDR"; usingVendorHdr -> "Multi-frame HDR"; else -> "Standard" }, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    CameraRoundButton(onClick = {
                        flashEnabled = !flashEnabled
                        imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
                    }, description = if (flashEnabled) "Turn flash off" else "Turn flash on") {
                        Icon(if (flashEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, null, tint = Color.White)
                    }
                }

                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (message.isNotBlank()) Text(message, color = Color.White, style = MaterialTheme.typography.labelSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                        FilterChip(
                            selected = photoFormat == CameraPhotoFormat.JPEG,
                            onClick = { photoFormat = CameraPhotoFormat.JPEG },
                            label = { Text("JPEG · Ultra HDR") }
                        )
                        Spacer(Modifier.width(6.dp))
                        FilterChip(
                            selected = photoFormat == CameraPhotoFormat.HEIC,
                            onClick = { photoFormat = CameraPhotoFormat.HEIC },
                            enabled = Build.VERSION.SDK_INT >= 28,
                            label = { Text("HEIC · smaller") }
                        )
                        IconButton(onClick = { showFormatInfo = true }, modifier = Modifier.size(38.dp)) {
                            Icon(Icons.Rounded.Info, contentDescription = "Photo format information", tint = Color.White)
                        }
                    }
                    if (lensOptions.isNotEmpty() && !useFrontCamera) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                            CameraLensChip("Auto", selectedPhysicalLens == null, { selectedPhysicalLens = null })
                            lensOptions.forEach { lens -> CameraLensChip(lens.label, selectedPhysicalLens == lens.id, { selectedPhysicalLens = lens.id }) }
                        }
                    } else if (!useFrontCamera && fallbackZoomStops.size > 1) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.Center) {
                            fallbackZoomStops.forEach { stop ->
                                val name = when {
                                    stop < .9f -> "${String.format("%.1f", stop)}×"
                                    stop < 1.1f -> "1×"
                                    else -> "${String.format("%.0f", stop)}×"
                                }
                                CameraLensChip(name, kotlin.math.abs(zoom - stop) < .08f, { zoom = stop; camera?.cameraControl?.setZoomRatio(stop) })
                            }
                        }
                    } else if (useFrontCamera && currentZoomState != null && currentZoomState.maxZoomRatio > 1f) {
                        Text("${String.format("%.1f", zoom)}×", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }

                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
                        Spacer(Modifier.size(54.dp))
                        Box(Modifier.size(82.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).background(Color.White, CircleShape)) {
                        Surface(onClick = { takeCameraPhoto(activity, imageCapture, photoFormat, ultraHdrSupported) { loading, text -> busy = loading; message = text } }, modifier = Modifier.fillMaxSize(), enabled = !busy && camera != null, shape = CircleShape, color = Color.White.copy(alpha = if (busy) .55f else 1f)) {}
                        }
                        CameraRoundButton(onClick = { useFrontCamera = !useFrontCamera; selectedPhysicalLens = null; camera = null }, description = "Switch front or rear camera") {
                            Icon(Icons.Rounded.FlipCameraAndroid, null, tint = Color.White)
                        }
                    }
                    Text("Tap to focus · hold steady for HDR", color = Color.White.copy(alpha = .82f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
    if (showFormatInfo) {
        AlertDialog(
            onDismissRequest = { showFormatInfo = false },
            title = { Text("Choose photo format") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("JPEG · Ultra HDR\nBest for HDR viewing and Instagram. Saves an HDR gain map when supported and opens in almost every app. Uses more storage; JPEG compression is still lossy.")
                    Text("HEIC · smaller\nUses less storage at similar visual quality, but is still compressed. This app converts the camera JPEG, so the extra encode can slightly reduce detail and camera metadata. It saves in standard dynamic range without the Ultra HDR gain map; some apps may not open HEIC.")
                }
            },
            confirmButton = { TextButton(onClick = { showFormatInfo = false }) { Text("Got it") } }
        )
    }
}

@Composable
private fun CameraRoundButton(onClick: () -> Unit, description: String, content: @Composable () -> Unit) {
    FilledIconButton(onClick = onClick, modifier = Modifier.size(46.dp).semantics { contentDescription = description }, shape = CircleShape, colors = IconButtonDefaults.filledIconButtonColors(containerColor = Color.Black.copy(alpha = .48f))) {
        content()
    }
}

@Composable
private fun CameraLensChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = if (selected) Color.White else Color.Black.copy(alpha = .48f), modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(label, Modifier.padding(horizontal = 15.dp, vertical = 10.dp), color = if (selected) Color(0xFF18211C) else Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
    }
}

private fun discoverPhysicalLenses(cameraInfo: androidx.camera.core.CameraInfo): List<CameraLensOption> {
    if (!cameraInfo.isLogicalMultiCameraSupported) return emptyList()
    val candidates = cameraInfo.physicalCameraInfos.mapNotNull { info ->
        runCatching {
            val camera2 = Camera2CameraInfo.from(info)
            val focal = camera2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.maxOrNull() ?: return@mapNotNull null
            val sensorWidth = camera2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width
                ?.takeIf { it > 0f } ?: return@mapNotNull null
            CameraLensOption(camera2.cameraId, "", focal, focal / sensorWidth)
        }.getOrNull()
    }.sortedBy { it.focalLengthMm }
    if (candidates.size < 2) return emptyList()
    // Compare field of view, not raw focal length: multi-camera modules have
    // different sensor sizes, so their millimeter focal lengths aren't comparable.
    val main = candidates.maxByOrNull { it.focalLengthMm / it.zoomRatio } ?: candidates[candidates.size / 2]
    val mainEquivalent = main.zoomRatio
    return candidates.map { lens ->
        val ratio = lens.zoomRatio / mainEquivalent
        // Use the phone's common optical switch points. Sensor-derived ratios
        // can land just below Samsung's 3×/5× cutovers (for example 2.9×),
        // which leaves CameraX on the main sensor and only digitally crops it.
        val zoom = when {
            ratio < .8f -> .6f
            ratio > 3.7f -> 5f
            ratio > 1.65f -> 3f
            else -> 1f
        }
        val name = when {
            ratio < .8f -> "Ultra-wide 0.6×"
            ratio > 3.7f -> "Periscope 5×"
            ratio > 1.65f -> "Telephoto 3×"
            else -> "Main 1×"
        }
        lens.copy(label = name, zoomRatio = zoom)
    }.distinctBy { (it.zoomRatio * 10).toInt() }.sortedBy { it.zoomRatio }
}

private fun takeCameraPhoto(activity: ComponentActivity, capture: ImageCapture?, format: CameraPhotoFormat, ultraHdr: Boolean, onState: (Boolean, String) -> Unit) {
    val imageCapture = capture ?: return
    if (format == CameraPhotoFormat.HEIC && Build.VERSION.SDK_INT < 28) {
        onState(false, "HEIC capture requires Android 9 or newer.")
        return
    }
    onState(true, "Capturing… keep steady")
    if (format == CameraPhotoFormat.HEIC) {
        val tempJpeg = File(activity.cacheDir, "pvt_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(tempJpeg).build()
        imageCapture.takePicture(options, ContextCompat.getMainExecutor(activity), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(result: ImageCapture.OutputFileResults) {
                Thread {
                    runCatching { transcodeJpegToHeic(activity, tempJpeg) }
                        .onSuccess { ContextCompat.getMainExecutor(activity).execute { onState(false, "HEIC photo saved (SDR)") } }
                        .onFailure { error -> ContextCompat.getMainExecutor(activity).execute { onState(false, error.message ?: "Could not save HEIC photo") } }
                }.start()
            }
            override fun onError(exception: ImageCaptureException) {
                tempJpeg.delete()
                onState(false, exception.message ?: "Could not capture the photo")
            }
        })
        return
    }
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "PVT_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pocket Video Toolkit")
    }
    val options = ImageCapture.OutputFileOptions.Builder(activity.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
    imageCapture.takePicture(options, ContextCompat.getMainExecutor(activity), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
            onState(false, if (ultraHdr) "4:3 Ultra HDR JPEG saved at maximum quality" else "4:3 JPEG saved at maximum quality")
        }
        override fun onError(exception: ImageCaptureException) {
            onState(false, exception.message ?: "Could not save the photo")
        }
    })
}

@androidx.annotation.RequiresApi(28)
private fun transcodeJpegToHeic(activity: ComponentActivity, jpeg: File) {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(jpeg.absolutePath, bounds)
    require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Could not read the captured image." }
    val decoded = BitmapFactory.decodeFile(jpeg.absolutePath)
        ?: throw IllegalStateException("Could not prepare the image for HEIC saving.")
    var oriented: Bitmap? = null
    var writer: HeifWriter? = null
    val tempHeic = File(activity.cacheDir, "pvt_${System.currentTimeMillis()}.heic")
    try {
        val orientation = ExifInterface(jpeg.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postScale(-1f, 1f); postRotate(270f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postScale(-1f, 1f); postRotate(90f) }
            }
        }
        oriented = if (!matrix.isIdentity) Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true) else decoded
        val bitmap = oriented ?: decoded
        writer = HeifWriter.Builder(tempHeic.absolutePath, bitmap.width, bitmap.height, HeifWriter.INPUT_MODE_BITMAP)
            .setQuality(100)
            .build()
        writer.start()
        writer.addBitmap(bitmap)
        writer.stop(0)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "PVT_${System.currentTimeMillis()}.heic")
            put(MediaStore.Images.Media.MIME_TYPE, "image/heic")
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pocket Video Toolkit")
        }
        val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Could not create the HEIC photo in Pictures.")
        activity.contentResolver.openOutputStream(uri)?.use { output -> tempHeic.inputStream().use { it.copyTo(output) } }
            ?: throw IllegalStateException("Could not write the HEIC photo.")
    } finally {
        runCatching { writer?.close() }
        if (oriented != null && oriented !== decoded) oriented?.recycle()
        decoded.recycle()
        jpeg.delete()
        tempHeic.delete()
    }
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(executor: Executor): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try { continuation.resume(get()) } catch (error: Throwable) { continuation.resumeWithException(error) }
    }, executor)
}
