@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BlurOn
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.Gesture
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selectableGroup
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import ch.lkmc.bangnidraw.engine.core.AppTheme
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.BrushToolGlyphPolicy
import ch.lkmc.bangnidraw.engine.core.EraserTogglePolicy
import ch.lkmc.bangnidraw.engine.core.LayoutSpec
import ch.lkmc.bangnidraw.engine.core.PaintSlotAssignments
import ch.lkmc.bangnidraw.engine.core.RailMode
import ch.lkmc.bangnidraw.engine.core.RailSlotPolicy
import ch.lkmc.bangnidraw.engine.core.ToolButtonEmphasis
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ToolRailColorPolicy
import ch.lkmc.bangnidraw.engine.core.ToolSliderPreset
import ch.lkmc.bangnidraw.engine.core.ToolSliderSecondary
import ch.lkmc.bangnidraw.ui.glyphs.brushGlyphIcon
import kotlin.math.roundToInt

/**
 * The desktop tool rail: the same object `:app`'s `ToolRail` draws — paint
 * slots, a rule, the eraser, and the size/secondary sliders standing in its
 * foot (`docs/plan/08-ui-and-layout.md` §3.2). Assignments, the visible
 * budget and the button emphasis colours come from shared engine-core
 * policies, and the surface/divider colours read the same Material roles
 * `:app`'s rail does, so the two behave alike.
 *
 * The structural difference is what this shell cannot run: Android's five
 * secondary tools (smudge, water, blur, fill, eyedropper) have no desktop
 * engine path, and Android overflows its extra presets into the settings
 * sheet — so the paints that do not fit go into a menu on the rail itself
 * rather than off it.
 */
