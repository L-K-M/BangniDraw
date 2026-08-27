package ch.lkmc.bangnidraw.ui.canvas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import androidx.compose.ui.res.stringResource

/** The only onboarding surface; its full-screen target consumes the first tap. */
@Composable
internal fun FirstRunHint(onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val dismissDescription = stringResource(R.string.canvas_hint_dismiss)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxSize()
            .semantics {
                role = Role.Button
                contentDescription = dismissDescription
            }
            .clickable(onClick = onDismiss),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = MaterialTheme.shapes.large,
            tonalElevation = 4.dp,
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(HINT_GAP),
                modifier = Modifier.padding(HINT_PADDING),
            ) {
                HintLine(Icons.Filled.Brush, R.string.canvas_hint_draw)
                HintLine(Icons.Filled.OpenWith, R.string.canvas_hint_navigate)
                HintLine(Icons.AutoMirrored.Filled.Undo, R.string.canvas_hint_undo)
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.canvas_hint_dismiss))
                }
            }
        }
    }
}

@Composable
private fun HintLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(HINT_GAP),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null)
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
    }
}

private val HINT_PADDING = 20.dp
private val HINT_GAP = 12.dp
