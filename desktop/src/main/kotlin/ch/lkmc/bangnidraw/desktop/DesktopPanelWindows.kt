package ch.lkmc.bangnidraw.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import ch.lkmc.bangnidraw.engine.core.MixerChoice
import ch.lkmc.bangnidraw.engine.core.RgbMixer
import ch.lkmc.bangnidraw.ui.shared.BangniTypography

/**
 * The panels the shell shows outside the document window.
 *
 * They are real OS windows, not sheets: the user asked to be able to move
 * them independently of the document. `alwaysOnTop` is what makes a utility
 * window behave like one — the panel a person opened to watch while they
 * paint must not disappear behind the canvas the moment they click it.
 *
 * Each is a sibling of the canvas window rather than nested inside it, which
 * is why [DesktopShellState] hoists the state they read.
 */
@Composable
internal fun DesktopPanelWindows(
    state: DesktopShellState,
    canvas: ch.lkmc.bangnidraw.engine.core.CanvasSize,
    documentName: String,
) {
    if (state.showLayerPanel) LayerPanelWindow(state, canvas, documentName)
    if (state.showBrushPanel) ToolPanelWindow(state, documentName)
}

@Composable
private fun LayerPanelWindow(
    state: DesktopShellState,
    canvas: ch.lkmc.bangnidraw.engine.core.CanvasSize,
    documentName: String,
) {
    // Thumbnails are rendered by the GL thread only while somebody is looking
    // at them: the pass costs an isolated layer render plus two PBO reads.
    DisposableEffect(state) {
        state.engine.requestLayerThumbnails(state::publishThumbnail)
        onDispose { state.engine.stopLayerThumbnails() }
    }
    // A layer whose pixels moved needs a fresh thumbnail; the engine refreshes
    // after every structural edit, and this covers stroke commits.
    LaunchedEffect(state.stack) {
        state.engine.requestLayerThumbnails(state::publishThumbnail)
    }

    PanelWindow(
        title = DesktopStrings.get("layers_title") + " — " + documentName,
        size = DpSize(LAYER_PANEL_WIDTH, LAYER_PANEL_HEIGHT),
        onClose = { state.showLayerPanel = false },
    ) {
        DesktopLayerPanel(
            stack = state.stack,
            paperColor = state.paperColor,
            canvas = canvas,
            layerCap = state.layerCap,
            thumbnails = state.thumbnails,
            refusal = state.refusal,
            actions = state.layerActions,
        )
    }
}

/**
 * The settings of whatever the rail has selected. One window rather than six:
 * the panel is opened by clicking the active slot, so it always shows that
 * slot's tool, and a second window per tool would be six things to close.
 */
@Composable
private fun ToolPanelWindow(state: DesktopShellState, documentName: String) {
    val secondary = state.rail.secondary
    val preset = state.activeBrush
    if (secondary == null && preset == null) return

    PanelWindow(
        title = toolPanelTitle(state, documentName),
        size = DpSize(BRUSH_PANEL_WIDTH, BRUSH_PANEL_HEIGHT),
        onClose = { state.showBrushPanel = false },
    ) {
        if (secondary != null) {
            DesktopToolSettings(state)
        } else if (preset != null) {
            DesktopBrushSettings(
                preset = preset,
                catalogue = state.catalogue,
                presets = state.presets,
                // The shell resolves one mixer at startup; a build without
                // Mixbox falls back to RGB, and pigment controls hide with it.
                mixerChoice = if (state.mixer === RgbMixer) MixerChoice.RGB else MixerChoice.PIGMENT,
                onChanged = state::tune,
                onSelectPreset = state::selectPreset,
            )
        }
    }
}

/** Panels name their document: several can be open, one set of panels each. */
private fun toolPanelTitle(state: DesktopShellState, documentName: String): String {
    val secondary = state.rail.secondary
    val name = if (secondary != null) {
        secondaryLabel(secondary)
    } else {
        state.activeBrush?.let(DesktopBrushUi::label).orEmpty()
    }
    return "$name — $documentName"
}

@Composable
private fun PanelWindow(
    title: String,
    size: DpSize,
    onClose: () -> Unit,
    content: @Composable () -> Unit,
) {
    val windowState = rememberWindowState(size = size, position = WindowPosition(Alignment_PlatformDefault))
    Window(
        onCloseRequest = onClose,
        state = windowState,
        title = title,
        icon = painterResource("bangnidraw.png"),
        alwaysOnTop = true,
    ) {
        MaterialTheme(colorScheme = DesktopTheme.colorScheme, typography = BangniTypography) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                content()
            }
        }
    }
}

/** The host window manager places a new panel; we only ask for a size. */
private val Alignment_PlatformDefault = androidx.compose.ui.Alignment.Center

private val LAYER_PANEL_WIDTH = 340.dp
private val LAYER_PANEL_HEIGHT = 520.dp
private val BRUSH_PANEL_WIDTH = 360.dp
private val BRUSH_PANEL_HEIGHT = 640.dp
