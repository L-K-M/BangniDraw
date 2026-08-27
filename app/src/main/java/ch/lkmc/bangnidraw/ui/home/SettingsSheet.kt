package ch.lkmc.bangnidraw.ui.home

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.BuildConfig
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.AutosavePolicy
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.HapticsMode
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.PenButtonAction
import ch.lkmc.bangnidraw.engine.core.PressurePreference
import ch.lkmc.bangnidraw.engine.core.TouchDrawingMode

/** One-level preference sheet; About/licenses is its only child. */
@Composable
internal fun SettingsSheet(
    state: StudioViewModel.UiState,
    historyMaxSteps: Int,
    historyMaxBytes: Long,
    onHandedness: (Hand) -> Unit,
    onTouchDrawingMode: (TouchDrawingMode) -> Unit,
    onPenButtonAction: (PenButtonAction) -> Unit,
    onPressurePreference: (PressurePreference) -> Unit,
    onHapticsMode: (HapticsMode) -> Unit,
    onGallerySync: (Boolean) -> Unit,
    onMixerChoice: (MixerChoice) -> Unit,
    onDebugLatency: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showAbout by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .weight(1f)
                    .semantics { heading() },
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.settings_close),
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = SETTINGS_MAX_HEIGHT),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 32.dp),
        ) {
            item { SectionTitle(R.string.settings_drawing) }
            item {
                Column(Modifier.selectableGroup()) {
                    SettingLabel(R.string.settings_handedness)
                    ChoiceRow(
                        R.string.settings_hand_right,
                        state.handedness == Hand.RIGHT,
                    ) { onHandedness(Hand.RIGHT) }
                    ChoiceRow(
                        R.string.settings_hand_left,
                        state.handedness == Hand.LEFT,
                    ) { onHandedness(Hand.LEFT) }
                }
            }
            item {
                SwitchRow(
                    title = R.string.settings_stylus_only,
                    body = R.string.settings_stylus_only_help,
                    checked = state.touchDrawingMode == TouchDrawingMode.STYLUS_ONLY,
                    onCheckedChange = {
                        onTouchDrawingMode(
                            if (it) TouchDrawingMode.STYLUS_ONLY else TouchDrawingMode.ENABLED,
                        )
                    },
                )
            }
            item {
                Column(Modifier.selectableGroup()) {
                    SettingLabel(R.string.settings_pen_button)
                    ChoiceRow(
                        R.string.settings_pen_button_eraser,
                        state.penButtonAction == PenButtonAction.Eraser,
                    ) { onPenButtonAction(PenButtonAction.Eraser) }
                    ChoiceRow(
                        R.string.settings_pen_button_eyedropper,
                        state.penButtonAction == PenButtonAction.Eyedropper,
                    ) { onPenButtonAction(PenButtonAction.Eyedropper) }
                    ChoiceRow(
                        R.string.settings_pen_button_none,
                        state.penButtonAction == PenButtonAction.None,
                    ) { onPenButtonAction(PenButtonAction.None) }
                }
            }
            item {
                Column(Modifier.selectableGroup()) {
                    SettingLabel(R.string.settings_pressure)
                    ChoiceRow(
                        R.string.settings_pressure_softer,
                        state.pressurePreference == PressurePreference.SOFTER,
                    ) { onPressurePreference(PressurePreference.SOFTER) }
                    ChoiceRow(
                        R.string.settings_pressure_linear,
                        state.pressurePreference == PressurePreference.LINEAR,
                    ) { onPressurePreference(PressurePreference.LINEAR) }
                    ChoiceRow(
                        R.string.settings_pressure_harder,
                        state.pressurePreference == PressurePreference.HARDER,
                    ) { onPressurePreference(PressurePreference.HARDER) }
                }
            }

            item { SectionTitle(R.string.settings_feedback) }
            item {
                SwitchRow(
                    title = R.string.settings_haptics,
                    checked = state.hapticsMode == HapticsMode.ENABLED,
                    onCheckedChange = {
                        onHapticsMode(if (it) HapticsMode.ENABLED else HapticsMode.DISABLED)
                    },
                )
            }

            item { SectionTitle(R.string.settings_storage) }
            item {
                SwitchRow(
                    title = R.string.settings_gallery_sync,
                    body = R.string.settings_gallery_sync_help,
                    checked = state.gallerySync,
                    onCheckedChange = onGallerySync,
                )
            }
            item {
                ReadoutRow(
                    R.string.settings_undo_limit,
                    stringResource(
                        R.string.settings_undo_limit_value,
                        historyMaxSteps,
                        historyMaxBytes / BYTES_PER_MIB,
                    ),
                )
            }

            if (BuildConfig.MIXBOX) {
                item { SectionTitle(R.string.settings_color) }
                item {
                    Column(Modifier.selectableGroup()) {
                        SettingLabel(R.string.settings_mixer)
                        ChoiceRow(
                            R.string.settings_mixer_pigment,
                            state.mixerChoice == MixerChoice.PIGMENT,
                        ) { onMixerChoice(MixerChoice.PIGMENT) }
                        ChoiceRow(
                            R.string.settings_mixer_rgb,
                            state.mixerChoice == MixerChoice.RGB,
                        ) { onMixerChoice(MixerChoice.RGB) }
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.settings_mixbox_license),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                    )
                }
            }

            if (BuildConfig.DEBUG) {
                item { SectionTitle(R.string.settings_debug) }
                item {
                    SwitchRow(
                        title = R.string.settings_latency_overlay,
                        checked = state.debugLatency,
                        onCheckedChange = onDebugLatency,
                    )
                }
            }

            item {
                Text(
                    text = stringResource(R.string.settings_accessibility_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                )
            }
            item {
                TextButton(
                    onClick = { showAbout = true },
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) {
                    Text(stringResource(R.string.settings_about))
                }
            }
        }
    }

    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }
}

@Composable
private fun SectionTitle(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 8.dp)
            .semantics { heading() },
    )
}

@Composable
private fun SettingLabel(title: Int) {
    Text(
        text = stringResource(title),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
}

@Composable
private fun ChoiceRow(title: Int, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TARGET)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(stringResource(title), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun SwitchRow(
    title: Int,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    body: Int? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TARGET)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            if (body != null) {
                Text(
                    stringResource(body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun ReadoutRow(title: Int, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = MIN_TARGET)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.about_title, BuildConfig.VERSION_NAME)) },
        text = {
            LazyColumn {
                item {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_bg),
                        contentDescription = null,
                        modifier = Modifier.size(ABOUT_ICON_SIZE),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.about_body))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(
                            R.string.about_autosave,
                            AutosavePolicy.QUIET_MS / MILLIS_PER_SECOND,
                            AutosavePolicy.ONE_CHECKPOINT_MS / MILLIS_PER_SECOND,
                        ),
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.about_uninstall))
                }
                if (BuildConfig.MIXBOX) {
                    item {
                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(16.dp))
                        Text(stringResource(R.string.about_mixbox))
                        TextButton(onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(MIXBOX_LICENSE_URL)),
                            )
                        }) {
                            Text(stringResource(R.string.about_mixbox_license))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.about_close))
            }
        },
    )
}

private val SETTINGS_MAX_HEIGHT = 640.dp
private val MIN_TARGET = 48.dp
private val ABOUT_ICON_SIZE = 96.dp
private const val BYTES_PER_MIB = 1024L * 1024L
private const val MILLIS_PER_SECOND = 1_000L
private const val MIXBOX_LICENSE_URL = "https://creativecommons.org/licenses/by-nc/4.0/"
