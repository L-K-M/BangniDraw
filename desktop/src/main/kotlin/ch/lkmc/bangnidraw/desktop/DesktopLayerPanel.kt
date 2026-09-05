package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerPanelOrder
import ch.lkmc.bangnidraw.engine.core.LayerReorderAction
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.LayerThumbnail
import ch.lkmc.bangnidraw.engine.core.Refusal

/** Every panel action, so the shell wires one object instead of fifteen lambdas. */
internal class DesktopLayerActions(
    val select: (Int) -> Unit,
    val add: () -> Unit,
    val duplicate: (Int) -> Unit,
    val delete: (Int) -> Unit,
    val clear: (Int) -> Unit,
    val mergeDown: (Int) -> Unit,
    val flatten: () -> Unit,
    val move: (Int, LayerReorderAction) -> Unit,
    /** An arbitrary reorder, which only a drag produces; both are model indices. */
    val moveTo: (Int, Int) -> Unit,
    val rename: (Int, String) -> Unit,
    val setOpacity: (Int, Float) -> Unit,
    val setVisible: (Int, Boolean) -> Unit,
    val setBlendMode: (Int, BlendMode) -> Unit,
    val setAlphaLock: (Int, Boolean) -> Unit,
    val setLocked: (Int, Boolean) -> Unit,
    val setPaperColor: (Int) -> Unit,
)

/**
 * The desktop layer panel — `:app`'s `LayerPanel` in a window of its own.
 *
 * Rows are **top-first**, the order the user sees on the canvas, while the
 * model is bottom-first; [LayerPanelOrder] is the one place that conversion
 * happens, on both products.
 *
 * Reordering is the panel menu's four moves rather than Android's drag: a
 * drag inside a floating utility window competes with the window's own move,
 * and `LayerPanelOrder.actions` already reports exactly which of the four
 * apply to a row.
 */
@Composable
internal fun DesktopLayerPanel(
    stack: LayerStack,
    paperColor: Int,
    canvas: CanvasSize,
    layerCap: Int,
    thumbnails: Map<LayerId, LayerThumbnail>,
    refusal: DesktopRefusal?,
    actions: DesktopLayerActions,
    modifier: Modifier = Modifier,
) {
    var renaming by remember { mutableStateOf<Int?>(null) }
    var hint by remember { mutableStateOf<String?>(null) }

    // The order the rows are *drawn* in while a drag is in flight. It follows
    // the model whenever the model changes, so a refused move snaps back
    // rather than leaving the panel showing an order the document never took.
    var displayOrder by remember(stack.layers) {
        mutableStateOf(stack.layers.asReversed().map(Layer::id))
    }
    var draggedId by remember { mutableStateOf<LayerId?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }

    // The refusal carries a revision, so two identical refusals in a row still
    // re-show the hint rather than looking like a dead button.
    LaunchedEffect(refusal) {
        val reason = refusal ?: return@LaunchedEffect
        // A refused move leaves the rows where the drag put them; the model
        // is the truth, so the panel goes back to it before saying why.
        displayOrder = stack.layers.asReversed().map(Layer::id)
        hint = DesktopLayerNames.refusal(reason.reason, canvas, layerCap)
        kotlinx.coroutines.delay(HINT_MS)
        hint = null
    }

    Column(modifier.fillMaxSize()) {
        Header(
            count = stack.size,
            cap = layerCap,
            onAdd = actions.add,
            onFlatten = actions.flatten,
        )
        Divider()
        if (hint != null) {
            Text(
                hint.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ROW_PADDING, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Assertive },
            )
        }

        // The panel's top row is the top layer, index size - 1 in the model;
        // `displayOrder` is that reversal, plus whatever a live drag moved.
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(displayOrder, key = LayerId::value) { id ->
                val stackIndex = stack.indexOf(id)
                val layer = stack.layers.getOrNull(stackIndex) ?: return@items
                LayerRow(
                    layer = layer,
                    stackIndex = stackIndex,
                    size = stack.size,
                    active = stackIndex == stack.activeIndex,
                    thumbnail = thumbnails[id],
                    actions = actions,
                    onRename = { renaming = stackIndex },
                    dragOffset = if (draggedId == id) dragOffset else 0f,
                    onDragStart = {
                        draggedId = id
                        dragOffset = 0f
                        actions.select(stackIndex)
                    },
                    onDrag = { delta ->
                        if (draggedId != id) return@LayerRow

                        dragOffset += delta
                        val current = displayOrder.indexOf(id)
                        val direction = when {
                            dragOffset > rowHeightPx / 2f -> 1
                            dragOffset < -rowHeightPx / 2f -> -1
                            else -> 0
                        }
                        val target = current + direction
                        if (direction == 0 || target !in displayOrder.indices) return@LayerRow

                        displayOrder = displayOrder.toMutableList().apply {
                            add(target, removeAt(current))
                        }
                        dragOffset -= direction * rowHeightPx
                    },
                    onDragEnd = {
                        // The model's own current position, not the one the
                        // drag started from: a stroke or another panel may
                        // have moved the stack underneath the gesture.
                        val fromDisplay = stack.layers.asReversed().indexOfFirst { it.id == id }
                        val toDisplay = displayOrder.indexOf(id)
                        draggedId = null
                        dragOffset = 0f
                        LayerPanelOrder.move(fromDisplay, toDisplay, stack.size)
                            ?.let { actions.moveTo(it.from, it.to) }
                    },
                    onDragCancel = {
                        draggedId = null
                        dragOffset = 0f
                        displayOrder = stack.layers.asReversed().map(Layer::id)
                    },
                )
                Divider()
            }
        }
        Divider()
        PaperRow(paperColor, actions.setPaperColor)
    }

    val renameIndex = renaming
    if (renameIndex != null && renameIndex in stack.layers.indices) {
        RenameDialog(
            initial = DesktopLayerNames.resolve(stack.layers[renameIndex].props.name),
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                actions.rename(renameIndex, name)
            },
        )
    }
}

