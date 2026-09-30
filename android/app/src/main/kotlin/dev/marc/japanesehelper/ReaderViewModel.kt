package dev.marc.japanesehelper

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.marc.japanesehelper.core.Detection
import dev.marc.japanesehelper.core.text.Analysis
import dev.marc.japanesehelper.core.text.GrammarPoint
import dev.marc.japanesehelper.core.text.JapaneseAnalyzer
import dev.marc.japanesehelper.core.text.Kana
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Texte de la bulle sélectionnée. */
sealed interface BubbleText {
    data object Reading : BubbleText
    data class Done(val text: String) : BubbleText
    data class Failed(val message: String) : BubbleText
}

/** Résultats du dictionnaire pour une forme cherchée. */
data class LookupResult(val key: String, val entries: List<DictEntry>)

/** Fiche d'un mot : résultats par forme (la plus pertinente d'abord) et ses kanji. */
data class WordInfo(val results: List<LookupResult>, val kanji: List<KanjiInfo>)

/**
 * Bulle sélectionnée : texte reconnu, analyse (mots, grammaire), et ce que l'on étudie
 * dedans ([word] : indice du mot touché, [grammar] : point de grammaire mis en évidence).
 */
data class Selection(
    val page: Int,
    val detection: Detection,
    val text: BubbleText,
    val analysis: Analysis? = null,
    val word: Int? = null,
    val wordInfo: WordInfo? = null,
    val grammar: GrammarPoint? = null,
)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = OcrEngine(app)

    /** Dictionnaire, ouvert en arrière-plan au démarrage (copie de ~60 Mo la 1re fois). */
    private val dictionary = viewModelScope.async(Dispatchers.IO) { Dictionary.open(app) }

    private val _source = MutableStateFlow<PageSource?>(null)
    val source: StateFlow<PageSource?> = _source.asStateFlow()

    private val _modelsReady = MutableStateFlow(false)
    val modelsReady: StateFlow<Boolean> = _modelsReady.asStateFlow()

    /** Étape de chargement en cours, affichée tant que les modèles ne sont pas prêts. */
    private val _loadingStatus = MutableStateFlow("Préparation des modèles…")
    val loadingStatus: StateFlow<String> = _loadingStatus.asStateFlow()

    /** Zones détectées par page (absente = pas encore analysée). */
    private val _detections = MutableStateFlow<Map<Int, List<Detection>>>(emptyMap())
    val detections: StateFlow<Map<Int, List<Detection>>> = _detections.asStateFlow()

    private val _selection = MutableStateFlow<Selection?>(null)
    val selection: StateFlow<Selection?> = _selection.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _cameraOpen = MutableStateFlow(false)
    val cameraOpen: StateFlow<Boolean> = _cameraOpen.asStateFlow()

    /** Page à afficher après un changement de source (nouvelle photo). */
    private val _jumpTo = MutableStateFlow<Int?>(null)
    val jumpTo: StateFlow<Int?> = _jumpTo.asStateFlow()

    /** Dossier des photos de la séance en cours. */
    val capturesDir = java.io.File(app.cacheDir, "captures")

    private val detecting = mutableSetOf<Int>()
    private var ocrJob: Job? = null

    init {
        // Analyseur japonais : ~1 s de chargement, fait d'avance
        viewModelScope.launch(Dispatchers.Default) { JapaneseAnalyzer.warmUp() }
        // Chargement anticipé : la copie et l'initialisation prennent quelques secondes
        viewModelScope.launch {
            runCatching { engine.load { _loadingStatus.value = it } }
                .onSuccess { _modelsReady.value = true }
                .onFailure {
                    _loadingStatus.value = "Reconnaissance du texte indisponible sur cet appareil"
                    report("Chargement des modèles impossible (NPU Qualcomm requis)", it)
                }
        }
    }

    fun open(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            runCatching { PageSource.open(getApplication(), uris) }
                .onSuccess { newSource ->
                    _source.value?.close()
                    detecting.clear()
                    _detections.value = emptyMap()
                    _selection.value = null
                    _source.value = newSource
                }
                .onFailure { report("Ouverture impossible", it) }
        }
    }

    fun openCamera() {
        _cameraOpen.value = true
    }

    fun closeCamera() {
        _cameraOpen.value = false
    }

    /** Nouvelle photo : ajoutée à la séance en cours, ou début d'une nouvelle séance. */
    fun onPhotoCaptured(file: java.io.File) {
        val current = _source.value as? CapturedPages
        val files = if (current != null) {
            current.files + file
        } else {
            // Nouvelle séance : on oublie les photos précédentes
            capturesDir.listFiles()?.filter { it != file }?.forEach { it.delete() }
            _source.value?.close()
            detecting.clear()
            _detections.value = emptyMap()
            listOf(file)
        }
        _selection.value = null
        _source.value = CapturedPages(files)
        _jumpTo.value = files.lastIndex
        _cameraOpen.value = false
    }

    fun jumpDone() {
        _jumpTo.value = null
    }

    fun close() {
        _source.value?.close()
        _source.value = null
        _selection.value = null
    }

    /** Lance la détection d'une page affichée, une seule fois. */
    fun detect(page: Int, bitmap: Bitmap) {
        if (page in _detections.value || !detecting.add(page)) return
        viewModelScope.launch {
            runCatching { engine.detect(bitmap) }
                .onSuccess { dets -> _detections.update { it + (page to dets) } }
                .onFailure { report("Détection impossible", it) }
            detecting.remove(page)
        }
    }

    fun read(page: Int, bitmap: Bitmap, detection: Detection) {
        ocrJob?.cancel()
        _selection.value = Selection(page, detection, BubbleText.Reading)
        ocrJob = viewModelScope.launch {
            val text = runCatching { engine.recognize(bitmap, detection) }
                .fold({ BubbleText.Done(it) }, { BubbleText.Failed(it.message ?: it.toString()) })
            _selection.update { if (it?.detection == detection) it.copy(text = text) else it }
            if (text is BubbleText.Done) {
                val analysis = withContext(Dispatchers.Default) { JapaneseAnalyzer.analyze(text.text) }
                _selection.update { if (it?.detection == detection) it.copy(analysis = analysis) else it }
            }
        }
    }

    /** Mot touché dans la phrase : recherche dans le dictionnaire. Re-toucher le désélectionne. */
    fun selectWord(index: Int) {
        val sel = _selection.value ?: return
        val analysis = sel.analysis ?: return
        if (sel.word == index) {
            _selection.value = sel.copy(word = null, wordInfo = null)
            return
        }
        _selection.value = sel.copy(word = index, wordInfo = null, grammar = null)
        viewModelScope.launch {
            val info = runCatching { lookupWord(analysis, index) }
                .onFailure { report("Recherche impossible", it) }.getOrNull() ?: return@launch
            _selection.update { if (it?.analysis == analysis && it.word == index) it.copy(wordInfo = info) else it }
        }
    }

    private suspend fun lookupWord(analysis: Analysis, index: Int): WordInfo = withContext(Dispatchers.IO) {
        val dict = dictionary.await()
        val word = analysis.words[index]
        val hits = analysis.lookupKeys(index)
            .map { LookupResult(it, dict.lookup(it)) }
            .filter { it.entries.isNotEmpty() }
        // Autres découpages utiles seulement pour un nom composé (中尊寺金色堂 -> 中尊寺) ;
        // pour un verbe conjugué, 備え (nom) à côté de 備える n'apporterait que de la confusion
        val compound = hits.firstOrNull()?.key?.let { it.length > word.surface.length && it != word.lemma } == true
        val results = if (compound) hits else hits.take(1)
        // Kanji de la forme trouvée (ou du mot tel qu'écrit)
        val written = results.firstOrNull()?.let { r -> r.entries.first().kanji.firstOrNull { Kana.hasKanji(it) } ?: r.key }
            ?: analysis.words[index].surface
        val kanji = written.filter(Kana::isKanji).toSet().mapNotNull { dict.kanji(it.toString()) }
        WordInfo(results, kanji)
    }

    /** Point de grammaire touché : mis en évidence dans la phrase (re-toucher l'enlève). */
    fun selectGrammar(point: GrammarPoint) {
        _selection.update { it?.copy(grammar = if (it.grammar == point) null else point) }
    }

    /** Libellé français d'un code JMdict (natures, registres). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun tagLabel(code: String): String =
        if (dictionary.isCompleted) runCatching { dictionary.getCompleted().tagLabel(code) }.getOrDefault(code) else code

    fun clearSelection() {
        ocrJob?.cancel()
        _selection.value = null
    }

    fun dismissError() {
        _error.value = null
    }

    private fun report(what: String, t: Throwable) {
        Log.e("ReaderViewModel", what, t)
        _error.value = "$what : ${t.message ?: t}"
    }

    override fun onCleared() {
        _source.value?.close()
    }
}
