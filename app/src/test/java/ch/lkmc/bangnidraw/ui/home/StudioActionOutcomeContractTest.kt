package ch.lkmc.bangnidraw.ui.home

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class StudioActionOutcomeContractTest {

    @Test
    fun `render failures stay inside Studio actions`() {
        val source = source()
        val sync = section(source, SYNC_START, SHARE_START)
        val share = section(source, SHARE_START, CREATE_START)
        val saveAs = section(source, SAVE_AS_START, DELETE_START)

        assertTrue("catch (e: Exception)" in sync)
        assertTrue("catch (e: Exception)" in share)
        assertTrue("onFailed()" in share)
        assertTrue("catch (e: Exception)" in saveAs)
    }

    @Test
    fun `blank rename remains a successful no-op`() {
        val rename = source().substringAfter(RENAME_START)

        assertTrue("onDone(true)" in rename.substringBefore("viewModelScope.launch"))
    }

    private fun source(): String = File(repositoryRoot(), STUDIO_VIEW_MODEL_PATH).readText()

    private fun section(source: String, start: String, end: String): String {
        val startIndex = source.indexOf(start)
        if (startIndex < 0) fail("missing source marker: $start")

        val endIndex = source.indexOf(end, startIndex)
        if (endIndex <= startIndex) fail("missing source marker: $end")

        return source.substring(startIndex, endIndex)
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
        const val STUDIO_VIEW_MODEL_PATH =
            "app/src/main/java/ch/lkmc/bangnidraw/ui/home/StudioViewModel.kt"
        const val SYNC_START = "private fun syncStale("
        const val SHARE_START = "fun share("
        const val CREATE_START = "fun createPainting("
        const val SAVE_AS_START = "internal fun saveAsNewGalleryItem("
        const val DELETE_START = "fun delete("
        const val RENAME_START = "fun rename("
    }
}