@Composable
private fun Header(count: Int, cap: Int, onAdd: () -> Unit, onFlatten: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(HEADER_HEIGHT).padding(horizontal = ROW_PADDING),
    ) {
        Text(DesktopStrings.get("layers_title"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.width(8.dp))
        Text(
            DesktopStrings.get("layers_count", count, cap),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = DesktopStrings.get("layer_add"))
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = DesktopStrings.get("layer_panel_more"))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(DesktopStrings.get("layer_flatten")) },
                    onClick = {
                        menu = false
                        onFlatten()
                    },
                )
            }
        }
    }
}

@Composable
private fun LayerRow(
    layer: Layer,
    stackIndex: Int,
    size: Int,
    active: Boolean,
    thumbnail: LayerThumbnail?,
    actions: DesktopLayerActions,
    onRename: () -> Unit,
    dragOffset: Float,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val name = DesktopLayerNames.resolve(layer.props.name)
    Surface(
        onClick = { actions.select(stackIndex) },
        color = if (active) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .graphicsLayer { translationY = dragOffset }
            .semantics {
                selected = active
                contentDescription = name
            },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = ROW_PADDING, vertical = 6.dp),
        ) {
            // The drag affordance, as `:app`'s row carries it. It publishes no
            // semantics of its own: a pointer drag is not something a screen
            // reader can perform, and the menu's four moves are the path that
            // is reachable without one — so a focusable handle here would
            // announce an action it can never carry out.
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(HANDLE)
                    .clearAndSetSemantics {}
                    .pointerInput(layer.id) {
                        detectDragGestures(
                            onDragStart = { onDragStart() },
                            onDragEnd = onDragEnd,
                            onDragCancel = onDragCancel,
                            onDrag = { change, amount ->
                                change.consume()
                                onDrag(amount.y)
                            },
                        )
                    },
            ) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Thumbnail(thumbnail)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // The two modes that change what a stroke does are worth
                // saying on the row: an alpha-locked or locked layer refuses
                // edits, and a panel that only shows that inside a menu makes
                // it look like a bug. They share the name's line because the
                // row is a fixed height (the drag counts rows by it).
                val badges = buildList {
                    if (layer.props.blendMode != BlendMode.NORMAL) {
                        add(DesktopLayerNames.blendMode(layer.props.blendMode))
                    }
                    if (layer.props.alphaLock) add(DesktopStrings.get("layer_alpha_lock"))
                    if (layer.props.locked) add(DesktopStrings.get("layer_lock"))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (badges.isNotEmpty()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            badges.joinToString(" · "),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DesktopThinSlider(
                        value = layer.props.opacity,
                        range = 0f..1f,
                        axis = DesktopSliderAxis.Horizontal,
                        description = DesktopStrings.get("layer_opacity"),
                        onValueChange = { actions.setOpacity(stackIndex, it) },
                        modifier = Modifier.weight(1f),
                        fillWidth = true,
                    )
                    Text(
                        // Truncating, because `:app`'s LayerPanel truncates
                        // the same product too: the two panels must read the
                        // same number for the same layer, and rounding here
                        // would put them one percent apart for half the
                        // slider. Change both or neither.
                        DesktopStrings.get(
                            "layer_opacity_value",
                            (layer.props.opacity * PERCENT).toInt(),
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.width(OPACITY_LABEL),
                    )
                }
            }
            IconButton(onClick = { actions.setVisible(stackIndex, !layer.props.visible) }) {
                Icon(
                    if (layer.props.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = DesktopStrings.get(
                        if (layer.props.visible) "layer_hide" else "layer_show",
                    ),
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = DesktopStrings.get("layer_more"))
                }
                LayerMenu(
                    open = menu,
                    onDismiss = { menu = false },
                    layer = layer,
                    stackIndex = stackIndex,
                    size = size,
                    actions = actions,
                    onRename = onRename,
                )
            }
        }
    }
}

