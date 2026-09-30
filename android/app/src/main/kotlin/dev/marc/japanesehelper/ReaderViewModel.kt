package dev.marc.japanesehelper

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.marc.japanesehelper.core.Detection
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

data class Selection(val page: Int, val detection: Detection, val text: BubbleText)

class ReaderViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = OcrEngine(app)

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

    private val detecting = mutableSetOf<Int>()
    private var ocrJob: Job? = null

    init {
        // Chargement anticipé : la copie et l'initialisation prennent quelques secondes
        viewModelScope.launch {
            runCatching { engine.load { _loadingStatus.value = it } }
                .onSuccess { _modelsReady.value = true }
                .onFailure { report("Chargement des modèles impossible", it) }
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
        }
    }

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
