package ch.lkmc.bangnidraw.engine.core

/**
 * The eraser slot's long-press toggle: which eraser the rail should switch
 * to when the user asks for "the other one" (`08-ui-and-layout.md` §3.2's
 * slot; the soft eraser is otherwise reachable only through the brush
 * settings sheet).
 *
 * The rail renders exactly one eraser at a time. A long-press on it swaps
 * between the two shipped erasers; a preset set with fewer than two erasers
 * (corrupt JSON falls back) has nothing to toggle to, and the current id is
 * never the answer — if it is not among the erasers, the first one is.
 */
object EraserTogglePolicy {

    fun next(currentId: String, presets: List<BrushPreset>): String? {
        val erasers = presets.filter(BrushPreset::eraseMode)
        if (erasers.size < 2) return null
        return erasers.firstOrNull { it.id != currentId }?.id
    }
}
