package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The seam's own invariant (`platform/GLES30.kt`): jvmShared compiles for a
 * JVM that has no `android.*` classes, so a stray platform import is fine
 * on the Android target and only breaks the desktop build — the exact
 * silent regression this suite exists to catch at `desktopTest` time.
 */
class PlatformImportBanContractTest {

    @Test
    fun `jvmShared sources never import android`() {
        val root = File("src/jvmShared/kotlin")
        assertTrue(root.isDirectory, "cannot locate jvmShared sources from ${File(".").canonicalPath}")

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapNotNull { line ->
                    if (line.startsWith("import android.")) "${file.name}: $line" else null
                }
            }
            .toList()

        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }
}
