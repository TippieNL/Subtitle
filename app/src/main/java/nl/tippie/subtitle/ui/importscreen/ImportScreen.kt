package nl.tippie.subtitle.ui.importscreen

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
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.tippie.subtitle.domain.model.AudioTrackInfo
import nl.tippie.subtitle.domain.model.MediaInfo
import nl.tippie.subtitle.domain.model.TranscriptionTask
import nl.tippie.subtitle.subtitle.format.TimeFormat
import nl.tippie.subtitle.ui.components.LabeledDropdown
import nl.tippie.subtitle.ui.components.PrivacyBanner
import nl.tippie.subtitle.util.Formatting
import nl.tippie.subtitle.util.Languages

data class ImportUiState(
    val loading: Boolean = true,
    val info: MediaInfo? = null,
    val error: String? = null,
    val selectedTrackIndex: Int? = null,
    val language: String = Languages.AUTO,
    val providerKey: String = "openai_whisper",
    val providerLabel: String = "OpenAI Whisper (cloud)",
    val privacyNote: String = "",
    val sendsAudioOffDevice: Boolean = true,
    val providerReady: Boolean = false,
    val providerBlockedReason: String? = null,
    val estimatedUploadBytes: Long = 0,
    val task: TranscriptionTask = TranscriptionTask.TRANSCRIBE,
    val supportsAudioTranslation: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    state: ImportUiState,
    onBack: () -> Unit,
    onSelectTrack: (Int) -> Unit,
    onSelectLanguage: (String) -> Unit,
    onSelectTask: (TranscriptionTask) -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import video") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        when {
            state.loading -> Column(
                Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Reading video details…", style = MaterialTheme.typography.bodyMedium)
            }

            state.error != null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "Can't use this file",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(8.dp))
                Text(state.error, style = MaterialTheme.typography.bodyMedium)
            }

            state.info != null -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                FileCard(state.info)
                Spacer(Modifier.height(16.dp))

                if (state.info.audioTracks.size > 1) {
                    SectionTitle("Audio track")
                    Text(
                        "This file has ${state.info.audioTracks.size} audio tracks — pick the one with the speech you want.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.selectableGroup()) {
                        state.info.audioTracks.forEach { track ->
                            TrackRow(
                                track = track,
                                selected = state.selectedTrackIndex == track.trackIndex,
                                onSelect = { onSelectTrack(track.trackIndex) },
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }

                SectionTitle("Transcription")
                Spacer(Modifier.height(8.dp))

                if (state.supportsAudioTranslation) {
                    Column(Modifier.selectableGroup()) {
                        TaskRow(
                            title = "Subtitles in the spoken language",
                            subtitle = "Transcribes what is said, as it is said.",
                            selected = state.task == TranscriptionTask.TRANSCRIBE,
                            onSelect = { onSelectTask(TranscriptionTask.TRANSCRIBE) },
                        )
                        TaskRow(
                            title = "English subtitles",
                            subtitle = "Whisper translates the speech straight to English. " +
                                "Same price as transcribing, and the timings come from the audio.",
                            selected = state.task == TranscriptionTask.TRANSLATE_TO_ENGLISH,
                            onSelect = { onSelectTask(TranscriptionTask.TRANSLATE_TO_ENGLISH) },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                LabeledDropdown(
                    label = if (state.task == TranscriptionTask.TRANSLATE_TO_ENGLISH)
                        "Spoken language (source)" else "Spoken language",
                    options = Languages.supported,
                    selectedKey = state.language,
                    onSelect = onSelectLanguage,
                )
                if (state.task == TranscriptionTask.TRANSLATE_TO_ENGLISH) {
                    Text(
                        "The output is always English. To get a different language, transcribe " +
                            "first and then translate the subtitles from the editor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Provider", style = MaterialTheme.typography.labelMedium)
                        Text(state.providerLabel, style = MaterialTheme.typography.bodyMedium)
                    }
                    androidx.compose.material3.TextButton(onClick = onOpenSettings) { Text("Change") }
                }

                Spacer(Modifier.height(12.dp))
                PrivacyBanner(
                    sendsAudioOffDevice = state.sendsAudioOffDevice,
                    note = state.privacyNote,
                    estimatedUploadBytes = state.estimatedUploadBytes,
                )

                state.providerBlockedReason?.let {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            androidx.compose.material3.TextButton(onClick = onOpenSettings) {
                                Text("Open settings")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onStart,
                    enabled = state.providerReady && state.selectedTrackIndex != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.height(0.dp))
                    Text("  Start transcription")
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun FileCard(info: MediaInfo) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                info.displayName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(12.dp))
            InfoRow("Duration", TimeFormat.display(info.durationMs))
            InfoRow("File size", Formatting.bytes(info.sizeBytes))
            Formatting.resolution(info.widthPx, info.heightPx)?.let { InfoRow("Resolution", it) }
            InfoRow(
                "Audio",
                if (info.audioTracks.isEmpty()) "None"
                else info.audioTracks.first().label +
                    if (info.audioTracks.size > 1) " (+${info.audioTracks.size - 1} more)" else ""
            )
            if (info.audioTracks.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "This video has no audio track, so there is nothing to transcribe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.4f),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.6f))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(top = 4.dp))
}

@Composable
private fun TaskRow(title: String, subtitle: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(top = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TrackRow(track: AudioTrackInfo, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text("Track ${track.trackIndex}", style = MaterialTheme.typography.bodyMedium)
            Text(
                track.label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
