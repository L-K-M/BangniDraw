package ch.lkmc.bangnidraw.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.lkmc.bangnidraw.engine.core.BlurParams
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.ColorMixer
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.SmudgeParams
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.WaterParams
import ch.lkmc.bangnidraw.engine.core.HsvSelection
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerPanelOrder
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.LayerThumbnail
import ch.lkmc.bangnidraw.engine.core.PaintSlotAssignments
import ch.lkmc.bangnidraw.engine.core.IdSource
import ch.lkmc.bangnidraw.engine.core.Refusal
import ch.lkmc.bangnidraw.engine.core.StackResult
import ch.lkmc.bangnidraw.engine.core.ToolSliderPreset

/**
 * One document's shell state, shared by every window that shows it.
 *
 * The layer and brush panels are windows of their own (the user asked for
 * panels they can move independently of the document), so this state cannot
 * live inside the canvas window's composable: a sibling `Window` would not
 * see it. Hoisting it here is also what a second document window will need.
 */
@Stable
internal class DesktopShellState(
    val engine: DesktopEngine,
    val catalogue: List<BrushPreset>,
    val mixer: ColorMixer,
    val prefs: DesktopPrefs,
) {
    /** Slider edits stay with the preset they were made on; the catalogue is the base copy. */
    var presets by mutableStateOf(catalogue)
    var rail by mutableStateOf(DesktopRailPolicy.initial(catalogue))

    /**
     * The durable paint assignments the Android rail keeps: choosing a preset
     * from the overflow swaps it into the active slot rather than duplicating
     * it, so every paint stays reachable exactly once.
     */
    var paintSlots by mutableStateOf(
        PaintSlotAssignments.restore(DesktopRailPolicy.paints(catalogue).map(BrushPreset::id)),
    )
    var colorSelection by mutableStateOf(HsvSelection.fromArgb(DesktopPalette.SWATCHES.first()))

    // Each secondary tool keeps its own tuning across a switch away and back,
    // exactly as a brush preset keeps its slider edits.
    var smudgeParams by mutableStateOf(SmudgeParams())
    var waterParams by mutableStateOf(WaterParams())
    var blurParams by mutableStateOf(BlurParams())
    var fillParams by mutableStateOf(FillParams())
    var eyedropperParams by mutableStateOf(EyedropperParams())

    var stack by mutableStateOf(engine.stack)
    var paperColor by mutableStateOf(DEFAULT_PAPER_ARGB)
    var thumbnails by mutableStateOf<Map<LayerId, LayerThumbnail>>(emptyMap())
    var refusal by mutableStateOf<DesktopRefusal?>(null)

    var savedMessage by mutableStateOf<String?>(null)
    var preferencesReady by mutableStateOf(false)
    var showColorPanel by mutableStateOf(false)
    var showLayerPanel by mutableStateOf(false)
    var showBrushPanel by mutableStateOf(false)
    var showHelp by mutableStateOf(false)

    val restoreGate = DesktopPreferenceRestoreGate()

    private var refusalRevision = 0L

    /**
     * The preset the rail highlights and every slider surface tunes. One
     * derivation, so the rail's foot and the ledge cannot end up tuning
     * different brushes.
     */
    val activeBrush: BrushPreset? get() = DesktopRailPolicy.activePreset(presets, rail)

    /**
     * The tool the next gesture uses. Null only when the catalogue ships no
     * preset at all, which the shell treats as "input is not ready".
     */
    val activeTool: ToolKind?
        get() = when (rail.secondary) {
            null -> activeBrush?.let(ToolKind::Brush)
            DesktopSecondaryTool.SMUDGE -> ToolKind.Smudge(smudgeParams)
            DesktopSecondaryTool.WATER -> ToolKind.Water(waterParams)
            DesktopSecondaryTool.BLUR -> ToolKind.Blur(blurParams)
            DesktopSecondaryTool.FILL -> ToolKind.Fill(fillParams)
            DesktopSecondaryTool.EYEDROPPER -> ToolKind.Eyedropper(eyedropperParams)
        }

    // ------------------------------------------------------------- brushes

    fun tune(tuned: BrushPreset) {
        presets = presets.map { if (it.id == tuned.id) tuned else it }
    }

    /** The rail's size slider, routed to the active tool's own size field. */
    fun tuneActiveSize(value: Float) {
        activeTool?.let { store(DesktopToolTuning.withSize(it, value)) }
    }

    /** The rail's second slider: opacity, flow, strength or water load. */
    fun tuneActiveSecondary(value: Float) {
        activeTool?.let { store(DesktopToolTuning.withSecondary(it, value)) }
    }

    /** Writes a tuned tool back where [activeTool] reads it from. */
    private fun store(next: ToolKind) {
        when (next) {
            is ToolKind.Brush -> tune(next.preset)
            is ToolKind.Smudge -> smudgeParams = next.params
            is ToolKind.Water -> waterParams = next.params
            is ToolKind.Blur -> blurParams = next.params
            is ToolKind.Fill -> fillParams = next.params
            is ToolKind.Eyedropper -> eyedropperParams = next.params
        }
    }

    fun selectSecondary(tool: DesktopSecondaryTool) {
        rail = DesktopRailPolicy.selectSecondary(rail, tool)
    }

    /**
     * Deliberately not [DesktopRailPolicy.activePreset]: that resolves what
     * the sliders tune, falling back to some other preset when a stored id has
     * gone. Persistence must name the brush the user actually picked, or a
     * stale selection would rewrite itself into a brush they never chose.
     */
    fun persistBrush(id: String) {
        presets.firstOrNull { it.id == id }?.let(prefs::writeBrush)
    }

    fun selectPreset(id: String) {
        restoreGate.markChanged(DesktopPreferenceKind.Brush)
        rail = DesktopRailPolicy.select(rail, id, presets)
        persistBrush(id)
    }

    fun eraserTap() {
        restoreGate.markChanged(DesktopPreferenceKind.Brush)
        rail = DesktopRailPolicy.eraserTap(rail, presets)
        persistBrush(rail.selectedId)
    }

    // -------------------------------------------------------------- colour

    fun pickColor(selection: HsvSelection) {
        restoreGate.markChanged(DesktopPreferenceKind.Color)
        colorSelection = selection
        prefs.writeColor(selection.argb)
    }

    fun pickSwatch(argb: Int) = pickColor(colorSelection.commit(argb))

    // -------------------------------------------------------------- layers

    /**
     * Every layer action, bound to this document. The panel calls these; the
     * engine evaluates each one against the live model on the GL thread, so a
     * stale index from the panel is refused rather than applied to the wrong
     * layer.
     */
    val layerActions = DesktopLayerActions(
        select = { index -> engine.selectLayer(index) },
        add = { edit { stack, ids -> stack.add(ids, layerCap) } },
        duplicate = { index -> edit { stack, ids -> stack.duplicate(index, ids, layerCap) } },
        delete = { index -> edit { stack, _ -> stack.delete(index) } },
        clear = { index -> edit { stack, _ -> stack.clear(index) } },
        mergeDown = { index -> edit { stack, _ -> stack.mergeDown(index) } },
        flatten = { edit { stack, ids -> stack.flatten(ids) } },
        move = { index, action ->
            edit { stack, _ ->
                val move = LayerPanelOrder.move(index, action, stack.size)
                    ?: return@edit StackResult.Refused(Refusal.NOOP)
                stack.move(move.from, move.to)
            }
        },
        rename = { index, name -> edit { stack, _ -> stack.rename(index, name) } },
        setOpacity = { index, value -> edit { stack, _ -> stack.setOpacity(index, value) } },
        setVisible = { index, value -> edit { stack, _ -> stack.setVisible(index, value) } },
        setBlendMode = { index, mode -> edit { stack, _ -> stack.setBlendMode(index, mode) } },
        setAlphaLock = { index, value -> edit { stack, _ -> stack.setAlphaLock(index, value) } },
        setLocked = { index, value -> edit { stack, _ -> stack.setLocked(index, value) } },
        setPaperColor = { argb -> engine.setPaperColor(argb) },
    )

    /** How many layers this canvas's memory budget allows. */
    val layerCap: Int get() = engine.layerCap

    private inline fun edit(crossinline operation: (LayerStack, IdSource) -> StackResult) {
        engine.editStack(
            onResult = { reason -> if (reason != null) reportRefusal(reason) },
        ) { stack, ids -> operation(stack, ids) }
    }

    /** Called off the UI thread by the engine; marshalled by the caller. */
    fun reportRefusal(reason: Refusal) {
        java.awt.EventQueue.invokeLater {
            refusalRevision += 1
            refusal = DesktopRefusal(reason, refusalRevision)
        }
    }

    fun publishStack(next: LayerStack) {
        java.awt.EventQueue.invokeLater { stack = next }
    }

    fun publishPaper(argb: Int) {
        java.awt.EventQueue.invokeLater { paperColor = argb }
    }

    fun publishThumbnail(layer: LayerId, thumbnail: LayerThumbnail?) {
        java.awt.EventQueue.invokeLater {
            thumbnails = if (thumbnail == null) {
                thumbnails - layer
            } else {
                thumbnails + (layer to thumbnail)
            }
        }
    }

    private companion object {
        const val DEFAULT_PAPER_ARGB = 0xFFFFFFFF.toInt()
    }
}
