package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.ReferenceVisibility
import ch.lkmc.bangnidraw.engine.core.TracingReference

@Composable
internal fun TracingReferencePanel(
    reference: TracingReference,
    importState: ReferenceImportState,
    onOpacity: (Float) -> Unit,
    onToggleVisibility: () -> Unit,
    onReplace: () -> Unit,
    onReset: () -> Unit,
    onRemove: () -> Unit,
    onDone: () -> Unit,
) {
    val enabled = importState == ReferenceImportState.IDLE

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(16.dp),
    ) {
        Text(stringResource(R.string.reference_image), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.reference_edit_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.reference_opacity))
        Slider(
            value = reference.opacity,
            onValueChange = onOpacity,
            enabled = enabled,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = reference.visibility == ReferenceVisibility.VISIBLE,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = { onToggleVisibility() },
                )
                .padding(vertical = 4.dp),
        ) {
            Text(stringResource(R.string.reference_visible))
            Switch(
                checked = reference.visibility == ReferenceVisibility.VISIBLE,
                onCheckedChange = null,
                enabled = enabled,
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(
                onClick = onReplace,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.reference_replace))
            }
            TextButton(
                onClick = onReset,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.reference_reset))
            }
            TextButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.weight(1f),
            ) {
                Text(stringResource(R.string.reference_remove))
            }
        }
        Row(
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (!enabled) CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
            TextButton(onClick = onDone) {
                Text(stringResource(R.string.reference_done))
            }
        }
    }
}
