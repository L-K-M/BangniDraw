package ch.lkmc.bangnidraw.engine.core

/**
 * Stable paint assignments for the adaptive rail. Preset IDs form a
 * permutation of the available paint catalogue, so assigning a preset swaps
 * it with the active slot instead of creating duplicate shortcuts.
 */
internal class PaintSlotAssignments private constructor(
    presetIds: List<String>,
    val activeIndex: Int,
) {
    val presetIds = presetIds.toList()

    init {
        require(presetIds.isNotEmpty()) { "paint slot assignments must not be empty" }
        require(presetIds.distinct().size == presetIds.size) {
            "paint slot assignments must contain unique preset IDs"
        }
        require(activeIndex in presetIds.indices) {
            "active paint slot $activeIndex is outside ${presetIds.indices}"
        }
    }

    val activePresetId: String
        get() = presetIds[activeIndex]

    fun activate(index: Int): PaintSlotAssignments {
        require(index in presetIds.indices) {
            "paint slot index $index is outside ${presetIds.indices}"
        }
        if (index == activeIndex) return this

        return PaintSlotAssignments(presetIds, index)
    }

    fun assign(presetId: String): PaintSlotAssignments {
        val assignedIndex = presetIds.indexOf(presetId)
        require(assignedIndex >= 0) { "paint preset $presetId is unavailable" }
        if (assignedIndex == activeIndex) return this

        // Swap rather than duplicate: each preset stays reachable once.
        val next = presetIds.toMutableList()
        next[assignedIndex] = next[activeIndex]
        next[activeIndex] = presetId
        return PaintSlotAssignments(next, activeIndex)
    }

    companion object {
        fun restore(
            cataloguePresetIds: List<String>,
            storedPresetIds: List<String> = emptyList(),
        ): PaintSlotAssignments {
            require(cataloguePresetIds.isNotEmpty()) {
                "paint slot catalogue must not be empty"
            }
            require(cataloguePresetIds.distinct().size == cataloguePresetIds.size) {
                "paint slot catalogue must contain unique preset IDs"
            }

            // Retain valid saved assignments, then append new catalogue entries.
            val catalogue = cataloguePresetIds.toHashSet()
            val seen = HashSet<String>()
            val restored = buildList {
                for (id in storedPresetIds) {
                    if (id !in catalogue || !seen.add(id)) continue
                    add(id)
                }
                for (id in cataloguePresetIds) {
                    if (!seen.add(id)) continue
                    add(id)
                }
            }
            return PaintSlotAssignments(restored, activeIndex = 0)
        }
    }
}

/** Stable DataStore encoding; safe preset IDs cannot contain line breaks. */
internal object StoredPaintSlots {
    fun encode(presetIds: List<String>): String = presetIds.joinToString(SEPARATOR)

    fun decode(stored: String?): List<String> = stored
        ?.split(SEPARATOR)
        ?.filter(String::isNotEmpty)
        .orEmpty()

    private const val SEPARATOR = "\n"
}

/**
 * Which assigned paint slots fit the FULL rail
 * (`docs/plan/08-ui-and-layout.md` §1).
 *
 * A reduced window still shows an active assignment beyond its budget in the
 * last visible position. This projection never mutates the durable assignment.
 */
internal object RailSlotPolicy {

    fun visibleIndices(
        assignments: PaintSlotAssignments,
        budget: Int,
    ): List<Int> {
        require(budget >= 1) { "paint slot budget must be at least 1, was $budget" }

        val visibleCount = minOf(assignments.presetIds.size, budget)
        val visible = (0 until visibleCount).toMutableList()
        if (assignments.activeIndex < visibleCount) return visible

        visible[visible.lastIndex] = assignments.activeIndex
        return visible
    }
}
