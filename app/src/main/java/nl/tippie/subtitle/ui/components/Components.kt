package nl.tippie.subtitle.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import nl.tippie.subtitle.util.Formatting

/** A read-only text field that opens a menu — the Material 3 exposed-dropdown pattern. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabeledDropdown(
    label: String,
    options: List<Pair<String, String>>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selectedKey }?.second ?: selectedKey

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = { onSelect(key); expanded = false },
                )
            }
        }
    }
}

/**
 * States plainly whether audio leaves the device. Driven by the provider's declared
 * capability, never hardcoded per screen, so a new provider cannot skip this.
 */
@Composable
fun PrivacyBanner(
    sendsAudioOffDevice: Boolean,
    note: String,
    estimatedUploadBytes: Long,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (sendsAudioOffDevice) MaterialTheme.colorScheme.tertiaryContainer
            else MaterialTheme.colorScheme.secondaryContainer
        ),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Icon(
                if (sendsAudioOffDevice) Icons.Default.CloudUpload else Icons.Default.PhoneAndroid,
                contentDescription = null,
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    if (sendsAudioOffDevice) "Audio is uploaded" else "Stays on this device",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(note, style = MaterialTheme.typography.bodySmall)
                if (sendsAudioOffDevice && estimatedUploadBytes > 0) {
                    Text(
                        "Estimated upload for this video: ${Formatting.bytes(estimatedUploadBytes)}. " +
                            "The video file itself is never uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
