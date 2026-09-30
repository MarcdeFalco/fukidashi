package dev.marc.japanesehelper.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marc.japanesehelper.BubbleText
import dev.marc.japanesehelper.CapturedPages
import dev.marc.japanesehelper.PageSource
import dev.marc.japanesehelper.ReaderViewModel
import dev.marc.japanesehelper.core.Box as TextBox
import dev.marc.japanesehelper.core.Detection
import dev.marc.japanesehelper.core.TextKind

private val BubbleColor = Color(0xFF4FC3F7)
private val FreeTextColor = Color(0xFFFFB74D)
private val SelectedColor = Color(0xFFFFEB3B)

@Composable
fun ReaderScreen(vm: ReaderViewModel, source: PageSource) {
    val pager = rememberPagerState { source.pageCount }
    val detections by vm.detections.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val modelsReady by vm.modelsReady.collectAsStateWithLifecycle()
    val loadingStatus by vm.loadingStatus.collectAsStateWithLifecycle()
    var zoomed by remember { mutableStateOf(false) }
    val jumpTo by vm.jumpTo.collectAsStateWithLifecycle()
    LaunchedEffect(jumpTo, source) {
        jumpTo?.let {
            pager.scrollToPage(it)
            vm.jumpDone()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Sens de lecture japonais : page suivante à gauche
        HorizontalPager(
            state = pager,
            reverseLayout = true,
            userScrollEnabled = !zoomed,
            beyondViewportPageCount = 1,
            key = { it },
        ) { page ->
            PageView(
                source = source,
                page = page,
                detections = detections[page].orEmpty(),
                selected = selection?.takeIf { it.page == page }?.detection,
                onLoaded = { vm.detect(page, it) },
                onTapDetection = { bitmap, det -> vm.read(page, bitmap, det) },
                onTapEmpty = vm::clearSelection,
                onZoomChanged = { if (page == pager.currentPage) zoomed = it },
            )
        }

        val status = when {
            !modelsReady -> loadingStatus
            detections[pager.currentPage] == null -> "Détection des bulles…"
            else -> "${detections[pager.currentPage]!!.size} zones"
        }
        Text(
            "${pager.currentPage + 1} / ${source.pageCount} · $status",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(8.dp)
                .background(Color.Black.copy(alpha = 0.6f), MaterialTheme.shapes.small)
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )

        // Livre papier : photographier la page suivante
        if (source is CapturedPages && selection == null) {
            Button(
                onClick = vm::openCamera,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp),
            ) { Text("Photographier la page suivante") }
        }

        selection?.let { sel ->
            BubblePanel(
                text = sel.text,
                onClose = vm::clearSelection,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun BubblePanel(text: BubbleText, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
    ) {
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            when (text) {
                BubbleText.Reading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Lecture…")
                }
                is BubbleText.Done -> SelectionContainer {
                    Text(text.text, fontSize = 24.sp, lineHeight = 34.sp)
                }
                is BubbleText.Failed -> Text("Erreur : ${text.message}", color = MaterialTheme.colorScheme.error)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (text is BubbleText.Done) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text.text)) }) { Text("Copier") }
                }
                TextButton(onClick = onClose) { Text("Fermer") }
            }
        }
    }
}

/** Placement de l'image ajustée à l'écran (avant zoom). */
private class Fit(bitmap: Bitmap, container: IntSize) {
    val scale = minOf(container.width.toFloat() / bitmap.width, container.height.toFloat() / bitmap.height)
    val offset = Offset((container.width - bitmap.width * scale) / 2, (container.height - bitmap.height * scale) / 2)

    fun toContent(b: TextBox) = Offset(offset.x + b.left * scale, offset.y + b.top * scale) to Size(b.width * scale, b.height * scale)
    fun toImage(p: Offset) = Offset((p.x - offset.x) / scale, (p.y - offset.y) / scale)
}

