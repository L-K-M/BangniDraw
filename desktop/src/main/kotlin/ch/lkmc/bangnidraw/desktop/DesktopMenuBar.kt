package ch.lkmc.bangnidraw.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyShortcut
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar

/**
 * The window's menu bar — the desktop affordances the Android chrome has no
 * place for: New, Open, Save, Save As, Close, and the panels.
 *
 * The accelerators are the platform's own: Compose maps [KeyShortcut]'s
 * `meta` to Command on macOS, and `ctrl` to Control elsewhere, so both are
 * offered and only the one the host understands fires.
 */
@Composable
internal fun FrameWindowScope.DesktopMenuBar(
    document: DesktopDocument,
    onNew: () -> Unit,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onSaveAs: () -> Unit,
    onExportPng: () -> Unit,
    onClose: () -> Unit,
    onAbout: () -> Unit,
    onHelp: () -> Unit,
) {
    val state = document.state
    MenuBar {
        Menu(DesktopStrings.get("desktop_menu_file"), mnemonic = 'F') {
            Item(DesktopStrings.get("desktop_new"), shortcut = shortcut(Key.N), onClick = onNew)
            Item(DesktopStrings.get("desktop_open"), shortcut = shortcut(Key.O), onClick = onOpen)
            Separator()
            Item(
                DesktopStrings.get("desktop_save"),
                shortcut = shortcut(Key.S),
                // A painting with no file has nothing to save *over*, but
                // Save still works: it falls through to Save As.
                enabled = document.dirty || document.file == null,
                onClick = onSave,
            )
            Item(
                DesktopStrings.get("desktop_save_as"),
                shortcut = shortcut(Key.S, shift = true),
                onClick = onSaveAs,
            )
            // Separate from Save because the formats are not interchangeable:
            // `.bangni` keeps the layers, a PNG flattens them.
            Item(
                DesktopStrings.get("desktop_export_png"),
                shortcut = shortcut(Key.E, shift = true),
                onClick = onExportPng,
            )
            Separator()
            Item(DesktopStrings.get("desktop_close"), shortcut = shortcut(Key.W), onClick = onClose)
        }
        Menu(DesktopStrings.get("desktop_menu_edit"), mnemonic = 'E') {
            Item(
                DesktopStrings.get("canvas_undo"),
                shortcut = shortcut(Key.Z),
                enabled = document.engine.canUndo(),
                onClick = { document.engine.undo() },
            )
            Item(
                DesktopStrings.get("canvas_redo"),
                shortcut = shortcut(Key.Z, shift = true),
                enabled = document.engine.canRedo(),
                onClick = { document.engine.redo() },
            )
        }
        Menu(DesktopStrings.get("desktop_menu_window"), mnemonic = 'W') {
            // Checkbox items, because these panels are windows that stay open
            // until they are closed — a plain item would not say which are up.
            CheckboxItem(
                DesktopStrings.get("layers_title"),
                checked = state.showLayerPanel,
                shortcut = shortcut(Key.L),
                onCheckedChange = { state.showLayerPanel = it },
            )
            CheckboxItem(
                DesktopStrings.get("desktop_tool_settings"),
                checked = state.showBrushPanel,
                shortcut = shortcut(Key.B),
                onCheckedChange = { state.showBrushPanel = it },
            )
            CheckboxItem(
                DesktopStrings.get("color_panel"),
                checked = state.showColorPanel,
                onCheckedChange = { state.showColorPanel = it },
            )
            Separator()
            // The host's own Preferences accelerator; the canvas overflow
            // opens the same window, as Android's overflow opens its sheet.
            CheckboxItem(
                DesktopStrings.get("settings_title"),
                checked = state.showSettings,
                shortcut = shortcut(Key.Comma),
                onCheckedChange = { state.showSettings = it },
            )
        }
        Menu(DesktopStrings.get("desktop_menu_help"), mnemonic = 'H') {
            Item(DesktopStrings.get("desktop_canvas_help"), onClick = onHelp)
            Item(DesktopStrings.get("desktop_about", DesktopBrand.displayName), onClick = onAbout)
        }
    }
}

/**
 * The host's own modifier: Command on macOS, Control everywhere else. The
 * branch picks one flag, so only the host's accelerator is ever registered —
 * `KeyShortcut` would happily carry both.
 */
private fun shortcut(key: Key, shift: Boolean = false): KeyShortcut =
    if (DesktopPlatform.isMacOs) {
        KeyShortcut(key, meta = true, shift = shift)
    } else {
        KeyShortcut(key, ctrl = true, shift = shift)
    }
