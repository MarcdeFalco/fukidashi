package dev.marc.japanesehelper.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

enum class TextKind { BUBBLE, FREE }

data class Detection(val box: Box, val score: Float, val kind: TextKind)

/**
 * Détecteur de zones de texte YOLO en ONNX. Reproduit export/validate_detector.py :
 * letterbox 1024x1024 (gris 114) -> YOLO -> seuil -> NMS par classe.
 *
 * Non thread-safe : un appel à la fois.
 */
class BubbleDetector(
    modelFile: File,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
    options: OrtSession.SessionOptions = OrtSession.SessionOptions(),
    private val confidence: Float = 0.3f,
    private val iouThreshold: Float = 0.45f,
) : Closeable {
    private val session = env.createSession(modelFile.path, options)

    /** Durées du dernier appel à [detect]. */
    var lastTimings = Timings()
        private set

    fun detect(image: RgbImage): List<Detection> {
        val t = Timings()
        lastTimings = t
        val prepStart = System.nanoTime()
        val scale = SIZE.toFloat() / max(image.width, image.height)
        val nw = (image.width * scale).roundToInt().coerceIn(1, SIZE)
        val nh = (image.height * scale).roundToInt().coerceIn(1, SIZE)
        val padX = (SIZE - nw) / 2
        val padY = (SIZE - nh) / 2

        val plane = SIZE * SIZE
        val input = FloatArray(3 * plane) { 114f / 255f }
        image.channels().forEachIndexed { c, channel ->
            val resized = PilResize.bilinear(channel, nw, nh)
            for (y in 0 until nh) for (x in 0 until nw) {
                input[c * plane + (y + padY) * SIZE + x + padX] = resized[x, y] / 255f
            }
        }

        t.ms["prétraitement"] = (System.nanoTime() - prepStart) / 1e6
        val raw = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            t.measure("modèle") { session.run(mapOf(session.inputNames.first() to tensor)) }.use { result ->
                val buf = (result[0] as OnnxTensor).floatBuffer
                FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
        // Sortie [1, 4 + nbClasses, N] : cx, cy, w, h puis un score par classe
        val n = raw.size / (4 + CLASSES)
        val candidates = mutableListOf<Detection>()
        for (i in 0 until n) {
            var best = 0
            for (c in 1 until CLASSES) if (raw[(4 + c) * n + i] > raw[(4 + best) * n + i]) best = c
            val score = raw[(4 + best) * n + i]
            if (score < confidence) continue
            val cx = raw[i]
            val cy = raw[n + i]
            val w = raw[2 * n + i]
            val h = raw[3 * n + i]
            // Retour aux coordonnées de l'image d'origine
            val box = Box(
                ((cx - w / 2 - padX) / scale).coerceIn(0f, image.width.toFloat()),
                ((cy - h / 2 - padY) / scale).coerceIn(0f, image.height.toFloat()),
                ((cx + w / 2 - padX) / scale).coerceIn(0f, image.width.toFloat()),
                ((cy + h / 2 - padY) / scale).coerceIn(0f, image.height.toFloat()),
            )
            candidates += Detection(box, score, TextKind.entries[best])
        }
        return t.measure("nms") { nms(candidates).sortedByDescending { it.score } }
    }

    /** NMS par classe, comme ultralytics par défaut. */
    private fun nms(dets: List<Detection>): List<Detection> =
        dets.groupBy { it.kind }.values.flatMap { group ->
            val remaining = group.sortedByDescending { it.score }.toMutableList()
            val kept = mutableListOf<Detection>()
            while (remaining.isNotEmpty()) {
                val best = remaining.removeAt(0)
                kept += best
                remaining.removeAll { it.box.iou(best.box) >= iouThreshold }
            }
            kept
        }

    override fun close() = session.close()

    companion object {
        const val SIZE = 1024
        private const val CLASSES = 2
    }
}
