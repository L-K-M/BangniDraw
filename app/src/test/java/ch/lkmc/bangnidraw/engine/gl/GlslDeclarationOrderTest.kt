package ch.lkmc.bangnidraw.engine.gl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * A tiny lint over every shader string (`docs/plan/11-testing.md` §4).
 *
 * GLSL ES is order-sensitive in ways a C programmer does not expect — the
 * `#version` line must be first, precision must precede the declarations it
 * governs, and there is no forward declaration for a variable. All of it is
 * a compile error on the device and invisible on the JVM, so this is
 * deliberately dumb regex work whose false-positive policy is **fix the
 * shader, not the regex**.
 *
 * It does not claim the shader compiles or produces the CPU reference's
 * pixels: that is the device checklist (§7) and, if it ever bites badly, the
 * instrumented golden-image job of §8.
 */
class GlslDeclarationOrderTest {

    private data class Stage(val what: String, val source: String)

    private val stages: List<Stage> = Shaders.ALL.flatMap {
        listOf(Stage("${it.name}.vert", it.vertex), Stage("${it.name}.frag", it.fragment))
    }

    @Test
    fun `the version line is the very first line`() {
        // GLSL allows only comments and whitespace before #version, and this
        // engine's sources are trimIndent()ed Kotlin strings — a stray blank
        // first line is one editing accident away.
        for (s in stages) {
            assertEquals(Shaders.VERSION_LINE, s.source.lines().first(), "${s.what}: first line")
            assertEquals(
                1,
                Regex("""^#version""", RegexOption.MULTILINE).findAll(s.source).count(),
                "${s.what}: exactly one #version line",
            )
        }
    }

    @Test
    fun `precision declarations precede every uniform, in, and out`() {
        // A sampler or float used before its precision is declared is a
        // compile error in the fragment stage, and the message points at the
        // use rather than at the missing line.
        for (s in stages) {
            val lines = s.source.lines()
            val lastPrecision = lines.indexOfLast { it.trimStart().startsWith("precision ") }
            if (lastPrecision < 0) fail("${s.what}: no precision declaration at all")
            val firstDeclaration = lines.indexOfFirst {
                Regex("""^\s*(uniform|in|out|layout)\b""").containsMatchIn(it)
            }
            if (firstDeclaration < 0) continue
            assertTrue(
                lastPrecision < firstDeclaration,
                "${s.what}: `${lines[lastPrecision].trim()}` comes after " +
                    "`${lines[firstDeclaration].trim()}`",
            )
        }
    }

    @Test
    fun `the fragment stage declares a precision for float`() {
        // Unlike the vertex stage, the fragment stage has NO default float
        // precision in ES. Omitting it is a compile error on a conformant
        // driver and a silent mediump on a lenient one, which shows up as
        // banding in a gradient and nowhere else.
        for (s in Shaders.ALL) {
            assertTrue(
                Regex("""^\s*precision\s+\w+\s+float\s*;""", RegexOption.MULTILINE)
                    .containsMatchIn(s.fragment),
                "${s.name}.frag has no float precision declaration",
            )
        }
    }

    @Test
    fun `every identifier is declared before its first use`() {
        // GLSL has no forward declaration. Moving a uniform below the function
        // that reads it is a natural-looking edit and a compile error.
        for (s in stages) {
            val lines = s.source.lines()
            val declaredAt = HashMap<String, Int>()
            for ((i, line) in lines.withIndex()) {
                val m = Regex(
                    """^\s*$QUALIFIERS(uniform|in|out)\s+\w+\s+(\w+)\s*(?:\[\w*\])?\s*;""",
                ).find(line) ?: continue
                declaredAt[m.groupValues[2]] = i
            }
            for ((name, declaration) in declaredAt) {
                val firstUse = lines.indexOfFirst { line ->
                    // Comments are not uses, and skipping them is not loosening
                    // the lint: a comment cannot contain a real GLSL use, so
                    // excluding it cannot hide an ordering bug. Leaving them in
                    // makes the natural documenting comment ABOVE a
                    // declaration — which this codebase writes everywhere —
                    // count as a use before it, and the only "fix the shader"
                    // available is deleting the documentation.
                    !line.trimStart().startsWith("//") &&
                        !Regex("""^\s*$QUALIFIERS(uniform|in|out)\b""").containsMatchIn(line) &&
                        Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(line)
                }
                if (firstUse < 0) continue
                assertTrue(
                    declaration < firstUse,
                    "${s.what}: $name is used on line ${firstUse + 1} and declared on " +
                        "line ${declaration + 1}",
                )
            }
        }
    }