@Composable
private fun LayerMenu(
    open: Boolean,
    onDismiss: () -> Unit,
    layer: Layer,
    stackIndex: Int,
    size: Int,
    actions: DesktopLayerActions,
    onRename: () -> Unit,
) {
    var blendMenu by remember { mutableStateOf(false) }
    DropdownMenu(expanded = open, onDismissRequest = onDismiss) {
        Item(DesktopStrings.get("layer_rename"), onDismiss, onRename)
        Item(DesktopStrings.get("layer_duplicate"), onDismiss) { actions.duplicate(stackIndex) }
        Item(DesktopStrings.get("layer_delete"), onDismiss) { actions.delete(stackIndex) }
        Item(DesktopStrings.get("layer_clear"), onDismiss) { actions.clear(stackIndex) }
        Item(DesktopStrings.get("layer_merge_down"), onDismiss) { actions.mergeDown(stackIndex) }
        Divider()
        // Only the moves this row can actually make; LayerPanelOrder decides,
        // so a top row never offers "move to top".
        for (action in LayerPanelOrder.actions(stackIndex, size)) {
            Item(reorderLabel(action), onDismiss) { actions.move(stackIndex, action) }
        }
        Divider()
        Box {
            DropdownMenuItem(
                text = {
                    Text(
                        DesktopStrings.get("layer_blend_mode") + ": " +
                            DesktopLayerNames.blendMode(layer.props.blendMode),
                    )
                },
                onClick = { blendMenu = true },
            )
            DropdownMenu(expanded = blendMenu, onDismissRequest = { blendMenu = false }) {
                for (mode in BlendMode.entries) {
                    DropdownMenuItem(
                        text = { Text(DesktopLayerNames.blendMode(mode)) },
                        trailingIcon = {
                            if (mode == layer.props.blendMode) Text("✓")
                        },
                        onClick = {
                            blendMenu = false
                            onDismiss()
                            actions.setBlendMode(stackIndex, mode)
                        },
                    )
                }
            }
        }
        Item(check(DesktopStrings.get("layer_alpha_lock"), layer.props.alphaLock), onDismiss) {
            actions.setAlphaLock(stackIndex, !layer.props.alphaLock)
        }
        Item(check(DesktopStrings.get("layer_lock"), layer.props.locked), onDismiss) {
            actions.setLocked(stackIndex, !layer.props.locked)
        }
    }
}