@Composable
internal fun DesktopToolRail(
    layout: LayoutSpec,
    presets: List<BrushPreset>,
    paintSlots: PaintSlotAssignments,
    rail: DesktopRailState,
    /** What the sliders tune: the selected brush, or the selected tool. */
    tool: ToolKind?,
    windowWidth: Dp,
    windowHeight: Dp,
    onPaintSlot: (Int) -> Unit,
    onAssignPaint: (BrushPreset) -> Unit,
    onEraserTap: () -> Unit,
    onSecondaryTool: (DesktopSecondaryTool) -> Unit,
    /** The slot that is already selected opens its settings, as Android's does. */
    onSettings: () -> Unit,
    onSizeChanged: (Float) -> Unit,
    onSecondaryChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val docked = layout.railMode == RailMode.DOCK
    // A dock runs along the window's width; every other rail runs down what
    // the top strip leaves of its height.
    val railExtent = if (docked) windowWidth else windowHeight - LayoutSpec.TOP_STRIP_DP.dp
    val paintsById = DesktopRailPolicy.paints(presets).associateBy(BrushPreset::id)
    val budget = DesktopRailPolicy.paintBudget(
        layout = layout,
        availableDp = railExtent.value.roundToInt(),
        // The rail lays out the assignments, so they are what must fit.
        paintCount = paintSlots.presetIds.size,
    )
    val visible = RailSlotPolicy.visibleIndices(paintSlots, budget)
    val hidden = paintSlots.presetIds
        .filterIndexed { index, _ -> index !in visible }
        .mapNotNull(paintsById::get)
    // The slot the last eraser toggle left in place, never a preset this
    // catalogue does not ship.
    val eraser = presets.firstOrNull { it.id == rail.eraserId && it.eraseMode }
        ?: DesktopRailPolicy.eraserOrNull(presets)
    // What the foot sliders tune, from the shared policy: a brush preset, an
    // RMW tool's synthesized preset, or nothing at all for fill and the
    // eyedropper, which have no size.
    val active = tool?.let(ToolSliderPreset::forKind)

    val paintSlotButtons = buildList {
        for (index in visible) {
            val paint = paintsById[paintSlots.presetIds[index]] ?: continue
            add(
                DesktopToolSlot(
                    icon = brushGlyphIcon(BrushToolGlyphPolicy.forPreset(paint)),
                    description = DesktopBrushUi.label(paint),
                    active = rail.brushSelected() && rail.selectedId == paint.id,
                    // `:app` opens the settings sheet from the slot that is
                    // already active; the same second click does it here.
                    onClick = {
                        if (rail.brushSelected() && rail.selectedId == paint.id) {
                            onSettings()
                        } else {
                            onPaintSlot(index)
                        }
                    },
                ),
            )
        }
        if (hidden.isNotEmpty()) {
            add(
                DesktopToolSlot(
                    icon = Icons.Filled.MoreHoriz,
                    description = DesktopStrings.get("desktop_more_brushes"),
                    active = rail.brushSelected() && hidden.any { it.id == rail.selectedId },
                    onClick = {},
                    menuItems = hidden.map { paint ->
                        DesktopToolMenuItem(
                            icon = brushGlyphIcon(BrushToolGlyphPolicy.forPreset(paint)),
                            description = DesktopBrushUi.label(paint),
                            active = rail.brushSelected() && rail.selectedId == paint.id,
                            // The same rule the visible slots follow: a
                            // second click on the selected one opens its
                            // settings. Without it a brush behind this menu
                            // has no path to its settings at all.
                            onClick = {
                                if (rail.brushSelected() && rail.selectedId == paint.id) {
                                    onSettings()
                                } else {
                                    onAssignPaint(paint)
                                }
                            },
                        )
                    },
                ),
            )
        }
    }

    val eraserButtons = if (eraser == null) {
        emptyList()
    } else {
        val alternates = EraserTogglePolicy.next(eraser.id, presets) != null
        val selected = rail.brushSelected() && rail.selectedId == eraser.id
        listOf(
            DesktopToolSlot(
                icon = brushGlyphIcon(BrushToolGlyphPolicy.forPreset(eraser)),
                description = DesktopBrushUi.label(eraser) +
                    if (alternates) DesktopStrings.get("desktop_eraser_settings_hint") else "",
                active = selected,
                // One rule for every slot: the first click selects, the second
                // opens settings. The hard/soft choice lives in that panel, so
                // a second click never has to mean two different things.
                onClick = { if (selected) onSettings() else onEraserTap() },
            ),
        )
    }
    val secondaryButtons = secondarySlots(layout, rail, onSecondaryTool, onSettings)
    val tools = paintSlotButtons + eraserButtons + secondaryButtons
    // Mirrors ToolRail.dividersAfter: paints and their overflow are one group,
    // the eraser another, the secondary tools a third. A catalogue missing one
    // of them must not put a rule through the middle of what is left.
    val dividersAfter = buildSet {
        if (paintSlotButtons.isNotEmpty() &&
            (eraserButtons.isNotEmpty() || secondaryButtons.isNotEmpty())
        ) {
            add(paintSlotButtons.lastIndex)
        }
        if (eraserButtons.isNotEmpty() && secondaryButtons.isNotEmpty()) {
            add(paintSlotButtons.size + eraserButtons.lastIndex)
        }
    }
    val slot = layout.toolSlotDp.dp

    if (docked) {
        // A dock runs along the bottom: a tooltip to a button's left would
        // cover its neighbours, and clip off-screen at the window's edge.
        Dock(tools, dividersAfter, slot, modifier)
        return
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = RAIL_ALPHA),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 2.dp,
        modifier = modifier.width(layout.railWidthDp.dp).heightIn(max = railExtent),
    ) {
        val verticalPadding = if (layout.railMode == RailMode.SHORT) 0.dp else RAIL_PADDING
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(
                horizontal = RAIL_HORIZONTAL_PADDING,
                vertical = verticalPadding,
            ),
        ) {
            val gap = if (layout.railMode == RailMode.SHORT) 0.dp else TOOL_GAP
            // The budget above is what keeps the column inside the window;
            // this scroll is the guard for the sizes it cannot foresee (a
            // catalogue grown past the fit, a window shorter than one slot).
            // It wraps the tools alone, so the tuning a stroke needs never
            // scrolls out of reach with them.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState())
                    .semantics { selectableGroup() },
            ) {
                for ((index, item) in tools.withIndex()) {
                    ToolButton(item, slot)
                    if (index == tools.lastIndex) continue

                    if (gap > 0.dp) Spacer(Modifier.height(gap))
                    if (index in dividersAfter) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(vertical = DIVIDER_MARGIN),
                        )
                    }
                }
            }

            if (layout.sliderLengthDp > 0 && tool != null && active != null) {
                Row {
                    ToolSliders(
                        kind = tool,
                        axis = DesktopSliderAxis.Vertical,
                        length = layout.sliderLengthDp.dp,
                        onSizeChanged = onSizeChanged,
                        onSecondaryChanged = onSecondaryChanged,
                    )
                }
            }
        }
    }
}