    @Test
    fun `every fragment input has a vertex output of the same name and type`() {
        // A varying that matches by name but not by type is a link error; one
        // that matches by neither silently reads garbage. Deliberately
        // one-directional: a vertex `out` that no fragment stage reads is
        // legal GLSL and merely dead, and several programs share
        // COMPOSITE_VERT while reading different parts of what it emits.
        for (s in Shaders.ALL) {
            val outs = varyings(s.vertex, "out")
            for ((name, type) in varyings(s.fragment, "in")) {
                val vertexType = outs[name]
                    ?: fail("${s.name}: fragment reads `in $type $name`, vertex emits no $name")
                assertEquals(
                    vertexType,
                    type,
                    "${s.name}: $name is `out $vertexType` in the vertex stage",
                )
            }
        }
    }

    @Test
    fun `the fragment stage declares exactly one out`() {
        // ES 3.0 with no MRT: two outs need explicit locations and a
        // glDrawBuffers call that nothing here makes, so the second would
        // write nowhere.
        for (s in Shaders.ALL) {
            val outs = varyings(s.fragment, "out")
            assertEquals(1, outs.size, "${s.name}.frag declares ${outs.keys}")
        }
    }

    @Test
    fun `no vertex attribute is missing its explicit location`() {
        // Without layout(location = N) the linker assigns indices in an
        // unspecified order, and the VAO's hard-coded indices then bind the
        // wrong buffers — a picture that is wrong rather than an error.
        for (s in Shaders.ALL) {
            val claimed = mutableSetOf<Int>()
            for (line in s.vertex.lines()) {
                val location = Regex("""^\s*layout\s*\(\s*location\s*=\s*(\d+)\s*\)\s*in\s+""")
                    .find(line)?.groupValues?.get(1)?.toInt()
                when {
                    // Two attributes at one location is a link error on the
                    // device and nothing on the JVM — the same class of bug as
                    // a missing location, and the check was one step short of
                    // catching it.
                    location != null -> assertTrue(
                        claimed.add(location),
                        "${s.name}.vert: location $location is claimed twice",
                    )
                    Regex("""^\s*in\s+""").containsMatchIn(line) ->
                        fail("${s.name}.vert: `${line.trim()}` has no layout(location = …)")
                }
            }
            assertTrue(claimed.isNotEmpty(), "${s.name}.vert declares no attributes at all")
        }
    }

    @Test
    fun `sources carry no include directive while nothing resolves one`() {
        // GLSL ES has no preprocessor include; §13 resolves `#include` by
        // string substitution in Shaders.kt, and that substitution arrives
        // with merge.frag (roadmap 2.4). Until it does, an #include in a
        // source would reach the driver verbatim and fail to compile.
        for (s in stages) {
            assertTrue("#include" !in s.source, "${s.what} has an #include and nothing resolves it")
        }
    }

    @Test
    fun `the lint actually sees the sources`() {
        // The guard on this file rather than on the shaders: every test above
        // is a loop, and a loop over an empty list passes while checking
        // nothing. Shaders.ALL going empty — or a stage list built wrong —
        // would turn this whole suite green and vacuous.
        assertTrue(Shaders.ALL.isNotEmpty(), "Shaders.ALL is empty")
        assertEquals(Shaders.ALL.size * 2, stages.size)
        for (s in stages) assertTrue(s.source.length > 50, "${s.what} is suspiciously short")
    }

    /**
     * `name -> type` for every `in`/`out` varying declared in [source].
     *
     * The optional `layout(...)` prefix and interpolation qualifiers are not
     * decoration: `flat out int v;` is legal ES 3.0 and is *the* declaration
     * most prone to the `flat int` vs `flat float` link mismatch this file
     * exists to front-run, and without [QUALIFIERS] it is invisible here — a
     * false negative, which the "fix the shader, not the regex" policy does not
     * cover. An explicit `layout(location = 0) out vec4` on a fragment output
     * is legal too, and made `exactly one out` fail with "declares []".
     */
    private fun varyings(source: String, keyword: String): Map<String, String> =
        Regex("""^\s*$QUALIFIERS$keyword\s+(\w+)\s+(\w+)\s*(?:\[\w*\])?\s*;""", RegexOption.MULTILINE)
            .findAll(source)
            .associate { it.groupValues[2] to it.groupValues[1] }

    private companion object {
        /** An optional `layout(...)` and any interpolation qualifiers before a declaration. */
        const val QUALIFIERS = """(?:layout\([^)]*\)\s*)?(?:(?:flat|smooth|centroid)\s+)*"""
    }
}
