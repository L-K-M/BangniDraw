package ch.lkmc.bangnidraw.ui.canvas

import android.os.Build
import android.view.HapticFeedbackConstants
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.R
import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.Layer
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerPanelOrder
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.LayerThumbnail
import ch.lkmc.bangnidraw.engine.core.OpacityMilestone
import ch.lkmc.bangnidraw.engine.core.Refusal
import ch.lkmc.bangnidraw.ui.theme.Indigo
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchBlack
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchGray
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchWarm
import ch.lkmc.bangnidraw.ui.theme.PaperSwatchWhite
import kotlinx.coroutines.delay

/** The top-first layer sheet from `08-ui-and-layout.md` §3.3. */
@Composable
internal fun LayerPanel(
    canvas: CanvasSize,
    stack: LayerStack,
    paperColor: Int,
    layerCap: Int,
    compact: Boolean,
    documentBusy: Boolean,
    feedbackRevision: Long,
    refusal: Refusal?,
    thumbnails: Map<LayerId, LayerThumbnail>,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
    onAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onDuplicate: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onMergeDown: (Int) -> Unit,
    onFlatten: () -> Unit,
    onClear: (Int) -> Unit,
    onRename: (Int, String) -> Unit,
    onOpacityPreview: (Int, Float) -> Boolean,
    onOpacityFinished: () -> Unit,
    onToggleVisibility: (Int) -> Unit,
    onBlendMode: (Int, BlendMode) -> Unit,
    onToggleAlphaLock: (Int) -> Unit,
    onToggleLock: (Int) -> Unit,
    onPaperColor: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayOrder by remember(stack.layers) {
        mutableStateOf(stack.layers.asReversed().map(Layer::id))
    }
    var draggedId by remember { mutableStateOf<LayerId?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    var opacityLayer by remember { mutableStateOf<LayerId?>(null) }
    var rename by remember { mutableStateOf<RenameRequest?>(null) }
    var confirmation by remember { mutableStateOf<LayerConfirmation?>(null) }
    var headerMenu by remember { mutableStateOf(false) }
    var paperMenu by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var hintRefusal by remember { mutableStateOf<Refusal?>(null) }
    var seenFeedback by remember { mutableLongStateOf(feedbackRevision) }
    val view = LocalView.current
    val rowHeightPx = with(LocalDensity.current) { ROW_HEIGHT.toPx() }
    val refusalText = refusalMessage(refusal, canvas, layerCap)
    val listState = rememberLazyListState()

    LaunchedEffect(stack.layers.map(Layer::id)) {
        displayOrder = stack.layers.asReversed().map(Layer::id)
    }
    LaunchedEffect(stack.active.id) {
        val displayIndex = LayerPanelOrder.displayIndex(stack.activeIndex, stack.size)
        listState.animateScrollToItem(displayIndex)
    }
    LaunchedEffect(feedbackRevision) {
        if (feedbackRevision == seenFeedback) return@LaunchedEffect
        seenFeedback = feedbackRevision

        if (refusal == null) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            return@LaunchedEffect
        }

        val rejected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            HapticFeedbackConstants.REJECT
        } else {
            HapticFeedbackConstants.LONG_PRESS
        }
        view.performHapticFeedback(rejected)
        displayOrder = stack.layers.asReversed().map(Layer::id)
        hint = refusalText
        hintRefusal = refusal
        delay(HINT_MS)
        hint = null
        hintRefusal = null
    }
    LaunchedEffect(stack.active.id) {
        val editing = opacityLayer ?: return@LaunchedEffect
        if (editing == stack.active.id) return@LaunchedEffect

        onOpacityFinished()
        opacityLayer = null
    }
    DisposableEffect(Unit) {
        onDispose(onOpacityFinished)
    }

    Box(modifier = modifier.fillMaxHeight()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            shape = if (compact) RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp)
            else MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxSize(),
        ) {
            Column {
                LayerPanelHeader(
                    count = stack.size,
                    cap = layerCap,
                    menuOpen = headerMenu,
                    onAdd = onAdd,
                    onMenuChange = { headerMenu = it },
                    onFlatten = { confirmation = LayerConfirmation.Flatten },
                )

                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                ) {
                    items(displayOrder, key = LayerId::value) { id ->
                        val index = stack.indexOf(id)
                        val layer = stack.layers.getOrNull(index) ?: return@items
                        val displayName = layerName(layer.props.name)
                        LayerRow(
                            layer = layer,
                            index = index,
                            selected = index == stack.activeIndex,
                            documentBusy = documentBusy,
                            editingOpacity = opacityLayer == id,
                            thumbnail = thumbnails[id],
                            dragOffset = if (draggedId == id) dragOffset else 0f,
                            onSelect = {
                                onOpacityFinished()
                                opacityLayer = null
                                onSelect(index)
                                if (compact) onDismiss()
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            },
                            onToggleVisibility = { onToggleVisibility(index) },
                            onOpacityClick = {
                                onSelect(index)
                                opacityLayer = id
                            },
                            onOpacityPreview = { value -> onOpacityPreview(index, value) },
                            onOpacityFinished = onOpacityFinished,
                            onRename = { rename = RenameRequest(index, displayName) },
                            onDuplicate = { onDuplicate(index) },
                            onMergeDown = {
                                val below = stack.layers.getOrNull(index - 1)
                                val needsConfirm = below != null &&
                                    (layer.props.blendMode != BlendMode.NORMAL ||
                                        below.props.blendMode != BlendMode.NORMAL)
                                if (needsConfirm) {
                                    confirmation = LayerConfirmation.Merge(index)
                                } else {
                                    onMergeDown(index)
                                }
                            },
                            onClear = { onClear(index) },
                            onToggleAlphaLock = { onToggleAlphaLock(index) },
                            onToggleLock = { onToggleLock(index) },
                            onBlendMode = { onBlendMode(index, it) },
                            onDelete = { onDelete(index) },
                            onDragStart = {
                                if (documentBusy) return@LayerRow
                                onOpacityFinished()
                                opacityLayer = null
                                draggedId = id
                                dragOffset = 0f
                                onSelect(index)
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
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            },
                            onDragEnd = {
                                val fromDisplay = stack.layers.asReversed().indexOfFirst { it.id == id }
                                val toDisplay = displayOrder.indexOf(id)
                                draggedId = null
                                dragOffset = 0f
                                val move = LayerPanelOrder.move(fromDisplay, toDisplay, stack.size)
                                if (move != null) onMove(move.from, move.to)
                            },
                            onDragCancel = {
                                draggedId = null
                                dragOffset = 0f
                                displayOrder = stack.layers.asReversed().map(Layer::id)
                            },
                        )
                    }
                }

                PaperRow(
                    paperColor = paperColor,
                    menuOpen = paperMenu,
                    onMenuChange = { paperMenu = it },
                    onPaperColor = onPaperColor,
                )
            }
        }

        hint?.let {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shape = MaterialTheme.shapes.large,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = HEADER_HEIGHT + 8.dp, start = 8.dp, end = 8.dp)
                    .then(
                        if (hintRefusal == Refusal.LAST_LAYER) {
                            Modifier.clickable {
                                hint = null
                                hintRefusal = null
                                onClear(stack.activeIndex)
                            }
                        } else {
                            Modifier
                        },
                    ),
            ) {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }

    RenameDialog(
        request = rename,
        onDismiss = { rename = null },
        onRename = { index, name ->
            rename = null
            onRename(index, name)
        },
    )
    ConfirmationDialog(
        confirmation = confirmation,
        layerCount = stack.size,
        onDismiss = { confirmation = null },
        onConfirm = { action ->
            confirmation = null
            when (action) {
                LayerConfirmation.Flatten -> onFlatten()
                is LayerConfirmation.Merge -> onMergeDown(action.index)
            }
        },
    )
}

