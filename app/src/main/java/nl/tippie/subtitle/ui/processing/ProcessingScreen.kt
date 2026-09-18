package nl.tippie.subtitle.ui.processing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nl.tippie.subtitle.domain.model.ProjectStatus
import nl.tippie.subtitle.domain.model.SubtitleProject
import nl.tippie.subtitle.subtitle.format.TimeFormat
import nl.tippie.subtitle.util.Formatting
import nl.tippie.subtitle.work.Stage
import nl.tippie.subtitle.work.WorkProgress

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProcessingScreen(
    project: SubtitleProject?,
    progress: WorkProgress?,
    errorMessage: String?,
    cueCount: Int,
    onBack: () -> Unit,
    onCancel: () -> Unit,
    onResume: () -> Unit,
    onRetryFailed: () -> Unit,
    onOpenEditor: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Processing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                project?.name ?: "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            val stage = progress?.stage
            val running = project?.status == ProjectStatus.EXTRACTING ||
                project?.status == ProjectStatus.TRANSCRIBING

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        when {
                            project?.status == ProjectStatus.COMPLETED -> "Done"
                            project?.status == ProjectStatus.FAILED -> "Failed"
                            project?.status == ProjectStatus.PAUSED -> "Paused"
                            stage != null -> stage.label
                            else -> "Queued"
                        },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(Modifier.height(10.dp))

                    val fraction = progress?.fraction ?: project?.progressFraction ?: 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${(fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (progress != null && progress.chunkCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Chunk ${progress.chunkIndex.coerceAtLeast(0) + 1} of ${progress.chunkCount}" +
                                if (progress.failedChunks > 0) " · ${progress.failedChunks} failed" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (progress?.totalMs != null && progress.totalMs > 0 && stage == Stage.EXTRACTING) {
                        Text(
                            "${TimeFormat.display(progress.processedMs)} of ${TimeFormat.display(progress.totalMs)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    progress?.etaSeconds?.let {
                        Text(
                            "About ${Formatting.eta(it)} remaining",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (cueCount > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "$cueCount subtitles so far",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }

            errorMessage?.let {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            if (project?.status == ProjectStatus.PAUSED) {
                Text(
                    "Progress is saved. Resuming continues from chunk " +
                        "${project.completedChunks + 1} — nothing already transcribed is redone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (running) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Cancel")
                    }
                } else if (project?.status == ProjectStatus.PAUSED ||
                    project?.status == ProjectStatus.FAILED
                ) {
                    Button(onClick = onResume, modifier = Modifier.weight(1f)) { Text("Resume") }
                }

                if (cueCount > 0) {
                    Button(onClick = onOpenEditor, modifier = Modifier.weight(1f)) {
                        Text(if (project?.status == ProjectStatus.COMPLETED) "Open editor" else "Preview")
                    }
                }
            }

            if ((progress?.failedChunks ?: 0) > 0 && !running) {
                OutlinedButton(onClick = onRetryFailed, modifier = Modifier.fillMaxWidth()) {
                    Text("Retry failed chunks")
                }
            }
        }
    }
}
