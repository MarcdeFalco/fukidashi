package dev.marc.japanesehelper.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp

/** Composant tiers et sa licence (mentions obligatoires, notamment CC BY-SA pour JMdict). */
private data class Credit(val name: String, val what: String, val license: String, val url: String)

private val CREDITS = listOf(
    Credit(
        "JMdict / KANJIDIC2", "Dictionnaire japonais et dictionnaire des kanji. " +
            "Propriété de l'Electronic Dictionary Research and Development Group, utilisés conformément à sa licence.",
        "CC BY-SA 4.0", "https://www.edrdg.org/edrdg/licence.html",
    ),
    Credit("jmdict-simplified", "Version JSON de JMdict et KANJIDIC2.", "CC BY-SA 4.0", "https://github.com/scriptin/jmdict-simplified"),
    Credit("manga-ocr (Maciej Budyś)", "Modèle de reconnaissance du texte des mangas.", "Apache 2.0", "https://github.com/kha-white/manga-ocr"),
    Credit(
        "Détecteur de bulles (YOLOv8, Ultralytics)", "Modèle de détection des bulles et du texte.",
        "AGPL-3.0", "https://github.com/ultralytics/ultralytics",
    ),
    Credit("Kuromoji + IPADIC", "Analyse morphologique du japonais.", "Apache 2.0 / licence IPADIC", "https://github.com/atilika/kuromoji"),
    Credit("ONNX Runtime", "Exécution des modèles sur le téléphone.", "MIT", "https://onnxruntime.ai"),
    Credit(
        "Qualcomm AI Engine Direct (QNN)", "Accélération sur le NPU des puces Snapdragon.",
        "Licence Qualcomm (redistribution)", "https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk",
    ),
    Credit("Jetpack Compose, CameraX (Google)", "Interface et caméra.", "Apache 2.0", "https://developer.android.com/jetpack"),
)

@Composable
fun AboutScreen(version: String, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        ) {
            TextButton(onClick = onClose) { Text("← Retour") }
            Text("吹き出し Fukidashi", style = MaterialTheme.typography.headlineMedium)
            Text("Version $version", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                "Tout fonctionne sur votre téléphone : aucune image, aucun texte ni aucune donnée " +
                    "personnelle n'est envoyé sur Internet.",
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Logiciel libre sous licence GNU AGPL-3.0 : vous pouvez consulter, modifier et " +
                    "redistribuer son code source.",
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { uri.openUri(SOURCE_URL) }) { Text("Code source") }
            TextButton(onClick = { uri.openUri(PRIVACY_URL) }) { Text("Politique de confidentialité") }
            Spacer(Modifier.height(16.dp))
            Text("Composants et données utilisés", style = MaterialTheme.typography.titleMedium)
            CREDITS.forEach { c ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    Text(c.what, style = MaterialTheme.typography.bodyMedium)
                    Text("Licence : ${c.license}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { uri.openUri(c.url) }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                        Text(c.url, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

// Dépôt public et politique de confidentialité (GitHub Pages, dossier docs/)
const val SOURCE_URL = "https://github.com/MarcdeFalco/fukidashi"
const val PRIVACY_URL = "https://marcdefalco.github.io/fukidashi/privacy.html"
