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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.marc.japanesehelper.ui.CameraScreen
import dev.marc.japanesehelper.ui.HomeScreen
import dev.marc.japanesehelper.ui.ReaderScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val vm: ReaderViewModel = viewModel()
                val source by vm.source.collectAsStateWithLifecycle()
                val error by vm.error.collectAsStateWithLifecycle()

                val cameraOpen by vm.cameraOpen.collectAsStateWithLifecycle()

                val current = source
                when {
                    cameraOpen -> {
                        BackHandler { vm.closeCamera() }
                        CameraScreen(vm.capturesDir, onCaptured = vm::onPhotoCaptured, onClose = vm::closeCamera)
                    }
                    current == null -> HomeScreen(vm)
                    else -> {
                        BackHandler { vm.close() }
                        ReaderScreen(vm, current)
                    }
                }

                error?.let {
                    AlertDialog(
                        onDismissRequest = vm::dismissError,
                        confirmButton = { TextButton(onClick = vm::dismissError) { Text("OK") } },
                        text = { Text(it) },
                    )
                }
            }
        }
    }
}