@Composable
private fun LayerPanelHeader(
    count: Int,
    cap: Int,
    menuOpen: Boolean,
    onAdd: () -> Unit,
    onMenuChange: (Boolean) -> Unit,
    onFlatten: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(HEADER_HEIGHT)
            .padding(start = 16.dp),
    ) {
        Text(
            text = stringResource(R.string.layers_title),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.layers_count, count, cap),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 8.dp),
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.layer_add))
        }
        Box {
            IconButton(onClick = { onMenuChange(true) }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.layer_panel_more),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuChange(false) }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.layer_flatten)) },
                    onClick = {
                        onMenuChange(false)
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
    index: Int,
    selected: Boolean,
    documentBusy: Boolean,
    editingOpacity: Boolean,
    thumbnail: LayerThumbnail?,
    dragOffset: Float,
    onSelect: () -> Unit,
    onToggleVisibility: () -> Unit,
    onOpacityClick: () -> Unit,
    onOpacityPreview: (Float) -> Boolean,
    onOpacityFinished: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onMergeDown: () -> Unit,
    onClear: () -> Unit,
    onToggleAlphaLock: () -> Unit,
    onToggleLock: () -> Unit,
    onBlendMode: (BlendMode) -> Unit,
    onDelete: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var opacity by remember(layer.id, layer.props.opacity) {
        mutableFloatStateOf(layer.props.opacity)
    }
    val view = LocalView.current
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceContainer
    val rowAlpha = if (layer.props.visible) 1f else HIDDEN_ALPHA

    Surface(
        color = background,
        tonalElevation = if (selected) 2.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .graphicsLayer { translationY = dragOffset }
            .alpha(rowAlpha)
            .combinedClickable(
                onClick = onSelect,
                onLongClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    menuOpen = true
                },
            ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .width(SELECTION_BAR)
                    .fillMaxHeight()
                    .background(if (selected) Indigo else Color.Transparent),
            )
            IconButton(
                enabled = !documentBusy,
                onClick = {},
                modifier = Modifier
                    .size(ROW_ACTION)
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
                    contentDescription = stringResource(R.string.layer_drag),
                )
            }
            LayerThumbnail(thumbnail)
            if (editingOpacity) {
                ThinSlider(
                    value = opacity,
                    range = 0f..1f,
                    axis = SliderAxis.Horizontal,
                    description = stringResource(R.string.layer_opacity),
                    onValueChange = { value ->
                        if (!onOpacityPreview(value)) return@ThinSlider

                        if (OpacityMilestone.crossed(opacity, value).isNotEmpty()) {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        opacity = value
                    },
                    onValueChangeFinished = onOpacityFinished,
                    modifier = Modifier.weight(1f),
                    length = OPACITY_SLIDER_LENGTH,
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
                ) {
                    Text(
                        text = layerName(layer.props.name),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = blendModeName(layer.props.blendMode),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = onToggleVisibility, modifier = Modifier.size(ROW_ACTION)) {
                Icon(
                    if (layer.props.visible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = stringResource(
                        if (layer.props.visible) R.string.layer_hide else R.string.layer_show,
                    ),
                )
            }
            TextButton(
                onClick = onOpacityClick,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier
                    .width(OPACITY_VALUE_WIDTH)
                    .height(ROW_ACTION),
            ) {
                Text(
                    stringResource(R.string.layer_opacity_value, (opacity * PERCENT).toInt()),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    modifier = Modifier.size(ROW_ACTION),
                ) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.layer_more),
                    )
                }
                LayerMenu(
                    expanded = menuOpen,
                    layer = layer,
                    onDismiss = { menuOpen = false },
                    onRename = onRename,
                    onDuplicate = onDuplicate,
                    onMergeDown = onMergeDown,
                    onClear = onClear,
                    onToggleAlphaLock = onToggleAlphaLock,
                    onToggleLock = onToggleLock,
                    onBlendMode = onBlendMode,
                    onDelete = onDelete,
                )
            }
        }
    }
}

