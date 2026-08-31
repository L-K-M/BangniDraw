package ch.lkmc.bangnidraw.engine.gl

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The seam's own invariant (`platform/GLES30.kt`): jvmShared compiles for a
 * JVM that has no `android.*` classes, so a stray platform reference is
 * fine on the Android target and only breaks the desktop build — the exact
 * silent regression this suite exists to catch at `desktopTest` time.
 *
 * Both spellings count: the import (`import android.opengl.GLES30`) and
 * the fully-qualified call (`android.util.Log.w(...)` — the form GlFbo
 * carried before the seam existed). KDoc and `//` mentions stay legal so
 * the facade's own documentation can name the platform binding.
 */
class PlatformImportBanContractTest {

    @Test
    fun `jvmShared sources never reference android`() {
        val root = File("src/jvmShared/kotlin")
        assertTrue(root.isDirectory, "cannot locate jvmShared sources from ${File(".").canonicalPath}")

        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val hit = line.startsWith("import android.") ||
                        (!line.isComment() && QUALIFIED_ANDROID.containsMatchIn(line))
                    if (hit) "${file.name}:${index + 1}: $line" else null
                }
            }
            .toList()

        assertTrue(offenders.isEmpty(), offenders.joinToString("\n"))
    }

    private fun String.isComment(): Boolean {
        val trimmed = trimStart()
        return trimmed.startsWith("*") || trimmed.startsWith("//") || trimmed.startsWith("/*")
    }

    private companion object {
        // `android.` + lowercase package + a second dot; deliberately does
        // not match `androidx.*`.
        val QUALIFIED_ANDROID = Regex("""\bandroid\.[a-z]+\.""")
    }
}