/** SHORT and DOCK rails cannot hold the sliders; they get a ledge instead. */
@Composable
internal fun DesktopSliderLedge(
    layout: LayoutSpec,
    kind: ToolKind,
    onSizeChanged: (Float) -> Unit,
    onSecondaryChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (layout.sliderLengthDp > 0) return
    // Fill and the eyedropper have no size and no secondary: `forKind` says
    // so, and the ledge draws nothing rather than two dead tracks.
    if (ToolSliderPreset.forKind(kind) == null) return

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = LEDGE_ALPHA),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.height(LEDGE_HEIGHT),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(LEDGE_PADDING),
            modifier = Modifier.padding(horizontal = LEDGE_PADDING),
        ) {
            ToolSliders(
                kind = kind,
                axis = DesktopSliderAxis.Horizontal,
                length = LEDGE_SLIDER_LENGTH,
                onSizeChanged = onSizeChanged,
                onSecondaryChanged = onSecondaryChanged,
            )
        }
    }
}

/**
 * The size and secondary pair, in the rail's foot or on the ledge. One
 * implementation for both: the mapping between the slider's 0..1 and the
 * preset's own range is the part that must not drift between the two
 * surfaces, and it lives here once.
 */
@Composable
private fun ToolSliders(
    kind: ToolKind,
    axis: DesktopSliderAxis,
    length: Dp,
    onSizeChanged: (Float) -> Unit,
    onSecondaryChanged: (Float) -> Unit,
) {
    // The shared policy decides both halves. An RMW tool's synthesized preset
    // carries its size range, but its *secondary* is a domain field the
    // preset cannot always hold — Water's load is not an opacity — so the two
    // values are read from the kind, never from the preset alone.
    val preset = ToolSliderPreset.forKind(kind) ?: return
    val secondary = ToolSliderPreset.secondaryValue(kind) ?: return
    // Identity with the preset's own bounds — `DesktopBrushUiTest` pins that
    // for every shipped preset, so routing both surfaces through it is
    // deduplication, not a change to where the slider's 0..1 lands.
    val range = DesktopBrushUi.sizeRange(preset)
    DesktopThinSlider(
        value = BrushSizeScale.fraction(preset.size, range.start, range.endInclusive),
        range = 0f..1f,
        axis = axis,
        description = sizeDescription(preset),
        onValueChange = {
            onSizeChanged(BrushSizeScale.size(it, range.start, range.endInclusive))
        },
        length = length,
    )
    DesktopThinSlider(
        value = secondary,
        range = 0f..1f,
        axis = axis,
        description = secondaryDescription(kind),
        onValueChange = onSecondaryChanged,
        length = length,
    )
}

