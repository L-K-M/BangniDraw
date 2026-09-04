package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.ReferenceVisibility
import ch.lkmc.bangnidraw.engine.core.TracingReference

/**
 * The tracing image's controls — `:app`'s `TracingReferencePanel`, with one
 * row it has no need for.
 *
 * Android moves, scales and rotates the image with two fingers on the canvas,
 * so its panel only has to say so. A mouse has one pointer and the canvas is
 * already painting with it, so the placement gesture needs a mode to live in:
 * [DesktopShellState.editingReference] is that mode, and the toggle is this
 * panel's extra row. Everything else — opacity, visibility, Replace, Reset,
 * Remove — is the same control over the same state.
 */
@Composable
internal fun DesktopReferencePanel(
    reference: TracingReference,
    state: DesktopShellState,
    onReplace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PANEL_PADDING),
    ) {
        Text(
            DesktopStrings.get("reference_image"),
            style = MaterialTheme.typography.titleSmall,
        )
        // Its own body, not `help_reference_body`: Android's says "two
        // fingers on the canvas", which is not a gesture this shell has, and
        // names a gallery mirror it does not have either.
        Text(
            DesktopStrings.get("desktop_reference_help"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val notice = state.referenceNotice
        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        SettingsGroup(DesktopStrings.get("reference_image"))
        SettingSlider(
            label = DesktopStrings.get("reference_opacity"),
            value = reference.opacity,
            range = 0f..1f,
            valueText = percent(reference.opacity),
            onChange = state::setReferenceOpacity,
        )
        SettingToggle(
            DesktopStrings.get("reference_visible"),
            reference.visibility == ReferenceVisibility.VISIBLE,
        ) { state.toggleReferenceVisible() }

        SettingToggle(
            DesktopStrings.get("desktop_reference_edit"),
            state.editingReference,
        ) { state.editingReference = it }
        Text(
            DesktopStrings.get("desktop_reference_edit_help"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onReplace, modifier = Modifier.weight(1f)) {
                Text(DesktopStrings.get("reference_replace"), maxLines = 1)
            }
            TextButton(onClick = state::resetReference, modifier = Modifier.weight(1f)) {
                Text(DesktopStrings.get("reference_reset"), maxLines = 1)
            }
            TextButton(
                onClick = {
                    state.removeReference()
                    state.showReferencePanel = false
                },
                modifier = Modifier.weight(1f),
            ) {
                Text(DesktopStrings.get("reference_remove"), maxLines = 1)
            }
        }
    }
}

private val PANEL_PADDING = 16.dp
