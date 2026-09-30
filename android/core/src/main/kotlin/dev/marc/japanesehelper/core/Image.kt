package dev.marc.japanesehelper.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Image indépendante d'Android : pixels ARGB, ligne par ligne. */
class RgbImage(val width: Int, val height: Int, val pixels: IntArray) {
    init {
        require(pixels.size == width * height)
    }

    fun crop(box: Box): RgbImage {
        val x0 = box.left.toInt().coerceIn(0, width - 1)
        val y0 = box.top.toInt().coerceIn(0, height - 1)
        val x1 = box.right.toInt().coerceIn(x0 + 1, width)
        val y1 = box.bottom.toInt().coerceIn(y0 + 1, height)
        val w = x1 - x0
        val h = y1 - y0
        val out = IntArray(w * h)
        for (y in 0 until h) System.arraycopy(pixels, (y0 + y) * width + x0, out, y * w, w)
        return RgbImage(w, h, out)
    }

    /** Niveaux de gris, formule de PIL `convert("L")`. */
    fun toGray(): GrayImage {
        val out = ByteArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            out[i] = ((r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16).toByte()
        }
        return GrayImage(width, height, out)
    }

    /** Sépare les canaux R, G, B (pour redimensionner chacun). */
    fun channels(): List<GrayImage> = listOf(16, 8, 0).map { shift ->
        GrayImage(width, height, ByteArray(pixels.size) { ((pixels[it] shr shift) and 0xFF).toByte() })
    }
}

class GrayImage(val width: Int, val height: Int, val data: ByteArray) {
    operator fun get(x: Int, y: Int): Int = data[y * width + x].toInt() and 0xFF
}

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val area get() = max(0f, width) * max(0f, height)

    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom

    fun iou(o: Box): Float {
        val inter = Box(max(left, o.left), max(top, o.top), min(right, o.right), min(bottom, o.bottom)).area
        return inter / (area + o.area - inter)
    }
}

/**
 * Redimensionnement bilinéaire identique à PIL `Image.resize(..., BILINEAR)`,
 * y compris l'anti-crénelage à la réduction et l'arithmétique en virgule fixe :
 * manga-ocr a été entraîné sur des images passées par PIL.
 */
object PilResize {
    private const val PRECISION_BITS = 32 - 8 - 2

    fun bilinear(src: GrayImage, outW: Int, outH: Int): GrayImage {
        var img = src
        if (outW != img.width) img = horizontal(img, outW)
        if (outH != img.height) img = vertical(img, outH)
        return img
    }

    /** Coefficients pour une dimension : (début, nombre, poids) par pixel de sortie. */
    private class Coeffs(val start: IntArray, val count: IntArray, val weights: IntArray, val kSize: Int)

    private fun coeffs(inSize: Int, outSize: Int): Coeffs {
        val scale = inSize.toDouble() / outSize
        val filterScale = max(scale, 1.0)
        val support = 1.0 * filterScale
        val kSize = kotlin.math.ceil(support).toInt() * 2 + 1
        val start = IntArray(outSize)
        val count = IntArray(outSize)
        val weights = IntArray(outSize * kSize)
        val k = DoubleArray(kSize)
        for (xx in 0 until outSize) {
            val center = (xx + 0.5) * scale
            val ss = 1.0 / filterScale
            val xmin = max((center - support + 0.5).toInt(), 0)
            val xmax = min((center + support + 0.5).toInt(), inSize) - xmin
            var ww = 0.0
            for (x in 0 until xmax) {
                val w = max(0.0, 1.0 - abs((x + xmin - center + 0.5) * ss))
                k[x] = w
                ww += w
            }
            for (x in 0 until xmax) {
                val norm = if (ww != 0.0) k[x] / ww else k[x]
                // Arrondi comme PIL (normalize_coeffs_8bpc)
                weights[xx * kSize + x] = if (norm < 0) {
                    (-0.5 + norm * (1 shl PRECISION_BITS)).toInt()
                } else {
                    (0.5 + norm * (1 shl PRECISION_BITS)).toInt()
                }
            }
            start[xx] = xmin
            count[xx] = xmax
        }
        return Coeffs(start, count, weights, kSize)
    }

    private fun clip8(v: Long): Byte = (v shr PRECISION_BITS).coerceIn(0, 255).toInt().toByte()

    private fun horizontal(src: GrayImage, outW: Int): GrayImage {
        val c = coeffs(src.width, outW)
        val out = ByteArray(outW * src.height)
        for (y in 0 until src.height) {
            val row = y * src.width
            for (xx in 0 until outW) {
                var ss = 1L shl (PRECISION_BITS - 1)
                val base = xx * c.kSize
                val s = c.start[xx]
                for (x in 0 until c.count[xx]) {
                    ss += (src.data[row + s + x].toInt() and 0xFF).toLong() * c.weights[base + x]
                }
                out[y * outW + xx] = clip8(ss)
            }
        }
        return GrayImage(outW, src.height, out)
    }

    private fun vertical(src: GrayImage, outH: Int): GrayImage {
        val c = coeffs(src.height, outH)
        val w = src.width
        val out = ByteArray(w * outH)
        for (yy in 0 until outH) {
            val base = yy * c.kSize
            val s = c.start[yy]
            for (x in 0 until w) {
                var ss = 1L shl (PRECISION_BITS - 1)
                for (y in 0 until c.count[yy]) {
                    ss += (src.data[(s + y) * w + x].toInt() and 0xFF).toLong() * c.weights[base + y]
                }
                out[yy * w + x] = clip8(ss)
            }
        }
        return GrayImage(w, outH, out)
    }
}
