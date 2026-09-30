package dev.marc.japanesehelper.ui

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marc.japanesehelper.SteadinessMonitor
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Photo d'une page de livre : aperçu stabilisé, déclenchement automatique quand le
 * téléphone est immobile (gyroscope), ou manuel. [onCaptured] reçoit le JPEG (avec EXIF).
 */
@Composable
fun CameraScreen(outputDir: File, onCaptured: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) askPermission.launch(Manifest.permission.CAMERA) }

    if (!granted) {
        Column(
            Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("L'accès à la caméra est nécessaire pour photographier une page.", color = Color.White)
            Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Autoriser") }
            TextButton(onClick = onClose) { Text("Retour") }
        }
        return
    }
    CameraContent(outputDir, onCaptured, onClose)
}

private suspend fun cameraProvider(context: android.content.Context): ProcessCameraProvider =
    suspendCancellableCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({ cont.resume(future.get()) }, ContextCompat.getMainExecutor(context))
    }

@Composable
private fun CameraContent(outputDir: File, onCaptured: (File) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER // toute la zone photographiée est visible
        }
    }
    val imageCapture = remember {
        ImageCapture.Builder()
            // Traitement multi-image du téléphone : texte plus net, au prix d'un peu de latence
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(true) }
    var capturing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Pas de déclenchement auto juste à l'ouverture : le temps de viser et de faire la mise au point
    var aimed by remember { mutableStateOf(false) }

    val monitor = remember { SteadinessMonitor(context) }
    val steadiness by monitor.steadiness.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        monitor.start()
        onDispose { monitor.stop() }
    }

    LaunchedEffect(Unit) {
        val provider = cameraProvider(context)
        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val previewBuilder = Preview.Builder()
        // Stabilisation de l'aperçu (EIS/OIS) si la caméra la propose
        val stabilized = Preview.getPreviewCapabilities(provider.getCameraInfo(selector)).isStabilizationSupported
        if (stabilized) previewBuilder.setPreviewStabilizationEnabled(true)
        Log.i("CameraScreen", "Stabilisation de l'aperçu : ${if (stabilized) "activée" else "non disponible"}")
        val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        kotlinx.coroutines.delay(AIM_DELAY_MS)
        monitor.reset()
        aimed = true
    }
    DisposableEffect(Unit) {
        onDispose { ProcessCameraProvider.getInstance(context).get().unbindAll() }
    }

    fun capture() {
        if (capturing) return
        capturing = true
        outputDir.mkdirs()
        val name = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(Date())
        val file = File(outputDir, "page_$name.jpg")
        imageCapture.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    onCaptured(file)
                }

                override fun onError(e: ImageCaptureException) {
                    Log.e("CameraScreen", "Photo impossible", e)
                    capturing = false
                    monitor.reset()
                    error = "Photo impossible : ${e.message}"
                }
            },
        )
    }

    // Déclenchement automatique dès que le téléphone est immobile
    LaunchedEffect(steadiness >= 1f, auto, aimed) {
        if (steadiness >= 1f && auto && aimed) capture()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(camera) {
                    // Toucher pour faire la mise au point à cet endroit
                    detectTapGestures { p ->
                        val point = previewView.meteringPointFactory.createPoint(p.x, p.y)
                        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
                        monitor.reset()
                    }
                },
        )

        // En haut : fermer, lampe, déclenchement auto
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChipButton("✕ Fermer", active = false, onClick = onClose)
            Row {
                if (camera?.cameraInfo?.hasFlashUnit() == true) {
                    ChipButton("Lampe", active = torch) {
                        torch = !torch
                        camera?.cameraControl?.enableTorch(torch)
                    }
                }
                ChipButton("Auto", active = auto) {
                    auto = !auto
                    monitor.reset()
                }
            }
        }

        // En bas : état + déclencheur entouré de la jauge d'immobilité
        Column(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val hint = when {
                error != null -> error!!
                capturing -> "Photo… ne bougez pas"
                !monitor.available -> "Appuyez pour photographier"
                auto && !aimed -> "Visez la page…"
                auto && steadiness > 0f -> "Tenez immobile…"
                auto -> "Visez la page et tenez le téléphone immobile"
                else -> "Appuyez pour photographier"
            }
            Text(
                hint,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
            Box(Modifier.padding(top = 16.dp).size(84.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 6.dp.toPx()
                    drawCircle(Color.White.copy(alpha = 0.3f), radius = size.minDimension / 2 - stroke / 2, style = Stroke(stroke))
                    drawArc(
                        color = if (steadiness >= 1f || capturing) Color(0xFF66BB6A) else Color(0xFFFFEB3B),
                        startAngle = -90f,
                        sweepAngle = 360f * if (capturing) 1f else if (aimed) steadiness else 0f,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                Box(
                    Modifier
                        .size(62.dp)
                        .background(if (capturing) Color.Gray else Color.White, CircleShape)
                        .clickable(enabled = !capturing && camera != null) { capture() },
                )
            }
        }
    }
}

private const val AIM_DELAY_MS = 1500L

@Composable
private fun ChipButton(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (active) Color.Black else Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .padding(4.dp)
            .background(if (active) Color(0xFFFFEB3B) else Color.Black.copy(alpha = 0.5f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}
