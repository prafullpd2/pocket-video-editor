package app.pocketvideotoolkit

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCaptureCapabilities
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Composable
internal fun UltraHdrCameraPage(activity: ComponentActivity) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var usingVendorHdr by remember { mutableStateOf(false) }
    var ultraHdrSupported by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var flashEnabled by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var zoomState by remember { mutableStateOf<androidx.camera.core.ZoomState?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var exposureIndex by remember { mutableIntStateOf(0) }
    var exposureRange by remember { mutableStateOf(android.util.Range(0, 0)) }

    LaunchedEffect(hasCameraPermission, previewView, useFrontCamera) {
        if (!hasCameraPermission) return@LaunchedEffect
        val view = previewView ?: return@LaunchedEffect
        runCatching {
            val mainExecutor = ContextCompat.getMainExecutor(activity)
            val provider = ProcessCameraProvider.getInstance(activity).await(mainExecutor)
            val extensions = runCatching { ExtensionsManager.getInstanceAsync(activity, provider).await(mainExecutor) }.getOrNull()
            val baseSelector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            val hdrAvailable = extensions?.let { runCatching { it.isExtensionAvailable(baseSelector, ExtensionMode.HDR) }.getOrDefault(false) } == true
            val selector = if (hdrAvailable && extensions != null) {
                runCatching { extensions.getExtensionEnabledCameraSelector(baseSelector, ExtensionMode.HDR) }.getOrDefault(baseSelector)
            } else baseSelector
            val cameraInfo = provider.getCameraInfo(selector)
            val capabilities: ImageCaptureCapabilities = ImageCapture.getImageCaptureCapabilities(cameraInfo)
            val canUltraHdr = Build.VERSION.SDK_INT >= 34 && ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in capabilities.supportedOutputFormats
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .setOutputFormat(if (canUltraHdr) ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR else ImageCapture.OUTPUT_FORMAT_JPEG)
                .build()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            provider.unbindAll()
            val boundCamera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            camera = boundCamera
            imageCapture = capture
            usingVendorHdr = hdrAvailable
            ultraHdrSupported = canUltraHdr
            zoomState = boundCamera.cameraInfo.zoomState.value
            zoom = zoomState?.minZoomRatio ?: 1f
            exposureRange = boundCamera.cameraInfo.exposureState.exposureCompensationRange
            exposureIndex = boundCamera.cameraInfo.exposureState.exposureCompensationIndex.coerceIn(exposureRange.lower, exposureRange.upper)
            message = if (canUltraHdr) "Ultra HDR photo capture is supported on this camera." else "This camera only advertises standard JPEG capture."
        }.onFailure {
            camera = null
            imageCapture = null
            message = it.message ?: "Could not start the camera. Try another camera or restart the app."
        }
    }

    DisposableEffect(Unit) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(activity).get().unbindAll() } }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard("Ultra HDR Camera", "Take a full-resolution photo with the phone's camera processing. Hold still for a moment after tapping the shutter.") {
            if (!hasCameraPermission) {
                Text("Camera access is needed to show a live preview and take a photo.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) { Text("Allow camera access") }
            } else {
                Box(Modifier.fillMaxWidth().aspectRatio(.78f)) {
                    AndroidView(
                        factory = { context -> PreviewView(context).also { it.scaleType = PreviewView.ScaleType.FILL_CENTER; previewView = it } },
                        modifier = Modifier.fillMaxSize().pointerInput(camera) {
                            detectTapGestures { point ->
                                val view = previewView ?: return@detectTapGestures
                                val meteringPoint = view.meteringPointFactory.createPoint(point.x, point.y)
                                camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(meteringPoint).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
                            }
                        },
                        update = { previewView = it }
                    )
                    Surface(Modifier.align(Alignment.TopStart).padding(10.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .86f), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(if (ultraHdrSupported) "ULTRA HDR" else "STANDARD PHOTO", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            if (usingVendorHdr) Text("Camera HDR processing", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Text(
                    when {
                        usingVendorHdr && ultraHdrSupported -> "The manufacturer HDR photo mode and Ultra HDR JPEG are enabled. This is the Android camera extension available to apps; Samsung's Camera app may apply additional proprietary processing."
                        usingVendorHdr -> "The phone's HDR multi-frame photo mode is enabled. Ultra HDR JPEG output is not advertised by this camera."
                        ultraHdrSupported -> "Ultra HDR JPEG is enabled. The phone's separate multi-frame HDR extension is not available to this app."
                        else -> "Ultra HDR capture is not advertised by this camera. A standard JPEG will be saved instead."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Zoom · ${String.format("%.1f", zoom)}×", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    IconButton(onClick = {
                        useFrontCamera = !useFrontCamera
                        camera = null
                    }) { Icon(Icons.Rounded.FlipCameraAndroid, "Switch camera") }
                    IconButton(onClick = { flashEnabled = !flashEnabled; imageCapture?.flashMode = if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF }) {
                        Icon(if (flashEnabled) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "Toggle flash")
                    }
                }
                val currentZoom = zoomState
                if (currentZoom != null && currentZoom.maxZoomRatio > currentZoom.minZoomRatio) {
                    Slider(value = zoom.coerceIn(currentZoom.minZoomRatio, currentZoom.maxZoomRatio), onValueChange = { zoom = it; camera?.cameraControl?.setZoomRatio(it) }, valueRange = currentZoom.minZoomRatio..currentZoom.maxZoomRatio)
                }
                if (exposureRange.lower < exposureRange.upper) {
                    Text("Exposure · ${exposureIndex} steps", style = MaterialTheme.typography.labelLarge)
                    Slider(value = exposureIndex.toFloat(), onValueChange = { exposureIndex = it.toInt(); camera?.cameraControl?.setExposureCompensationIndex(exposureIndex) }, valueRange = exposureRange.lower.toFloat()..exposureRange.upper.toFloat())
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(onClick = { takeCameraPhoto(activity, imageCapture, ultraHdrSupported) { loading, text -> busy = loading; message = text } }, enabled = !busy && camera != null, modifier = Modifier.size(76.dp), shape = CircleShape) {
                        Text(if (busy) "…" else "●", style = MaterialTheme.typography.headlineLarge)
                    }
                }
                Text("Tap the preview to focus. HDR multi-frame capture may take longer; keep the phone steady.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun takeCameraPhoto(
    activity: ComponentActivity,
    capture: ImageCapture?,
    ultraHdr: Boolean,
    onState: (Boolean, String) -> Unit
) {
    val imageCapture = capture ?: return
    onState(true, "Capturing photo… keep the phone steady.")
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "PVT_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pocket Video Toolkit")
    }
    val options = ImageCapture.OutputFileOptions.Builder(activity.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
    imageCapture.takePicture(options, ContextCompat.getMainExecutor(activity), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
            onState(false, if (ultraHdr) "Ultra HDR photo saved to Pictures / Pocket Video Toolkit." else "Photo saved to Pictures / Pocket Video Toolkit as standard JPEG.")
        }
        override fun onError(exception: ImageCaptureException) {
            onState(false, exception.message ?: "Could not save the photo.")
        }
    })
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(executor: Executor): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try { continuation.resume(get()) } catch (error: Throwable) { continuation.resumeWithException(error) }
    }, executor)
}
