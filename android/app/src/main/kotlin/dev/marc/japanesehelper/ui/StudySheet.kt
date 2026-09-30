package dev.marc.japanesehelper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.marc.japanesehelper.BubbleText
import dev.marc.japanesehelper.DictEntry
import dev.marc.japanesehelper.KanjiInfo
import dev.marc.japanesehelper.Selection
import dev.marc.japanesehelper.Sense
import dev.marc.japanesehelper.WordInfo
import dev.marc.japanesehelper.core.text.Analysis
import dev.marc.japanesehelper.core.text.Furigana
import dev.marc.japanesehelper.core.text.GrammarPoint
import dev.marc.japanesehelper.core.text.Lang
import dev.marc.japanesehelper.core.text.text
import androidx.compose.ui.res.stringResource
import dev.marc.japanesehelper.R
import dev.marc.japanesehelper.contentLang
import dev.marc.japanesehelper.core.text.Kana
import dev.marc.japanesehelper.core.text.Ruby
import dev.marc.japanesehelper.core.text.Word
import dev.marc.japanesehelper.core.text.WordKind

/** Couleur d'un mot selon sa nature, pour lire la structure de la phrase d'un coup d'œil. */
@Composable
private fun kindColor(kind: WordKind): Color {
    val dark = MaterialTheme.colorScheme.background.let { it.red + it.green + it.blue < 1.5f }
    return when (kind) {
        WordKind.VERB -> if (dark) Color(0xFF82B1FF) else Color(0xFF1565C0)
        WordKind.I_ADJECTIVE, WordKind.NA_ADJECTIVE -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        WordKind.PARTICLE -> if (dark) Color(0xFFFFB74D) else Color(0xFFE65100)
        WordKind.COPULA -> if (dark) Color(0xFFBDBDBD) else Color(0xFF616161)
        WordKind.PROPER_NOUN -> if (dark) Color(0xFFCE93D8) else Color(0xFF7B1FA2)
        WordKind.ADVERB -> if (dark) Color(0xFF4DD0E1) else Color(0xFF00838F)
        else -> MaterialTheme.colorScheme.onSurface
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudySheet(
    selection: Selection,
    onWord: (Int) -> Unit,
    onGrammar: (GrammarPoint) -> Unit,
    tagLabel: (String, Lang) -> String,
    onDismiss: () -> Unit,
) {
    val lang = contentLang()
    val label: (String) -> String = { tagLabel(it, lang) }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    var furigana by rememberSaveable { mutableStateOf(true) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val clipboard = LocalClipboardManager.current

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding()) {
            when (val text = selection.text) {
                BubbleText.Reading -> Loading(stringResource(R.string.study_reading))
                is BubbleText.Failed -> Text(stringResource(R.string.study_error, text.message), color = MaterialTheme.colorScheme.error)
                is BubbleText.Done -> {
                    val analysis = selection.analysis
                    if (analysis == null) {
                        Text(text.text, fontSize = 24.sp)
                        Loading(stringResource(R.string.study_analyzing))
                    } else {
                        val highlight = analysis.grammar.filter { it.point == selection.grammar }.map { it.first..it.last }
                        Sentence(analysis, selection.readings, selection.word, highlight, furigana, onWord)
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = furigana, onClick = { furigana = !furigana }, label = { Text(stringResource(R.string.study_furigana)) })
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { clipboard.setText(AnnotatedString(text.text)) }) { Text(stringResource(R.string.study_copy)) }
                    }
                    if (analysis != null) {
                        TabRow(selectedTabIndex = tab) {
                            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.study_tab_word)) })
                            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.study_tab_grammar, analysis.grammar.distinctBy { it.point }.size)) })
                        }
                        // Seul le contenu de l'onglet défile : la phrase reste visible
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                            Spacer(Modifier.height(12.dp))
                            when (tab) {
                                0 -> WordTab(analysis, selection.word, selection.wordInfo, selection.readings, lang, label)
                                else -> GrammarTab(analysis, selection.grammar, lang, onGrammar)
                            }
                            Spacer(Modifier.height(24.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Loading(label: String) {
    Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(12.dp))
        Text(label)
    }
}

