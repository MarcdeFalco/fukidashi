package dev.marc.japanesehelper.core.text

/**
 * Un point de grammaire. Les textes ici sont en français ; l'anglais est dans GrammarEn.kt.
 * [level] : niveau JLPT indicatif. [example] : « phrase japonaise + espace + traduction ».
 * Utiliser [text] pour l'affichage.
 */
data class GrammarPoint(
    val id: String,
    val pattern: String,
    val title: String,
    val level: String,
    val explanation: String,
    val example: String? = null,
)

/** Textes d'un point de grammaire dans une langue donnée. */
data class GrammarPointText(
    val pattern: String,
    val title: String,
    val explanation: String,
    val exampleJa: String?,
    val exampleTranslation: String?,
)

fun GrammarPoint.text(lang: Lang): GrammarPointText {
    val ja = example?.substringBefore(' ')
    return when (lang) {
        Lang.FR -> GrammarPointText(pattern, title, explanation, ja, example?.substringAfter(' ', ""))
        Lang.EN -> GRAMMAR_EN.getValue(id).let { GrammarPointText(it.pattern ?: pattern, it.title, it.explanation, ja, it.example) }
    }
}

/** Point de grammaire repéré sur les morceaux [first]..[last] de la phrase. */
data class GrammarMatch(val point: GrammarPoint, val first: Int, val last: Int)

/**
 * Détection des points de grammaire sur les morceaux Kuromoji (IPADIC).
 * Chaque règle regarde à partir d'un morceau et renvoie l'indice du dernier morceau reconnu.
 */
object Grammar {
    private class Rule(val point: GrammarPoint, val match: (List<Token>, Int) -> Int?)

    private val rules = mutableListOf<Rule>()

    private fun rule(
        id: String, pattern: String, title: String, level: String, explanation: String, example: String? = null,
        match: (List<Token>, Int) -> Int?,
    ) {
        rules += Rule(GrammarPoint(id, pattern, title, level, explanation, example), match)
    }

    val points: List<GrammarPoint> get() = rules.map { it.point }

    fun detect(tokens: List<Token>): List<GrammarMatch> {
        val found = linkedMapOf<Triple<String, Int, Int>, GrammarMatch>()
        for (i in tokens.indices) for (r in rules) {
            val end = r.match(tokens, i) ?: continue
            found.getOrPut(Triple(r.point.id, i, end)) { GrammarMatch(r.point, i, end) }
        }
        // Dans l'ordre de la phrase, les tournures longues avant leurs parties
        return found.values.sortedWith(compareBy({ it.first }, { -(it.last - it.first) }))
    }

    // --- Outils de reconnaissance ---

    private fun List<Token>.at(i: Int) = getOrNull(i)
    private fun Token?.particle(vararg base: String, pos2: String? = null) =
        this != null && pos1 == "助詞" && baseForm in base && (pos2 == null || this.pos2.startsWith(pos2))
    private fun Token?.aux(vararg base: String) = this != null && pos1 == "助動詞" && baseForm in base
    private fun Token?.subVerb(vararg base: String) = this != null && pos1 == "動詞" && pos2 == "非自立" && baseForm in base
    private fun Token?.te() = particle("て", "で", pos2 = "接続助詞")
    private fun Token?.isEnd() = this == null || pos1 == "記号"

    /** Morceau conjugable (verbe, adjectif, auxiliaire) : ce qui peut précéder て, ば… */
    private fun Token?.conjugable() = this != null && pos1 in setOf("動詞", "形容詞", "助動詞")

    /** Fin de la forme en て + verbe auxiliaire [base] (〜ている, 〜てしまう…). */
    private fun teForm(t: List<Token>, i: Int, vararg base: String): Int? =
        if (t.at(i).te() && t.at(i - 1).conjugable() && t.at(i + 1).subVerb(*base)) i + 1 else null

