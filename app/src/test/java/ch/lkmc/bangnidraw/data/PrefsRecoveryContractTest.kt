package ch.lkmc.bangnidraw.data

import ch.lkmc.bangnidraw.ui.canvas.ContractTestSources
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every preference READ FLOW carries the recovery `appTheme` was given:
 * `DataStore.data` surfaces transient read failures as `IOException`, the
 * collectors are plain `viewModelScope.launch` blocks with no handler, and
 * an unrecovered flow therefore crashes the process over a setting it could
 * have defaulted. Point reads inside suspend functions stay their callers'
 * responsibility; this pins the flows.
 */
class PrefsRecoveryContractTest {

    @Test
    fun `every preference read flow is recovered`() {
        val prefs = ContractTestSources.read(PREFS_PATH)

        // Each `val name: Flow<...> = dataStore.data...` declaration must
        // chain the shared helper (or appTheme's bespoke, contract-tested
        // call). The declaration ends where the next member begins.
        val declaration = Regex(
            """val (\w+): Flow<[^=]*=\s*(?:dataStore\.data|paintSlotState)[\s\S]*?(?=\n    (?:va|in|pr|su|fu|co|ov|op|ty|ob|cl|en|la|@|/\*)|\n\}\n)""",
        )
        val offenders = ArrayList<String>()
        var flows = 0
        for (match in declaration.findAll(prefs)) {
            val name = match.groupValues[1]
            val body = match.value
            if ("paintSlotState" in body) continue // in-memory, no IO to recover
            flows++
            if (".recovered(" !in body && "retryIoWithInitialFallback(" !in body) {
                offenders += name
            }
        }
        if (flows < MIN_EXPECTED_FLOWS) {
            fail("matcher found only $flows preference flows; it has gone stale")
        }
        assertTrue(offenders.isEmpty(), "unrecovered preference flows: $offenders")
    }

    @Test
    fun `a failed paint-slot read cannot canonicalize defaults over the stored arrangement`() {
        val prefs = ContractTestSources.read(PREFS_PATH)

        assertTrue(
            "paintSlotLoadDegraded = stored == null" in prefs,
            "the init block must distinguish a failed read from an absent value",
        )
        assertTrue(
            "if (write == PaintSlotWrite.CANONICALIZE) return" in prefs,
            "a degraded session must not write canonicalized defaults back",
        )
        assertTrue(
            "publishPaintSlotIdsLocked(assignments.presetIds, PaintSlotWrite.ASSIGN)" in prefs,
            "a user's own assignment still writes — their intent defines the new truth",
        )
    }

    private companion object {
        const val PREFS_PATH = "app/src/main/java/ch/lkmc/bangnidraw/data/Prefs.kt"

        /**
         * The exact count in Prefs.kt today: fewer means the regex went
         * stale (a flow refactored past its anchors escapes the contract),
         * and the intentional removal of a flow updates this alongside it.
         */
        const val MIN_EXPECTED_FLOWS = 17
    }
}
