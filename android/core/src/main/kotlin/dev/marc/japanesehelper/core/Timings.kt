package dev.marc.japanesehelper.core

/** Durées (ms) des étapes du dernier appel, pour le test de vitesse. */
class Timings {
    val ms = linkedMapOf<String, Double>()

    inline fun <T> measure(step: String, block: () -> T): T {
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            ms[step] = (ms[step] ?: 0.0) + (System.nanoTime() - t0) / 1e6
        }
    }

    fun count(step: String, n: Int = 1) {
        ms[step] = (ms[step] ?: 0.0) + n
    }

    override fun toString() = ms.entries.joinToString(" ") { (k, v) -> "$k=%.0f".format(v) }
}