@Composable
private fun PageView(
    source: PageSource,
    page: Int,
    detections: List<Detection>,
    selected: Detection?,
    onLoaded: (Bitmap) -> Unit,
    onTapDetection: (Bitmap, Detection) -> Unit,
    onTapEmpty: () -> Unit,
    onZoomChanged: (Boolean) -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, source, page) { value = runCatching { source.load(page) }.getOrNull() }
    LaunchedEffect(bitmap) { bitmap?.let(onLoaded) }

    var size by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val currentDetections by rememberUpdatedState(detections)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)

    // écran = centre + (contenu - centre) * zoom + pan
    fun center() = Offset(size.width / 2f, size.height / 2f)
    fun screenToContent(p: Offset) = (p - center() - pan) / zoom + center()
    fun setZoom(newZoom: Float, newPan: Offset) {
        zoom = newZoom.coerceIn(1f, 5f)
        val maxX = size.width * (zoom - 1) / 2
        val maxY = size.height * (zoom - 1) / 2
        pan = Offset(newPan.x.coerceIn(-maxX, maxX), newPan.y.coerceIn(-maxY, maxY))
        currentOnZoomChanged(zoom > 1f)
    }
    /** Zoom autour d'un point fixe de l'écran. */
    fun zoomAround(focus: Offset, newZoom: Float, extraPan: Offset = Offset.Zero) {
        val q = screenToContent(focus)
        val z = newZoom.coerceIn(1f, 5f)
        setZoom(z, focus - center() - (q - center()) * z + extraPan)
    }

    val image = remember(bitmap) { bitmap?.asImageBitmap() }
    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                pinchZoom(
                    isZoomed = { zoom > 1f },
                    onGesture = { centroid, panChange, zoomChange -> zoomAround(centroid, zoom * zoomChange, panChange) },
                )
            }
            .pointerInput(bitmap) {
                val bmp = bitmap ?: return@pointerInput
                detectTapGestures(
                    onDoubleTap = { p -> if (zoom > 1f) setZoom(1f, Offset.Zero) else zoomAround(p, 2.5f) },
                    onTap = { p ->
                        val pt = Fit(bmp, size).toImage(screenToContent(p))
                        val hit = currentDetections.filter { it.box.contains(pt.x, pt.y) }.minByOrNull { it.box.area }
                        if (hit != null) onTapDetection(bmp, hit) else onTapEmpty()
                    },
                )
            },
    ) {
        val bmp = bitmap ?: return@Canvas
        val img = image ?: return@Canvas
        val fit = Fit(bmp, size)
        translate(pan.x, pan.y) {
            scale(zoom, pivot = center) {
                drawImage(
                    img,
                    dstOffset = IntOffset(fit.offset.x.toInt(), fit.offset.y.toInt()),
                    dstSize = IntSize((bmp.width * fit.scale).toInt(), (bmp.height * fit.scale).toInt()),
                    filterQuality = FilterQuality.Medium,
                )
                for (det in detections) {
                    val (topLeft, rectSize) = fit.toContent(det.box)
                    val color = when {
                        det == selected -> SelectedColor
                        det.kind == TextKind.BUBBLE -> BubbleColor
                        else -> FreeTextColor
                    }
                    drawRect(color.copy(alpha = if (det == selected) 0.3f else 0.12f), topLeft, rectSize)
                    drawRect(color, topLeft, rectSize, style = Stroke(width = 2.dp.toPx() / zoom))
                }
            }
        }
    }
}

/**
 * Pincer pour zoomer ; glisser pour se déplacer seulement si déjà zoomé.
 * À zoom 1, un doigt seul n'est pas consommé : le pager peut tourner la page.
 */
private suspend fun PointerInputScope.pinchZoom(
    isZoomed: () -> Boolean,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float) -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    do {
        val event = awaitPointerEvent()
        val fingers = event.changes.count { it.pressed }
        if (fingers >= 2 || isZoomed()) {
            val zoomChange = event.calculateZoom()
            val panChange = event.calculatePan()
            if (zoomChange != 1f || panChange != Offset.Zero) {
                onGesture(event.calculateCentroid(), panChange, zoomChange)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        }
    } while (event.changes.any { it.pressed })
}
