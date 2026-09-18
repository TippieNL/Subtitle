package nl.tippie.subtitle.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import nl.tippie.subtitle.domain.model.Cue
import nl.tippie.subtitle.subtitle.format.TimeFormat
import java.io.File

data class EditorUiState(
    val projectName: String = "",
    val sourceUri: String = "",
    val cues: List<Cue> = emptyList(),
    val previewFile: File? = null,
    val query: String = "",
    val selectedCueId: Long? = null,
    val playbackMs: Long = 0,
    val seekTarget: Long? = null,
    val shiftDialogOpen: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    state: EditorUiState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSelectCue: (Cue) -> Unit,
    onEditCue: (Cue) -> Unit,
    onDeleteCue: (Cue) -> Unit,
    onSplitCue: (Cue) -> Unit,
    onMergeWithNext: (Cue) -> Unit,
    onAddCue: () -> Unit,
    onShiftAll: (Long) -> Unit,
    onOpenShiftDialog: (Boolean) -> Unit,
    onPositionChange: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    onExport: () -> Unit,
) {
    val listState = rememberLazyListState()
    val visible = remember(state.cues, state.query) {
        if (state.query.isBlank()) state.cues
        else state.cues.filter { it.text.contains(state.query, ignoreCase = true) }
    }

    // Follow playback: scroll the list to the cue currently on screen.
    LaunchedEffect(state.playbackMs, state.query) {
        if (state.query.isNotBlank()) return@LaunchedEffect
        val index = visible.indexOfLast { it.startMs <= state.playbackMs }
        if (index >= 0) {
            val info = listState.layoutInfo.visibleItemsInfo
            val alreadyVisible = info.any { it.index == index }
            if (!alreadyVisible) listState.scrollToItem(index.coerceAtLeast(0))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.projectName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${state.cues.size} subtitles",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { onOpenShiftDialog(true) }) {
                        Icon(Icons.Default.Timer, contentDescription = "Shift all timings")
                    }
                    IconButton(onClick = onAddCue) {
                        Icon(Icons.Default.Add, contentDescription = "Add subtitle")
                    }
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.FileDownload, contentDescription = "Export")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.sourceUri.isNotBlank()) {
                SubtitlePlayer(
                    sourceUri = state.sourceUri,
                    subtitleFile = state.previewFile,
                    onPositionChange = onPositionChange,
                    seekToMs = state.seekTarget,
                )
            }

            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                label = { Text("Search subtitles") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            )

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.cues.isEmpty()) "No subtitles yet."
                        else "Nothing matches \"${state.query}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp, 0.dp, 12.dp, 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(visible, key = { _, cue -> cue.id }) { index, cue ->
                        CueRow(
                            cue = cue,
                            active = state.playbackMs in cue.startMs until cue.endMs,
                            expanded = state.selectedCueId == cue.id,
                            canMerge = index < visible.lastIndex,
                            onClick = { onSelectCue(cue); onSeek(cue.startMs) },
                            onEdit = onEditCue,
                            onDelete = { onDeleteCue(cue) },
                            onSplit = { onSplitCue(cue) },
                            onMerge = { onMergeWithNext(cue) },
                        )
                    }
                }
            }
        }
    }

    if (state.shiftDialogOpen) {
        ShiftDialog(onDismiss = { onOpenShiftDialog(false) }, onApply = onShiftAll)
    }
}

@Composable
private fun CueRow(
    cue: Cue,
    active: Boolean,
    expanded: Boolean,
    canMerge: Boolean,
    onClick: () -> Unit,
    onEdit: (Cue) -> Unit,
    onDelete: () -> Unit,
    onSplit: () -> Unit,
    onMerge: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHigh
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TimeFormat.precise(cue.startMs)}  →  ${TimeFormat.precise(cue.endMs)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                if (cue.charsPerSecond > 21) {
                    Text(
                        "${cue.charsPerSecond.toInt()} cps",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))

            if (expanded) {
                OutlinedTextField(
                    value = cue.text,
                    onValueChange = { onEdit(cue.copy(text = it)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Text") },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TimeField("Start", cue.startMs) { onEdit(cue.copy(startMs = it)) }
                    TimeField("End", cue.endMs) { onEdit(cue.copy(endMs = it)) }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = onSplit) {
                        Icon(Icons.Default.CallSplit, contentDescription = null)
                        Text(" Split")
                    }
                    if (canMerge) {
                        TextButton(onClick = onMerge) {
                            Icon(Icons.Default.CallMerge, contentDescription = null)
                            Text(" Merge next")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = null)
                        Text(" Delete")
                    }
                }
            } else {
                Text(cue.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun TimeField(label: String, valueMs: Long, onChange: (Long) -> Unit) {
    var text by remember(valueMs) { mutableStateOf(TimeFormat.precise(valueMs)) }
    OutlinedTextField(
        value = text,
        onValueChange = { input ->
            text = input
            TimeFormat.parse(input)?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        isError = TimeFormat.parse(text) == null,
        modifier = Modifier.width(150.dp),
    )
}

@Composable
private fun ShiftDialog(onDismiss: () -> Unit, onApply: (Long) -> Unit) {
    var value by remember { mutableStateOf("0.0") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Shift all timings") },
        text = {
            Column {
                Text(
                    "Moves every subtitle by this many seconds. Negative values make them appear earlier.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Seconds") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    value.trim().toDoubleOrNull()?.let { onApply((it * 1000).toLong()) }
                    onDismiss()
                }
            ) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
