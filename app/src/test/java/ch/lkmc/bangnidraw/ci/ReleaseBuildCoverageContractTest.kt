package ch.lkmc.bangnidraw.ci

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * CI builds the configuration that actually ships.
 *
 * `release.yml` publishes a plain `assembleRelease` — Mixbox in, R8 on. CI
 * covered neither half of that: `assembleDebug` is unminified, and the
 * Mixbox-stripped release swaps the source set R8 traverses. So the one
 * combination users install was first built when a `v*` tag had already been
 * pushed, which is the worst moment to discover a missing keep rule.
 *
 * AGENTS.md names this failure mode directly — "works in debug, breaks in
 * release" is almost always a missing R8 keep rule for a new reflection or
 * serialization entry point — and the risk rose when `:engine-core` and
 * `:engine-gl` became separate modules, because R8 now crosses a boundary
 * that did not exist when `proguard-rules.pro` was written.
 *
 * Pinned as a test because the gap was invisible: both workflows looked
 * thorough on their own, and only reading them side by side showed that
 * neither built what the other shipped.
 */
class ReleaseBuildCoverageContractTest {

    @Test
    fun `CI assembles the release configuration release yml publishes`() {
        val ci = ContractTestSources.read(CI_PATH)
        val release = ContractTestSources.read(RELEASE_PATH)

        val shipped = gradleInvocations(release).filter { it.runs(ASSEMBLE_RELEASE) }
        if (shipped.isEmpty()) fail("$RELEASE_PATH no longer runs $ASSEMBLE_RELEASE — renamed?")
        // What ships carries no -P overrides; if that ever changes, this test
        // should be updated deliberately rather than silently satisfied.
        assertTrue(
            shipped.any { PROPERTY_FLAG !in it },
            "expected release.yml to publish the default configuration, found $shipped",
        )

        assertTrue(
            gradleInvocations(ci).any { it.runs(ASSEMBLE_RELEASE) && PROPERTY_FLAG !in it },
            "CI must assemble the shipped release configuration, not only " +
                "assembleDebug and the Mixbox-stripped release",
        )
    }

    @Test
    fun `CI still covers the stripped release too`() {
        // The two are not interchangeable: -Pbangnidraw.mixbox=false selects a
        // different source set, so R8 shrinks a different graph. Dropping
        // either one leaves a shipped-or-shippable build unproven.
        assertTrue(
            gradleInvocations(ContractTestSources.read(CI_PATH))
                .any { it.runs(ASSEMBLE_RELEASE) && MIXBOX_OFF in it },
            "CI must keep building the Mixbox-stripped release",
        )
    }

    @Test
    fun `the parser reads both block styles and whole task names`() {
        // A folded command spanning two continuation lines must arrive whole;
        // reading only the first would drop its task list and fail this suite
        // with a message pointing at the wrong problem.
        val workflow = """
            |      - name: Folded
            |        run: >-
            |          ./gradlew testDebugUnitTest lintDebug
            |          :engine-core:desktopTest
            |      - name: Literal
            |        run: |
            |          echo hello
            |          ./gradlew assembleRelease
            |      - name: Plain
            |        run: ./gradlew -Pbangnidraw.mixbox=false assembleRelease
            |      - name: Stripped literal
            |        run: |-
            |          echo "bangnidraw.mixbox=false is not a build"
            |          ./gradlew assembleDebug
            |      - run: ./gradlew lintDebug
            |        env:
            |          FOO: bar
            |      -   run: ./gradlew testDebugUnitTest
            |      - name: Folded across a blank line
            |        run: >-
            |          ./gradlew -Pbangnidraw.mixbox=false assembleDebug
            |
            |          ./gradlew assembleRelease
            |      - name: Folded across a more-indented line
            |        run: >-
            |          ./gradlew -Pbangnidraw.mixbox=false lintDebug
            |            ./gradlew assembleRelease
        """.trimMargin()

        assertEquals(
            listOf(
                "./gradlew testDebugUnitTest lintDebug :engine-core:desktopTest",
                "./gradlew assembleRelease",
                "./gradlew -Pbangnidraw.mixbox=false assembleRelease",
                // `|-` is literal like `|`: its lines stay separate, so the
                // echo above cannot lend its text to the gradlew line below.
                "./gradlew assembleDebug",
                // The compact `- run:` form, with a sibling key that must not
                // be absorbed into the command.
                "./gradlew lintDebug",
                // An aligned dash: YAML allows any spacing after it.
                "./gradlew testDebugUnitTest",
                // A blank line inside a folded scalar folds to a real
                // newline, so these are two commands to the shell. Joined,
                // the first one's -P flag would satisfy a pin about the
                // second — a stripped-release build standing in for the
                // shipped one, silently.
                "./gradlew -Pbangnidraw.mixbox=false assembleDebug",
                "./gradlew assembleRelease",
                // A more-indented line keeps its break for the same reason,
                // and is its own command.
                "./gradlew -Pbangnidraw.mixbox=false lintDebug",
                "./gradlew assembleRelease",
            ),
            gradleInvocations(workflow),
        )
    }

