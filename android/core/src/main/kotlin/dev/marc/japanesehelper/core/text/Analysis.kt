package dev.marc.japanesehelper.core.text

import com.atilika.kuromoji.ipadic.Tokenizer

/** Un morceau de Kuromoji (IPADIC). [reading] en hiragana, null si inconnue. */
data class Token(
    val surface: String,
    val pos1: String,
    val pos2: String,
    val pos3: String,
    val conjType: String,
    val conjForm: String,
    val baseForm: String,
    val reading: String?,
) {
    val isContent get() = pos1 !in setOf("記号")
}

enum class WordKind(private val fr: String, private val en: String) {
    NOUN("nom", "noun"),
    PROPER_NOUN("nom propre", "proper noun"),
    PRONOUN("pronom", "pronoun"),
    NUMBER("nombre", "number"),
    VERB("verbe", "verb"),
    I_ADJECTIVE("adjectif en -i", "i-adjective"),
    NA_ADJECTIVE("adjectif en -na", "na-adjective"),
    ADVERB("adverbe", "adverb"),
    ADNOMINAL("déterminant", "adnominal"),
    PARTICLE("particule", "particle"),
    COPULA("copule", "copula"),
    CONJUNCTION("conjonction", "conjunction"),
    INTERJECTION("interjection", "interjection"),
    PREFIX("préfixe", "prefix"),
    SYMBOL("ponctuation", "punctuation"),
    OTHER("autre", "other"),
    ;

    fun label(lang: Lang) = if (lang == Lang.FR) fr else en
}

/** Texte avec sa lecture éventuelle (furigana au-dessus des kanji). */
data class Ruby(val text: String, val reading: String? = null)

/** Mot cliquable : un ou plusieurs morceaux (verbe + terminaisons, nom + suffixe…). */
data class Word(val tokens: List<Token>, val firstToken: Int, val kind: WordKind) {
    val surface = tokens.joinToString("") { it.surface }
    val lastToken get() = firstToken + tokens.size - 1

    /** Morceau principal (hors préfixe). */
    val head get() = tokens.firstOrNull { it.pos1 != "接頭詞" } ?: tokens.first()

    val reading: String? = tokens.map { it.reading ?: return@map null }.let { r -> if (null in r) null else r.joinToString("") }

    val isClickable get() = kind != WordKind.SYMBOL

    /** Forme du dictionnaire : 寄せた -> 寄せる, 勉強させられた -> 勉強する. */
    val lemma: String
        get() {
            val h = head
            return when {
                kind == WordKind.VERB && h.pos2 == "サ変接続" -> h.surface + "する"
                kind == WordKind.VERB || kind == WordKind.I_ADJECTIVE -> h.baseForm.takeIf { it != "*" } ?: h.surface
                kind == WordKind.NA_ADJECTIVE -> h.surface
                else -> tokens.filter { it.pos1 != "助動詞" }.joinToString("") { it.surface }.ifEmpty { surface }
            }
        }

    /** Furigana : lecture alignée sur chaque groupe de kanji (十+八+万+騎 : une seule lecture). */
    val furigana: List<Ruby> get() = Furigana.merge(tokens.flatMap { Furigana.align(it.surface, it.reading) })

    /** Décomposition de la conjugaison : morceaux après le mot principal. */
    val inflection: List<Inflection>
        get() = tokens.dropWhile { it !== head }.drop(1).mapNotNull { Inflection.describe(it) }
}

/** Une terminaison ou un auxiliaire, avec son rôle (français / anglais). */
data class Inflection(val surface: String, val baseForm: String, private val fr: String, private val en: String) {
    /** い (いる), なかっ (ない) : forme lue + forme de base si elle diffère. */
    val label get() = if (baseForm == surface || baseForm == "*") surface else "$surface ($baseForm)"

    fun meaning(lang: Lang) = if (lang == Lang.FR) fr else en

