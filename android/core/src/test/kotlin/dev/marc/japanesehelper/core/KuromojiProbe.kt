package dev.marc.japanesehelper.core

import com.atilika.kuromoji.ipadic.Tokenizer
import kotlin.test.Test

/** Exploration : ce que Kuromoji renvoie sur des phrases typiques (pas un vrai test). */
class KuromojiProbe {
    @Test
    fun probe() {
        if (System.getProperty("probe") == null) return
        val t = Tokenizer()
        listOf(
            "義経が身を寄せた奥州平泉は",
            "初代清衡から三代続く藤原氏であった。",
            "十八万騎をほこる強大な軍事力を備えていた。",
            "警察にも先生にも町中の人達に！！",
            "少し黙っている",
            "わかるかな〜？",
            "食べてしまった",
            "行かなければならない",
            "雨が降りそうだ",
            "彼は来ないらしい",
            "読んでもいいですか",
            "見られたくなかったのに",
            "猫みたいな人だった",
            "勉強させられました",
            "明日は行こうと思う",
            "こんなことしか言えない",
            "高くない",
            "静かな部屋でした",
        ).forEach { s ->
            println("== $s")
            t.tokenize(s).forEach { k ->
                println("  ${k.surface}\t${k.partOfSpeechLevel1}-${k.partOfSpeechLevel2}-${k.partOfSpeechLevel3}\t${k.conjugationType}\t${k.conjugationForm}\tbase=${k.baseForm}\tread=${k.reading}")
            }
        }
    }
}