    @Test
    fun `a longer task name cannot stand in for the shipped build`() {
        assertTrue("./gradlew :app:assembleRelease".runs(ASSEMBLE_RELEASE))
        assertFalse(
            "./gradlew assembleReleaseSmoke".runs(ASSEMBLE_RELEASE),
            "a suffixed task must not satisfy the pin — it does not build the shipped APK",
        )
        assertFalse("./gradlew checkAssembleRelease".runs(ASSEMBLE_RELEASE))
    }

    /**
     * Whether this command runs [task] — as a whole task name, not as a
     * prefix. `:app:assembleRelease` counts; a future `assembleReleaseSmoke`
     * does not, and must not be able to satisfy a pin by name alone.
     */
    private fun String.runs(task: String): Boolean =
        Regex("(?<![A-Za-z0-9_])" + Regex.escape(task) + "(?![A-Za-z0-9_])").containsMatchIn(this)

    /**
     * Every `./gradlew …` command in a workflow.
     *
     * Handles both block scalars these files use, because they mean opposite
     * things: `>-` folds its lines into one command, while `|` keeps them as
     * separate lines of a script. Reading a folded command as one line and
     * dropping the rest would have made this test fail with a message saying
     * CI does not assemble the release when it does — loud, but pointed at
     * the wrong problem.
     */
    private fun gradleInvocations(workflow: String): List<String> {
        val lines = workflow.lines()
        val commands = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            index++
            // `- run:` is the compact list form and the commonest step style
            // in real workflows. YAML allows any amount of whitespace after
            // the dash, so the dash is stripped generically rather than as a
            // fixed `"- "` — an aligned-dash style would otherwise leave
            // leading spaces, fail the `run:` test, and drop the step in
            // silence, which is the one thing this test exists to prevent.
            val trimmed = line.trim().removePrefix(DASH).trim()
            if (!trimmed.startsWith(RUN_KEY)) continue

            // The key's own column, dash or no dash: the block scan measures
            // from there, so a sibling key of the same mapping ends the block
            // instead of being swallowed into the command. Safe because
            // `trimmed` is a prefix-stripped substring of `line`, so its first
            // occurrence is where the key begins.
            val keyIndent = line.indexOf(trimmed)
            val head = trimmed.removePrefix(RUN_KEY).trim()
            // Chomping indicators are part of the header, not the style: `|`,
            // `|-` and `|+` are all literal — separate script lines — while
            // `>`, `>-` and `>+` all fold into one command. Comparing the
            // header for equality with "|" read `|-`, the commonest literal
            // spelling in Actions, as folded, and joining a script's lines
            // lets one line's text satisfy a check about another's.
            val blockStyle = head.firstOrNull()?.takeIf { it in BLOCK_STYLES }
            val folded = blockStyle != LITERAL
            val body = mutableListOf<String>()
            var baseIndent = NO_INDENT
            if (head.isNotEmpty() && blockStyle == null) body += head

            while (index < lines.size) {
                val next = lines[index]
                // Folding is not "join the whole block". A blank line folds to
                // a real newline, and a more-indented line keeps its break, so
                // either one is a command separator to the shell. Joining
                // across one is a silent false pass rather than a loud failure:
                // the `-P` flag of the first command would satisfy a pin about
                // the second, which is exactly the invisible gap this test
                // exists to close.
                if (next.isBlank()) {
                    index++
                    if (folded) commands.foldInto(body)
                    continue
                }
                val indent = next.indexOfFirst { !it.isWhitespace() }
                // A block scalar's indentation is fixed by its first content
                // line, so less than that ends the block just as a sibling key
                // at the run: column does.
                if (indent <= keyIndent) break
                if (baseIndent == NO_INDENT) baseIndent = indent
                if (indent < baseIndent) break
                index++
                if (folded && indent > baseIndent) {
                    commands.foldInto(body)
                    commands += next.trim()
                    continue
                }
                body += next.trim()
            }
            if (folded) commands.foldInto(body) else commands += body
        }

        return commands.filter { it.startsWith(GRADLEW) }
    }

    /**
     * Ends a folded run: everything accumulated since the last break is one
     * command. A no-op on an empty body, so a leading blank line or a block
     * that ends on one does not emit a phantom command.
     */
    private fun MutableList<String>.foldInto(body: MutableList<String>) {
        if (body.isEmpty()) return
        this += body.joinToString(" ")
        body.clear()
    }

    private companion object {
        const val CI_PATH = ".github/workflows/ci.yml"
        const val RELEASE_PATH = ".github/workflows/release.yml"
        const val ASSEMBLE_RELEASE = "assembleRelease"
        const val PROPERTY_FLAG = "-P"
        const val MIXBOX_OFF = "bangnidraw.mixbox=false"
        const val RUN_KEY = "run:"
        const val GRADLEW = "./gradlew"
        const val DASH = "-"
        const val LITERAL = '|'
        const val NO_INDENT = -1
        const val BLOCK_STYLES = "|>"
    }
}
