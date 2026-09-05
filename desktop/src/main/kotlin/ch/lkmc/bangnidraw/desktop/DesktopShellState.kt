package ch.lkmc.bangnidraw.desktop

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import ch.lkmc.bangnidraw.engine.core.AppTheme
import ch.lkmc.bangnidraw.engine.core.BlurParams
import ch.lkmc.bangnidraw.engine.core.ColorMixerResolver
import ch.lkmc.bangnidraw.engine.core.Hand
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.PigmentAvailability
import ch.lkmc.bangnidraw.engine.core.BrushPreset
import ch.lkmc.bangnidraw.engine.core.BrushPresets
import ch.lkmc.bangnidraw.engine.core.BrushSizeScale
import ch.lkmc.bangnidraw.engine.core.CanvasChromeState
import ch.lkmc.bangnidraw.engine.core.CanvasShortcut
import ch.lkmc.bangnidraw.engine.core.CanvasUiPolicy
import ch.lkmc.bangnidraw.engine.core.ColorMixer
import ch.lkmc.bangnidraw.engine.core.CompositionGuideVisibility
import ch.lkmc.bangnidraw.engine.core.EyedropperParams
import ch.lkmc.bangnidraw.engine.core.FillParams
import ch.lkmc.bangnidraw.engine.core.FocusMode
import ch.lkmc.bangnidraw.engine.core.HsvSelection
import ch.lkmc.bangnidraw.engine.core.IdSource
import ch.lkmc.bangnidraw.engine.core.LayerId
import ch.lkmc.bangnidraw.engine.core.LayerPanelOrder
import ch.lkmc.bangnidraw.engine.core.LayerStack
import ch.lkmc.bangnidraw.engine.core.LayerThumbnail
import ch.lkmc.bangnidraw.engine.core.DishState
import ch.lkmc.bangnidraw.engine.core.DishWell
import ch.lkmc.bangnidraw.engine.core.PaintSlotAssignments
import ch.lkmc.bangnidraw.engine.core.Palette
import ch.lkmc.bangnidraw.engine.core.PaletteCatalog
import ch.lkmc.bangnidraw.engine.core.PalettePolicy
import ch.lkmc.bangnidraw.engine.core.StoredColors
import ch.lkmc.bangnidraw.engine.core.ReferenceImportDecision
import ch.lkmc.bangnidraw.engine.core.ReferenceLayerReserve
import ch.lkmc.bangnidraw.engine.core.ReferenceTransform
import ch.lkmc.bangnidraw.engine.core.ReferenceVisibility
import ch.lkmc.bangnidraw.engine.core.TracingReference
import ch.lkmc.bangnidraw.engine.core.TracingReferencePolicy
import ch.lkmc.bangnidraw.engine.core.Refusal
import ch.lkmc.bangnidraw.engine.core.SizeAdjustment
import ch.lkmc.bangnidraw.engine.core.SmudgeParams
import ch.lkmc.bangnidraw.engine.core.StackResult
import ch.lkmc.bangnidraw.engine.core.ToolKind
import ch.lkmc.bangnidraw.engine.core.ToolSliderPreset
import ch.lkmc.bangnidraw.engine.core.ViewTransform
import ch.lkmc.bangnidraw.engine.core.WaterParams

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
    var showSettings by mutableStateOf(false)

    // ----------------------------------------------------------- settings

    /**
     * The persisted choices. They are session state until the first read
     * resolves, and [restoreGate] keeps that read from overwriting a choice
     * the user made while it was in flight — the same rule the brush and the
     * colour already follow.
     */
    var theme by mutableStateOf(AppTheme.SAFFRON)
        private set
    var hand by mutableStateOf(Hand.RIGHT)
        private set
    var mixerChoice by mutableStateOf(
        if (mixer.isPigment) MixerChoice.PIGMENT else MixerChoice.RGB,
    )
        private set
    var snapRightAngles by mutableStateOf(true)
        private set

    /** A build with no Mixbox has no pigment mixer to switch to. */
    val pigmentAvailable: Boolean = mixer.isPigment

    val mixboxAttribution: MixboxAttribution =
        if (mixer.isPigment) MixboxAttribution.Included else MixboxAttribution.Excluded

    /** What a stroke actually mixes with, once the choice is applied. */
    val activeMixer: ColorMixer
        get() = ColorMixerResolver.resolve(mixerChoice, mixer)

    fun chooseTheme(value: AppTheme) {
        restoreGate.markChanged(DesktopPreferenceKind.Settings)
        theme = value
        prefs.writeText(DesktopPreferenceKeys.THEME, value.name)
    }

    fun chooseHand(value: Hand) {
        restoreGate.markChanged(DesktopPreferenceKind.Settings)
        hand = value
        prefs.writeText(DesktopPreferenceKeys.HAND, value.name)
    }

    fun chooseMixer(value: MixerChoice) {
        restoreGate.markChanged(DesktopPreferenceKind.Settings)
        mixerChoice = value
        prefs.writeText(DesktopPreferenceKeys.MIXER, value.name)
    }

    fun chooseSnapRightAngles(value: Boolean) {
        restoreGate.markChanged(DesktopPreferenceKind.Settings)
        snapRightAngles = value
        prefs.writeNumber(DesktopPreferenceKeys.SNAP_RIGHT_ANGLES, if (value) 1 else 0)
    }

    /**
     * Applies what the store held. Enum names are the stored values, so a
     * name this build does not know keeps the default rather than throwing —
     * the same tolerance `AppTheme`'s own note asks for.
     */
    fun restoreSettings(
        themeName: String?,
        handName: String?,
        mixerName: String?,
        snap: Int?,
    ) {
        if (!restoreGate.allows(DesktopPreferenceKind.Settings)) return

        AppTheme.entries.firstOrNull { it.name == themeName }?.let { theme = it }
        Hand.entries.firstOrNull { it.name == handName }?.let { hand = it }
        mixerChoice = MixerChoice.fromStored(
            mixerName,
            if (pigmentAvailable) PigmentAvailability.AVAILABLE else PigmentAvailability.ABSENT,
        )
        snap?.let { snapRightAngles = it != 0 }
    }

    /**
     * Focus mode hides the chrome so the canvas is unobstructed (Tab).
     *
     * The transitions come from the shared [CanvasUiPolicy], but its
     * `openPanel` is deliberately *not* used: it models one panel at a time,
     * and on desktop the panels are independent windows the user asked to be
     * able to arrange side by side.
     */
    var chrome by mutableStateOf(CanvasChromeState())

    var guides by mutableStateOf(CompositionGuideVisibility.HIDDEN)

    /**
     * The pan/zoom/rotate the canvas is showing. Mirrored into snapshot state
     * because `CanvasTouchHandler.view` is a plain field on a single-writer
     * struct: the reset pill has to recompose when it moves, and reading the
     * handler directly makes that depend on whatever else recomposed.
     */
    var view by mutableStateOf(ViewTransform())

    /**
     * Bumped on every hover move. The cursor position lives on a
     * single-writer struct rather than snapshot state, so the overlay needs a
     * key to invalidate its draw node.
     */
    var hoverRevision by androidx.compose.runtime.mutableIntStateOf(0)

    /** Set by the shell, which owns the touch handler the view lives on. */
    var resetView: (() -> Unit)? = null

    val focused: Boolean get() = chrome.focusMode == FocusMode.FOCUSED

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
        // Choosing any other tool abandons an armed panel pick. The
        // eyedropper is exempt because arming one selects it.
        if (tool != DesktopSecondaryTool.EYEDROPPER) cancelPendingPick()
        rail = DesktopRailPolicy.selectSecondary(rail, tool)
    }

    // ---------------------------------------------------------- shortcuts

    /** What the tool was before Alt borrowed the eyedropper. */
    private var borrowedFrom: DesktopSecondaryTool? = null
    private var borrowing = false

    /**
     * Runs one entry of the shared keyboard table. Unhandled actions are a
     * no-op rather than an error: the table is the same one Android's
     * Settings sheet prints, and a chord this shell has no surface for must
     * still be listed there.
     */
    fun run(shortcut: CanvasShortcut) {
        when (shortcut) {
            CanvasShortcut.UNDO -> engine.undo()
            CanvasShortcut.REDO -> engine.redo()
            CanvasShortcut.SIZE_DOWN -> adjustSize(SizeAdjustment.DECREASE)
            CanvasShortcut.SIZE_UP -> adjustSize(SizeAdjustment.INCREASE)
            CanvasShortcut.BRUSH -> selectPreset(lastPaintId())
            CanvasShortcut.ERASER -> eraserTap()
            CanvasShortcut.SMUDGE -> selectSecondary(DesktopSecondaryTool.SMUDGE)
            CanvasShortcut.WATER -> selectSecondary(DesktopSecondaryTool.WATER)
            CanvasShortcut.FILL -> selectSecondary(DesktopSecondaryTool.FILL)
            CanvasShortcut.EYEDROPPER -> selectSecondary(DesktopSecondaryTool.EYEDROPPER)
            CanvasShortcut.BEGIN_EYEDROPPER -> borrowEyedropper()
            CanvasShortcut.END_EYEDROPPER -> returnEyedropper()
            CanvasShortcut.RESET_VIEW -> resetView?.invoke()
            CanvasShortcut.TOGGLE_FOCUS -> chrome = if (focused) {
                CanvasUiPolicy.exitFocus(chrome)
            } else {
                CanvasUiPolicy.enterFocus(chrome)
            }
            CanvasShortcut.TOGGLE_LAYERS -> showLayerPanel = !showLayerPanel
            CanvasShortcut.TOGGLE_COLOR -> showColorPanel = !showColorPanel
        }
    }

    /** The paint the rail remembers, or the catalogue's opening brush. */
    private fun lastPaintId(): String =
        rail.selectedId.takeIf { it != rail.eraserId } ?: BrushPresets.INK_PEN_ID

    private fun adjustSize(adjustment: SizeAdjustment) {
        val kind = activeTool ?: return
        val preset = ToolSliderPreset.forKind(kind) ?: return
        tuneActiveSize(
            BrushSizeScale.adjust(preset.size, preset.sizeMin, preset.sizeMax, adjustment),
        )
    }

    /**
     * Alt held is a *temporary* eyedropper: releasing it returns the tool the
     * user was painting with, rather than leaving them on a tool they never
     * chose. Held-key repeat sends more downs, so the first one wins.
     */
    private fun borrowEyedropper() {
        if (borrowing) return

        borrowing = true
        borrowedFrom = rail.secondary
        selectSecondary(DesktopSecondaryTool.EYEDROPPER)
    }

    private fun returnEyedropper() {
        if (!borrowing) return
        // Alt can be released while a panel pick is armed: the borrow was
        // taken by `beginPickInto`, not by the key, and giving it back here
        // would leave `pendingPick` armed on a brush — so the user's next
        // deliberate sample would land in the well instead of the paint.
        // `finishPick` clears the arming first, then returns it.
        if (pendingPick != null) return

        borrowing = false
        val previous = borrowedFrom
        borrowedFrom = null
        if (previous == null) {
            rail = DesktopRailPolicy.select(rail, rail.selectedId, presets)
        } else {
            selectSecondary(previous)
        }
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
        cancelPendingPick()
        rail = DesktopRailPolicy.select(rail, id, presets)
        persistBrush(id)
    }

    fun eraserTap() {
        restoreGate.markChanged(DesktopPreferenceKind.Brush)
        cancelPendingPick()
        rail = DesktopRailPolicy.eraserTap(rail, presets)
        persistBrush(rail.selectedId)
    }

    // -------------------------------------------------------------- colour

    fun pickColor(selection: HsvSelection) {
        restoreGate.markChanged(DesktopPreferenceKind.Color)
        if (selection.argb != colorSelection.argb) previousColor = colorSelection.argb
        colorSelection = selection
        prefs.writeColor(selection.argb)
    }

    fun pickSwatch(argb: Int) = pickColor(colorSelection.commit(argb))

    // ----------------------------------------------------------- palettes

    /** The colour before the current one, for the panel's revert swatch. */
    var previousColor by mutableStateOf(DesktopPalette.SWATCHES.first())
        private set

    var recentColors by mutableStateOf(emptyList<Int>())
        private set

    /**
     * The palettes the user made. `:app` keeps one JSON file per palette
     * under `palettes/`; here they ride in one preference, because the
     * desktop store is already a DataStore and a second file format would be
     * a second thing to sweep, validate and keep atomic.
     */
    var userPalettes by mutableStateOf(listOf(DEFAULT_USER_PALETTE))
        private set

    var activePaletteId by mutableStateOf(PaletteCatalog.PAINTERS_ID)
        private set

    /** Two wells and the point between them the dish is showing. */
    var dish by mutableStateOf(DishState(DEFAULT_WELL_A, DEFAULT_WELL_B))
        private set

    /**
     * Where the *next* eyedropper read lands. Null is the ordinary case: the
     * read becomes the paint colour. A well or a swatch here means the panel
     * armed the eyedropper to fill that slot instead, which is `:app`'s
     * long-press on the same chip.
     */
    var pendingPick by mutableStateOf<DesktopPickTarget?>(null)
        private set

    val palettes: List<Palette>
        get() = listOf(
            PaletteCatalog.Painters,
            PaletteCatalog.Basic,
            PaletteCatalog.recent(recentColors),
        ) + userPalettes

    val activePalette: Palette
        get() = palettes.firstOrNull { it.id == activePaletteId } ?: PaletteCatalog.Painters

    fun selectPalette(id: String) {
        if (palettes.none { it.id == id }) return

        activePaletteId = id
    }

    /** Swaps the paint colour with the one before it, as `:app`'s chip does. */
    fun swapColors() {
        val target = previousColor
        if (target == colorSelection.argb) return

        pickSwatch(target)
    }

    /**
     * Remembers a colour that was actually painted with. `:app` notes this at
     * stroke commit, which is the same rule: a colour glanced at in the picker
     * is not one the palette should offer back.
     */
    fun notePainted(argb: Int) {
        val next = PalettePolicy.noteRecent(recentColors, argb)
        if (next == recentColors) return

        recentColors = next
        restoreGate.markChanged(DesktopPreferenceKind.Color)
        prefs.writeText(DesktopPreferenceKeys.RECENT_COLORS, StoredColors.encode(next))
    }

    /** A new palette starting from what the active one holds, as `:app` does. */
    fun createPalette(name: String) {
        val created = Palette(
            id = newPaletteId(),
            name = PalettePolicy.createdName(name),
            swatches = activePalette.swatches,
        )
        userPalettes = userPalettes + created
        activePaletteId = created.id
        persistPalettes()
    }

    /**
     * Adds to the active palette, forking a built-in one first: the catalogue
     * palettes are immutable, and `:app` answers "add" on one by copying it
     * into a palette of the user's own rather than refusing.
     */
    fun addToPalette(argb: Int) {
        val active = activePalette
        val editable = if (active.builtIn) {
            Palette(
                id = newPaletteId(),
                name = PaletteCatalog.MY_PALETTE_NAME,
                swatches = active.swatches,
            ).also {
                userPalettes = userPalettes + it
                activePaletteId = it.id
            }
        } else {
            active
        }
        replacePalette(PalettePolicy.append(editable, argb or OPAQUE_ALPHA))
    }

    fun replaceSwatch(index: Int, argb: Int) = editUserPalette {
        PalettePolicy.replace(it, index, argb or OPAQUE_ALPHA)
    }

    fun deleteSwatch(index: Int) = editUserPalette { PalettePolicy.remove(it, index) }

    fun moveSwatch(from: Int, to: Int) = editUserPalette { PalettePolicy.move(it, from, to) }

    fun setDishWell(well: DishWell, argb: Int) {
        dish = when (well) {
            DishWell.A -> dish.copy(a = argb)
            DishWell.B -> dish.copy(b = argb)
        }
    }

    fun setDishBlend(t: Float) {
        dish = dish.copy(t = if (t.isNaN()) dish.t else t.coerceIn(0f, 1f))
    }

    /**
     * Arms the eyedropper to fill [target] and borrows the tool for it, so a
     * chip's "pick from canvas" is one gesture rather than two. The borrow is
     * the same one Alt makes, and [finishPick] returns the tool either way —
     * including when the gesture picked nothing.
     */
    fun beginPickInto(target: DesktopPickTarget) {
        pendingPick = target
        borrowEyedropper()
    }

    /** Routes one eyedropper read: to the armed slot, or to the paint colour. */
    fun applyPick(argb: Int) {
        when (val target = pendingPick) {
            null -> pickSwatch(argb)
            is DesktopPickTarget.Well -> setDishWell(target.well, argb)
            is DesktopPickTarget.Swatch -> {
                // The palette may have moved under an armed pick; re-checking
                // the id keeps a stale index off some other palette.
                if (target.paletteId == activePaletteId) replaceSwatch(target.index, argb)
            }
        }
    }

    /** Ends an armed pick at pen-up, whether or not it read anything. */
    fun finishPick() {
        if (pendingPick == null) return

        pendingPick = null
        returnEyedropper()
    }

    /**
     * Abandons an armed pick because the user chose a tool instead of using
     * it. Without this the arming survives: they click "Pick from canvas",
     * change their mind, go back to a brush — and the *next* eyedropper read,
     * minutes later and deliberate, silently lands in a dish well instead of
     * the paint colour.
     *
     * The borrow is dropped rather than returned. Returning it would move the
     * tool, and the user just chose one; dropping it also keeps a later Alt
     * release from taking that choice away. Guarded on an armed pick, so the
     * ordinary Alt-held borrow is untouched.
     */
    private fun cancelPendingPick() {
        if (pendingPick == null) return

        pendingPick = null
        borrowing = false
        borrowedFrom = null
    }

    fun restorePalettes(recent: String?, stored: String?) {
        if (!restoreGate.allows(DesktopPreferenceKind.Color)) return

        recentColors = StoredColors.decode(recent)
        DesktopPaletteCodec.decode(stored)?.let { userPalettes = it }
        if (palettes.none { it.id == activePaletteId }) {
            activePaletteId = PaletteCatalog.PAINTERS_ID
        }
    }

    private inline fun editUserPalette(edit: (Palette) -> Palette) {
        val active = activePalette
        if (active.builtIn) return

        replacePalette(edit(active))
    }

    private fun replacePalette(palette: Palette) {
        userPalettes = PalettePolicy.upsert(userPalettes, palette)
        persistPalettes()
    }

    private fun persistPalettes() {
        restoreGate.markChanged(DesktopPreferenceKind.Color)
        prefs.writeText(DesktopPreferenceKeys.PALETTES, DesktopPaletteCodec.encode(userPalettes))
    }

    private fun newPaletteId(): String = "$USER_PALETTE_PREFIX${java.util.UUID.randomUUID()}"

    // --------------------------------------------------- tracing reference

    /**
     * The tracing image over the paper, or null when there is none.
     *
     * Private project data, exactly as `:app` treats it: it is not a layer, it
     * never reaches an export or a flatten, and it costs one layer of the
     * pool's budget while it is here — which is why [layerCap] shrinks.
     */
    var reference by mutableStateOf<TracingReference?>(null)
        private set

    /**
     * The image's own bytes, kept so a save can write them into the container.
     * Not snapshot state: nothing draws from it, and a byte array in a Compose
     * `State` invites a reader to treat a shared buffer as immutable.
     */
    var referencePng: ByteArray? = null
        private set

    var showReferencePanel by mutableStateOf(false)

    /** What the last reference action refused to do, for the panel to show. */
    var referenceNotice by mutableStateOf<String?>(null)

    /**
     * While true the mouse moves the tracing image instead of painting.
     * Android reaches the same state with two fingers on the canvas; a mouse
     * has one pointer, so the mode is explicit here.
     *
     * It belongs to the reference panel: the only control that turns it off
     * is there, so closing that window clears it too, and so does removing
     * the image. A mode that outlived its switch would read as a canvas that
     * had stopped painting for no reason.
     */
    var editingReference by mutableStateOf(false)

    /** The transient decode allowance a reference import must fit. */
    val referenceImageBytes: Long get() = engine.transientImageBytes

    /**
     * Places an imported image, or refuses it for the reason
     * [TracingReferencePolicy] gives. The refusal is the policy's, not a
     * guess: a reference holds one layer of the pool, so a full stack has to
     * give one up first.
     */
    fun placeReference(image: DesktopReferenceImage): Boolean {
        val decision = TracingReferencePolicy.importDecision(
            layerCount = stack.size,
            maxLayers = engine.layerCap,
            transientImageBytes = referenceImageBytes,
            layerReserve = if (reference == null) {
                ReferenceLayerReserve.REQUIRED
            } else {
                ReferenceLayerReserve.HELD
            },
        )
        if (decision != ReferenceImportDecision.ACCEPT) {
            reportReference(
                DesktopStrings.get(
                    when (decision) {
                        ReferenceImportDecision.REFUSE_LAYER_BUDGET -> "err_reference_layer_budget"
                        else -> "err_reference_memory"
                    },
                ),
            )
            return false
        }

        referenceNotice = null
        referencePng = image.png
        applyReference(image.reference)
        engine.uploadReferenceTiles(image.reference.assetName, image.tiles.toList())
        return true
    }

    /**
     * Adopts a reference the document opened with. Only the shell's own copy:
     * the engine places the pixels while it builds its renderer, because a
     * push from here would race that and lose silently on a cold start.
     */
    fun restoreReference(reference: TracingReference, png: ByteArray) {
        referencePng = png
        this.reference = reference
    }

    fun setReferenceOpacity(opacity: Float) {
        val current = reference ?: return
        applyReference(current.copy(opacity = opacity.coerceIn(0f, 1f)))
    }

    fun toggleReferenceVisible() {
        val current = reference ?: return
        applyReference(
            current.copy(
                visibility = if (current.visibility == ReferenceVisibility.VISIBLE) {
                    ReferenceVisibility.HIDDEN
                } else {
                    ReferenceVisibility.VISIBLE
                },
            ),
        )
    }

    /** Back to fitted and centred, the placement an import starts from. */
    fun resetReference() {
        val current = reference ?: return
        applyReference(
            current.copy(
                transform = ReferenceTransform.fit(
                    imageWidth = current.imageWidth,
                    imageHeight = current.imageHeight,
                    canvasWidth = engine.canvas.width,
                    canvasHeight = engine.canvas.height,
                ),
            ),
        )
    }

    fun removeReference() {
        referencePng = null
        referenceNotice = null
        editingReference = false
        applyReference(null)
    }

    /**
     * One move/scale/rotate step, in canvas coordinates.
     *
     * The arithmetic is [ReferenceTransform.gesture] — the same call Android's
     * two-finger path makes — so the two products place an image identically
     * however the gesture reached them.
     */
    fun transformReference(
        pivotX: Float,
        pivotY: Float,
        panX: Float,
        panY: Float,
        zoom: Float,
        rotationDelta: Float,
    ) {
        val current = reference ?: return
        if (!pivotX.isFinite() || !pivotY.isFinite() || !panX.isFinite() || !panY.isFinite()) return
        if (!zoom.isFinite() || zoom <= 0f || !rotationDelta.isFinite()) return

        applyReference(
            current.copy(
                transform = current.transform.gesture(
                    pivotX = pivotX,
                    pivotY = pivotY,
                    panX = panX,
                    panY = panY,
                    zoom = zoom,
                    rotationDelta = rotationDelta,
                ),
            ),
        )
    }

    /**
     * Says why a reference action refused. The panel is the natural place —
     * but a refused *import* is exactly the case where there is no reference
     * and therefore no panel, so the strip carries it then.
     */
    fun reportReference(message: String) {
        referenceNotice = message
        if (!showReferencePanel || reference == null) savedMessage = message
    }

    private fun applyReference(next: TracingReference?) {
        reference = next
        engine.setTracingReference(next)
    }

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
        moveTo = { from, to -> edit { stack, _ -> stack.move(from, to) } },
        rename = { index, name -> edit { stack, _ -> stack.rename(index, name) } },
        setOpacity = { index, value -> edit { stack, _ -> stack.setOpacity(index, value) } },
        setVisible = { index, value -> edit { stack, _ -> stack.setVisible(index, value) } },
        setBlendMode = { index, mode -> edit { stack, _ -> stack.setBlendMode(index, mode) } },
        setAlphaLock = { index, value -> edit { stack, _ -> stack.setAlphaLock(index, value) } },
        setLocked = { index, value -> edit { stack, _ -> stack.setLocked(index, value) } },
        setPaperColor = { argb -> engine.setPaperColor(argb) },
    )

    /**
     * How many layers this canvas's memory budget allows — one fewer while
     * a tracing reference holds a layer's worth of the pool, and never
     * below what the stack already has.
     */
    val layerCap: Int
        get() = TracingReferencePolicy.layerCap(stack.size, engine.layerCap, reference)

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
        const val USER_PALETTE_PREFIX = "user."
        const val OPAQUE_ALPHA = 0xFF000000.toInt()

        /** One empty palette to add to, so "add" never needs a dialog first. */
        val DEFAULT_USER_PALETTE = Palette(
            id = "user.default",
            name = PaletteCatalog.MY_PALETTE_NAME,
            swatches = emptyList(),
        )

        /** The dish opens on the two colours every mixing demo starts from. */
        const val DEFAULT_WELL_A = 0xFFFFFFFF.toInt()
        const val DEFAULT_WELL_B = 0xFF141414.toInt()
    }
}
