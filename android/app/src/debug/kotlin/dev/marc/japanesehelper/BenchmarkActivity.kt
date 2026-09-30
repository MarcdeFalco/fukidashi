package dev.marc.japanesehelper

import ai.onnxruntime.OrtEnvironment
import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import dev.marc.japanesehelper.core.BubbleDetector
import dev.marc.japanesehelper.core.MangaOcr
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Test de vitesse sur l'appareil, lancé depuis le Mac (voir android/bench.sh) :
 *
 *   adb shell am start -n dev.marc.japanesehelper/.BenchmarkActivity \
 *     --es backend npu --es enc_backend npu --es dec_backend cpu --ei threads 6 \
 *     --es det <chemin .onnx> --es ocr <dossier encodeur/décodeur>
 *
 * Sans --es det / --es ocr : modèles et réglages de l'appli (OcrEngine).
 *
 * Données dans files/bench/ (dossier interne, rempli par bench.sh via run-as) :
 * page.jpg, les images de test dans ocr/ et ocr/expected_results.json.
 * Résultats : logcat (tag Bench) et bench/result.json.
 */
class BenchmarkActivity : Activity() {
    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(32, 96, 32, 32); textSize = 12f }
        setContentView(ScrollView(this).apply { addView(out) })
        thread(name = "bench") {
            val result = runCatching { run() }.getOrElse { e ->
                Log.e(TAG, "Échec", e)
                JSONObject().put("error", e.toString())
            }
            File(filesDir, "bench/result.json").writeText(result.toString(2))
            log("RESULT ${result}")
            log("FIN")
        }
    }

    private fun run(): JSONObject {
        val x = intent.extras ?: Bundle()
        val backend = x.getString("backend", "cpu")
        fun backendOf(key: String) = Backend.valueOf(x.getString(key, backend).uppercase())
        val threads = x.getInt("threads", 4)
        val runs = x.getInt("runs", 5)
        val detBackend = backendOf("det_backend")
        val encBackend = backendOf("enc_backend")
        val decBackend = backendOf("dec_backend")

        // Modèles de l'appli (copie + compilation NPU au 1er lancement, chronométrées)
        val engine = OcrEngine(this@BenchmarkActivity)
        val tLoad = System.nanoTime()
        runBlocking { engine.load { log("statut : $it") } }
        val appLoadMs = ms(tLoad)
        val detFile = File(x.getString("det") ?: "(appli)")
        val ocrDir = File(x.getString("ocr") ?: "(appli)")
        val benchDir = File(filesDir, "bench")

        val config = JSONObject()
            .put("det", "${detFile.path} [$detBackend]")
            .put("enc", "${ocrDir.path}/${MangaOcr.ENCODER_FILE} [$encBackend]")
            .put("dec", "${ocrDir.path}/${MangaOcr.DECODER_FILE} [$decBackend]")
            .put("threads", threads)
        log("CONFIG $config")

        // Réglages QNN supplémentaires : --es qnn "clé=valeur,clé=valeur"
        val qnnExtra = x.getString("qnn").orEmpty().split(',').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        val useCache = x.getBoolean("npu_cache", true)
        config.put("qnn", JSONObject(qnnExtra as Map<*, *>)).put("npu_cache", useCache)
        fun session(file: File, b: Backend) =
            if (b == Backend.NPU && useCache) Accelerators.npuModel(this, file, threads, qnnExtra).let { it.file to it.options }
            else file to Accelerators.options(this, b, threads, qnnExtra)

        val env = OrtEnvironment.getEnvironment()
        val result = JSONObject().put("config", config).put("app_load_ms", appLoadMs)

        // --- Détection ---
        if (x.getBoolean("skip_det", false).not()) {
            val t0 = System.nanoTime()
            val detector = if (x.containsKey("det")) {
                val (detModel, detOptions) = session(detFile, detBackend)
                BubbleDetector(detModel, env, detOptions)
            } else {
                engine.detector!!
            }
            result.put("det_load_ms", ms(t0))
            val page = runBlocking { PageSource.open(this@BenchmarkActivity, listOf(Uri.fromFile(File(benchDir, "page.jpg")))).load(0) }
            detector.detectBitmap(page) // chauffe
            val times = mutableListOf<Double>()
            var zones = 0
            repeat(runs) {
                val t = System.nanoTime()
                zones = detector.detectBitmap(page).size
                times += ms(t)
                log("det ${"%.0f".format(times.last())} ms ${detector.lastTimings}")
            }
            val steps = JSONObject(detector.lastTimings.ms as Map<*, *>)
            result.put("det_ms_median", times.sorted()[times.size / 2]).put("det_zones", zones).put("det_steps", steps)
            if (x.containsKey("det")) detector.close()
        }

        // --- OCR ---
        val t0 = System.nanoTime()
        val ocr = if (x.containsKey("ocr")) {
            val (encModel, encOptions) = session(File(ocrDir, MangaOcr.ENCODER_FILE), encBackend)
            val (decModel, decOptions) = session(File(ocrDir, MangaOcr.DECODER_FILE), decBackend)
            MangaOcr(encModel, decModel, File(ocrDir, "vocab.txt"), env, encOptions, decOptions)
        } else {
            engine.ocr!!
        }
        result.put("ocr_load_ms", ms(t0))
        val expected = JSONArray(File(benchDir, "ocr/expected_results.json").readText())
        val images = (0 until expected.length()).map { i ->
            val item = expected.getJSONObject(i)
            val bmp = BitmapFactory.decodeFile(File(benchDir, "ocr/${item.getString("filename")}").path)
            Triple(item.getString("filename"), bmp.toRgbImage(), item.getString("result"))
        }
        ocr.recognize(images[0].second) // chauffe
        var correct = 0
        var total = 0.0
        var enc = 0.0
        var dec = 0.0
        var steps = 0.0
        val texts = JSONObject()
        for ((name, img, want) in images) {
            val t = System.nanoTime()
            val text = ocr.recognize(img)
            val took = ms(t)
            total += took
            enc += ocr.lastTimings.ms["encodeur"] ?: 0.0
            dec += ocr.lastTimings.ms["décodeur"] ?: 0.0
            steps += ocr.lastTimings.ms["étapes"] ?: 0.0
            if (text == want) correct++
            texts.put(name, text)
            log("ocr $name ${"%.0f".format(took)} ms ${ocr.lastTimings} ${if (text == want) "✓" else "✗ $text"}")
        }
        if (x.containsKey("ocr")) ocr.close()
        val n = images.size
        return result
            .put("ocr_ms_avg", total / n)
            .put("ocr_encoder_ms_avg", enc / n)
            .put("ocr_decoder_ms_avg", dec / n)
            .put("ocr_decoder_ms_per_step", dec / steps)
            .put("ocr_correct", "$correct/$n")
            .put("ocr_texts", texts)
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    private fun log(line: String) {
        Log.i(TAG, line)
        runOnUiThread { out.append(line + "\n") }
    }

    companion object {
        private const val TAG = "Bench"
    }
}
