package dev.marc.japanesehelper.core

import dev.marc.japanesehelper.core.text.Furigana
import dev.marc.japanesehelper.core.text.JapaneseAnalyzer
import dev.marc.japanesehelper.core.text.Ruby
import dev.marc.japanesehelper.core.text.WordKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalysisTest {
    private fun words(text: String) = JapaneseAnalyzer.analyze(text).words.map { it.surface }
    private fun grammar(text: String) = JapaneseAnalyzer.analyze(text).grammar.map { it.point.id }

    @Test
    fun groupsInflectedWords() {
        assertEquals(listOf("義経", "が", "身", "を", "寄せた", "奥州", "平泉", "は"), words("義経が身を寄せた奥州平泉は"))
        assertEquals(listOf("十八万騎", "を", "ほこる", "強大な", "軍事力", "を", "備えていた", "。"), words("十八万騎をほこる強大な軍事力を備えていた。"))
        assertEquals(listOf("勉強させられました"), words("勉強させられました"))
        assertEquals(listOf("行かなければならない"), words("行かなければならない"))
        assertEquals(listOf("見られたくなかった", "のに"), words("見られたくなかったのに"))
    }

    @Test
    fun lemmasAndKinds() {
        val a = JapaneseAnalyzer.analyze("義経が身を寄せた")
        val yoseta = a.words[4]
        assertEquals("寄せる", yoseta.lemma)
        assertEquals(WordKind.VERB, yoseta.kind)
        assertEquals(listOf("た"), yoseta.inflection.map { it.surface })
        val study = JapaneseAnalyzer.analyze("勉強させられました").words[0]
        assertEquals("勉強する", study.lemma)
        assertEquals("勉強する", JapaneseAnalyzer.analyze("勉強させられました").lookupKeys(0).first())
    }

    @Test
    fun compoundLookupKeys() {
        val a = JapaneseAnalyzer.analyze("中尊寺金色堂")
        val keys = a.lookupKeys(0)
        assertEquals(a.words.joinToString("") { it.surface }, keys.first())
    }

    @Test
    fun furiganaAlignsOnKanji() {
        assertEquals(listOf(Ruby("寄", "よ"), Ruby("せ")), Furigana.align("寄せ", "よせ"))
        assertEquals(listOf(Ruby("奥州", "おうしゅう")), Furigana.align("奥州", "おうしゅう"))
        assertEquals(listOf(Ruby("お"), Ruby("茶", "ちゃ")), Furigana.align("お茶", "おちゃ"))
        assertEquals(listOf(Ruby("食", "た"), Ruby("べ"), Ruby("物", "もの")), Furigana.align("食べ物", "たべもの"))
        assertEquals(listOf(Ruby("ハンパ")), Furigana.align("ハンパ", "はんぱ"))
    }

    @Test
    fun detectsGrammar() {
        assertTrue("teiru" in grammar("少し黙っている"))
        assertTrue("dearu" in grammar("藤原氏であった。"))
        assertTrue("nakereba" in grammar("行かなければならない"))
        assertTrue("kana" in grammar("わかるかな〜？"))
        assertTrue("shika" in grammar("こんなことしか言えない"))
        assertTrue("saserareru" in grammar("勉強させられました"))
        assertTrue("temoii" in grammar("読んでもいいですか"))
        assertTrue("sou-looks" in grammar("雨が降りそうだ"))
        assertTrue("you" in grammar("まるで独立国のような状態にあり"))
        assertTrue("youtoomou" in grammar("明日は行こうと思う"))
    }
}
