package ch.lkmc.bangnidraw.ui.home

import ch.lkmc.bangnidraw.data.ProjectStore
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioGallerySyncPolicyTest {

    @Test
    fun `only available stale paintings are sync candidates`() {
        val candidates = StudioGallerySyncPolicy.staleCandidates(
            listOf(
                summary(
                    id = "available-stale",
                    availability = ProjectStore.ShelfAvailability.AVAILABLE,
                    updatedAt = 20L,
                    lastSyncAt = 10L,
                ),
                summary(
                    id = "available-current",
                    availability = ProjectStore.ShelfAvailability.AVAILABLE,
                    updatedAt = 20L,
                    lastSyncAt = 20L,
                ),
                summary(
                    id = "newer",
                    availability = ProjectStore.ShelfAvailability.NEWER_VERSION,
                    updatedAt = 20L,
                    lastSyncAt = 10L,
                ),
                summary(
                    id = "unreadable",
                    availability = ProjectStore.ShelfAvailability.UNREADABLE,
                    updatedAt = 20L,
                    lastSyncAt = 10L,
                ),
            ),
        )

        assertEquals(listOf("available-stale"), candidates.map { it.id })
    }

    private fun summary(
        id: String,
        availability: ProjectStore.ShelfAvailability,
        updatedAt: Long,
        lastSyncAt: Long,
    ): ProjectStore.Summary = ProjectStore.Summary(
        id = id,
        title = id,
        updatedAt = updatedAt,
        width = 1,
        height = 1,
        layerCount = 1,
        thumbnail = null,
        bytes = 0L,
        galleryUri = null,
        lastGallerySyncAt = lastSyncAt,
        availability = availability,
    )
}
