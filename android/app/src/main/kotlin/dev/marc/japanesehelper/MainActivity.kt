package dev.marc.japanesehelper

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.marc.japanesehelper.ui.AboutScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.content.Intent
import android.net.Uri
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import dev.marc.japanesehelper.ui.CameraScreen
import dev.marc.japanesehelper.ui.HomeScreen
import dev.marc.japanesehelper.ui.ReaderScreen

class MainActivity : ComponentActivity() {
    private val vm: ReaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openFrom(intent)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val vm = vm
                val source by vm.source.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()

                val cameraOpen by vm.cameraOpen.collectAsStateWithLifecycle()
                var aboutOpen by rememberSaveable { mutableStateOf(false) }

                val current = source
                when {
                    aboutOpen -> {
                        BackHandler { aboutOpen = false }
                        AboutScreen(appVersion(), onClose = { aboutOpen = false })
                    }
                    cameraOpen -> {
                        BackHandler { vm.closeCamera() }
                        CameraScreen(vm.capturesDir, onCaptured = vm::onPhotoCaptured, onClose = vm::closeCamera)
                    }
                    current == null -> HomeScreen(vm, onAbout = { aboutOpen = true })
                    else -> {
                        BackHandler { vm.close() }
                        ReaderScreen(vm, current)
                    }
                }

                error?.let {
                    AlertDialog(
                        onDismissRequest = vm::dismissError,
                        confirmButton = { TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.ok)) } },
                        text = { Text(it) },
                    )
                }
            }
        }
    }

    private fun appVersion(): String = packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFrom(intent)
    }

    /** Fichiers reçus d'une autre appli (Ouvrir avec / Partager). */
    private fun openFrom(intent: Intent) {
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) vm.open(uris)
    }
}
