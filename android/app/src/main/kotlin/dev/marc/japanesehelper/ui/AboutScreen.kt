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
import androidx.compose.ui.res.stringResource
import dev.marc.japanesehelper.R
import androidx.compose.ui.unit.dp

/** Composant tiers et sa licence (mentions obligatoires, notamment CC BY-SA pour JMdict). */
private data class Credit(val name: String, val what: Int, val license: String, val url: String)

private val CREDITS = listOf(
    Credit("JMdict / KANJIDIC2", R.string.credit_jmdict, "CC BY-SA 4.0", "https://www.edrdg.org/edrdg/licence.html"),
    Credit("jmdict-simplified", R.string.credit_jmdict_simplified, "CC BY-SA 4.0", "https://github.com/scriptin/jmdict-simplified"),
    Credit("manga-ocr (Maciej Budyś)", R.string.credit_manga_ocr, "Apache 2.0", "https://github.com/kha-white/manga-ocr"),
    Credit("YOLOv8 (Ultralytics)", R.string.credit_detector, "AGPL-3.0", "https://github.com/ultralytics/ultralytics"),
    Credit("Kuromoji + IPADIC", R.string.credit_kuromoji, "Apache 2.0 / IPADIC", "https://github.com/atilika/kuromoji"),
    Credit("ONNX Runtime", R.string.credit_onnx, "MIT", "https://onnxruntime.ai"),
    Credit(
        "Qualcomm AI Engine Direct (QNN)", R.string.credit_qnn, "Qualcomm",
        "https://www.qualcomm.com/developer/software/qualcomm-ai-engine-direct-sdk",
    ),
    Credit("Jetpack Compose, CameraX (Google)", R.string.credit_jetpack, "Apache 2.0", "https://developer.android.com/jetpack"),
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
            TextButton(onClick = onClose) { Text(stringResource(R.string.about_back)) }
            Text("吹き出し Fukidashi", style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.about_version, version), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.about_offline),
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                stringResource(R.string.about_free_software),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { uri.openUri(SOURCE_URL) }) { Text(stringResource(R.string.about_source)) }
            TextButton(onClick = { uri.openUri(PRIVACY_URL) }) { Text(stringResource(R.string.about_privacy)) }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.about_components), style = MaterialTheme.typography.titleMedium)
            CREDITS.forEach { c ->
                Column(Modifier.padding(vertical = 8.dp)) {
                    Text(c.name, style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(c.what), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.about_license, c.license), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
