package ch.lkmc.bangnidraw.desktop

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import ch.lkmc.bangnidraw.engine.core.CanvasShortcut
import ch.lkmc.bangnidraw.engine.core.CanvasShortcutCatalog
import ch.lkmc.bangnidraw.engine.core.ShortcutKey
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopCanvasOverlayTest {

    @Test
    fun `every catalogue chord the shell prints is reachable from a key event`() {
        // The catalogue is what Android's Settings sheet lists. A chord in it
        // that the desktop cannot produce is a promise the shell breaks.
        for (row in CanvasShortcutCatalog.rows) {
            val resolved = DesktopShortcuts.resolve(
                key = composeKey(row.key),
                type = KeyEventType.KeyDown,
                primary = row.modifiers != ch.lkmc.bangnidraw.engine.core.KeyModifiers.NONE,
                shift = row.modifiers == ch.lkmc.bangnidraw.engine.core.KeyModifiers.CTRL_SHIFT,
            )

            assertEquals(row.action, resolved, "no key event produces ${row.chord}")
        }
    }

    @Test
    fun `a key outside the table is not consumed`() {
        assertNull(down(Key.Q))
        assertNull(down(Key.F1))
    }

    @Test
    fun `releasing Alt ends the temporary eyedropper`() {
        val pressed = down(Key.AltLeft)
        val released = DesktopShortcuts.resolve(
            Key.AltLeft, KeyEventType.KeyUp, primary = false, shift = false,
        )

        assertEquals(CanvasShortcut.BEGIN_EYEDROPPER, pressed)
        assertEquals(CanvasShortcut.END_EYEDROPPER, released)
    }

    @Test
    fun `a key release is otherwise ignored`() {
        assertNull(
            DesktopShortcuts.resolve(Key.B, KeyEventType.KeyUp, primary = false, shift = false),
        )
    }

    // ------------------------------------------------------------ contract

    @Test
    fun `the overlays are the shared implementations, not desktop copies`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        assertTrue(main.contains("import ch.lkmc.bangnidraw.ui.shared.CompositionGuides"))
        assertTrue(main.contains("import ch.lkmc.bangnidraw.ui.shared.HoverCursor"))
        // Both live in the directory `:desktop` compiles through kotlin.srcDir.
        assertTrue(
            File(repoRoot(), "app/src/main/java/ch/lkmc/bangnidraw/ui/shared/HoverCursor.kt").isFile,
        )
        assertTrue(
            File(
                repoRoot(),
                "app/src/main/java/ch/lkmc/bangnidraw/ui/shared/CompositionGuides.kt",
            ).isFile,
        )
        assertTrue(
            source("desktop/build.gradle.kts").contains("ui/shared"),
            "the shared directory must be on the desktop source path",
        )
    }

    @Test
    fun `the hover cursor is told when the pointer arrives, not only when it moves`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        // A hover *enter* fills StylusState.tool, and the cursor is chosen
        // from it; a stream of moves alone leaves it FINGER, which reads as
        // "no cursor at all".
        assertTrue(main.contains("handler.onHoverEnter(hover)"))
        assertTrue(main.contains("handler.onHoverExit(timeNs)"))
    }

    @Test
    fun `navigation reaches the shell through one publisher`() {
        val main = source("desktop/src/main/kotlin/ch/lkmc/bangnidraw/desktop/Main.kt")

        // `setView` only moves the handler's own state, so a reset that
        // skipped this would leave the engine drawing the old view.
        assertTrue(main.contains("override fun onViewChanged(view: ViewTransform) {"))
        assertTrue(main.contains("onView(view)"))
        val reset = main.substringAfter("val resetView: () -> Unit").substringBefore("val eraserPreset")
        assertTrue(reset.contains("handler.setView(fit)"))
        assertTrue(reset.contains("engine.setView(fit)"))
        assertTrue(reset.contains("state.view = fit"))
    }

    private fun down(key: Key): CanvasShortcut? =
        DesktopShortcuts.resolve(key, KeyEventType.KeyDown, primary = false, shift = false)

    private fun composeKey(key: ShortcutKey): Key = when (key) {
        ShortcutKey.Z -> Key.Z
        ShortcutKey.Y -> Key.Y
        ShortcutKey.LEFT_BRACKET -> Key.LeftBracket
        ShortcutKey.RIGHT_BRACKET -> Key.RightBracket
        ShortcutKey.B -> Key.B
        ShortcutKey.E -> Key.E
        ShortcutKey.S -> Key.S
        ShortcutKey.W -> Key.W
        ShortcutKey.G -> Key.G
        ShortcutKey.I -> Key.I
        ShortcutKey.DIGIT_ZERO -> Key.Zero
        ShortcutKey.TAB -> Key.Tab
        ShortcutKey.L -> Key.L
        ShortcutKey.C -> Key.C
        ShortcutKey.ALT -> Key.AltLeft
    }

    private fun source(path: String): String = File(repoRoot(), path).readText(Charsets.UTF_8)

    private fun repoRoot(): File {
        var candidate = File(".").canonicalFile
        while (!File(candidate, "settings.gradle.kts").isFile) {
            candidate = candidate.parentFile ?: error("repository root not found")
        }
        return candidate
    }
}
