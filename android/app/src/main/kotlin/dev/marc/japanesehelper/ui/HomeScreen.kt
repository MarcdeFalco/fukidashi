package dev.marc.japanesehelper.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.marc.japanesehelper.ReaderViewModel

@Composable
fun HomeScreen(vm: ReaderViewModel, onAbout: () -> Unit) {
    val modelsReady by vm.modelsReady.collectAsStateWithLifecycle()
    val loadingStatus by vm.loadingStatus.collectAsStateWithLifecycle()
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.open(it) }
    val pickCbz = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.open(listOf(it)) }
    }

    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.safeDrawingPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("吹き出し", style = MaterialTheme.typography.headlineLarge)
            Text("Fukidashi", style = MaterialTheme.typography.titleLarge)
            Text("Lire le japonais, bulle par bulle", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(48.dp))
            Button(onClick = vm::openCamera, Modifier.fillMaxWidth()) {
                Text("Photographier une page")
            }
            OutlinedButton(onClick = { pickImages.launch(arrayOf("image/*")) }, Modifier.fillMaxWidth()) {
                Text("Ouvrir des images")
            }
            OutlinedButton(
                onClick = {
                    pickCbz.launch(arrayOf("application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/octet-stream"))
                },
                Modifier.fillMaxWidth(),
            ) {
                Text("Ouvrir un CBZ")
            }
            Spacer(Modifier.height(24.dp))
            Text(
                if (modelsReady) "Modèles prêts" else loadingStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onAbout) { Text("À propos et licences") }
        }
    }
}
