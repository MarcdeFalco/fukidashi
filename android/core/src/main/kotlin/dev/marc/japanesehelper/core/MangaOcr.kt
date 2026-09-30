package dev.marc.japanesehelper.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * manga-ocr (ViT + BERT) en ONNX. Reproduit export/validate_ocr.py :
 * gris -> 224x224 bilinéaire PIL -> [-1, 1] -> encodeur -> recherche en faisceau.
 *
 * Non thread-safe : un appel à la fois.
 */
class MangaOcr(
    modelDir: File,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
    options: OrtSession.SessionOptions = OrtSession.SessionOptions(),
) : Closeable {
    private val encoder = env.createSession(File(modelDir, "encoder_model.onnx").path, options)
    private val decoder = env.createSession(File(modelDir, "decoder_model.onnx").path, options)
    private val vocab = File(modelDir, "vocab.txt").readLines(Charsets.UTF_8)

    fun recognize(image: RgbImage): String {
        val hidden = encode(preprocess(image))
        val ids = beamSearch(hidden)
        return TextPostProcess.apply(detokenize(ids))
    }

    internal fun preprocess(image: RgbImage): FloatArray {
        val gray = PilResize.bilinear(image.toGray(), SIZE, SIZE)
        val plane = SIZE * SIZE
        val out = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val v = ((gray.data[i].toInt() and 0xFF) / 255f - 0.5f) / 0.5f
            out[i] = v
            out[plane + i] = v
            out[2 * plane + i] = v
        }
        return out
    }

    /** Sortie de l'encodeur : [197 * 768]. */
    private fun encode(pixels: FloatArray): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            encoder.run(mapOf("pixel_values" to input)).use { result ->
                val buf = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(buf.remaining()).also { buf.get(it) }
            }
        }
    }

    /** log-softmax de la dernière position pour chaque faisceau : [nbFaisceaux][vocab]. */
    private fun decodeStep(beams: List<IntArray>, hidden: FloatArray): Array<FloatArray> {
        val b = beams.size
        val len = beams[0].size
        val ids = LongArray(b * len) { beams[it / len][it % len].toLong() }
        val hiddenBatch = FloatArray(b * hidden.size).also { for (i in 0 until b) hidden.copyInto(it, i * hidden.size) }
        val encLen = (hidden.size / HIDDEN).toLong()
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), longArrayOf(b.toLong(), len.toLong())).use { idsT ->
            OnnxTensor.createTensor(env, FloatBuffer.wrap(hiddenBatch), longArrayOf(b.toLong(), encLen, HIDDEN.toLong())).use { hT ->
                decoder.run(mapOf("input_ids" to idsT, "encoder_hidden_states" to hT)).use { result ->
                    val logits = (result[0] as OnnxTensor).floatBuffer
                    val v = vocab.size
                    return Array(b) { i ->
                        val row = FloatArray(v)
                        logits.position((i * len + len - 1) * v)
                        logits.get(row)
                        logSoftmax(row)
                    }
                }
            }
        }
    }

    private class Beam(val tokens: IntArray, val score: Float)

    /** Recherche en faisceau façon HF generate (num_beams=4, length_penalty=2, early_stopping). */
    internal fun beamSearch(hidden: FloatArray): IntArray {
        var beams = listOf(Beam(intArrayOf(START), 0f))
        val finished = mutableListOf<Pair<Double, IntArray>>()
        repeat(MAX_LEN - 1) {
            val logp = decodeStep(beams.map { it.tokens }, hidden)
            for ((i, beam) in beams.withIndex()) {
                for (t in bannedTokens(beam.tokens)) logp[i][t] = Float.NEGATIVE_INFINITY
                for (t in logp[i].indices) logp[i][t] += beam.score
            }
            val candidates = topK(logp, 2 * NUM_BEAMS)
            val next = mutableListOf<Beam>()
            for ((rank, cand) in candidates.withIndex()) {
                val (bi, tok) = cand
                val score = logp[bi][tok]
                val toks = beams[bi].tokens
                if (tok == EOS) {
                    // HF ignore un EOS hors des num_beams meilleurs candidats
                    if (rank < NUM_BEAMS) finished += score / toks.size.toDouble().pow(LENGTH_PENALTY) to toks
                } else {
                    next += Beam(toks + tok, score)
                }
                if (next.size == NUM_BEAMS) break
            }
            beams = next
            if (finished.size >= NUM_BEAMS) return best(finished)
        }
        if (finished.isEmpty()) {
            beams.forEach { finished += it.score / it.tokens.size.toDouble().pow(LENGTH_PENALTY) to it.tokens }
        }
        return best(finished)
    }

    /** Meilleure hypothèse terminée, sans le token de départ. */
    private fun best(finished: List<Pair<Double, IntArray>>): IntArray {
        val tokens = finished.maxBy { it.first }.second
        return tokens.copyOfRange(1, tokens.size)
    }

    /** Les k meilleurs (faisceau, token), par score décroissant. */
    private fun topK(scores: Array<FloatArray>, k: Int): List<Pair<Int, Int>> {
        val bestScore = FloatArray(k) { Float.NEGATIVE_INFINITY }
        val bestIdx = Array(k) { -1 to -1 }
        for (b in scores.indices) {
            val row = scores[b]
            for (t in row.indices) {
                val s = row[t]
                if (s <= bestScore[k - 1]) continue
                var pos = k - 1
                while (pos > 0 && bestScore[pos - 1] < s) {
                    bestScore[pos] = bestScore[pos - 1]
                    bestIdx[pos] = bestIdx[pos - 1]
                    pos--
                }
                bestScore[pos] = s
                bestIdx[pos] = b to t
            }
        }
        return bestIdx.filter { it.first >= 0 }
    }

    /** Tokens qui recréeraient un n-gramme déjà présent (no_repeat_ngram_size=3). */
    private fun bannedTokens(ids: IntArray): Set<Int> {
        val n = NO_REPEAT_NGRAM
        if (ids.size < n) return emptySet()
        val banned = mutableSetOf<Int>()
        for (i in 0..ids.size - n) {
            var same = true
            for (j in 0 until n - 1) if (ids[i + j] != ids[ids.size - n + 1 + j]) { same = false; break }
            if (same) banned += ids[i + n - 1]
        }
        return banned
    }

    // 0..14 = [PAD] [UNK] [CLS] [SEP] [MASK] <unused0..9> : ignorés (skip_special_tokens)
    private fun detokenize(ids: IntArray) = ids.filter { it >= 15 }.joinToString("") { vocab[it] }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    companion object {
        const val SIZE = 224
        private const val HIDDEN = 768
        private const val START = 2
        private const val EOS = 3
        private const val MAX_LEN = 300
        private const val NUM_BEAMS = 4
        private const val LENGTH_PENALTY = 2.0
        private const val NO_REPEAT_NGRAM = 3

        private fun logSoftmax(x: FloatArray): FloatArray {
            val max = x.max()
            var sum = 0.0
            for (v in x) sum += exp((v - max).toDouble())
            val lse = max + ln(sum).toFloat()
            for (i in x.indices) x[i] -= lse
            return x
        }
    }
}
