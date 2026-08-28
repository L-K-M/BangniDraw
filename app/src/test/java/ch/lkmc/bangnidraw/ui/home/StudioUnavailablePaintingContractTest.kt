package ch.lkmc.bangnidraw.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class StudioUnavailablePaintingContractTest {

    @Test
    fun `unavailable paintings have no open action and retain delete`() {
        val source = File(repositoryRoot(), STUDIO_SCREEN_PATH).readText()
        val paintingCell = section(
            source = source,
            start = PAINTING_CELL_START,
            end = PAINTING_CELL_END,
        )

        assertTrue(UNAVAILABLE_BADGE in paintingCell)
        assertTrue(UNAVAILABLE_SEMANTICS in paintingCell)

        val menu = section(
            source = paintingCell,
            start = MENU_START,
            end = MENU_END,
        )
        val availableActions = bracedBlock(menu, AVAILABLE_ACTIONS_START)

        for (action in AVAILABLE_ACTIONS) {
            assertOnlyInside(action, availableActions, menu)
        }

        val deleteIndex = menu.indexOf(DELETE_ACTION)
        if (deleteIndex < 0) fail("missing safe action: $DELETE_ACTION")
        assertTrue(deleteIndex > availableActions.last, "delete must stay outside the guard")
    }

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        if (startIndex < 0) fail("missing source marker: $start")

        val endIndex = source.indexOf(end, startIndex + start.length)
        if (endIndex <= startIndex) fail("missing source marker: $end")

        return source.substring(startIndex, endIndex)
    }

    private fun bracedBlock(source: String, marker: String): IntRange {
        val markerIndex = source.indexOf(marker)
        if (markerIndex < 0) fail("missing block marker: $marker")

        val openIndex = source.indexOf('{', markerIndex + marker.length)
        if (openIndex < 0) fail("missing block start: $marker")

        var depth = 0
        for (index in openIndex until source.length) {
            when (source[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return openIndex..index
                }
            }
        }

        fail("unclosed block: $marker")
    }

    private fun assertOnlyInside(marker: String, block: IntRange, source: String) {
        val firstIndex = source.indexOf(marker)
        if (firstIndex < 0) fail("missing guarded action: $marker")

        val repeatedIndex = source.indexOf(marker, firstIndex + marker.length)
        assertTrue(repeatedIndex < 0, "duplicate action marker: $marker")
        assertTrue(firstIndex in block, "action escaped availability guard: $marker")
    }

    private fun repositoryRoot(): File {
        val workingDirectory = File(
            requireNotNull(System.getProperty(USER_DIRECTORY_PROPERTY)),
        ).canonicalFile

        return generateSequence(workingDirectory) { it.parentFile }
            .firstOrNull { File(it, ROOT_MARKER).isFile && File(it, APP_DIRECTORY).isDirectory }
            ?: fail("cannot locate repository root from $workingDirectory")
    }

    private companion object {
        const val USER_DIRECTORY_PROPERTY = "user.dir"
        const val ROOT_MARKER = "settings.gradle.kts"
        const val APP_DIRECTORY = "app/src/main"
        const val PAINTING_CELL_START = "private fun PaintingCell("
        const val PAINTING_CELL_END = "private fun ThumbnailCheckerboard()"
        const val MENU_START = "DropdownMenu(expanded = menuOpen"
        const val MENU_END = "Spacer(Modifier.height(4.dp))"
        const val AVAILABLE_ACTIONS_START = "if (available)"
        const val DELETE_ACTION = "confirmDelete = true"
        const val UNAVAILABLE_BADGE = "R.string.studio_painting_unavailable_badge"
        const val UNAVAILABLE_SEMANTICS =
            "semantics(mergeDescendants = true) { disabled() }"
        val AVAILABLE_ACTIONS = listOf(
            "onOpen()",
            "renaming = true",
            "onDuplicate()",
            "sharing = true",
        )
        const val STUDIO_SCREEN_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioScreen.kt"
    }
}