    companion object {
        fun describe(t: Token): Inflection? {
            val b = t.baseForm
            val (fr, en) = when {
                t.pos1 == "助動詞" -> when (b) {
                    "た" -> "passé / action accomplie" to "past / completed action"
                    "ない" -> "négation" to "negation"
                    "ぬ", "ん" -> "négation (littéraire ou familière)" to "negation (literary or casual)"
                    "ます" -> "forme polie" to "polite form"
                    "です" -> "copule polie (« être »)" to "polite copula (\"to be\")"
                    "だ" -> if (t.conjForm == "体言接続") "liaison avec un nom (な)" to "link to a noun (な)"
                    else "copule (« être »)" to "copula (\"to be\")"
                    "たい" -> "désir (« vouloir »)" to "desire (\"want to\")"
                    "う" -> "volonté, proposition (« faisons », « je vais »)" to "volition, suggestion (\"let's\", \"I will\")"
                    "まい" -> "volonté négative ou supposition négative" to "negative volition or negative guess"
                    "らしい" -> "ouï-dire, apparence (« il paraît que »)" to "hearsay, appearance (\"apparently\")"
                    "ある" -> "« être » (dans である, style écrit)" to "\"to be\" (in である, written style)"
                    else -> "auxiliaire $b" to "auxiliary $b"
                }
                t.pos1 == "動詞" && t.pos2 == "接尾" -> when (b) {
                    "れる", "られる" -> "passif, potentiel ou respect" to "passive, potential or respect"
                    "せる", "させる" -> "causatif (« faire faire »)" to "causative (\"make someone do\")"
                    else -> "suffixe verbal $b" to "verb suffix $b"
                }
                t.pos1 == "動詞" && t.pos2 == "非自立" -> when (b) {
                    "いる" -> "action en cours ou état (〜ている)" to "ongoing action or state (〜ている)"
                    "しまう" -> "action achevée, souvent avec regret (〜てしまう)" to "completed action, often regretted (〜てしまう)"
                    "おく" -> "faire à l'avance (〜ておく)" to "do in advance (〜ておく)"
                    "みる" -> "essayer (〜てみる)" to "try doing (〜てみる)"
                    "くる" -> "évolution vers le locuteur, « commencer à » (〜てくる)" to "change towards the speaker, \"start to\" (〜てくる)"
                    "いく" -> "évolution qui s'éloigne, « continuer à » (〜ていく)" to "change moving away, \"go on\" (〜ていく)"
                    "ある" -> "état résultant d'une action (〜てある)" to "state resulting from an action (〜てある)"
                    "なる" -> "« devenir » ; avec ならない : obligation" to "\"become\"; with ならない: obligation"
                    "くれる", "もらう", "あげる" -> "service rendu (〜て$b)" to "doing a favor (〜て$b)"
                    else -> "verbe auxiliaire $b" to "auxiliary verb $b"
                }
                t.pos1 == "助詞" && t.pos2 == "接続助詞" -> when (b) {
                    "て", "で" -> "forme en て (liaison, enchaînement)" to "て-form (linking)"
                    "ば" -> "condition (« si »)" to "condition (\"if\")"
                    else -> "liaison $b" to "conjunction $b"
                }
                t.pos1 == "形容詞" && t.pos2 == "非自立" -> "adjectif auxiliaire $b" to "auxiliary adjective $b"
                t.pos1 == "名詞" && t.pos3 == "助動詞語幹" -> when (b) {
                    "そう" -> "apparence (« avoir l'air de »)" to "appearance (\"looks like\")"
                    "よう" -> "ressemblance, apparence (〜ようだ)" to "resemblance, appearance (〜ようだ)"
                    else -> b to b
                }
                t.pos1 == "名詞" && t.pos2 == "接尾" -> "suffixe ${t.surface}" to "suffix ${t.surface}"
                else -> return null
            }
            return Inflection(t.surface, b, fr, en)
        }
    }
}

/** Résultat de l'analyse d'une bulle. */
data class Analysis(val text: String, val tokens: List<Token>, val words: List<Word>, val grammar: List<GrammarMatch>) {
    /** Mot contenant le morceau [tokenIndex]. */
    fun wordAt(tokenIndex: Int) = words.indexOfFirst { tokenIndex in it.firstToken..it.lastToken }