@Composable
private fun LayerThumbnail(thumbnail: LayerThumbnail?) {
    val checkerA = MaterialTheme.colorScheme.surface
    val checkerB = MaterialTheme.colorScheme.surfaceVariant
    val image = remember(thumbnail) {
        thumbnail?.let {
            Bitmap.createBitmap(it.argb, it.width, it.height, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }
    }
    Box(
        modifier = Modifier
            .padding(horizontal = 2.dp)
            .size(THUMB_SIZE)
            .background(checkerA),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cell = size.minDimension / CHECKER_CELLS
            for (y in 0 until CHECKER_CELLS) {
                for (x in 0 until CHECKER_CELLS) {
                    if ((x + y) % 2 == 0) continue
                    drawRect(
                        color = checkerB,
                        topLeft = androidx.compose.ui.geometry.Offset(x * cell, y * cell),
                        size = androidx.compose.ui.geometry.Size(cell, cell),
                    )
                }
            }
        }
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun LayerMenu(
    expanded: Boolean,
    layer: Layer,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onMergeDown: () -> Unit,
    onClear: () -> Unit,
    onToggleAlphaLock: () -> Unit,
    onToggleLock: () -> Unit,
    onBlendMode: (BlendMode) -> Unit,
    onDelete: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        ActionItem(R.string.layer_rename, onDismiss, onRename)
        ActionItem(R.string.layer_duplicate, onDismiss, onDuplicate)
        ActionItem(R.string.layer_merge_down, onDismiss, onMergeDown)
        ActionItem(R.string.layer_clear, onDismiss, onClear)
        CheckableActionItem(R.string.layer_alpha_lock, layer.props.alphaLock, onDismiss, onToggleAlphaLock)
        CheckableActionItem(R.string.layer_lock, layer.props.locked, onDismiss, onToggleLock)
        Text(
            text = stringResource(R.string.layer_blend_mode),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        )
        for (mode in BlendMode.entries) {
            DropdownMenuItem(
                text = { Text(blendModeName(mode)) },
                leadingIcon = if (layer.props.blendMode == mode) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = {
                    onDismiss()
                    onBlendMode(mode)
                },
            )
        }
        ActionItem(R.string.layer_delete, onDismiss, onDelete)
    }
}

@Composable
private fun ActionItem(label: Int, onDismiss: () -> Unit, action: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        onClick = {
            onDismiss()
            action()
        },
    )
}

