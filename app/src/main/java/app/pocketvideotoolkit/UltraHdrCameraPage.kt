package app.pocketvideotoolkit

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private data class CameraLensOption(val id: String, val label: String, val focalLengthMm: Float)

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
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var zoomState by remember { mutableStateOf<androidx.camera.core.ZoomState?>(null) }
    var zoom by remember { mutableFloatStateOf(1f) }
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

    LaunchedEffect(hasCameraPermission, previewView, useFrontCamera, selectedPhysicalLens) {
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
                selectedPhysicalLens != null -> CameraSelector.Builder()
                    .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                    .setPhysicalCameraId(selectedPhysicalLens!!)
                    .build()
                hdrAvailable && extensions != null -> runCatching {
                    extensions.getExtensionEnabledCameraSelector(logicalSelector, ExtensionMode.HDR)
                }.getOrDefault(logicalSelector)
                else -> logicalSelector
            }
            val cameraInfo = provider.getCameraInfo(selector)
            val capabilities: ImageCaptureCapabilities = ImageCapture.getImageCaptureCapabilities(cameraInfo)
            val canUltraHdr = Build.VERSION.SDK_INT >= 34 && ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR in capabilities.supportedOutputFormats
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setJpegQuality(100)
                .setFlashMode(if (flashEnabled) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
                .setOutputFormat(if (canUltraHdr) ImageCapture.OUTPUT_FORMAT_JPEG_ULTRA_HDR else ImageCapture.OUTPUT_FORMAT_JPEG)
                .build()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
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
            zoom = zoomState?.minZoomRatio ?: 1f
            message = when {
                selectedPhysicalLens != null -> "${lensOptions.firstOrNull { it.id == selectedPhysicalLens }?.label ?: "Selected lens"} · ${if (canUltraHdr) "Ultra HDR ready" else "JPEG ready"}"
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

    val currentZoomState = zoomState
    val fallbackZoomStops = currentZoomState?.let { state ->
        listOf(state.minZoomRatio, 1f, 2f, 3f, 5f).distinct().filter { it >= state.minZoomRatio && it <= state.maxZoomRatio }
    }.orEmpty()

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!hasCameraPermission) {
            Column(Modifier.align(Alignment.Center).padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Camera access is needed to take photos.", color = Color.White, style = MaterialTheme.typography.titleMedium)
                Button(onClick = { permissionRequest.launch(Manifest.permission.CAMERA) }) { Text("Allow camera access") }
                TextButton(onClick = onExit) { Text("Back", color = Color.White) }
            }
        } else {
            AndroidView(
                factory = { context -> PreviewView(context).also { it.scaleType = PreviewView.ScaleType.FILL_CENTER; previewView = it } },
                modifier = Modifier.fillMaxSize().pointerInput(camera) {
                    detectTapGestures { point ->
                        val preview = previewView ?: return@detectTapGestures
                        val meteringPoint = preview.meteringPointFactory.createPoint(point.x, point.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(meteringPoint).setAutoCancelDuration(3, TimeUnit.SECONDS).build())
                    }
                },
                update = { previewView = it }
            )

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
                            Surface(onClick = { takeCameraPhoto(activity, imageCapture, ultraHdrSupported) { loading, text -> busy = loading; message = text } }, modifier = Modifier.fillMaxSize(), enabled = !busy && camera != null, shape = CircleShape, color = Color.White.copy(alpha = if (busy) .55f else 1f)) {}
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
            CameraLensOption(camera2.cameraId, "", focal)
        }.getOrNull()
    }.sortedBy { it.focalLengthMm }
    if (candidates.size < 2) return emptyList()
    val mainFocal = candidates.getOrNull(1)?.focalLengthMm ?: candidates.first().focalLengthMm
    return candidates.map { lens ->
        val ratio = lens.focalLengthMm / mainFocal
        val name = when {
            ratio < .78f -> "Ultra-wide"
            ratio > 3.6f -> "Periscope"
            ratio > 1.65f -> "Telephoto"
            else -> "Main"
        }
        lens.copy(label = name)
    }.distinctBy { it.label }.sortedBy { it.focalLengthMm }
}

private fun takeCameraPhoto(activity: ComponentActivity, capture: ImageCapture?, ultraHdr: Boolean, onState: (Boolean, String) -> Unit) {
    val imageCapture = capture ?: return
    onState(true, "Capturing… keep steady")
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "PVT_${System.currentTimeMillis()}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Pocket Video Toolkit")
    }
    val options = ImageCapture.OutputFileOptions.Builder(activity.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
    imageCapture.takePicture(options, ContextCompat.getMainExecutor(activity), object : ImageCapture.OnImageSavedCallback {
        override fun onImageSaved(result: ImageCapture.OutputFileResults) {
            onState(false, if (ultraHdr) "Ultra HDR photo saved" else "Standard JPEG saved")
        }
        override fun onError(exception: ImageCaptureException) {
            onState(false, exception.message ?: "Could not save the photo")
        }
    })
}

private suspend fun <T> com.google.common.util.concurrent.ListenableFuture<T>.await(executor: Executor): T = suspendCancellableCoroutine { continuation ->
    addListener({
        try { continuation.resume(get()) } catch (error: Throwable) { continuation.resumeWithException(error) }
    }, executor)
}
