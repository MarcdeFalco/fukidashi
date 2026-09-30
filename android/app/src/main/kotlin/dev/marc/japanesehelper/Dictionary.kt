package dev.marc.japanesehelper

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File

/** Un sens d'une entrée : gloses + natures et remarques (codes JMdict). */
data class Sense(val glosses: List<String>, val pos: List<String>, val misc: List<String>, val info: List<String>)

data class DictEntry(
    val id: Long,
    val kanji: List<String>,
    val kana: List<String>,
    /** Lectures marquées « courantes » dans JMdict. */
    val commonKana: List<String>,
    val senses: List<Sense>,
    val sensesFr: List<Sense>,
    val common: Boolean,
)

data class KanjiInfo(
    val literal: String,
    val onyomi: List<String>,
    val kunyomi: List<String>,
    val meaningsFr: List<String>,
    val meaningsEn: List<String>,
    val grade: Int?,
    val strokes: Int?,
    val jlpt: Int?,
)

/** JMdict + KANJIDIC (export/build_dictionary.py), en lecture seule. */
class Dictionary private constructor(private val db: SQLiteDatabase) {
    private val tags: Map<String, String> by lazy {
        db.rawQuery("SELECT name, description FROM tag", null).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
    }

    /** Entrées dont une forme écrite est exactement [text], les plus courantes d'abord. */
    fun lookup(text: String, limit: Int = 6): List<DictEntry> =
        db.rawQuery(
            """SELECT e.id, e.kanji, e.kana, e.senses_en, e.senses_fr, e.common, MIN(f.rank) AS r
               FROM form f JOIN entry e ON e.id = f.entry_id
               WHERE f.text = ? GROUP BY e.id ORDER BY r, e.id LIMIT ?""",
            arrayOf(text, limit.toString()),
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        DictEntry(
                            id = c.getLong(0),
                            kanji = forms(c.getString(1)),
                            kana = forms(c.getString(2)),
                            commonKana = forms(c.getString(2), commonOnly = true),
                            senses = senses(c.getString(3)),
                            sensesFr = c.getString(4)?.let(::senses).orEmpty(),
                            common = c.getInt(5) == 1,
                        ),
                    )
                }
            }
        }

    fun kanji(literal: String): KanjiInfo? =
        db.rawQuery(
            "SELECT onyomi, kunyomi, meanings_fr, meanings_en, grade, strokes, jlpt FROM kanji WHERE literal = ?",
            arrayOf(literal),
        ).use { c ->
            if (!c.moveToFirst()) return null
            KanjiInfo(
                literal, strings(c.getString(0)), strings(c.getString(1)), strings(c.getString(2)), strings(c.getString(3)),
                c.intOrNull(4), c.intOrNull(5), c.intOrNull(6),
            )
        }

    /** Libellé d'un code JMdict (nature, registre…) : français pour les plus courants. */
    fun tagLabel(code: String): String = TAG_FR[code] ?: when {
        code.startsWith("v5") -> "verbe godan"            // v5k, v5r, v5s…
        code.startsWith("v1") -> "verbe ichidan"
        code.startsWith("v2") || code.startsWith("v4") -> "verbe ancien (classique)"
        code.startsWith("vs") -> "verbe en する"
        else -> null
    } ?: tags[code] ?: code

    private fun android.database.Cursor.intOrNull(i: Int) = if (isNull(i)) null else getInt(i)

    private fun forms(json: String, commonOnly: Boolean = false): List<String> = JSONArray(json).let { a ->
        (0 until a.length()).map { a.getJSONArray(it) }.filter { !commonOnly || it.getInt(1) == 1 }.map { it.getString(0) }
    }

    private fun strings(json: String?): List<String> = json?.let { JSONArray(it) }?.let { a -> List(a.length()) { a.getString(it) } }.orEmpty()

    private fun senses(json: String): List<Sense> = JSONArray(json).let { a ->
        List(a.length()) { i ->
            val s = a.getJSONObject(i)
            Sense(strings(s.optJSONArray("g")?.toString()), strings(s.optJSONArray("p")?.toString()),
                strings(s.optJSONArray("m")?.toString()), strings(s.optJSONArray("i")?.toString()))
        }
    }

    companion object {
        private const val ASSET = "models/dictionary.db"

        /** Copie la base depuis l'APK (compressée) à la 1re utilisation ou après une mise à jour. */
        fun open(context: Context): Dictionary {
            val file = File(context.noBackupFilesDir, "dictionary.db")
            val stamp = File(context.noBackupFilesDir, "dictionary.version")
            val version = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toString()
            if (!file.exists() || !stamp.exists() || stamp.readText() != version) {
                val tmp = File(file.path + ".tmp")
                context.assets.open(ASSET).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(file)
                stamp.writeText(version)
            }
            return Dictionary(SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY))
        }

        private val TAG_FR = mapOf(
            "n" to "nom", "pn" to "pronom", "n-suf" to "suffixe nominal", "n-pref" to "préfixe nominal",
            "n-adv" to "nom adverbial", "n-t" to "nom temporel", "n-pr" to "nom propre",
            "v1" to "verbe ichidan", "vs" to "verbe en する", "vs-i" to "verbe en する",
            "vk" to "verbe irrégulier 来る", "vz" to "verbe en ずる", "vt" to "transitif", "vi" to "intransitif",
            "v1-s" to "verbe ichidan (くれる)", "vs-s" to "verbe en する (spécial)",
            "adj-i" to "adjectif en -i", "adj-ix" to "adjectif en -i (いい/よい)", "adj-na" to "adjectif en -na",
            "adj-no" to "nom + の (qualificatif)", "adj-pn" to "déterminant", "adj-t" to "adjectif en -taru",
            "adj-f" to "qualificatif", "adv" to "adverbe", "adv-to" to "adverbe + と", "aux" to "auxiliaire",
            "aux-v" to "verbe auxiliaire", "aux-adj" to "adjectif auxiliaire", "conj" to "conjonction",
            "cop" to "copule", "ctr" to "compteur", "exp" to "expression", "int" to "interjection",
            "num" to "nombre", "pref" to "préfixe", "prt" to "particule", "suf" to "suffixe", "unc" to "non classé",
            "uk" to "surtout en kana", "uK" to "surtout en kanji", "arch" to "archaïque", "obs" to "vieilli",
            "col" to "familier", "hon" to "honorifique (sonkeigo)", "hum" to "humble (kenjōgo)",
            "pol" to "poli (teineigo)", "sl" to "argot", "vulg" to "vulgaire", "male" to "masculin",
            "fem" to "féminin", "abbr" to "abréviation", "id" to "expression idiomatique",
            "on-mim" to "onomatopée", "yoji" to "expression en 4 kanji", "rare" to "rare",
            "joc" to "humoristique", "derog" to "péjoratif", "form" to "soutenu", "poet" to "poétique",
            "chn" to "langage enfantin", "sens" to "sensible", "person" to "nom de personne", "place" to "lieu",
        )
    }
}