@Composable
private fun Dock(
    tools: List<DesktopToolSlot>,
    dividersAfter: Set<Int>,
    slot: Dp,
    modifier: Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = RAIL_ALPHA),
        // Top corners only, exactly as the Android dock: rounding just the
        // edge that meets the canvas keeps it flush with the window bottom.
        shape = MaterialTheme.shapes.large.copy(
            bottomStart = CornerSize(0.dp),
            bottomEnd = CornerSize(0.dp),
        ),
        tonalElevation = 2.dp,
        modifier = modifier.fillMaxWidth().height(DOCK_HEIGHT),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .semantics { selectableGroup() },
        ) {
            for ((index, item) in tools.withIndex()) {
                ToolButton(item, slot, tooltipAnchor = TooltipAnchorPosition.Above)
                if (index in dividersAfter) {
                    VerticalDivider(
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .height(slot - DIVIDER_INSET)
                            .padding(horizontal = DIVIDER_MARGIN),
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolButton(
    item: DesktopToolSlot,
    slot: Dp,
    tooltipAnchor: TooltipAnchorPosition = TooltipAnchorPosition.Left,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.medium
    val emphasis = if (item.active) ToolButtonEmphasis.ACTIVE else ToolButtonEmphasis.INACTIVE
    val colors = ToolRailColorPolicy.colors(DESKTOP_THEME, emphasis)
    val container = Color(colors.containerArgb)
    val iconColor = Color(colors.iconArgb)
    val visual = minOf(TOOL_VISUAL, slot - TOOL_VISUAL_INSET)
    val border = if (item.active) {
        Modifier.border(ACTIVE_BORDER, MaterialTheme.colorScheme.primary, shape)
    } else {
        Modifier
    }
    Box(modifier = Modifier.size(slot), contentAlignment = Alignment.Center) {
        Surface(color = container, shape = shape, modifier = border.size(visual)) {}
        // The rail replaced a column of labelled buttons, so every glyph
        // still has to say its own name somewhere a pointer can find it.
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(tooltipAnchor),
            tooltip = { PlainTooltip { Text(item.description) } },
            state = rememberTooltipState(),
        ) {
            IconButton(
                onClick = {
                    if (item.menuItems.isEmpty()) item.onClick() else menuExpanded = true
                },
                colors = IconButtonDefaults.iconButtonColors(contentColor = iconColor),
                modifier = Modifier
                    .size(slot)
                    .semantics {
                        role = Role.Button
                        selected = item.active
                        if (item.active) stateDescription = SELECTED_STATE
                    },
            ) {
                Icon(item.icon, contentDescription = item.description, tint = iconColor)
            }
        }
        if (item.menuItems.isNotEmpty()) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
                modifier = Modifier.semantics { selectableGroup() },
            ) {
                for (entry in item.menuItems) {
                    DropdownMenuItem(
                        text = { Text(entry.description) },
                        onClick = {
                            menuExpanded = false
                            entry.onClick()
                        },
                        leadingIcon = { Icon(entry.icon, contentDescription = null) },
                        modifier = Modifier.semantics {
                            role = Role.RadioButton
                            selected = entry.active
                        },
                    )
                }
            }
        }
    }
}

/**
 * Never actually null for a brush — `ToolSliderPreset.secondaryValue` only
 * returns null for Fill and Eyedropper, which this shell has no path for —
 * but the type is nullable, and opacity is the right reading of the
 * OPACITY secondary this rail's presets all use.
 */
/**
 * The five secondary tools, laid out as `:app`'s rail lays them out: all
 * five get a slot in FULL, and every shorter mode shows smudge, water and
 * fill with blur and the eyedropper behind a menu.
 */
private fun secondarySlots(
    layout: LayoutSpec,
    rail: DesktopRailState,
    onSelect: (DesktopSecondaryTool) -> Unit,
    onSettings: () -> Unit,
): List<DesktopToolSlot> {
    fun slot(tool: DesktopSecondaryTool) = DesktopToolSlot(
        icon = secondaryIcon(tool),
        description = secondaryLabel(tool),
        active = rail.secondary == tool,
        // Same rule as every other slot: click to select, click again for
        // that tool's settings.
        onClick = { if (rail.secondary == tool) onSettings() else onSelect(tool) },
    )

    val visible = DesktopRailPolicy.visibleSecondary(layout).map(::slot)
    val hidden = DesktopRailPolicy.hiddenSecondary(layout)
    if (hidden.isEmpty()) return visible

    return visible + DesktopToolSlot(
        icon = Icons.Filled.MoreHoriz,
        description = DesktopStrings.get("tool_more"),
        active = hidden.any { it == rail.secondary },
        onClick = {},
        menuItems = hidden.map { tool ->
            DesktopToolMenuItem(
                icon = secondaryIcon(tool),
                description = secondaryLabel(tool),
                active = rail.secondary == tool,
                onClick = { if (rail.secondary == tool) onSettings() else onSelect(tool) },
            )
        },
    )
}

/**
 * Material's own glyphs, the same five `:app`'s rail uses. These are not
 * among the repo-owned silhouettes: `Gesture`, `WaterDrop`, `BlurOn`,
 * `FormatColorFill` and `Colorize` all depict the tool rather than an
 * unrelated action, which is why the drawing tools needed their own and
 * these did not.
 */
private fun secondaryIcon(tool: DesktopSecondaryTool): ImageVector = when (tool) {
    DesktopSecondaryTool.SMUDGE -> Icons.Filled.Gesture
    DesktopSecondaryTool.WATER -> Icons.Filled.WaterDrop
    DesktopSecondaryTool.BLUR -> Icons.Filled.BlurOn
    DesktopSecondaryTool.FILL -> Icons.Filled.FormatColorFill
    DesktopSecondaryTool.EYEDROPPER -> Icons.Filled.Colorize
}

internal fun secondaryLabel(tool: DesktopSecondaryTool): String = DesktopStrings.get(
    when (tool) {
        DesktopSecondaryTool.SMUDGE -> "tool_smudge"
        DesktopSecondaryTool.WATER -> "tool_water"
        DesktopSecondaryTool.BLUR -> "tool_blur"
        DesktopSecondaryTool.FILL -> "tool_fill"
        DesktopSecondaryTool.EYEDROPPER -> "tool_eyedropper"
    },
)

private fun sizeDescription(preset: BrushPreset): String {
    val range = DesktopBrushUi.sizeRange(preset)
    return DesktopStrings.get(
        "desktop_brush_size_value",
        DesktopStrings.get("brush_size"),
        preset.size.toInt(),
        range.start.toInt(),
        range.endInclusive.toInt(),
    )
}

private fun secondaryDescription(kind: ToolKind): String = DesktopStrings.get(
    when (ToolSliderPreset.secondaryFor(kind)) {
        ToolSliderSecondary.FLOW -> "brush_flow"
        ToolSliderSecondary.WATER -> "water_amount"
        ToolSliderSecondary.OPACITY -> "brush_opacity"
    },
)

private data class DesktopToolSlot(
    val icon: ImageVector,
    val description: String,
    val active: Boolean,
    val onClick: () -> Unit,
    val menuItems: List<DesktopToolMenuItem> = emptyList(),
)

private data class DesktopToolMenuItem(
    val icon: ImageVector,
    val description: String,
    val active: Boolean,
    val onClick: () -> Unit,
)

/** The shell wears the Android app's default palette; see [AppTheme]. */
internal val DESKTOP_THEME = AppTheme.SAFFRON

private const val SELECTED_STATE = "Selected"
private val TOOL_VISUAL = 40.dp
private val TOOL_VISUAL_INSET = 8.dp
private val TOOL_GAP = DesktopRailGeometry.TOOL_GAP_DP.dp
// Half of the budget's padding allowance, which counts both ends.
private val RAIL_PADDING = (DesktopRailGeometry.RAIL_PADDING_DP / 2).dp
private val RAIL_HORIZONTAL_PADDING = 4.dp
private val DOCK_HEIGHT = LayoutSpec.DOCK_HEIGHT_DP.dp
private val ACTIVE_BORDER = 2.dp
private val DIVIDER_MARGIN = 4.dp
private val DIVIDER_INSET = 16.dp
private val LEDGE_HEIGHT = 48.dp
private val LEDGE_PADDING = 8.dp
private val LEDGE_SLIDER_LENGTH = 140.dp
private const val RAIL_ALPHA = 0.92f
private const val LEDGE_ALPHA = 0.94f
