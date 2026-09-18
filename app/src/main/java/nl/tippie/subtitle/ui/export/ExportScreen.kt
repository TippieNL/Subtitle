package nl.tippie.subtitle.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.tippie.subtitle.subtitle.format.SubtitleFormat
import nl.tippie.subtitle.ui.ExportUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(
    state: ExportUiState,
    onBack: () -> Unit,
    onSelectFormat: (SubtitleFormat) -> Unit,
    onPickDestination: () -> Unit,
    onBurnIn: () -> Unit,
    onOpenStyle: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Text(state.projectName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "${state.cueCount} subtitles",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(20.dp))
            Text("Subtitle file", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 8.dp))

            Column(Modifier.selectableGroup()) {
                SubtitleFormat.entries.forEach { format ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = state.format == format,
                            onClick = { onSelectFormat(format) },
                        )
                        Column {
                            Text(format.label, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                when (format) {
                                    SubtitleFormat.SRT -> "Widest compatibility — players, TVs, editors."
                                    SubtitleFormat.VTT -> "Web and HTML5 video; carries styling."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onPickDestination,
                enabled = state.cueCount > 0 && !state.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Choose location and export") }

            Spacer(Modifier.height(24.dp))
            Text("Video with burned-in subtitles", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 8.dp))

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text(
                        "Renders a new video file with the subtitles drawn permanently into the picture. " +
                            "The video is re-encoded, so this takes roughly as long as the video itself on most phones " +
                            "and produces a file of similar size.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenStyle) { Text("Style") }
                        Button(
                            onClick = onBurnIn,
                            enabled = state.canBurnIn && state.cueCount > 0 && !state.busy,
                        ) { Text("Render video") }
                    }
                    if (!state.canBurnIn) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "This project has no video track to render into.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (state.busy) {
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }

            state.message?.let {
                Spacer(Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(it, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
