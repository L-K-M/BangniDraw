package ch.lkmc.bangnidraw.ui.common

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.lkmc.bangnidraw.R

/**
 * The (i) affordance every panel, sheet, and settings section offers for its
 * non-obvious behaviour: tap it and a popup explains the feature in place.
 *
 * All help text is a `help_*` string resource — this composable has no
 * knowledge of any feature, so adding documentation is adding a string and a
 * button, never editing logic here. Bodies use blank lines to separate one
 * paragraph per control.
 */
@Composable
fun InfoButton(title: String, @StringRes body: Int, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }, modifier = modifier) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = stringResource(R.string.help_button_cd, title),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (open) {
        InfoDialog(title = title, body = body, onDismiss = { open = false })
    }
}

@Composable
fun InfoDialog(title: String, @StringRes body: Int, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(body), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.about_close))
            }
        },
    )
}
