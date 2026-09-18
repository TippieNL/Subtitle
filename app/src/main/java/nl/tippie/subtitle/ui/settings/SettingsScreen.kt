package nl.tippie.subtitle.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import nl.tippie.subtitle.domain.model.ProviderId
import nl.tippie.subtitle.domain.model.SubtitleStyle
import nl.tippie.subtitle.media.sink.ChunkEncoding
import nl.tippie.subtitle.ui.SettingsUiState
import nl.tippie.subtitle.ui.components.LabeledDropdown
import nl.tippie.subtitle.util.Formatting
import nl.tippie.subtitle.util.Languages

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSetApiKey: (String) -> Unit,
    onClearApiKey: () -> Unit,
    onValidateKey: () -> Unit,
    onSetProvider: (String) -> Unit,
    onSetLanguage: (String) -> Unit,
    onSetChunkMinutes: (Int) -> Unit,
    onSetEncoding: (ChunkEncoding) -> Unit,
    onSetParallel: (Int) -> Unit,
    onSetRequireUnmetered: (Boolean) -> Unit,
    onSetStyle: (SubtitleStyle) -> Unit,
    onSetTranslationTarget: (String) -> Unit,
    onSetTranslationModel: (String) -> Unit,
    onSetTranslationBatchSize: (Int) -> Unit,
    onClearCache: () -> Unit,
) {
    var keyInput by remember { mutableStateOf("") }
    val settings = state.settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
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
            Section("Transcription provider")
            LabeledDropdown(
                label = "Provider",
                options = ProviderId.entries.map { it.key to it.displayName },
                selectedKey = settings.providerKey,
                onSelect = onSetProvider,
            )
            if (settings.providerKey != ProviderId.OPENAI_WHISPER.key) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "On-device Whisper and custom backends are planned but not included in this build. " +
                        "Only OpenAI Whisper works right now.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Spacer(Modifier.height(16.dp))
            Section("OpenAI API key")
            Text(
                "Stored encrypted with a key held in the Android Keystore, excluded from backups, and never logged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            state.maskedKey?.let {
                Text("Current key: $it", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
            }
            OutlinedTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                label = { Text(if (state.maskedKey == null) "Paste API key" else "Replace key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSetApiKey(keyInput); keyInput = "" },
                    enabled = keyInput.isNotBlank(),
                ) { Text("Save") }
                OutlinedButton(onClick = onValidateKey, enabled = !state.validating) {
                    Text(if (state.validating) "Checking…" else "Test key")
                }
                if (state.maskedKey != null) {
                    TextButton(onClick = onClearApiKey) { Text("Remove") }
                }
            }
            state.validationMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            Section("Defaults")
            LabeledDropdown(
                label = "Default language",
                options = Languages.supported,
                selectedKey = settings.defaultLanguage,
                onSelect = onSetLanguage,
            )

            Spacer(Modifier.height(16.dp))
            Section("Processing")
            SliderRow(
                label = "Chunk length",
                value = settings.chunkMinutes.toFloat(),
                range = 1f..20f,
                steps = 18,
                display = "${settings.chunkMinutes} min",
                help = "Shorter chunks mean finer progress and less lost work if interrupted; " +
                    "longer chunks give the model more context.",
                onChange = { onSetChunkMinutes(it.toInt()) },
            )
            SliderRow(
                label = "Parallel requests",
                value = settings.parallelRequests.toFloat(),
                range = 1f..4f,
                steps = 2,
                display = "${settings.parallelRequests}",
                help = "How many chunks are transcribed at once. Higher is faster but hits rate limits sooner.",
                onChange = { onSetParallel(it.toInt()) },
            )

            Spacer(Modifier.height(8.dp))
            Text("Upload format", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { onSetEncoding(ChunkEncoding.M4A) },
                    enabled = settings.uploadEncoding != ChunkEncoding.M4A,
                ) { Text("AAC (14 MB/h)") }
                OutlinedButton(
                    onClick = { onSetEncoding(ChunkEncoding.WAV) },
                    enabled = settings.uploadEncoding != ChunkEncoding.WAV,
                ) { Text("WAV (115 MB/h)") }
            }
            Text(
                "AAC at 32 kbps mono is about 8x smaller with no measurable accuracy cost for speech. " +
                    "WAV is lossless if you want the absolute best input.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wi-Fi only", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Wait for an unmetered connection before uploading audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.requireUnmetered, onCheckedChange = onSetRequireUnmetered)
            }

            Spacer(Modifier.height(16.dp))
            Section("Translation")
            Text(
                "Used when you translate finished subtitles from the editor. Translating the " +
                    "speech directly to English during transcription is a separate option on " +
                    "the import screen — it costs nothing extra and keeps timings tighter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LabeledDropdown(
                label = "Default target language",
                options = Languages.supported.filterNot { it.first == Languages.AUTO },
                selectedKey = settings.translationTarget,
                onSelect = onSetTranslationTarget,
            )
            Spacer(Modifier.height(10.dp))
            var modelInput by remember(settings.translationModel) {
                mutableStateOf(settings.translationModel)
            }
            OutlinedTextField(
                value = modelInput,
                onValueChange = { modelInput = it },
                label = { Text("Translation model") },
                singleLine = true,
                supportingText = {
                    Text("Change this if the model is retired or unavailable on your account.")
                },
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(
                onClick = { onSetTranslationModel(modelInput) },
                enabled = modelInput.isNotBlank() && modelInput != settings.translationModel,
            ) { Text("Save model") }
            SliderRow(
                label = "Lines per request",
                value = settings.translationBatchSize.toFloat(),
                range = 5f..40f,
                steps = 6,
                display = "${settings.translationBatchSize}",
                help = "More lines per request is cheaper and gives the model more context, " +
                    "but a miscount costs more to retry.",
                onChange = { onSetTranslationBatchSize(it.toInt()) },
            )

            Spacer(Modifier.height(16.dp))
            Section("Subtitle appearance")
            SliderRow(
                label = "Font size",
                value = settings.style.fontSizeSp,
                range = 12f..40f,
                steps = 0,
                display = "${settings.style.fontSizeSp.toInt()} sp",
                help = "Used for the preview and for burned-in video.",
                onChange = { onSetStyle(settings.style.copy(fontSizeSp = it)) },
            )
            SliderRow(
                label = "Bottom margin",
                value = settings.style.bottomMarginPercent,
                range = 0f..25f,
                steps = 0,
                display = "${settings.style.bottomMarginPercent.toInt()}%",
                help = "Distance from the edge of the frame when burning in.",
                onChange = { onSetStyle(settings.style.copy(bottomMarginPercent = it)) },
            )
            Text("Position", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SubtitleStyle.VerticalPosition.entries.forEach { position ->
                    OutlinedButton(
                        onClick = { onSetStyle(settings.style.copy(verticalPosition = position)) },
                        enabled = settings.style.verticalPosition != position,
                    ) { Text(position.name.lowercase().replaceFirstChar { it.uppercase() }) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Section("Storage")
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                )
            ) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Temporary audio", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            Formatting.bytes(state.cacheBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onClearCache) { Text("Clear") }
                }
            }
            Text(
                "Chunks are deleted as soon as they are transcribed, so this is usually near zero. " +
                    "Anything left belongs to a paused or failed job.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(16.dp))
            Section("Privacy")
            Text(
                "Your video file is never uploaded. With the OpenAI provider, only extracted audio " +
                    "(16 kHz mono) is sent, chunk by chunk, to api.openai.com. With an on-device provider " +
                    "nothing leaves the phone at all. Subtitle text and audio are never written to logs.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    HorizontalDivider(Modifier.padding(top = 4.dp, bottom = 8.dp))
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    display: String,
    help: String,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(display, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
        Text(
            help,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
