package ch.lkmc.bangnidraw.ui.home

import ch.lkmc.bangnidraw.data.ProjectStore
import ch.lkmc.bangnidraw.engine.core.GallerySyncDecision

internal object StudioGallerySyncPolicy {
    internal fun staleCandidates(
        summaries: List<ProjectStore.Summary>,
    ): List<ProjectStore.Summary> = summaries.filter(::isStale)

    private fun isStale(summary: ProjectStore.Summary): Boolean {
        if (summary.availability != ProjectStore.ShelfAvailability.AVAILABLE) return false

        val updatedAt = summary.updatedAt ?: return false
        val lastSyncAt = summary.lastGallerySyncAt ?: return false

        return GallerySyncDecision.isStaleOnDisk(updatedAt, lastSyncAt)
    }
}
