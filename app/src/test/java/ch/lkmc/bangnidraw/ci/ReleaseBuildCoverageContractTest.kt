package ch.lkmc.bangnidraw.ci

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
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

        val shipped = gradleInvocations(release).filter { ASSEMBLE_RELEASE in it }
        if (shipped.isEmpty()) fail("$RELEASE_PATH no longer runs $ASSEMBLE_RELEASE — renamed?")
        // What ships carries no -P overrides; if that ever changes, this test
        // should be updated deliberately rather than silently satisfied.
        assertTrue(
            shipped.any { PROPERTY_FLAG !in it },
            "expected release.yml to publish the default configuration, found $shipped",
        )

        assertTrue(
            gradleInvocations(ci).any { ASSEMBLE_RELEASE in it && PROPERTY_FLAG !in it },
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
                .any { ASSEMBLE_RELEASE in it && MIXBOX_OFF in it },
            "CI must keep building the Mixbox-stripped release",
        )
    }

    /** Every `./gradlew …` command in a workflow, one per line, folds joined. */
    private fun gradleInvocations(workflow: String): List<String> = workflow
        .replace(Regex(""">-\s*\n"""), " ")
        .lines()
        .map { it.trim() }
        .filter { it.startsWith("run:") || it.startsWith("./gradlew") }
        .map { it.removePrefix("run:").trim() }
        .filter { it.startsWith("./gradlew") }

    private companion object {
        const val CI_PATH = ".github/workflows/ci.yml"
        const val RELEASE_PATH = ".github/workflows/release.yml"
        const val ASSEMBLE_RELEASE = "assembleRelease"
        const val PROPERTY_FLAG = "-P"
        const val MIXBOX_OFF = "bangnidraw.mixbox=false"
    }
}