@Composable
private fun CheckableActionItem(
    label: Int,
    checked: Boolean,
    onDismiss: () -> Unit,
    action: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(stringResource(label)) },
        leadingIcon = if (checked) {
            { Icon(Icons.Filled.Check, contentDescription = null) }
        } else {
            null
        },
        onClick = {
            onDismiss()
            action()
        },
    )
}

@Composable
private fun PaperRow(
    paperColor: Int,
    menuOpen: Boolean,
    onMenuChange: (Boolean) -> Unit,
    onPaperColor: (Int) -> Unit,
) {
    Box {
        Surface(
            onClick = { onMenuChange(true) },
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .height(ROW_HEIGHT),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                PaperSwatch(Color(paperColor))
                Text(
                    text = stringResource(R.string.layer_paper),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp),
                )
            }
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { onMenuChange(false) }) {
            for (choice in paperChoices()) {
                DropdownMenuItem(
                    text = { Text(stringResource(choice.label)) },
                    leadingIcon = { PaperSwatch(choice.color) },
                    onClick = {
                        onMenuChange(false)
                        onPaperColor(choice.color.toArgb())
                    },
                )
            }
        }
    }
}

@Composable
private fun PaperSwatch(color: Color) {
    val checker = MaterialTheme.colorScheme.surfaceVariant
    Canvas(Modifier.size(PAPER_SWATCH)) {
        drawRect(checker)
        drawRect(color)
    }
}

@Composable
private fun paperChoices(): List<PaperChoice> = listOf(
    PaperChoice(PaperSwatchWhite, R.string.paper_white),
    PaperChoice(PaperSwatchWarm, R.string.paper_warm),
    PaperChoice(PaperSwatchGray, R.string.paper_gray),
    PaperChoice(PaperSwatchBlack, R.string.paper_black),
    PaperChoice(Color.Transparent, R.string.paper_transparent),
)

@Composable
private fun RenameDialog(
    request: RenameRequest?,
    onDismiss: () -> Unit,
    onRename: (Int, String) -> Unit,
) {
    if (request == null) return
    var value by remember(request) { mutableStateOf(request.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.layer_rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.layer_rename_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onRename(request.index, value) }, enabled = value.isNotBlank()) {
                Text(stringResource(R.string.layer_rename))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.layer_cancel)) }
        },
    )
}

@Composable
private fun ConfirmationDialog(
    confirmation: LayerConfirmation?,
    layerCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (LayerConfirmation) -> Unit,
) {
    if (confirmation == null) return
    val flatten = confirmation == LayerConfirmation.Flatten
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (flatten) stringResource(R.string.layer_flatten_title, layerCount)
                else stringResource(R.string.layer_merge_title),
            )
        },
        text = {
            Text(
                stringResource(
                    if (flatten) R.string.layer_flatten_body else R.string.layer_merge_body,
                ),
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(confirmation) }) {
                Text(stringResource(R.string.layer_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.layer_cancel)) }
        },
    )
}

@Composable
private fun refusalMessage(refusal: Refusal?, canvas: CanvasSize, layerCap: Int): String? = when (refusal) {
    Refusal.AT_CAP -> stringResource(R.string.layer_limit, canvas.width, canvas.height, layerCap)
    Refusal.LAST_LAYER -> stringResource(R.string.layer_only)
    Refusal.LOCKED -> stringResource(R.string.layer_locked)
    Refusal.HIDDEN_PARTNER -> stringResource(R.string.layer_hidden_partner)
    Refusal.NO_LAYER_BELOW -> stringResource(R.string.layer_no_below)
    Refusal.NOOP -> stringResource(R.string.layer_no_change)
    null -> null
}

private data class RenameRequest(val index: Int, val name: String)

private sealed interface LayerConfirmation {
    data object Flatten : LayerConfirmation
    data class Merge(val index: Int) : LayerConfirmation
}

private data class PaperChoice(val color: Color, val label: Int)

private val HEADER_HEIGHT = 56.dp
private val ROW_HEIGHT = 64.dp
private val ROW_ACTION = 40.dp
private val THUMB_SIZE = 40.dp
private val PAPER_SWATCH = 32.dp
private val SELECTION_BAR = 4.dp
private val OPACITY_VALUE_WIDTH = 40.dp
private val OPACITY_SLIDER_LENGTH = 80.dp
private const val CHECKER_CELLS = 4
private const val PERCENT = 100f
private const val HIDDEN_ALPHA = 0.48f
private const val HINT_MS = 1_800L
