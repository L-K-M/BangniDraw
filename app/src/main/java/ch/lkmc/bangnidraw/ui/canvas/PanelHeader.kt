package ch.lkmc.bangnidraw.ui.canvas

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.ui.common.InfoButton

/**
 * A panel's title row with a visible way out beside it.
 *
 * Panels dismiss on a scrim tap or Back (08 §4.1), which a first-time user
 * cannot see; the close icon is the same affordance the Settings sheet's
 * header already carries. Dismissal never destroys state — the panel keeps
 * its edits either way. [helpBody] adds the (i) popup explaining the panel.
 */
@Composable
internal fun PanelHeader(
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    @StringRes helpBody: Int? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        if (helpBody != null) {
            InfoButton(title = title, body = helpBody)
        }
        PanelCloseButton(onClose)
    }
}

/** The panels' one close affordance, shared so the icon and label cannot drift. */
@Composable
internal fun PanelCloseButton(onClose: () -> Unit) {
    IconButton(onClick = onClose) {
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.panel_close),
        )
    }
}
