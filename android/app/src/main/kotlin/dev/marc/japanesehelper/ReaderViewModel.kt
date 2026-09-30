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
