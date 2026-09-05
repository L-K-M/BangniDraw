package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BlendMode
import ch.lkmc.bangnidraw.engine.core.CanvasSize
import ch.lkmc.bangnidraw.engine.core.LayerNameResolver
import ch.lkmc.bangnidraw.engine.core.Refusal

/**
 * The English strings the desktop shell shows for model values.
 *
 * `:app` reads these from `strings.xml`; the desktop shell has no resources
 * yet (AGENTS.md), so its labels live here — in one object, so the eventual
 * string migration has a single call site to replace rather than a literal
 * scattered through every panel.
 */
internal object DesktopLayerNames {

    /**
     * A stored layer name as the user should read it.
     *
     * Names are a **closed grammar**, not a prefix (AGENTS.md): only
     * `@string/layer_flattened`, `@string/layer_default N` and
     * `<name> @string/layer_copy_suffix` resolve, recursively; anything else
     * — including a user who literally typed `@string/app_name` — is shown
     * verbatim. [LayerNameResolver] owns that rule; this only supplies the
     * three strings it substitutes.
     */
    fun resolve(stored: String): String = LayerNameResolver.resolve(
        stored = stored,
        defaultName = { n -> DesktopStrings.get("layer_default_display") + " " + n },
        flattenedName = DesktopStrings.get("layer_flattened"),
        copySuffix = DesktopStrings.get("layer_copy_suffix"),
    )

    fun blendMode(mode: BlendMode): String = DesktopStrings.get(
        when (mode) {
            BlendMode.NORMAL -> "blend_normal"
            BlendMode.MULTIPLY -> "blend_multiply"
            BlendMode.SCREEN -> "blend_screen"
            BlendMode.OVERLAY -> "blend_overlay"
            BlendMode.DARKEN -> "blend_darken"
            BlendMode.LIGHTEN -> "blend_lighten"
            BlendMode.ADD -> "blend_add"
            BlendMode.DIFFERENCE -> "blend_difference"
        },
    )

    /**
     * Why an operation did nothing. [Refusal] is a value precisely so the
     * panel can say this instead of failing silently.
     */
    fun refusal(reason: Refusal, canvas: CanvasSize, layerCap: Int): String = when (reason) {
        // The cap depends on the canvas, so its message names both — the same
        // plural `:app`'s panel shows.
        Refusal.AT_CAP ->
            DesktopStrings.plural("layer_limit", layerCap, canvas.width, canvas.height, layerCap)
        Refusal.LAST_LAYER -> DesktopStrings.get("layer_only")
        Refusal.LOCKED -> DesktopStrings.get("layer_locked")
        Refusal.HIDDEN_PARTNER -> DesktopStrings.get("layer_hidden_partner")
        Refusal.NO_LAYER_BELOW -> DesktopStrings.get("layer_no_below")
        Refusal.NOOP -> DesktopStrings.get("layer_no_change")
    }
}

/**
 * A refusal the shell still has to show. [revision] rises on every reported
 * refusal, so two identical ones in a row are two distinct values and the
 * panel's hint re-appears instead of a button looking dead.
 */
internal data class DesktopRefusal(val reason: Refusal, val revision: Long)
