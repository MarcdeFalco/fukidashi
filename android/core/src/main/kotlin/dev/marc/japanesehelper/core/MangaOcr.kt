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
 * manga-ocr (ViT + BERT) en ONNX, graphes de export/export_cached.py.
 * Reproduit `beam_decode_cached` de export/validate_ocr.py :
 * gris -> 224x224 bilinéaire PIL -> [-1, 1] -> encoder_kv (K/V de l'attention croisée)
 * -> recherche en faisceau, un token par pas avec cache K/V de l'auto-attention.
 *
 * Non thread-safe : un appel à la fois.
 */
class MangaOcr(
    encoderFile: File,
    decoderFile: File,
    vocabFile: File,
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment(),
    encoderOptions: OrtSession.SessionOptions = OrtSession.SessionOptions(),
    decoderOptions: OrtSession.SessionOptions = encoderOptions,
) : Closeable {
    constructor(
        modelDir: File,
        env: OrtEnvironment = OrtEnvironment.getEnvironment(),
        options: OrtSession.SessionOptions = OrtSession.SessionOptions(),
    ) : this(
        File(modelDir, ENCODER_FILE), File(modelDir, DECODER_FILE), File(modelDir, "vocab.txt"),
        env, options, options,
    )

    private val encoder = env.createSession(encoderFile.path, encoderOptions)
    private val decoder = env.createSession(decoderFile.path, decoderOptions)
    private val vocab = vocabFile.readLines(Charsets.UTF_8)

    /** Durées du dernier appel à [recognize]. */
    var lastTimings = Timings()
        private set

    fun recognize(image: RgbImage): String {
        val t = Timings()
        lastTimings = t
        val pixels = t.measure("prétraitement") { preprocess(image) }
        val ids = OnnxTensor.createTensor(env, FloatBuffer.wrap(pixels), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { input ->
            t.measure("encodeur") { encoder.run(mapOf(encoder.inputNames.first() to input)) }.use { cross ->
                // K/V de l'attention croisée, partagés par tous les faisceaux
                val crossFeed = CROSS_NAMES.associateWith { cross.get(it).get() as OnnxTensor }
                beamSearch(crossFeed)
            }
        }
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

    /** Cache K/V de l'auto-attention : 4 tableaux [faisceaux, 12, longueur, 64]. */
    private class Cache(val beams: Int, val length: Int, val arrays: List<FloatArray>) {
        val beamSize get() = HEADS * length * HEAD_DIM

        /** Garde, dans l'ordre, les faisceaux [parents]. */
        fun select(parents: IntArray) = Cache(
            parents.size, length,
            arrays.map { a -> FloatArray(parents.size * beamSize).also { out -> parents.forEachIndexed { j, p -> a.copyInto(out, j * beamSize, p * beamSize, (p + 1) * beamSize) } } },
        )

        companion object {
            fun empty() = Cache(1, 0, List(4) { FloatArray(0) })
        }
    }

    private class Step(val logProbs: Array<FloatArray>, val cache: Cache)

    /** Un pas du décodeur : dernier token de chaque faisceau -> log-probas + cache prolongé. */
    private fun decodeStep(lastTokens: IntArray, position: Int, cache: Cache, cross: Map<String, OnnxTensor>): Step {
        val b = lastTokens.size.toLong()
        val pastShape = longArrayOf(b, HEADS.toLong(), cache.length.toLong(), HEAD_DIM.toLong())
        val inputs = mutableMapOf<String, OnnxTensor>()
        try {
            inputs["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(lastTokens.size) { lastTokens[it].toLong() }), longArrayOf(b, 1))
            inputs["position"] = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(position.toLong())), longArrayOf(1))
            PAST_NAMES.forEachIndexed { i, name -> inputs[name] = OnnxTensor.createTensor(env, FloatBuffer.wrap(cache.arrays[i]), pastShape) }
            lastTimings.count("étapes")
            return lastTimings.measure("décodeur") { decoder.run(inputs + cross) }.use { result ->
                val logits = (result.get("logits").get() as OnnxTensor).floatBuffer
                val v = vocab.size
                val logProbs = Array(lastTokens.size) { i -> FloatArray(v).also { logits.position(i * v); logits.get(it); logSoftmax(it) } }
                val newCache = Cache(
                    lastTokens.size, cache.length + 1,
                    NEW_NAMES.map { name -> (result.get(name).get() as OnnxTensor).floatBuffer.let { buf -> FloatArray(buf.remaining()).also { buf.get(it) } } },
                )
                Step(logProbs, newCache)
            }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    private class Beam(val tokens: IntArray, val score: Float)

    /** Recherche en faisceau façon HF generate (num_beams=4, length_penalty=2, early_stopping). */
    private fun beamSearch(cross: Map<String, OnnxTensor>): IntArray {
        var beams = listOf(Beam(intArrayOf(START), 0f))
        var cache = Cache.empty()
        val finished = mutableListOf<Pair<Double, IntArray>>()
        for (position in 0 until MAX_LEN - 1) {
            val step = decodeStep(IntArray(beams.size) { beams[it].tokens.last() }, position, cache, cross)
            val logp = step.logProbs
            for ((i, beam) in beams.withIndex()) {
                for (t in bannedTokens(beam.tokens)) logp[i][t] = Float.NEGATIVE_INFINITY
                for (t in logp[i].indices) logp[i][t] += beam.score
            }
            val next = mutableListOf<Beam>()
            val parents = mutableListOf<Int>()
            for ((rank, cand) in topK(logp, 2 * NUM_BEAMS).withIndex()) {
                val (bi, tok) = cand
                val score = logp[bi][tok]
                val toks = beams[bi].tokens
                if (tok == EOS) {
                    // HF ignore un EOS hors des num_beams meilleurs candidats
                    if (rank < NUM_BEAMS) finished += score / toks.size.toDouble().pow(LENGTH_PENALTY) to toks
                } else {
                    next += Beam(toks + tok, score)
                    parents += bi
                }
                if (next.size == NUM_BEAMS) break
            }
            beams = next
            cache = step.cache.select(parents.toIntArray())
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
        const val ENCODER_FILE = "encoder_kv.onnx"
        const val DECODER_FILE = "decoder_step.onnx"
        private const val HEADS = 12
        private const val HEAD_DIM = 64
        private const val START = 2
        private const val EOS = 3
        private const val MAX_LEN = 300
        private const val NUM_BEAMS = 4
        private const val LENGTH_PENALTY = 2.0
        private const val NO_REPEAT_NGRAM = 3
        private val CROSS_NAMES = listOf("cross_k0", "cross_v0", "cross_k1", "cross_v1")
        private val PAST_NAMES = listOf("past_k0", "past_v0", "past_k1", "past_v1")
        private val NEW_NAMES = listOf("new_k0", "new_v0", "new_k1", "new_v1")

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
