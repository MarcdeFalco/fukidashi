package dev.marc.japanesehelper

import ai.onnxruntime.OrtSession
import android.content.Context
import android.system.Os
import android.util.Log
import java.io.File

/** Moteur d'exécution ONNX Runtime pour un modèle. */
enum class Backend {
    /** CPU, noyaux ONNX Runtime par défaut. */
    CPU,

    /** NPU Hexagon (Qualcomm QNN HTP), calculs en fp16. */
    NPU,
}

object Accelerators {
    private var qnnReady = false

    /**
     * Le NPU charge ses bibliothèques depuis le dossier natif de l'appli :
     * il faut le lui indiquer avant de créer la première session QNN.
     */
    fun prepareNpu(context: Context) {
        if (qnnReady) return
        val libDir = context.applicationInfo.nativeLibraryDir
        Os.setenv("ADSP_LIBRARY_PATH", "$libDir;/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp", true)
        Log.i("Accelerators", "ADSP_LIBRARY_PATH=$libDir")
        qnnReady = true
    }

    /** Réglages QNN par défaut ; [extra] permet d'en essayer d'autres (test de vitesse). */
    private val npuDefaults = mapOf(
        "backend_path" to "libQnnHtp.so",
        "htp_performance_mode" to "burst",
        "enable_htp_fp16_precision" to "1",
    )

    fun options(context: Context, backend: Backend, threads: Int = 4, extra: Map<String, String> = emptyMap()) =
        OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(threads)
            if (backend == Backend.NPU) {
                prepareNpu(context)
                // Journal détaillé (répartition NPU/CPU) : --es qnn "log=1" dans le test de vitesse
                if (extra["log"] == "1") setSessionLogLevel(ai.onnxruntime.OrtLoggingLevel.ORT_LOGGING_LEVEL_INFO)
                addQnn(npuDefaults + (extra - "log"))
            }
        }

    /**
     * Modèle pour le NPU avec cache de compilation. Si le graphe compilé [key] existe,
     * on l'ouvre directement ; sinon on compile [source] (long) et ONNX Runtime
     * enregistre le résultat, puis [NpuModel.compiled] existe pour les fois suivantes.
     */
    fun npuModel(
        context: Context,
        key: String,
        threads: Int = 4,
        extra: Map<String, String> = emptyMap(),
        source: () -> File,
    ): NpuModel {
        val dir = File(context.noBackupFilesDir, "npu").apply { mkdirs() }
        val compiled = File(dir, "$key.ctx.onnx")
        val options = options(context, Backend.NPU, threads, extra)
        if (compiled.exists()) return NpuModel(compiled, options, compiling = false, compiled)
        // Anciennes compilations de ce modèle (autre version de l'appli) : place libérée
        val prefix = key.substringBefore('@')
        dir.listFiles { f -> f.name.startsWith("$prefix@") }?.forEach { it.delete() }
        options.addConfigEntry("ep.context_enable", "1")
        options.addConfigEntry("ep.context_file_path", compiled.path)
        // Données du graphe dans un .bin à côté (le .onnx seul serait énorme)
        options.addConfigEntry("ep.context_embed_mode", "0")
        return NpuModel(source(), options, compiling = true, compiled)
    }

    /** Variante pour le test de vitesse : clé tirée du fichier (taille, date). */
    fun npuModel(context: Context, model: File, threads: Int = 4, extra: Map<String, String> = emptyMap()) =
        npuModel(context, "${model.nameWithoutExtension}@${model.length()}_${model.lastModified()}_${extra.hashCode()}", threads, extra) { model }
}

/** Fichier à ouvrir avec ses options ; [compiling] : la session va compiler puis écrire [compiled]. */
class NpuModel(val file: File, val options: OrtSession.SessionOptions, val compiling: Boolean, val compiled: File)
