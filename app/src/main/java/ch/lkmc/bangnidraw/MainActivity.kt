package ch.lkmc.bangnidraw

import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.lkmc.bangnidraw.engine.core.CanvasShortcut
import ch.lkmc.bangnidraw.engine.core.CanvasShortcuts
import ch.lkmc.bangnidraw.engine.core.KeyModifiers
import ch.lkmc.bangnidraw.engine.core.KeyPhase
import ch.lkmc.bangnidraw.engine.core.ShortcutContext
import ch.lkmc.bangnidraw.engine.core.ShortcutKey
import ch.lkmc.bangnidraw.engine.core.ThemeTone
import ch.lkmc.bangnidraw.ui.navigation.BangniNavHost
import ch.lkmc.bangnidraw.ui.theme.AppThemeViewModel
import ch.lkmc.bangnidraw.ui.theme.BangniTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val appThemeViewModel by viewModels<AppThemeViewModel>()
    private var shortcutSink: CanvasShortcutSink? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launch window cannot read DataStore: cold starts begin light,
        // while recreation seeds from the retained ViewModel's tone so the
        // bars match the first frame instead of flashing light icons.
        val initialTone = appThemeViewModel.uiState.value.appTheme?.tone ?: ThemeTone.LIGHT
        enableEdgeToEdge(
            statusBarStyle = systemBarStyle(initialTone),
            navigationBarStyle = systemBarStyle(initialTone),
        )
        setContent {
            val state by appThemeViewModel.uiState.collectAsStateWithLifecycle()
            val appTheme = state.appTheme ?: return@setContent

            val tone = appTheme.tone
            LaunchedEffect(tone) {
                enableEdgeToEdge(
                    statusBarStyle = systemBarStyle(tone),
                    navigationBarStyle = systemBarStyle(tone),
                )
            }

            BangniTheme(appTheme = appTheme) {
                BangniNavHost()
            }
        }
    }

    private fun systemBarStyle(tone: ThemeTone): SystemBarStyle = when (tone) {
        ThemeTone.LIGHT -> SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        ThemeTone.DARK -> SystemBarStyle.dark(Color.TRANSPARENT)
    }

    internal fun installShortcutSink(sink: CanvasShortcutSink) {
        shortcutSink = sink
    }

    internal fun uninstallShortcutSink(sink: CanvasShortcutSink) {
        if (shortcutSink === sink) shortcutSink = null
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (dispatchShortcut(keyCode, event, KeyPhase.DOWN)) return true

        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (dispatchShortcut(keyCode, event, KeyPhase.UP)) return true

        return super.onKeyUp(keyCode, event)
    }

    private fun dispatchShortcut(keyCode: Int, event: KeyEvent, phase: KeyPhase): Boolean {
        val sink = shortcutSink ?: return false
        val key = shortcutKey(keyCode) ?: return false
        if (key != ShortcutKey.ALT && event.isAltPressed) return false
        if (key == ShortcutKey.ALT && phase == KeyPhase.DOWN && event.repeatCount > 0) return true

        val shortcut = CanvasShortcuts.resolve(key, phase, modifiers(event)) ?: return false
        if (
            sink.shortcutContext == ShortcutContext.TEXT_INPUT &&
            shortcut != CanvasShortcut.END_EYEDROPPER
        ) {
            return false
        }

        return sink.onShortcut(shortcut)
    }

    private fun modifiers(event: KeyEvent): KeyModifiers = when {
        event.isCtrlPressed && event.isShiftPressed -> KeyModifiers.CTRL_SHIFT
        event.isCtrlPressed -> KeyModifiers.CTRL
        else -> KeyModifiers.NONE
    }

    private fun shortcutKey(keyCode: Int): ShortcutKey? = when (keyCode) {
        KeyEvent.KEYCODE_Z -> ShortcutKey.Z
        KeyEvent.KEYCODE_Y -> ShortcutKey.Y
        KeyEvent.KEYCODE_LEFT_BRACKET -> ShortcutKey.LEFT_BRACKET
        KeyEvent.KEYCODE_RIGHT_BRACKET -> ShortcutKey.RIGHT_BRACKET
        KeyEvent.KEYCODE_B -> ShortcutKey.B
        KeyEvent.KEYCODE_E -> ShortcutKey.E
        KeyEvent.KEYCODE_S -> ShortcutKey.S
        KeyEvent.KEYCODE_W -> ShortcutKey.W
        KeyEvent.KEYCODE_G -> ShortcutKey.G
        KeyEvent.KEYCODE_I -> ShortcutKey.I
        KeyEvent.KEYCODE_0 -> ShortcutKey.DIGIT_ZERO
        KeyEvent.KEYCODE_TAB -> ShortcutKey.TAB
        KeyEvent.KEYCODE_L -> ShortcutKey.L
        KeyEvent.KEYCODE_C -> ShortcutKey.C
        KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT -> ShortcutKey.ALT
        else -> null
    }
}

internal interface CanvasShortcutSink {
    val shortcutContext: ShortcutContext

    fun onShortcut(shortcut: CanvasShortcut): Boolean
}
