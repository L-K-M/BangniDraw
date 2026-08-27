package ch.lkmc.bangnidraw

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ReleaseWorkflowContractTest {

    @Test
    fun `tag build verifies the release without Mixbox before publishing`() {
        val workflow = File(repositoryRoot(), RELEASE_WORKFLOW_PATH).readText()

        val strippedIndex = workflow.indexOf(STRIPPED_RELEASE_CHECK)
        val releaseIndex = workflow.indexOf(STANDARD_RELEASE_BUILD)
        val stagingIndex = workflow.indexOf(STAGE_RELEASE_ARTIFACTS)

        assertTrue(strippedIndex >= 0, "tag builds must test and assemble without Mixbox")
        assertTrue(releaseIndex > strippedIndex, "the published APK must restore Mixbox")
        assertTrue(stagingIndex > releaseIndex, "only the standard release may be staged")
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
        const val RELEASE_WORKFLOW_PATH = ".github/workflows/release.yml"
        const val STRIPPED_RELEASE_CHECK =
            "./gradlew -Pbangnidraw.mixbox=false testDebugUnitTest assembleRelease"
        const val STANDARD_RELEASE_BUILD = "run: ./gradlew assembleRelease"
        const val STAGE_RELEASE_ARTIFACTS = "- name: Stage release artifacts"
    }
}