    /**
     * Formes à chercher dans le dictionnaire pour le mot [index], de la plus longue à la
     * plus courte : noms composés coupés par Kuromoji (中尊寺金色堂), forme du dictionnaire,
     * puis morceau principal seul.
     */
    fun lookupKeys(index: Int): List<String> {
        val w = words[index]
        val keys = mutableListOf<String>()
        val nounish = setOf(WordKind.NOUN, WordKind.PROPER_NOUN, WordKind.NUMBER, WordKind.PREFIX)
        if (w.kind in nounish) {
            var end = index
            while (end + 1 < words.size && end - index < 3 && words[end + 1].kind in nounish) end++
            for (e in end downTo index + 1) keys += words.subList(index, e + 1).joinToString("") { it.surface }
        }
        keys += w.lemma
        keys += w.surface
        keys += w.head.baseForm.takeIf { it != "*" } ?: w.head.surface
        keys += w.head.surface
        return keys.distinct()
    }
}

object JapaneseAnalyzer {
    // Lourd à créer (~1 s, dictionnaire IPADIC) : une seule instance, créée à la demande
    private val tokenizer by lazy { Tokenizer() }

    /** À appeler en arrière-plan au démarrage pour éviter l'attente au premier mot. */
    fun warmUp() {
        tokenizer.tokenize("準備")
    }

    fun analyze(text: String): Analysis {
        val tokens = tokenizer.tokenize(text).map { k ->
            Token(
                k.surface, k.partOfSpeechLevel1, k.partOfSpeechLevel2, k.partOfSpeechLevel3,
                k.conjugationType, k.conjugationForm, k.baseForm,
                k.reading.takeIf { it != "*" }?.let(Kana::toHiragana),
            )
        }
        val words = group(tokens)
        return Analysis(text, tokens, words, Grammar.detect(tokens))
    }

    /** Regroupe les morceaux en mots (règles sur les natures IPADIC). */
    internal fun group(tokens: List<Token>): List<Word> {
        val groups = mutableListOf<MutableList<Int>>()
        fun last() = groups.lastOrNull()?.let { tokens[it.last()] }
        fun head() = groups.lastOrNull()?.let { g -> g.map { tokens[it] }.firstOrNull { it.pos1 != "接頭詞" } }

        for ((i, t) in tokens.withIndex()) {
            val prev = last()
            val prevHead = head()
            val prevConjugable = prev != null && (prev.pos1 in setOf("動詞", "形容詞", "助動詞") ||
                (prev.pos1 == "助詞" && prev.pos2 == "接続助詞" && prev.baseForm in setOf("て", "で", "ば")))
            val attach = when {
                prev == null -> false
                prev.pos1 == "接頭詞" -> t.pos1 == "名詞"
                // Ponctuation qui se suit (！！, ……) : un seul bloc
                t.pos1 == "記号" && prev.pos1 == "記号" -> true
                t.pos1 == "記号" || prev.pos1 == "記号" -> false
                // Terminaisons et auxiliaires d'un verbe, adjectif ou copule
                t.pos1 == "助動詞" -> prevConjugable || prev.pos3 == "助動詞語幹" || prev.pos2 == "形容動詞語幹" ||
                    (prev.pos1 == "名詞" && prev.pos3 == "形容動詞語幹")
                t.pos1 == "動詞" && t.pos2 == "接尾" -> prev.pos1 == "動詞"
                t.pos1 == "動詞" && t.pos2 == "非自立" -> prevConjugable
                t.pos1 == "形容詞" && t.pos2 == "非自立" -> prev.pos1 == "助詞" && prev.baseForm in setOf("て", "で") ||
                    prev.pos1 == "動詞" && prev.conjForm == "連用形"
                t.pos1 == "助詞" && t.pos2 == "接続助詞" && t.baseForm in setOf("て", "で", "ば", "ちゃ") -> prevConjugable
                // 勉強 + する, 心配 + しない…
                t.pos1 == "動詞" && t.baseForm == "する" -> prev.pos2 == "サ変接続" && prevHead == prev
                // Suffixes de nom (軍事+力, 人+達, 三+代) et nombres (十+八+万)
                t.pos1 == "名詞" && t.pos2 == "接尾" && t.pos3 != "助動詞語幹" -> prev.pos1 == "名詞"
                t.pos1 == "名詞" && t.pos3 == "助動詞語幹" -> prev.pos1 in setOf("動詞", "形容詞") || prev.pos2 == "形容動詞語幹"
                t.pos1 == "名詞" && t.pos2 == "数" -> prev.pos2 == "数"
                // Morceau inconnu de Kuromoji collé à un nom (清 + 衡)
                t.pos1 == "名詞" && t.baseForm == "*" -> prev.pos1 == "名詞"
                else -> false
            }
            if (attach) groups.last() += i else groups += mutableListOf(i)
        }
        return groups.map { g -> Word(g.map { tokens[it] }, g.first(), kindOf(g.map { tokens[it] })) }
    }

