package ch.lkmc.bangnidraw.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.CanvasPreset
import ch.lkmc.bangnidraw.engine.core.CanvasPresetId
import ch.lkmc.bangnidraw.engine.core.CanvasPresets
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.CustomSizeResult
import ch.lkmc.bangnidraw.engine.core.MemoryBudget
import ch.lkmc.bangnidraw.engine.core.SizeRefusal
import ch.lkmc.bangnidraw.engine.core.TileGrid
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchBlack
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchGray
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchWarm
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchWhite

/**
 * The New Canvas dialog (`docs/plan/08-ui-and-layout.md` §2.1): the fixed
 * presets annotated with what this device holds, a Custom row validated on
 * every keystroke, orientation for non-square sizes, and the paper swatches.
 * Every decision it renders comes from `CanvasPresets`/`MemoryBudget` —
 * pure, already tested — so this composable only lays the answers out.
 *
 * The "+" custom-paper swatch waits for the color panel (roadmap step 7);
 * the swatch row here is 08 §2.1's fixed five.
 */
@Composable
fun NewCanvasDialog(
    budget: MemoryBudget.Result,
    onDismiss: () -> Unit,
    onCreate: (CanvasSize, paperColor: Int) -> Unit,
) {
    val presets = CanvasPresets.forDevice(budget)
    var selected by rememberSaveable { mutableIntStateOf(CanvasPresets.defaultIndex(presets)) }
    var customW by rememberSaveable { mutableStateOf("2048") }
    var customH by rememberSaveable { mutableStateOf("2048") }
    var landscape by rememberSaveable { mutableStateOf(false) }
    var paper by rememberSaveable { mutableIntStateOf(PaperSwatchWhite.toArgb()) }

    val isCustom = selected == presets.size
    val chosenSize: CanvasSize? = if (isCustom) {
        val w = customW.toIntOrNull()
        val h = customH.toIntOrNull()
        if (w != null && h != null && w > 0 && h > 0) CanvasSize(w, h) else null
    } else {
        presets.getOrNull(selected)?.takeIf { it.enabled }
            ?.let { if (it.isSquare) it.size else it.oriented(landscape) }
    }
    val validation: CustomSizeResult? = chosenSize?.let { CanvasPresets.custom(it, budget) }
    val ok = validation as? CustomSizeResult.Ok

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_canvas_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                presets.forEachIndexed { index, preset ->
                    PresetRow(
                        preset = preset,
                        selected = selected == index,
                        onSelect = { if (preset.enabled) selected = index },
                    )
                }

                // The Custom row: two fields, revalidated per keystroke.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(selected = isCustom, onClick = { selected = presets.size }),
                ) {
                    RadioButton(selected = isCustom, onClick = { selected = presets.size })
                    Text(
                        stringResource(R.string.canvas_preset_custom),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = customW,
                        onValueChange = { customW = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.canvas_width)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                        enabled = isCustom,
                    )
                    Text(" × ", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = customH,
                        onValueChange = { customH = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.canvas_height)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.width(96.dp),
                        enabled = isCustom,
                    )
                }
                val helper = when {
                    !isCustom -> null
                    chosenSize == null -> stringResource(
                        R.string.size_too_small, TileGrid.MIN_EDGE,
                    )
                    else -> when (val v = validation) {
                        is CustomSizeResult.Ok ->
                            stringResource(R.string.canvas_preset_fits, v.preset.maxLayers)
                        is CustomSizeResult.Refused -> refusalText(v.reason, budget)
                        null -> null
                    }
                }
                if (helper != null) {
                    Text(
                        helper,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Orientation, hidden for square sizes (08 §2.1).
                val orientable = if (isCustom) false else presets.getOrNull(selected)?.isSquare == false
                if (orientable) {
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = landscape,
                            onClick = { landscape = true },
                            label = { Text(stringResource(R.string.orientation_landscape)) },
                        )
                        FilterChip(
                            selected = !landscape,
                            onClick = { landscape = false },
                            label = { Text(stringResource(R.string.orientation_portrait)) },
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.paper_label),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    for ((color, label) in paperSwatches()) {
                        PaperSwatch(
                            color = color,
                            label = label,
                            selected = paper == color.toArgb(),
                            onSelect = { paper = color.toArgb() },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { ok?.let { onCreate(it.preset.size, paper) } },
                enabled = ok != null,
            ) { Text(stringResource(R.string.new_canvas_create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.new_canvas_cancel)) }
        },
    )
}

@Composable
private fun PresetRow(preset: CanvasPreset, selected: Boolean, onSelect: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, enabled = preset.enabled, onClick = onSelect),
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = preset.enabled)
        Column(Modifier.weight(1f)) {
            Text(
                presetName(preset.id),
                style = MaterialTheme.typography.bodyMedium,
                color = if (preset.enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "${preset.size.width} × ${preset.size.height}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            if (preset.enabled) stringResource(R.string.canvas_preset_fits, preset.maxLayers)
            else stringResource(R.string.canvas_preset_too_large),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PaperSwatch(color: Color, label: String, selected: Boolean, onSelect: () -> Unit) {
    val border = if (selected) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.outlineVariant
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .size(28.dp)
            .border(if (selected) 2.dp else 1.dp, border, CircleShape)
            .background(
                // The transparent swatch shows the surface variant as its
                // "checkerboard" stand-in — an actual checker at 28 dp reads
                // as noise.
                if (color == Color.Transparent) MaterialTheme.colorScheme.surfaceVariant else color,
                CircleShape,
            )
            .selectable(selected = selected, onClick = onSelect)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        if (color == Color.Transparent) {
            Text(
                "∅",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun presetName(id: CanvasPresetId): String = when (id) {
    CanvasPresetId.PHONE_SKETCH -> stringResource(R.string.canvas_preset_phone)
    CanvasPresetId.SQUARE_2048 -> stringResource(R.string.canvas_preset_square)
    CanvasPresetId.TABLET -> stringResource(R.string.canvas_preset_tablet)
    CanvasPresetId.LARGE_4096 -> stringResource(R.string.canvas_preset_large)
    CanvasPresetId.CUSTOM -> stringResource(R.string.canvas_preset_custom)
}

@Composable
private fun refusalText(reason: SizeRefusal, budget: MemoryBudget.Result): String = when (reason) {
    SizeRefusal.TOO_SMALL -> stringResource(R.string.size_too_small, TileGrid.MIN_EDGE)
    SizeRefusal.TOO_LARGE_FOR_FORMAT ->
        stringResource(R.string.size_too_large_format, TileGrid.MAX_EDGE)
    SizeRefusal.TOO_MANY_TILES -> stringResource(R.string.size_too_many_tiles)
    SizeRefusal.TOO_LARGE_FOR_DEVICE ->
        stringResource(R.string.size_too_large_device, budget.maxCanvasEdge)
}

@Composable
private fun paperSwatches(): List<Pair<Color, String>> = listOf(
    PaperSwatchWhite to stringResource(R.string.paper_white),
    PaperSwatchWarm to stringResource(R.string.paper_warm),
    PaperSwatchGray to stringResource(R.string.paper_gray),
    PaperSwatchBlack to stringResource(R.string.paper_black),
    Color.Transparent to stringResource(R.string.paper_transparent),
)
