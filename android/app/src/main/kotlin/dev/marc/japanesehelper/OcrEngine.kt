package dev.marc.japanesehelper

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.marc.japanesehelper.core.BubbleDetector
import dev.marc.japanesehelper.core.Detection
import dev.marc.japanesehelper.core.MangaOcr
import dev.marc.japanesehelper.core.RgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.system.measureTimeMillis

/** Détection + OCR sur le téléphone. Un seul appel aux modèles à la fois. */
class OcrEngine(private val context: Context) {
    private val mutex = Mutex()
    private var detector: BubbleDetector? = null
    private var ocr: MangaOcr? = null

    /** Copie les modèles des assets vers filesDir (ONNX Runtime veut un fichier) et les charge. */
    suspend fun load() = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (detector != null) return@withLock
            val dir = File(context.filesDir, "models")
            copyAssets("models", dir)
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val ms = measureTimeMillis {
                detector = BubbleDetector(File(dir, "bubble_detector.onnx"), env, options)
                ocr = MangaOcr(File(dir, "manga_ocr"), env, options)
            }
            Log.i(TAG, "Modèles chargés en $ms ms")
        }
    }

    suspend fun detect(page: Bitmap): List<Detection> = timed("Détection") { detector!!.detect(page.toRgbImage()) }

    suspend fun recognize(page: Bitmap, detection: Detection): String = timed("OCR") {
        val b = detection.box
        val x = b.left.toInt().coerceIn(0, page.width - 1)
        val y = b.top.toInt().coerceIn(0, page.height - 1)
        val crop = Bitmap.createBitmap(
            page, x, y,
            (b.right.toInt() - x).coerceIn(1, page.width - x),
            (b.bottom.toInt() - y).coerceIn(1, page.height - y),
        )
        ocr!!.recognize(crop.toRgbImage())
    }

    private suspend fun <T> timed(label: String, block: () -> T): T {
        load()
        return withContext(Dispatchers.Default) {
            mutex.withLock {
                val result: T
                val ms = measureTimeMillis { result = block() }
                Log.i(TAG, "$label : $ms ms")
                result
            }
        }
    }

    /** Copie récursive, en sautant les fichiers déjà présents avec la bonne taille. */
    private fun copyAssets(path: String, target: File) {
        val children = context.assets.list(path).orEmpty()
        if (children.isEmpty()) {
            val size = context.assets.openFd(path).use { it.length }
            if (target.exists() && target.length() == size) return
            target.parentFile?.mkdirs()
            context.assets.open(path).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        children.forEach { copyAssets("$path/$it", File(target, it)) }
    }

    companion object {
        private const val TAG = "OcrEngine"
    }
}

fun Bitmap.toRgbImage(): RgbImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return RgbImage(width, height, px)
}