@Composable
private fun Item(label: String, onDismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        onClick = {
            onDismiss()
            action()
        },
    )
}

@Composable
private fun Thumbnail(thumbnail: LayerThumbnail?) {
    val bitmap = remember(thumbnail) { thumbnail?.toImageBitmap() }
    Box(
        Modifier
            .size(THUMBNAIL)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(bitmap = bitmap, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PaperRow(paperColor: Int, onPaperColor: (Int) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { menu = true },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().height(PAPER_ROW_HEIGHT),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = ROW_PADDING),
            ) {
                PaperSwatch(Color(paperColor))
                Spacer(Modifier.width(12.dp))
                Text(DesktopStrings.get("layer_paper"), style = MaterialTheme.typography.bodyMedium)
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            for ((label, argb) in DesktopPalette.PAPERS) {
                DropdownMenuItem(
                    text = { Text(DesktopStrings.get(label)) },
                    leadingIcon = { PaperSwatch(Color(argb)) },
                    onClick = {
                        menu = false
                        onPaperColor(argb)
                    },
                )
            }
        }
    }
}

/** A checker behind the fill, so the transparent choice reads as see-through. */
@Composable
private fun PaperSwatch(color: Color) {
    val checkerA = MaterialTheme.colorScheme.surface
    val checkerB = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(PAPER_SWATCH)) {
        val half = size / 2f
        drawRect(checkerA)
        drawRect(checkerB, size = half)
        drawRect(
            checkerB,
            topLeft = androidx.compose.ui.geometry.Offset(half.width, half.height),
            size = half,
        )
        drawRect(color)
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(DesktopStrings.get("layer_rename_title")) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(DesktopStrings.get("layer_rename_hint")) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text(DesktopStrings.get("layer_rename")) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(DesktopStrings.get("layer_cancel")) }
        },
    )
}

private fun reorderLabel(action: LayerReorderAction): String = DesktopStrings.get(
    when (action) {
        LayerReorderAction.UP -> "layer_move_up"
        LayerReorderAction.DOWN -> "layer_move_down"
        LayerReorderAction.TOP -> "layer_move_top"
        LayerReorderAction.BOTTOM -> "layer_move_bottom"
    },
)

private fun check(label: String, on: Boolean): String = if (on) "$label ✓" else label

/**
 * [LayerThumbnail] is straight ARGB ints, top-down; Skia wants bytes. The
 * alpha type is UNPREMUL because the thumbnail pass already recovered the
 * straight channels.
 */
private fun LayerThumbnail.toImageBitmap(): ImageBitmap {
    val bytes = ByteArray(width * height * RGBA_BYTES)
    var offset = 0
    for (pixel in argb) {
        bytes[offset] = ((pixel ushr 16) and 0xFF).toByte()
        bytes[offset + 1] = ((pixel ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = (pixel and 0xFF).toByte()
        bytes[offset + 3] = (pixel ushr 24).toByte()
        offset += RGBA_BYTES
    }
    return org.jetbrains.skia.Image.makeRaster(
        imageInfo = org.jetbrains.skia.ImageInfo(
            width, height,
            org.jetbrains.skia.ColorType.RGBA_8888,
            org.jetbrains.skia.ColorAlphaType.UNPREMUL,
        ),
        bytes = bytes,
        rowBytes = width * RGBA_BYTES,
    ).toComposeImageBitmap()
}

private val HEADER_HEIGHT = 56.dp
/**
 * Fixed, because the drag arithmetic counts rows by their height — so the
 * row's content has to fit it: 12 dp of padding, the name line, and the
 * slider's own 48 dp slab. The badges share the name's line for that reason.
 */
private val ROW_HEIGHT = 84.dp
private val HANDLE = 32.dp
private val PAPER_ROW_HEIGHT = 48.dp
private val ROW_PADDING = 12.dp
private val THUMBNAIL = 44.dp
private val PAPER_SWATCH = 24.dp
private val OPACITY_LABEL = 44.dp
private const val PERCENT = 100f
private const val HINT_MS = 4_000L
private const val RGBA_BYTES = 4
