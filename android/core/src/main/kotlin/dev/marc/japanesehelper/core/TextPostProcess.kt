package dev.marc.japanesehelper.core

import java.text.Normalizer

/** Nettoyage du texte OCR, identique à `manga_ocr.ocr.post_process`. */
object TextPostProcess {
    private val DOTS = Regex("[・.]{2,}")

    fun apply(raw: String): String {
        var text = raw.filterNot { it.isWhitespace() }
        text = text.replace("…", "...")
        text = DOTS.replace(text) { ".".repeat(it.value.length) }
        return halfToFullWidth(text)
    }

    /** Équivalent de `jaconv.h2z(text, ascii=True, digit=True)`. */
    fun halfToFullWidth(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == ' ' -> sb.append('　')
                c in '!'..'~' -> sb.append(c + 0xFEE0)
                // Katakana demi-largeur ; ﾞ ﾟ isolés restent tels quels (comme jaconv)
                c in '｡'..'ﾝ' -> {
                    val next = text.getOrNull(i + 1)
                    val combined = if (next == 'ﾞ' || next == 'ﾟ') nfkc("$c$next") else null
                    if (combined != null && combined.length == 1) {
                        sb.append(combined)
                        i++
                    } else {
                        sb.append(nfkc(c.toString()))
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }

    private fun nfkc(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC)
}