    private fun kindOf(tokens: List<Token>): WordKind {
        val h = tokens.firstOrNull { it.pos1 != "接頭詞" } ?: return WordKind.PREFIX
        return when (h.pos1) {
            "名詞" -> when {
                tokens.any { it.pos1 == "動詞" } -> WordKind.VERB // 勉強する
                h.pos2 == "形容動詞語幹" && tokens.size > 1 -> WordKind.NA_ADJECTIVE
                h.pos2 == "固有名詞" -> WordKind.PROPER_NOUN
                h.pos2 == "代名詞" -> WordKind.PRONOUN
                h.pos2 == "数" -> WordKind.NUMBER
                else -> WordKind.NOUN
            }
            "動詞" -> WordKind.VERB
            "形容詞" -> WordKind.I_ADJECTIVE
            "副詞" -> WordKind.ADVERB
            "連体詞" -> WordKind.ADNOMINAL
            "助詞" -> WordKind.PARTICLE
            "助動詞" -> WordKind.COPULA
            "接続詞" -> WordKind.CONJUNCTION
            "感動詞", "フィラー" -> WordKind.INTERJECTION
            "記号" -> WordKind.SYMBOL
            else -> WordKind.OTHER
        }
    }
}

object Kana {
    fun toHiragana(s: String) = buildString(s.length) {
        for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }

    fun isKana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c == 'ー' || c == 'ゝ' || c == 'ゞ' || c == 'ヽ' || c == 'ヾ'

    fun hasKanji(s: String) = s.any { isKanji(it) }

    fun isKanji(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々' || c == 'ヶ' || c == '〆'
}

object Furigana {
    /** Fusionne les groupes de kanji voisins : leurs lectures ne se chevauchent plus à l'affichage. */
    fun merge(parts: List<Ruby>): List<Ruby> = parts.fold(mutableListOf()) { acc, r ->
        val prev = acc.lastOrNull()
        if (prev?.reading != null && r.reading != null) acc[acc.lastIndex] = Ruby(prev.text + r.text, prev.reading + r.reading)
        else acc += r
        acc
    }

    /**
     * Aligne la lecture sur les groupes de kanji : 寄せ + よせ -> [寄(よ), せ].
     * Les kana de la forme écrite servent d'ancres ; sans alignement possible, la lecture
     * couvre tout le mot.
     */
    fun align(surface: String, reading: String?): List<Ruby> {
        if (reading == null || !Kana.hasKanji(surface)) return listOf(Ruby(surface))
        // Découpe en groupes kanji / non-kanji
        val runs = mutableListOf<Pair<String, Boolean>>()
        for (c in surface) {
            val k = Kana.isKanji(c)
            if (runs.isNotEmpty() && runs.last().second == k) runs[runs.lastIndex] = runs.last().first + c to k
            else runs += c.toString() to k
        }
        val pattern = runs.joinToString("", "^", "$") { (text, kanji) ->
            if (kanji) "(.+?)" else "(" + Regex.escape(Kana.toHiragana(text)) + ")"
        }
        val m = Regex(pattern).find(Kana.toHiragana(reading)) ?: return listOf(Ruby(surface, reading))
        return runs.mapIndexed { i, (text, kanji) -> if (kanji) Ruby(text, m.groupValues[i + 1]) else Ruby(text) }
    }
}