/** La phrase, mot par mot, avec furigana ; chaque mot se touche. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Sentence(
    analysis: Analysis,
    readings: Map<Int, String>,
    selected: Int?,
    highlight: List<IntRange>,
    furigana: Boolean,
    onWord: (Int) -> Unit,
) {
    FlowRow(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        analysis.words.forEachIndexed { i, word ->
            val inGrammar = highlight.any { word.lastToken >= it.first && word.firstToken <= it.last }
            val bg = when {
                i == selected -> MaterialTheme.colorScheme.primaryContainer
                inGrammar -> Color(0x66FFEB3B)
                else -> Color.Transparent
            }
            Box(
                Modifier
                    .padding(horizontal = 1.dp)
                    .background(bg, RoundedCornerShape(6.dp))
                    .then(if (word.isClickable) Modifier.clickable { onWord(i) } else Modifier)
                    .padding(horizontal = 2.dp),
            ) {
                val parts = readings[i]?.let { Furigana.align(word.surface, it) } ?: word.furigana
                RubyText(parts, kindColor(word.kind), furigana, 26.sp)
            }
        }
    }
}

/** Texte avec lecture au-dessus des kanji ; une ligne de lecture vide garde l'alignement. */
@Composable
private fun RubyText(parts: List<Ruby>, color: Color, showReading: Boolean, size: TextUnit) {
    Row(verticalAlignment = Alignment.Bottom) {
        parts.forEach { part ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (showReading) {
                    Text(
                        part.reading.orEmpty(),
                        fontSize = size * 0.42f,
                        lineHeight = size * 0.5f,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Text(part.text, fontSize = size, lineHeight = size * 1.2f, color = color)
            }
        }
    }
}

// ---------------- Onglet « Mot » ----------------

@Composable
private fun WordTab(
    analysis: Analysis,
    index: Int?,
    info: WordInfo?,
    readings: Map<Int, String>,
    lang: Lang,
    tagLabel: (String) -> String,
) {
    if (index == null) {
        Text(stringResource(R.string.study_word_hint), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Legend(lang)
        return
    }
    val word = analysis.words[index]
    WordHeader(word, info, readings[index], lang)
    Inflection(word, lang)
    when {
        info == null -> Loading(stringResource(R.string.study_looking_up))
        info.results.isEmpty() -> Text(stringResource(R.string.study_no_entry), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Definitions(info, lang, tagLabel)
    }
    info?.kanji?.takeIf { it.isNotEmpty() }?.let { KanjiSection(it, lang) }
}

@Composable
private fun WordHeader(word: Word, info: WordInfo?, corrected: String?, lang: Lang) {
    val entry = info?.results?.firstOrNull()?.entries?.firstOrNull()
    // Lecture : corrigée par JMdict, sinon celle de Kuromoji, sinon celle du dictionnaire
    val parts = if (corrected != null) {
        Furigana.align(word.surface, corrected)
    } else if (word.reading == null && entry != null && Kana.hasKanji(word.surface)) {
        Furigana.align(word.surface, entry.kana.firstOrNull())
    } else {
        word.furigana
    }
    Row(verticalAlignment = Alignment.Bottom) {
        SelectionContainer { RubyText(parts, kindColor(word.kind), true, 34.sp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.padding(bottom = 6.dp)) {
            Text(word.kind.label(lang), style = MaterialTheme.typography.labelLarge, color = kindColor(word.kind))
            if (word.lemma != word.surface) {
                Text(stringResource(R.string.study_base_form, word.lemma), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** Décomposition : 寄せた = 寄せる + た (passé). */
@Composable
private fun Inflection(word: Word, lang: Lang) {
    val steps = word.inflection
    if (steps.isEmpty()) return
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                "${word.surface} = ${word.lemma} + " + steps.joinToString(" + ") { it.label },
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            val sep = if (lang == Lang.FR) " : " else ": "
            steps.forEach { Text("• ${it.label}$sep${it.meaning(lang)}", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun Definitions(info: WordInfo, lang: Lang, tagLabel: (String) -> String) {
    info.results.forEachIndexed { r, result ->
        if (r > 0) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.study_other_split, result.key), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        result.entries.take(if (r == 0) 3 else 1).forEach { EntryCard(it, lang, tagLabel) }
    }
}

@Composable
private fun EntryCard(entry: DictEntry, lang: Lang, tagLabel: (String) -> String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val written = (entry.kanji.take(2) + entry.kana.take(2)).distinct().joinToString(" ・ ")
            Text(written, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (entry.common) {
                Spacer(Modifier.width(8.dp))
                Badge(stringResource(R.string.study_common), MaterialTheme.colorScheme.tertiaryContainer)
            }
        }
        // Définitions françaises (JMdict fre) seulement en français ; l'anglais toujours
        if (lang == Lang.FR && entry.sensesFr.isNotEmpty()) {
            SensesList(entry.sensesFr, lang, tagLabel)
            Text(stringResource(R.string.study_in_english), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        SensesList(entry.senses.take(6), lang, tagLabel)
        HorizontalDivider(Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SensesList(senses: List<Sense>, lang: Lang, tagLabel: (String) -> String) {
    // Typographie : espace avant le point-virgule en français seulement
    val sep = if (lang == Lang.FR) " ; " else "; "
    senses.forEachIndexed { i, s ->
        Column(Modifier.padding(top = 4.dp)) {
            val tags = (s.pos + s.misc).map(tagLabel).distinct()
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${i + 1}. " + s.glosses.joinToString(sep), style = MaterialTheme.typography.bodyLarge)
            s.info.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun KanjiSection(kanji: List<KanjiInfo>, lang: Lang) {
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.study_kanji), style = MaterialTheme.typography.titleMedium)
    kanji.forEach { k ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                k.literal, fontSize = 40.sp, textAlign = TextAlign.Center,
                modifier = Modifier.width(64.dp).border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val meanings = if (lang == Lang.FR) k.meaningsFr.ifEmpty { k.meaningsEn } else k.meaningsEn
                Text(meanings.take(6).joinToString(", "), style = MaterialTheme.typography.bodyLarge)
                if (k.onyomi.isNotEmpty()) Text(stringResource(R.string.kanji_on, k.onyomi.joinToString("、")), style = MaterialTheme.typography.bodyMedium)
                if (k.kunyomi.isNotEmpty()) Text(stringResource(R.string.kanji_kun, k.kunyomi.joinToString("、")), style = MaterialTheme.typography.bodyMedium)
                val meta = listOfNotNull(
                    k.jlpt?.let { stringResource(R.string.kanji_jlpt, it) },
                    k.grade?.let { if (it <= 6) stringResource(R.string.kanji_grade, it) else stringResource(R.string.kanji_jouyou) },
                    k.strokes?.let { stringResource(R.string.kanji_strokes, it) },
                )
                if (meta.isNotEmpty()) {
                    Text(meta.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(lang: Lang) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(WordKind.NOUN, WordKind.VERB, WordKind.I_ADJECTIVE, WordKind.PARTICLE, WordKind.PROPER_NOUN, WordKind.ADVERB, WordKind.COPULA)
            .forEach {
                val label = if (it == WordKind.I_ADJECTIVE) stringResource(R.string.study_adjective) else it.label(lang)
                Text(label, color = kindColor(it), style = MaterialTheme.typography.labelLarge)
            }
    }
}

// ---------------- Onglet « Grammaire » ----------------

@Composable
private fun GrammarTab(analysis: Analysis, selected: GrammarPoint?, lang: Lang, onGrammar: (GrammarPoint) -> Unit) {
    if (analysis.grammar.isEmpty()) {
        Text(stringResource(R.string.grammar_none), color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    Text(
        stringResource(R.string.grammar_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Un point par fiche, même s'il apparaît plusieurs fois (deux を…)
    analysis.grammar.groupBy { it.point }.forEach { (p, matches) ->
        val text = p.text(lang)
        val excerpts = matches.map { m -> analysis.tokens.subList(m.first, m.last + 1).joinToString("") { it.surface } }.distinct()
        Surface(
            color = if (p == selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onGrammar(p) },
        ) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text.pattern, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(text.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Badge(p.level, levelColor(p.level))
                }
                val quoted = excerpts.map { stringResource(R.string.grammar_quote, it) }.joinToString(", ")
                val times = if (matches.size > 1) " " + stringResource(R.string.grammar_times, matches.size) else ""
                Text(
                    stringResource(R.string.grammar_in_bubble, quoted) + times,
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(4.dp))
                Text(text.explanation, style = MaterialTheme.typography.bodyMedium)
                text.exampleJa?.let {
                    Text(stringResource(R.string.grammar_example, "$it ${text.exampleTranslation.orEmpty()}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

private fun levelColor(level: String) = when (level) {
    "N5" -> Color(0xFFC8E6C9)
    "N4" -> Color(0xFFBBDEFB)
    "N3" -> Color(0xFFFFE0B2)
    else -> Color(0xFFF8BBD0)
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = Color.Black,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.background(color, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}
