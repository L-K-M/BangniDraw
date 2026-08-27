package ch.lkmc.bangnidraw.engine.core

/**
 * The eraser slot's long-press toggle: which eraser the rail should switch
 * to when the user asks for "the other one" (`08-ui-and-layout.md` §3.2's
 * slot; the soft eraser is otherwise reachable only through the brush
 * settings sheet).
 *
 * The rail renders exactly one eraser at a time. A long-press on it swaps
 * through the shipped erasers in preset order; a set with fewer than two
 * erasers has nothing to toggle to. An unknown current id starts at the
 * first eraser.
 */
object EraserTogglePolicy {

    fun next(currentId: String, presets: List<BrushPreset>): String? {
        val erasers = presets.filter(BrushPreset::eraseMode)
        if (erasers.size < 2) return null

        val currentIndex = erasers.indexOfFirst { it.id == currentId }
        return erasers[(currentIndex + 1) % erasers.size].id
    }
}