    init {
        // ---------------- Particules ----------------
        rule("wa", "〜は", "Thème", "N5",
            "Marque le thème de la phrase : ce dont on parle (« quant à… »). Se prononce « wa ». " +
                "Il peut aussi marquer un contraste.",
            "私は学生です。 Moi, je suis étudiant.") { t, i -> i.takeIf { t[i].particle("は", pos2 = "係助詞") } }
        rule("ga-subject", "〜が", "Sujet", "N5",
            "Marque le sujet grammatical, souvent une information nouvelle ou mise en valeur.",
            "雨が降る。 La pluie tombe.") { t, i -> i.takeIf { t[i].particle("が", pos2 = "格助詞") } }
        rule("ga-but", "〜が、", "Mais", "N4",
            "Relie deux propositions en opposition (« …, mais… »), style assez soutenu.",
            "高いが、おいしい。 C'est cher, mais bon.") { t, i -> i.takeIf { t[i].particle("が", pos2 = "接続助詞") } }
        rule("wo", "〜を", "Complément d'objet direct", "N5",
            "Marque ce sur quoi porte l'action. Aussi le lieu que l'on traverse ou quitte.",
            "本を読む。 Lire un livre.") { t, i -> i.takeIf { t[i].particle("を") } }
        rule("ni", "〜に", "Destination, lieu, temps, destinataire", "N5",
            "Point d'arrivée : lieu où l'on va ou où quelque chose se trouve, moment précis, " +
                "personne à qui l'on donne ou parle, but ; agent d'un verbe passif.",
            "駅に行く。 Aller à la gare.") { t, i -> i.takeIf { t[i].particle("に", pos2 = "格助詞") } }
        rule("he", "〜へ", "Direction", "N5",
            "Direction d'un déplacement (« vers »). Se prononce « e ».",
            "東へ向かう。 Se diriger vers l'est.") { t, i -> i.takeIf { t[i].particle("へ") } }
        rule("de", "〜で", "Lieu de l'action, moyen, cause", "N5",
            "Lieu où se déroule l'action, moyen ou outil utilisé, cause, ou cadre (« en », « à », « avec »).",
            "電車で行く。 Y aller en train.") { t, i -> i.takeIf { t[i].particle("で", pos2 = "格助詞") } }
        rule("to", "〜と", "Avec, et", "N5",
            "Relie des noms de façon exhaustive (« et ») ou indique avec qui l'on fait quelque chose.",
            "友達と話す。 Parler avec un ami.") { t, i ->
            i.takeIf { t[i].particle("と") && t[i].pos2 != "接続助詞" && t[i].pos3 != "引用" }
        }
        rule("to-quote", "〜と言う／〜と思う", "Citation", "N4",
            "Marque ce qui est dit, pensé ou nommé (« que… »).",
            "行くと言った。 Il a dit qu'il irait.") { t, i -> i.takeIf { t[i].particle("と") && t[i].pos3 == "引用" } }
        rule("to-cond", "〜と", "Condition automatique", "N4",
            "Après un verbe : conséquence naturelle ou habituelle (« quand…, alors… »).",
            "春になると、暖かくなる。 Quand vient le printemps, il fait plus doux.") { t, i ->
            i.takeIf { t[i].particle("と", pos2 = "接続助詞") }
        }
        rule("mo", "〜も", "Aussi, même", "N5",
            "« Aussi » ; avec une négation « non plus » ; après un nombre, insiste sur la quantité.",
            "私も行く。 J'y vais aussi.") { t, i -> i.takeIf { t[i].particle("も") } }
        rule("no", "〜の〜", "Lien entre deux noms", "N5",
            "Relie deux noms : possession ou précision (« de »). Le premier nom qualifie le second.",
            "私の本 Mon livre.") { t, i -> i.takeIf { t[i].particle("の", pos2 = "連体化") } }
        rule("no-nominal", "〜の", "Nominalisation", "N4",
            "Transforme une proposition en nom (« le fait de… », « celui qui… »).",
            "読むのが好き。 J'aime lire.") { t, i ->
            i.takeIf { t[i].pos1 == "名詞" && t[i].pos2 == "非自立" && t[i].surface in setOf("の", "ん") && !t.at(i + 1).aux("だ", "です") }
        }
        rule("noda", "〜のだ／〜んです", "Explication, insistance", "N4",
            "Présente la phrase comme une explication ou une mise en contexte (« c'est que… »).",
            "疲れたんです。 C'est que je suis fatigué.") { t, i ->
            if (t[i].pos1 == "名詞" && t[i].pos2 == "非自立" && t[i].surface in setOf("の", "ん") && t.at(i + 1).aux("だ", "です")) i + 1 else null
        }
        rule("kara-from", "〜から", "À partir de", "N5",
            "Point de départ dans l'espace ou le temps, ou origine (« de », « depuis »).",
            "東京から来た。 Je viens de Tokyo.") { t, i -> i.takeIf { t[i].particle("から", pos2 = "格助詞") } }
        rule("kara-because", "〜から", "Parce que", "N5",
            "Après une proposition : la cause (« parce que », « comme »), ton assez subjectif.",
            "寒いから、帰る。 Il fait froid, alors je rentre.") { t, i -> i.takeIf { t[i].particle("から", pos2 = "接続助詞") } }
        rule("made", "〜まで", "Jusqu'à", "N5", "Limite dans l'espace ou le temps (« jusqu'à »).",
            "五時まで働く。 Travailler jusqu'à 5 h.") { t, i -> i.takeIf { t[i].particle("まで") } }
        rule("yori", "〜より", "Comparaison", "N4",
            "« Plus que » dans une comparaison (A より B : B plutôt que A) ; à l'écrit, aussi « depuis ».",
            "昨日より暑い。 Il fait plus chaud qu'hier.") { t, i -> i.takeIf { t[i].particle("より") } }
        rule("ya", "〜や〜", "Et (énumération partielle)", "N5",
            "Énumère des exemples sans être exhaustif (« … et …, entre autres »).",
            "本や雑誌 Des livres, des magazines…") { t, i -> i.takeIf { t[i].particle("や", pos2 = "並立助詞") } }
        rule("ka", "〜か", "Question", "N5",
            "En fin de phrase : question. Entre deux noms : « ou ». Après un mot interrogatif : « quelque… ».",
            "行きますか。 Vous y allez ?") { t, i ->
            i.takeIf { t[i].particle("か") && !t.at(i + 1).particle("な", pos2 = "終助詞") }
        }
        rule("kana", "〜かな", "Je me demande si…", "N4",
            "Doute ou question posée à soi-même, ton familier (« je me demande si… », « est-ce que… ? »).",
            "わかるかな。 Est-ce qu'il va comprendre ?") { t, i ->
            if (t[i].particle("か") && t.at(i + 1).particle("な", pos2 = "終助詞")) i + 1 else null
        }
        rule("ne", "〜ね", "N'est-ce pas ?", "N5",
            "En fin de phrase : cherche l'accord ou la connivence (« hein », « n'est-ce pas »).",
            "いい天気ですね。 Il fait beau, hein.") { t, i -> i.takeIf { t[i].particle("ね", pos2 = "終助詞") } }
        rule("yo", "〜よ", "Affirmation", "N5",
            "En fin de phrase : apporte une information nouvelle ou insiste (« tu sais », « je te dis »).",
            "おいしいよ。 C'est bon, tu sais.") { t, i -> i.takeIf { t[i].particle("よ", pos2 = "終助詞") } }
        rule("na-prohibition", "〜な", "Interdiction", "N4",
            "Après un verbe au présent, en fin de phrase : ordre négatif brutal (« ne… pas ! »).",
            "動くな！ Ne bouge pas !") { t, i ->
            i.takeIf { t[i].particle("な", pos2 = "終助詞") && t.at(i - 1)?.pos1 == "動詞" && t.at(i - 1)?.conjForm == "基本形" && t.at(i + 1).isEnd() }
        }
        rule("shika", "〜しか〜ない", "Seulement (avec négation)", "N4",
            "しか + verbe négatif = « ne… que » : insiste sur le peu.",
            "百円しかない。 Je n'ai que 100 yens.") { t, i ->
            if (!t[i].particle("しか")) null
            else (i + 1 until t.size).firstOrNull { t[it].aux("ない", "ぬ", "ん") } ?: i
        }
        rule("dake", "〜だけ", "Seulement", "N5", "Limite (« seulement », « rien que »), sans jugement négatif.",
            "一つだけ Un seul.") { t, i -> i.takeIf { t[i].particle("だけ") } }
        rule("bakari", "〜ばかり", "Rien que, ne faire que", "N3",
            "« Rien que », « ne faire que » ; après た : « venir juste de ».",
            "遊んでばかりいる。 Il ne fait que s'amuser.") { t, i -> i.takeIf { t[i].particle("ばかり") } }
        rule("hodo", "〜ほど", "Au point de, environ", "N3",
            "Degré (« au point de ») ou quantité approximative ; A ほど〜ない : « pas autant que A ».",
            "死ぬほど疲れた。 Fatigué à en mourir.") { t, i -> i.takeIf { t[i].particle("ほど") } }
        rule("kurai", "〜くらい／ぐらい", "Environ, au point de", "N4",
            "Quantité approximative (« environ ») ou degré (« au point de »).",
            "十分ぐらい Environ dix minutes.") { t, i -> i.takeIf { t[i].particle("くらい", "ぐらい") } }
        rule("nado", "〜など", "Et cetera, par exemple", "N4", "Présente des exemples (« entre autres », « par exemple »).",
            "りんごなど Des pommes, entre autres.") { t, i -> i.takeIf { t[i].particle("など") } }
        rule("kedo", "〜けど", "Mais (familier)", "N4",
            "« Mais », « pourtant » ; en fin de phrase, adoucit ou laisse en suspens.",
            "行きたいけど、時間がない。 Je voudrais y aller, mais je n'ai pas le temps.") { t, i ->
            i.takeIf { t[i].particle("けど", "けれど", "けれども") }
        }
        rule("noni", "〜のに", "Alors que, pourtant", "N4",
            "Opposition avec une nuance de surprise, de reproche ou de regret (« alors que… »).",
            "約束したのに来なかった。 Il n'est pas venu alors qu'il avait promis.") { t, i -> i.takeIf { t[i].particle("のに") } }
        rule("node", "〜ので", "Parce que (explication)", "N4",
            "Cause présentée de façon objective et polie (« comme », « étant donné que »).",
            "雨なので、行かない。 Comme il pleut, je n'y vais pas.") { t, i -> i.takeIf { t[i].particle("ので") } }
        rule("nagara", "〜ながら", "Tout en…", "N4",
            "Deux actions simultanées du même sujet (« tout en… ») ; parfois « bien que ».",
            "歩きながら話す。 Parler en marchant.") { t, i -> i.takeIf { t[i].particle("ながら") } }
        rule("tari", "〜たり〜たりする", "Faire des choses comme…", "N4",
            "Énumère des actions à titre d'exemples (« faire entre autres… et… »).",
            "読んだり書いたりする。 Lire, écrire, ce genre de choses.") { t, i -> i.takeIf { t[i].particle("たり", "だり") } }
        rule("shi", "〜し", "Et en plus", "N4", "Accumule des raisons ou des faits (« et puis… », « en plus… »).",
            "安いし、おいしい。 C'est pas cher, et en plus c'est bon.") { t, i ->
            i.takeIf { t[i].particle("し", pos2 = "接続助詞") }
        }
        rule("demo", "〜でも", "Même, … ou autre", "N4",
            "Après un nom : « même » ; propose un exemple parmi d'autres (« un café ou quelque chose »).",
            "子供でもわかる。 Même un enfant comprend.") { t, i -> i.takeIf { t[i].particle("でも") } }
        rule("sae", "〜さえ", "Même", "N3", "« Même » (cas extrême) ; さえ〜ば : « il suffit que… ».",
            "名前さえ知らない。 Je ne connais même pas son nom.") { t, i -> i.takeIf { t[i].particle("さえ") } }
        rule("koso", "〜こそ", "Justement, c'est bien…", "N3", "Met fortement en valeur le mot qui précède.",
            "今年こそ Cette année, c'est la bonne.") { t, i -> i.takeIf { t[i].particle("こそ") } }
        rule("tte", "〜って", "Citation familière", "N4",
            "Équivaut à と / という à l'oral : « il paraît que », « (ce qu'on appelle)… ».",
            "明日来るって。 Il dit qu'il vient demain.") { t, i -> i.takeIf { t[i].particle("って") } }
        rule("toka", "〜とか", "Des choses comme", "N4", "Donne des exemples de façon vague, ton familier.",
            "映画とか見る。 Je regarde des films, des trucs comme ça.") { t, i -> i.takeIf { t[i].particle("とか") } }

        // ---------------- Formes verbales et auxiliaires ----------------
        rule("ta", "〜た", "Passé, action accomplie", "N5",
            "Forme passée des verbes, adjectifs et de la copule ; aussi une action achevée.",
            "食べた。 J'ai mangé.") { t, i -> i.takeIf { t[i].aux("た") && t[i].surface in setOf("た", "だ") } }
        rule("nai", "〜ない", "Négation", "N5", "Forme négative (« ne… pas »). Se conjugue comme un adjectif en -i.",
            "わからない。 Je ne comprends pas.") { t, i -> i.takeIf { t[i].aux("ない") } }
        rule("nu", "〜ぬ／〜ん／〜ず", "Négation (littéraire ou familière)", "N3",
            "Ancienne négation : ぬ (écrit, archaïque), ん (oral), ず / ずに (« sans… »).",
            "知らずに Sans le savoir.") { t, i -> i.takeIf { t[i].aux("ぬ", "ん") } }
        rule("masu", "〜ます", "Forme polie", "N5", "Rend le verbe poli (style neutre-poli, avec des inconnus).",
            "行きます。 J'y vais.") { t, i -> i.takeIf { t[i].aux("ます") } }
        rule("desu", "〜です", "Copule polie", "N5", "« Être » en style poli ; rend aussi un adjectif poli.",
            "学生です。 Je suis étudiant.") { t, i -> i.takeIf { t[i].aux("です") } }
        rule("da", "〜だ", "Copule", "N5", "« Être » en style neutre (だ, だった, で…).",
            "学生だ。 Je suis étudiant.") { t, i ->
            i.takeIf {
                t[i].aux("だ") && t[i].conjForm != "体言接続" && t[i].conjForm != "仮定形" &&
                    t.at(i - 1)?.pos3 != "助動詞語幹" && !(t[i].surface == "で" && t.at(i + 1).aux("ある"))
            }
        }
        rule("dearu", "〜である", "Copule écrite", "N3",
            "« Être » en style écrit (récits, explications, articles) : である, であった.",
            "藤原氏であった。 C'étaient les Fujiwara.") { t, i ->
            if (t[i].aux("だ") && t[i].surface == "で" && t.at(i + 1).aux("ある")) i + 1 else null
        }
        rule("na-adjective", "〜な＋nom", "Adjectif en -na devant un nom", "N5",
            "な relie un adjectif en -na (ou みたい, よう) au nom qu'il qualifie.",
            "静かな部屋 Une pièce calme.") { t, i -> i.takeIf { t[i].aux("だ") && t[i].conjForm == "体言接続" } }
        rule("teiru", "〜ている", "Action en cours, état", "N5",
            "Action en train de se faire, habitude, ou état résultant d'une action passée. " +
                "À l'oral, souvent contracté en 〜てる.",
            "備えていた Était doté de (état).") { t, i -> teForm(t, i, "いる", "てる") }
        rule("teshimau", "〜てしまう／〜ちゃう", "Action achevée (regret)", "N4",
            "Action complètement terminée, souvent involontaire ou regrettée (« finir par », « malheureusement »).",
            "食べてしまった。 J'ai tout mangé (oups).") { t, i -> teForm(t, i, "しまう", "ちゃう", "じゃう") }
        rule("teoku", "〜ておく", "Faire à l'avance", "N4",
            "Faire quelque chose en prévision, ou laisser dans un état.",
            "買っておく。 L'acheter à l'avance.") { t, i -> teForm(t, i, "おく", "とく") }
        rule("temiru", "〜てみる", "Essayer", "N4", "Faire quelque chose pour voir (« essayer de »).",
            "食べてみる。 Goûter pour voir.") { t, i -> teForm(t, i, "みる") }
        rule("tekuru", "〜てくる／〜ていく", "Évolution, direction", "N3",
            "てくる : vers le locuteur ou le présent (« commencer à »). ていく : s'éloigne ou continue dans le futur.",
            "寒くなってきた。 Il commence à faire froid.") { t, i -> teForm(t, i, "くる", "いく") }
        rule("tearu", "〜てある", "État résultant (voulu)", "N4",
            "Quelque chose a été fait (par quelqu'un) et l'état demeure.",
            "窓が開けてある。 La fenêtre a été ouverte (et l'est).") { t, i -> teForm(t, i, "ある") }
        rule("tekureru", "〜てくれる／〜てもらう／〜てあげる", "Service rendu", "N4",
            "Faire quelque chose pour quelqu'un : くれる (pour moi), もらう (je reçois le service), あげる (je le rends).",
            "教えてくれた。 Il me l'a expliqué.") { t, i -> teForm(t, i, "くれる", "もらう", "あげる", "くださる", "いただく") }
        rule("temoii", "〜てもいい", "Permission", "N4", "« On peut », « ça ne dérange pas si… ».",
            "読んでもいいですか。 Je peux le lire ?") { t, i ->
            if (t[i].te() && t.at(i + 1).particle("も") && t.at(i + 2)?.baseForm in setOf("いい", "よい", "良い")) i + 2 else null
        }
        rule("tewaikenai", "〜てはいけない", "Interdiction", "N4",
            "« Il ne faut pas », « interdit de ». À l'oral : 〜ちゃいけない / 〜じゃだめ.",
            "ここで吸ってはいけない。 Interdit de fumer ici.") { t, i ->
            val forbid = setOf("いける", "なる", "だめ")
            val end = when {
                t[i].te() && t.at(i + 1).particle("は") && t.at(i + 2)?.baseForm in forbid -> i + 2
                t[i].particle("ちゃ", "じゃ") && t.at(i + 1)?.baseForm in forbid -> i + 1
                else -> null
            }
            end?.let { if (t.at(it + 1).aux("ない")) it + 1 else it }
        }
        rule("rareru", "〜れる／〜られる", "Passif, potentiel ou respect", "N4",
            "Selon le contexte : passif (« être … par »), potentiel (« pouvoir »), ou forme de respect.",
            "見られた Avoir été vu.") { t, i -> i.takeIf { t[i].pos1 == "動詞" && t[i].pos2 == "接尾" && t[i].baseForm in setOf("れる", "られる") } }
        rule("saseru", "〜せる／〜させる", "Causatif", "N4", "« Faire faire » ou « laisser faire » quelque chose à quelqu'un.",
            "勉強させる Faire étudier.") { t, i -> i.takeIf { t[i].pos1 == "動詞" && t[i].pos2 == "接尾" && t[i].baseForm in setOf("せる", "させる") } }
        rule("saserareru", "〜させられる", "Causatif-passif", "N3", "« Être obligé de » (on me fait faire).",
            "勉強させられた。 On m'a obligé à étudier.") { t, i ->
            if (t[i].pos2 == "接尾" && t[i].baseForm in setOf("せる", "させる") && t.at(i + 1)?.baseForm in setOf("られる", "れる")) i + 1 else null
        }
        rule("tai", "〜たい", "Désir", "N5", "« Vouloir » faire (pour soi) ; se conjugue comme un adjectif en -i.",
            "行きたい。 Je veux y aller.") { t, i -> i.takeIf { t[i].aux("たい") } }
        rule("volitional", "〜う／〜よう", "Volitif", "N4", "Proposition (« faisons ») ou intention (« je vais »).",
            "行こう！ Allons-y !") { t, i -> i.takeIf { t[i].aux("う", "よう") } }
        rule("youtoomou", "〜ようと思う", "Avoir l'intention de", "N4", "Volitif + と思う : « je pense faire », « j'ai l'intention de ».",
            "行こうと思う。 Je pense y aller.") { t, i ->
            if (t[i].aux("う", "よう") && t.at(i + 1).particle("と") && t.at(i + 2)?.baseForm in setOf("思う", "する")) i + 2 else null
        }
        rule("ba", "〜ば", "Condition", "N4", "« Si » : la condition entraîne la conséquence.",
            "安ければ買う。 Si c'est pas cher, j'achète.") { t, i -> i.takeIf { t[i].particle("ば", pos2 = "接続助詞") } }
        rule("nakereba", "〜なければならない", "Obligation", "N4",
            "« Il faut », « devoir » (littéralement « si on ne le fait pas, ça ne va pas »). Oral : 〜なきゃ.",
            "行かなければならない。 Je dois y aller.") { t, i ->
            if (t[i].aux("ない") && t[i].conjForm == "仮定形" && t.at(i + 1).particle("ば") &&
                t.at(i + 2)?.baseForm in setOf("なる", "いける") && t.at(i + 3).aux("ない")
            ) i + 3 else null
        }
        rule("tara", "〜たら", "Si, quand", "N4", "Condition ou moment (« si… », « une fois que… », « quand… »).",
            "着いたら電話する。 J'appelle quand j'arrive.") { t, i -> i.takeIf { t[i].aux("た") && t[i].surface in setOf("たら", "だら") } }
        rule("nara", "〜なら", "Si c'est…", "N4", "Condition sur un sujet évoqué (« si c'est ça… », « quant à… »).",
            "日本語なら話せる。 Le japonais, je le parle.") { t, i -> i.takeIf { t[i].aux("だ") && t[i].conjForm == "仮定形" } }
        rule("sou-looks", "〜そう（だ）", "Apparence", "N4",
            "Après un radical de verbe ou d'adjectif : « avoir l'air de », « on dirait que ça va… ».",
            "雨が降りそうだ。 On dirait qu'il va pleuvoir.") { t, i ->
            i.takeIf { t[i].pos1 == "名詞" && t[i].pos3 == "助動詞語幹" && t[i].baseForm == "そう" }
        }
        rule("sou-hearsay", "〜そうだ", "Ouï-dire", "N4", "Après une forme complète : « j'ai entendu dire que », « il paraît que ».",
            "明日は雨だそうだ。 Il paraît qu'il pleuvra demain.") { t, i -> i.takeIf { t[i].aux("そうだ") } }
        rule("you", "〜よう（だ／な／に）", "Ressemblance, manière", "N4",
            "« Comme », « on dirait » (ようだ, ような) ; « de façon à » (ように).",
            "独立国のような状態 Une situation comme celle d'un pays indépendant.") { t, i ->
            i.takeIf { t[i].pos1 == "名詞" && t[i].baseForm == "よう" && t[i].pos3 == "助動詞語幹" }
        }
        rule("mitai", "〜みたい", "Comme, on dirait (familier)", "N4", "Équivalent familier de ようだ.",
            "猫みたいな人 Quelqu'un comme un chat.") { t, i -> i.takeIf { t[i].baseForm == "みたい" && t[i].pos1 == "名詞" } }
        rule("rashii", "〜らしい", "Il paraît que, typique de", "N4",
            "Supposition fondée sur des indices (« il paraît que ») ; après un nom : « digne de », « typique de ».",
            "来ないらしい。 Il paraît qu'il ne vient pas.") { t, i -> i.takeIf { t[i].aux("らしい") } }
        rule("koto-dekiru", "〜ことができる", "Pouvoir (capacité)", "N4", "« Être capable de », « avoir la possibilité de ».",
            "泳ぐことができる。 Je sais nager.") { t, i ->
            if (t[i].surface == "こと" && t.at(i + 1).particle("が") && t.at(i + 2)?.baseForm == "できる") i + 2 else null
        }
        rule("takotogaaru", "〜たことがある", "Expérience", "N4", "« Avoir déjà fait » (au moins une fois).",
            "行ったことがある。 J'y suis déjà allé.") { t, i ->
            if (t[i].aux("た") && t.at(i + 1)?.surface == "こと" && t.at(i + 2).particle("が", "も") && t.at(i + 3)?.baseForm == "ある") i + 3 else null
        }
        rule("koto", "〜こと", "Nominalisation", "N4", "こと transforme une proposition en nom (« le fait de… »).",
            "読むことが好き。 J'aime lire.") { t, i ->
            i.takeIf { t[i].surface == "こと" && t[i].pos2 == "非自立" && t.at(i - 1)?.pos1 in setOf("動詞", "助動詞", "形容詞") }
        }
        rule("tsumori", "〜つもり", "Intention", "N4", "« Avoir l'intention de », « compter faire ».",
            "行くつもりだ。 Je compte y aller.") { t, i -> i.takeIf { t[i].surface == "つもり" } }
        rule("hazu", "〜はず", "Devrait (attente logique)", "N3", "Ce qui devrait être vrai logiquement (« normalement… »).",
            "もう着いたはずだ。 Il devrait être arrivé.") { t, i -> i.takeIf { t[i].surface == "はず" && t[i].pos1 == "名詞" } }
        rule("beki", "〜べき", "Il faut (devoir moral)", "N3", "Ce qu'il convient de faire (« on devrait »).",
            "謝るべきだ。 Tu devrais t'excuser.") { t, i -> i.takeIf { t[i].baseForm == "べし" || t[i].surface == "べき" } }
        rule("o-prefix", "お〜／ご〜", "Préfixe de politesse", "N5", "お / ご devant un nom : politesse ou respect.",
            "お茶 Le thé.") { t, i -> i.takeIf { t[i].pos1 == "接頭詞" && t[i].surface in setOf("お", "ご", "御") } }
    }
}
