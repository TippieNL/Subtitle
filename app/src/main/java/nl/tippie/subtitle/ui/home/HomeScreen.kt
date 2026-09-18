package nl.tippie.subtitle.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import nl.tippie.subtitle.domain.model.ProjectStatus
import nl.tippie.subtitle.domain.model.SubtitleProject
import nl.tippie.subtitle.subtitle.format.TimeFormat
import nl.tippie.subtitle.util.Formatting

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    projects: List<SubtitleProject>,
    onPickVideo: () -> Unit,
    onOpenProject: (SubtitleProject) -> Unit,
    onDeleteProject: (SubtitleProject) -> Unit,
    onOpenSettings: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<SubtitleProject?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subtitle") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onPickVideo,
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("Select video") },
            )
        }
    ) { padding ->
        if (projects.isEmpty()) {
            EmptyState(Modifier.padding(padding))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Projects",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
                items(projects, key = { it.id }) { project ->
                    ProjectCard(
                        project = project,
                        onClick = { onOpenProject(project) },
                        onDelete = { pendingDelete = project },
                    )
                }
            }
        }
    }

    pendingDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete project?") },
            text = {
                Text(
                    "This removes the subtitles and progress for \"${project.name}\". " +
                        "Your video file is not touched."
                )
            },
            confirmButton = {
                TextButton(onClick = { onDeleteProject(project); pendingDelete = null }) {
                    Text("Delete")
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Default.Subtitles,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text("No projects yet", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Pick a video and Subtitle will transcribe its audio and build timed subtitles you can edit and export.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProjectCard(
    project: SubtitleProject,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        project.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        buildString {
                            append(TimeFormat.display(project.durationMs))
                            if (project.sizeBytes > 0) append(" · ${Formatting.bytes(project.sizeBytes)}")
                            Formatting.resolution(project.widthPx, project.heightPx)?.let { append(" · $it") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete project")
                }
            }

            Spacer(Modifier.height(10.dp))
            StatusRow(project)
        }
    }
}

@Composable
private fun StatusRow(project: SubtitleProject) {
    val (label, showBar) = when (project.status) {
        ProjectStatus.DRAFT -> "Not started" to false
        ProjectStatus.EXTRACTING -> "Extracting audio" to true
        ProjectStatus.TRANSCRIBING ->
            "Transcribing · ${project.completedChunks}/${project.totalChunks} chunks" to true
        ProjectStatus.PAUSED ->
            "Paused · ${project.completedChunks}/${project.totalChunks} chunks done" to true
        ProjectStatus.COMPLETED -> "Ready" to false
        ProjectStatus.FAILED -> "Failed" to false
        ProjectStatus.CANCELLED -> "Cancelled" to false
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = when (project.status) {
                ProjectStatus.FAILED -> MaterialTheme.colorScheme.error
                ProjectStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        if (showBar) {
            Spacer(Modifier.width(12.dp))
            LinearProgressIndicator(
                progress = { project.progressFraction },
                modifier = Modifier.weight(1f),
            )
        }
    }
    project.errorMessage?.let {
        Spacer(Modifier.height(6.dp))
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}
