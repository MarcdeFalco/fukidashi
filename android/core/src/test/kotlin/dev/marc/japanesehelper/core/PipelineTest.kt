package dev.marc.japanesehelper.core

import org.json.JSONArray
import java.io.File
import javax.imageio.ImageIO
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compare le pipeline Kotlin aux résultats des scripts Python (export/validate_*.py).
 * Nécessite les modèles générés par export/export_models.py.
 */
class PipelineTest {
    private val modelsDir = File(System.getProperty("models.dir"))
    private val repoDir = File(System.getProperty("repo.dir"))
    private val mangaOcrTests = File(System.getProperty("user.home"), "dev/manga-ocr/tests/data")

    private fun load(file: File, rotate90: Boolean = false): RgbImage {
        val img = ImageIO.read(file)
        val px = img.getRGB(0, 0, img.width, img.height, null, 0, img.width)
        if (!rotate90) return RgbImage(img.width, img.height, px)
        // Rotation de 90° dans le sens horaire
        val w = img.height
        val h = img.width
        return RgbImage(w, h, IntArray(w * h) { i -> px[(img.height - 1 - i % w) * img.width + i / w] })
    }

    @Test
    fun postProcessMatchesJaconv() {
        assertEquals("ガギパヴアー。「」、・ﾞﾟ", TextPostProcess.halfToFullWidth("ｶﾞｷﾞﾊﾟｳﾞｱｰ｡｢｣､･ﾞﾟ"))
        assertEquals("ＬＩＮＫ！７人．．．", TextPostProcess.apply("LINK! 7人…"))
    }

    @Test
    fun ocrMatchesPythonReference() {
        val expected = JSONArray(File(mangaOcrTests, "expected_results.json").readText())
        // Seul écart connu du modèle int8, identique en Python (validate_ocr.py)
        val int8Exceptions = mapOf("01.jpg" to "立川で見た、穴への下の巨大な眼は．．．")

        MangaOcr(File(modelsDir, "manga_ocr_cached_int8")).use { ocr ->
            for (i in 0 until expected.length()) {
                val item = expected.getJSONObject(i)
                val name = item.getString("filename")
                val text: String
                val ms = measureTimeMillis { text = ocr.recognize(load(File(mangaOcrTests, "images/$name"))) }
                println("$name (${ms} ms) : $text")
                assertEquals(int8Exceptions[name] ?: item.getString("result"), text, name)
            }
        }
    }

    @Test
    fun detectorFindsBubblesOnPhoto() {
        // test_ocr.jpg a l'orientation EXIF 6 : rotation de 90° à appliquer
        val page = load(File(repoDir, "test_ocr.jpg"), rotate90 = true)
        BubbleDetector(File(modelsDir, "bubble_detector_int8.onnx")).use { detector ->
            MangaOcr(File(modelsDir, "manga_ocr_cached_int8")).use { ocr ->
                val dets: List<Detection>
                val ms = measureTimeMillis { dets = detector.detect(page) }
                println("Détection : ${dets.size} zones en $ms ms")
                val texts = dets.map { d -> ocr.recognize(page.crop(d.box)).also { println("[%.2f %s] %s".format(d.score, d.kind, it)) } }
                assertEquals(9, dets.size)
                assertTrue("義経が身を寄せた奥州平泉は" in texts)
                assertTrue("中尊寺金色堂" in texts)
            }
        }
    }
}
