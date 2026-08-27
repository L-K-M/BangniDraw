package ch.lkmc.bangnidraw.engine.core

/**
 * Which paint presets earn a FULL-rail slot (`docs/plan/08-ui-and-layout.md`
 * §1). The rail's height fits [LayoutSpec.paintSlotBudget] slots; presets
 * beyond the budget stay reachable through the settings sheet's chip row,
 * the same path GROUPED/SHORT/DOCK modes already use for every preset but
 * the active one.
 *
 * The active preset always keeps a slot: a user who picks a tail preset
 * from the chips must still see it highlighted on the rail. It takes the
 * last budgeted slot, so the rail's order is otherwise undisturbed and the
 * displaced preset is the one nearest the overflow anyway.
 */
internal object RailSlotPolicy {

    fun visible(
        paints: List<BrushPreset>,
        activePaintId: String?,
        budget: Int,
    ): List<BrushPreset> {
        require(budget >= 1) { "paint slot budget must be at least 1, was $budget" }
        if (paints.size <= budget) return paints

        val taken = paints.take(budget).toMutableList()
        val active = paints.firstOrNull { it.id == activePaintId } ?: return taken
        if (taken.any { it.id == active.id }) return taken

        taken[taken.lastIndex] = active
        return taken
    }
}
