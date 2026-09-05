package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.AppTheme
import ch.lkmc.bangnidraw.engine.core.CanvasShortcutCatalog
import ch.lkmc.bangnidraw.engine.core.CanvasShortcut
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.ThemeColorPolicy

/**
 * The desktop Settings window — `:app`'s `SettingsSheet`, minus the rows that
 * describe hardware this shell does not have.
 *
 * Absent on purpose, not forgotten: haptics (no vibrator), stylus-only, the
 * pen button, the eraser-end preset and the pressure curve (Compose Desktop
 * reports no stylus at all — see AGENTS.md), and the gallery mirror (there is
 * no gallery; a painting is a file). Everything else is the same choice
 * stored in the same shape, so the two products read alike.
 */
@Composable
internal fun DesktopSettings(state: DesktopShellState, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SettingsGroup(DesktopStrings.get("settings_appearance"))
        Text(
            DesktopStrings.get("settings_theme_color"),
            style = MaterialTheme.typography.bodySmall,
        )
        // Wraps rather than scrolls: eight themes do not fit this window's
        // width, and a theme the user cannot see is one they cannot choose.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().semantics { selectableGroup() },
        ) {
            for (theme in AppTheme.entries) {
                ThemeChip(theme, state.theme == theme) { state.chooseTheme(theme) }
            }
        }

        SettingChoice(DesktopStrings.get("settings_handedness")) {
            SettingChip(DesktopStrings.get("settings_hand_right"), state.hand == Hand.RIGHT) {
                state.chooseHand(Hand.RIGHT)
            }
            SettingChip(DesktopStrings.get("settings_hand_left"), state.hand == Hand.LEFT) {
                state.chooseHand(Hand.LEFT)
            }
        }

        SettingsGroup(DesktopStrings.get("settings_drawing"))
        SettingToggle(
            DesktopStrings.get("settings_snap_right_angles"),
            state.snapRightAngles,
        ) { state.chooseSnapRightAngles(it) }
        Text(
            DesktopStrings.get("settings_snap_right_angles_help"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsGroup(DesktopStrings.get("settings_color"))
        SettingChoice(DesktopStrings.get("settings_mixer")) {
            SettingChip(
                DesktopStrings.get("settings_mixer_pigment"),
                state.mixerChoice == MixerChoice.PIGMENT,
                // A build without Mixbox has nothing to switch to; the chip
                // says so by being unavailable rather than by lying.
                enabled = state.pigmentAvailable,
            ) { state.chooseMixer(MixerChoice.PIGMENT) }
            SettingChip(
                DesktopStrings.get("settings_mixer_rgb"),
                state.mixerChoice == MixerChoice.RGB,
            ) { state.chooseMixer(MixerChoice.RGB) }
        }
        Text(
            DesktopStrings.get("help_mixer_body"),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SettingsGroup(DesktopStrings.get("settings_shortcuts"))
        // The catalogue itself, not a transcription of it: the rows and the
        // behaviour come from the same table, so help cannot drift from what
        // the keyboard does.
        for (row in CanvasShortcutCatalog.rows) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    row.chord,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(CHORD_WIDTH),
                )
                Text(
                    shortcutLabel(row.action),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsGroup(DesktopStrings.get("about_title"))
        Text(
            DesktopAbout.body(state.mixboxAttribution),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ThemeChip(theme: AppTheme, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Box(
                Modifier
                    .size(THEME_SWATCH)
                    .background(
                        Color(ThemeColorPolicy.colors(theme).primaryContainerArgb),
                        CircleShape,
                    )
                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
            )
        },
        modifier = Modifier.semantics { contentDescription = themeLabel(theme) },
    )
}

/**
 * A theme's name, from `:app`'s own strings — the chip's label is a colour
 * swatch, which announces nothing, so without this the picker reads as
 * "selected, 1 of 8" eight times over. Exhaustive, so a new theme cannot ship
 * nameless.
 */
private fun themeLabel(theme: AppTheme): String = DesktopStrings.get(
    when (theme) {
        AppTheme.SAFFRON -> "settings_theme_saffron"
        AppTheme.CORAL -> "settings_theme_coral"
        AppTheme.VIOLET -> "settings_theme_violet"
        AppTheme.TEAL -> "settings_theme_teal"
        AppTheme.NINETIES -> "settings_theme_nineties"
        AppTheme.SYNTHWAVE -> "settings_theme_synthwave"
        AppTheme.MIDNIGHT -> "settings_theme_midnight"
        AppTheme.FOREST -> "settings_theme_forest"
    },
)

/**
 * The catalogue rows' verbs. Android's Settings sheet prints a subset; the
 * desktop lists every row it publishes, so a chord that does something has a
 * line that says what.
 */
private fun shortcutLabel(action: CanvasShortcut): String = DesktopStrings.get(
    when (action) {
        CanvasShortcut.UNDO -> "canvas_undo"
        CanvasShortcut.REDO -> "canvas_redo"
        CanvasShortcut.SIZE_DOWN -> "shortcut_action_size_down"
        CanvasShortcut.SIZE_UP -> "shortcut_action_size_up"
        CanvasShortcut.BRUSH -> "shortcut_action_brush"
        CanvasShortcut.ERASER -> "tool_eraser"
        CanvasShortcut.SMUDGE -> "tool_smudge"
        CanvasShortcut.WATER -> "tool_water"
        CanvasShortcut.FILL -> "tool_fill"
        CanvasShortcut.EYEDROPPER -> "tool_eyedropper"
        CanvasShortcut.BEGIN_EYEDROPPER, CanvasShortcut.END_EYEDROPPER ->
            "shortcut_action_eyedropper_hold"
        CanvasShortcut.RESET_VIEW -> "canvas_reset_view"
        CanvasShortcut.TOGGLE_FOCUS -> "shortcut_action_toggle_controls"
        CanvasShortcut.TOGGLE_LAYERS -> "shortcut_action_toggle_layers"
        CanvasShortcut.TOGGLE_COLOR -> "shortcut_action_toggle_color"
    },
)

private val PANEL_PADDING = 16.dp
private val THEME_SWATCH = 18.dp
private val CHORD_WIDTH = 120.dp
