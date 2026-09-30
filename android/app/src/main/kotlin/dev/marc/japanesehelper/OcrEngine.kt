package dev.marc.japanesehelper

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import dev.marc.japanesehelper.core.BubbleDetector
import dev.marc.japanesehelper.core.Box as TextBox
import dev.marc.japanesehelper.core.Detection
import dev.marc.japanesehelper.core.MangaOcr
import dev.marc.japanesehelper.core.RgbImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

/**
 * Détection + OCR sur le téléphone. Un seul appel aux modèles à la fois.
 *
 * Détecteur et encodeur (taille fixe) sur le NPU, décodeur (taille variable) sur le CPU.
 * Sans NPU (émulateur, autre puce), tout passe sur le CPU, en plus lent.
 */
class OcrEngine(private val context: Context, private val forceCpu: Boolean = false) {
    private val mutex = Mutex()
    internal var detector: BubbleDetector? = null
        private set
    internal var ocr: MangaOcr? = null
        private set

    /** Charge les modèles ; [onStatus] reçoit les étapes longues (1re compilation NPU). */
    suspend fun load(onStatus: (String) -> Unit = {}) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (detector != null) return@withLock
            val env = OrtEnvironment.getEnvironment()
            val ms = measureTimeMillis {
                val (detFile, detOptions) = npuOrCpu("bubble_detector.onnx", onStatus)
                detector = BubbleDetector(detFile(), env, detOptions)
                val (encFile, encOptions) = npuOrCpu("manga_ocr/${MangaOcr.ENCODER_FILE}", onStatus)
                ocr = MangaOcr(
                    encFile(), copyAsset("manga_ocr/${MangaOcr.DECODER_FILE}"), copyAsset("manga_ocr/vocab.txt"), env,
                    encOptions, Accelerators.options(context, Backend.CPU, CPU_THREADS),
                )
            }
            Log.i(TAG, "Modèles chargés en $ms ms")
        }
    }

    /**
     * Prépare un modèle pour le NPU (compilation mise en cache), ou pour le CPU si le NPU
     * est indisponible. Renvoie de quoi ouvrir la session.
     */
    private fun npuOrCpu(asset: String, onStatus: (String) -> Unit): Pair<() -> File, OrtSession.SessionOptions> {
        val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        val key = asset.substringAfterLast('/').removeSuffix(".onnx") + "@$version"
        return try {
            if (forceCpu) error("CPU forcé")
            val model = Accelerators.npuModel(context, key, CPU_THREADS) {
                onStatus("Optimisation pour le NPU (première fois, ~1 min)…")
                copyAsset(asset)
            }
            if (!model.compiling) return Pair({ model.file }, model.options)
            // Compile maintenant pour savoir si le NPU fonctionne, puis libère la copie source
            OrtEnvironment.getEnvironment().createSession(model.file.path, model.options).close()
            if (model.compiled.exists()) model.file.delete()
            Log.i(TAG, "$asset compilé pour le NPU")
            val cached = Accelerators.npuModel(context, key, CPU_THREADS) { error("déjà compilé") }
            Pair({ cached.file }, cached.options)
        } catch (e: Exception) {
            Log.w(TAG, "NPU indisponible pour $asset, passage sur le CPU", e)
            Pair({ copyAsset(asset) }, Accelerators.options(context, Backend.CPU, CPU_THREADS))
        }
    }

    /** Copie un modèle des assets vers filesDir (ONNX Runtime veut un fichier), si besoin. */
    private fun copyAsset(path: String): File {
        val target = File(context.filesDir, "models/$path")
        val size = context.assets.openFd("models/$path").use { it.length }
        if (target.exists() && target.length() == size) return target
        target.parentFile?.mkdirs()
        context.assets.open("models/$path").use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    suspend fun detect(page: Bitmap): List<Detection> = timed("Détection") { detector!!.detectBitmap(page) }

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

    companion object {
        private const val TAG = "OcrEngine"

        /** Les 6 cœurs « performance » du Snapdragon 8 Elite (mesuré : mieux que 4 ou 8). */
        private const val CPU_THREADS = 6
    }
}

/**
 * Détection sur une page : réduction à 1024 px par Android (bien plus rapide que la
 * réduction exacte façon PIL, sans effet notable sur la détection), puis coordonnées
 * ramenées à la page d'origine.
 */
fun BubbleDetector.detectBitmap(page: Bitmap): List<Detection> {
    val scale = BubbleDetector.SIZE.toFloat() / maxOf(page.width, page.height)
    if (scale >= 1f) return detect(page.toRgbImage())
    val t0 = System.nanoTime()
    val small = Bitmap.createScaledBitmap(page, (page.width * scale).roundToInt(), (page.height * scale).roundToInt(), true)
    val rgb = small.toRgbImage()
    val reductionMs = (System.nanoTime() - t0) / 1e6
    val dets = detect(rgb)
    lastTimings.ms["réduction"] = reductionMs
    return dets.map { d ->
        d.copy(box = TextBox(d.box.left / scale, d.box.top / scale, d.box.right / scale, d.box.bottom / scale))
    }
}

fun Bitmap.toRgbImage(): RgbImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return RgbImage(width, height, px)
}
