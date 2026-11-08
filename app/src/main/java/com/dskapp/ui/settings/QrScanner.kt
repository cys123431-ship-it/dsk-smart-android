package com.dskapp.ui.settings

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Camera QR reader for the PC pairing code, shown full screen.
 *
 * Why full screen and not a block inside the settings column: that column scrolls, so its
 * height is unbounded, and an `aspectRatio` child under an unbounded height constraint throws
 * on layout - which is exactly how the first version of this screen crashed. A dialog always
 * has the window's height, so the preview gets a real size.
 *
 * CameraX hands frames over as YUV 4:2:0. A QR is black and white, so only the luma plane is
 * needed: it goes to zxing as a [PlanarYUVLuminanceSource] with no Bitmap round trip. Decoding
 * runs on one background thread, and [ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST] drops frames
 * that arrive while the previous one is still being read, so a slow phone never queues up.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        QrScannerContent(onResult = onResult, onCancel = onCancel)
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun QrScannerContent(
    onResult: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnResult by rememberUpdatedState(onResult)

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraReady by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (!granted) {
            cameraError = "카메라 권한이 없어 QR 을 찍을 수 없습니다.\n휴대폰 설정에서 DSK 스마트의 카메라 권한을 켜 주세요."
        }
    }
    LaunchedEffect(Unit) {
        if (!permissionGranted) launcher.launch(Manifest.permission.CAMERA)
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val decoder = remember { QrDecoder() }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(permissionGranted, lifecycleOwner, previewView) {
        if (!permissionGranted) return@DisposableEffect onDispose { }

        // The camera provider arrives asynchronously. If the screen is closed before it does,
        // binding then would touch a preview that is already gone, which takes the app down.
        var disposed = false
        var cameraProvider: ProcessCameraProvider? = null
        var analysis: ImageAnalysis? = null
        // CameraX does not expose whether an analyzer is still attached, so it is tracked here:
        // the camera is unbound when the app leaves the foreground and rebound on return.
        var bound = false
        val future = ProcessCameraProvider.getInstance(context)

        // Stop the camera when the app goes to the background, start it again on return.
        val observer = LifecycleEventObserver { _, event ->
            if (disposed) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_STOP -> runCatching {
                    analysis?.clearAnalyzer()
                    cameraProvider?.unbindAll()
                    bound = false
                    cameraReady = false
                }
                Lifecycle.Event.ON_START -> {
                    val provider = cameraProvider ?: return@LifecycleEventObserver
                    val imageAnalysis = analysis ?: return@LifecycleEventObserver
                    if (bound) {
                        cameraReady = true
                    } else {
                        bindCamera(provider, lifecycleOwner, previewView, imageAnalysis, decoder, analysisExecutor) { text ->
                            currentOnResult(text)
                        }.onSuccess {
                            bound = true
                            cameraReady = true
                        }.onFailure { e ->
                            cameraError = "카메라를 켜지 못했습니다: ${e.message ?: e::class.java.simpleName}"
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        future.addListener({
            if (disposed) return@addListener
            try {
                val provider = future.get()
                cameraProvider = provider
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(1280, 720))
                    .build()
                analysis = imageAnalysis
                bindCamera(provider, lifecycleOwner, previewView, imageAnalysis, decoder, analysisExecutor) { text ->
                    currentOnResult(text)
                }.onSuccess {
                    bound = true
                    cameraReady = true
                }.onFailure { e ->
                    cameraError = "카메라를 켜지 못했습니다: ${e.message ?: e::class.java.simpleName}"
                }
            } catch (e: Exception) {
                if (!disposed) {
                    cameraError = "카메라를 켜지 못했습니다: ${e.message ?: e::class.java.simpleName}"
                }
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            runCatching { lifecycleOwner.lifecycle.removeObserver(observer) }
            runCatching { analysis?.clearAnalyzer() }
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            decoder.close()
            analysisExecutor.shutdown()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Text(
                text = "PC 화면의 QR 을 사각형 안에 맞춰 주세요",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (permissionGranted) {
                    AndroidView(
                        factory = { previewView },
                        modifier = Modifier.fillMaxSize()
                    )
                }
                if (permissionGranted && !cameraReady) {
                    Text(
                        text = "카메라 준비 중…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White
                    )
                }
                // A frame that shows the user where to aim; the decoder reads the whole image.
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.7f)
                        .aspectRatio(1f)
                )
            }

            cameraError?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFF8A80),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }

            Button(
                onClick = onCancel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Text("닫기")
            }
        }
    }
}

/**
 * Binds preview + analysis and starts feeding frames to [onDecoded].
 *
 * The rear camera is preferred; a phone that has none (or refuses it) still scans with the
 * front one. Failures come back as a [Result] instead of throwing, so the screen can say what
 * went wrong rather than crashing.
 */
private fun bindCamera(
    provider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    previewView: PreviewView,
    analysis: ImageAnalysis,
    decoder: QrDecoder,
    executor: Executor,
    onDecoded: (String) -> Unit
): Result<Unit> = runCatching {
    val preview = Preview.Builder().build().also {
        it.surfaceProvider = previewView.surfaceProvider
    }
    analysis.setAnalyzer(executor) { image: ImageProxy ->
        val text = try {
            decoder.decode(image)
        } finally {
            image.close()
        }
        if (text != null) {
            analysis.clearAnalyzer()
            onDecoded(text)
        }
    }

    val hasRear = runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }
        .getOrDefault(false)
    val selector = if (hasRear) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA

    provider.unbindAll()
    provider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
}

/**
 * Turns one camera frame into QR text, or null when the frame holds no readable code.
 *
 * Rear cameras usually deliver frames rotated by 90 degrees; that is passed to zxing as a
 * rotated source instead of rotating pixels. Only QR codes are attempted, since asking for
 * every barcode format would triple the work per frame.
 */
private class QrDecoder {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(
                DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                DecodeHintType.TRY_HARDER to true
            )
        )
    }

    fun decode(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val source = PlanarYUVLuminanceSource(
            data,
            image.width,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false
        )
        val upright = when (image.imageInfo.rotationDegrees) {
            90 -> source.rotateCounterClockwise()
            180 -> source.rotateCounterClockwise().rotateCounterClockwise()
            270 -> source.rotateCounterClockwise().rotateCounterClockwise().rotateCounterClockwise()
            else -> source
        }
        return runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(upright))).text
        }.getOrNull().also { reader.reset() }
    }

    fun close() {
        reader.reset()
    }
}
