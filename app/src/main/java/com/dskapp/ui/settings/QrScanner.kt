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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

/**
 * Camera QR reader for the PC pairing code.
 *
 * CameraX delivers frames as YUV 4:2:0; a black-and-white QR only needs the luma plane, so
 * the frame is handed to zxing as a [PlanarYUVLuminanceSource] with no Bitmap conversion.
 * Decoding runs on one background thread and a frame is skipped while the previous one is
 * still being decoded, so a slow phone cannot build up a queue of stale frames.
 */
@SuppressLint("UnsafeOptInUsageError")
@Composable
fun QrScanner(
    onResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
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

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (!granted) {
            cameraError = "카메라 권한이 없어 QR 을 찍을 수 없습니다. 휴대폰 설정에서 DSK 스마트의 카메라 권한을 켜 주세요."
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

    DisposableEffect(permissionGranted, lifecycleOwner) {
        if (!permissionGranted) return@DisposableEffect onDispose { }
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            try {
                val cameraProvider = future.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(Size(1280, 720))
                    .build()
                analysis.setAnalyzer(analysisExecutor) { image: ImageProxy ->
                    val text = decoder.decode(image)
                    image.close()
                    if (text != null) {
                        analysis.clearAnalyzer()
                        currentOnResult(text)
                    }
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            } catch (e: Exception) {
                cameraError = "카메라를 켜지 못했습니다: ${e.message ?: e::class.java.simpleName}"
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { provider?.unbindAll() }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            decoder.close()
            analysisExecutor.shutdown()
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            if (permissionGranted) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = "PC 의 DSK-Remote 창에 있는 QR 을 화면 안에 맞춰 주세요",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }
        cameraError?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.Center
        ) {
            TextButton(onClick = onCancel) { Text("닫기") }
        }
    }
}

/**
 * Turns one camera frame into QR text, or null when the frame holds no readable code.
 *
 * Rear cameras usually deliver frames rotated by 90°, which is passed to zxing as a rotated
 * source instead of rotating pixels. Only QR codes are tried: asking for every barcode
 * format would triple the work per frame.
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
