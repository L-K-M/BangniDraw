package ch.lkmc.bangnidraw.desktop

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DesktopStringsTest {

    private val english = DesktopStrings.catalogueFor(Locale.ENGLISH)
    private val chinese = DesktopStrings.catalogueFor(Locale.SIMPLIFIED_CHINESE)

    @Test
    fun `the shell reads the same catalogue the Android app ships`() {
        assertEquals("Layers", english.get("layers_title"))
        assertEquals("Undo", english.get("canvas_undo"))
        assertEquals("Blur", english.get("tool_blur"))
    }

    @Test
    fun `a translated locale gets the translation, and the rest falls back`() {
        assertNotEquals(english.get("layers_title"), chinese.get("layers_title"))
        // Untranslatable by declaration: the product's name is the same
        // everywhere, so both catalogues agree on it.
        assertEquals(english.get("app_name"), chinese.get("app_name"))
    }

    @Test
    fun `an unknown name yields itself rather than an empty label`() {
        // A missing string must be findable in a screenshot, not invisible.
        assertEquals("not_a_real_string", english.get("not_a_real_string"))
    }

    @Test
    fun `arguments are substituted`() {
        val formatted = english.get("layers_count", 2, 16)

        assertTrue(formatted.contains("2"), formatted)
        assertTrue(formatted.contains("16"), formatted)
    }

    @Test
    fun `a template whose placeholders do not match the arguments still renders`() {
        // A translation can carry the wrong placeholders; a panel must not
        // crash because of one.
        assertEquals(english.get("layers_count"), english.get("layers_count"))
        assertTrue(english.get("layers_count", "not a number").isNotBlank())
    }

    @Test
    fun `plurals pick a form, and Chinese has only one`() {
        val one = english.plural("layer_limit", 1, 2048, 2048, 1)
        val many = english.plural("layer_limit", 12, 2048, 2048, 12)

        assertTrue(one.isNotBlank())
        assertTrue(many.isNotBlank())
        assertNotEquals(one, many)
        assertEquals(
            chinese.plural("layer_limit", 1, 2048, 2048, 1),
            chinese.plural("layer_limit", 12, 2048, 2048, 12).replace("12", "1"),
        )
    }

    @Test
    fun `a quoted value keeps its spaces and loses its quotes`() {
        // Android's rule; without it the copy suffix reaches a label as
        // `" copy"` with the quotation marks on screen.
        assertEquals(" copy", english.get("layer_copy_suffix"))
    }

    @Test
    fun `Android's own escapes are decoded`() {
        // `\'` in strings.xml is an apostrophe, not a backslash.
        val text = english.get("studio_empty_hint")

        assertTrue(!text.contains("\\'"), text)
        assertTrue(!text.contains("&amp;"), text)
    }

    @Test
    fun `every string the desktop shell asks for exists in the catalogue`() {
        val sources = java.io.File(repoRoot(), "desktop/src/main/kotlin")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.readText() }
            .toList()
        val requested = sources
            .flatMap { STRING_CALL.findAll(it).map { match -> match.groupValues[1] } }
            .toSortedSet()
        val plurals = sources
            .flatMap { PLURAL_CALL.findAll(it).map { match -> match.groupValues[1] } }
            .toSortedSet()

        assertTrue(requested.isNotEmpty(), "no string lookups found; the matcher is stale")
        // A missing name resolves to itself, which is exactly the signal.
        val missing = requested.filter { english.get(it) == it } +
            plurals.filter { english.plural(it, 1) == it }
        assertTrue(missing.isEmpty(), "not in strings.xml: $missing")
    }

    private fun repoRoot(): java.io.File {
        var candidate = java.io.File(".").canonicalFile
        while (!java.io.File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }

    private companion object {
        /** `DesktopStrings.get("name"`, literal names only. */
        val STRING_CALL = Regex("""DesktopStrings\.get\(\s*"([a-z0-9_]+)"""")
        val PLURAL_CALL = Regex("""DesktopStrings\.plural\(\s*"([a-z0-9_]+)"""")
    }
}
