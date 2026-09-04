package ch.lkmc.bangnidraw.desktop

import ch.lkmc.bangnidraw.engine.core.BlendMode
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
        defaultName = { n -> "Layer $n" },
        flattenedName = "Flattened",
        copySuffix = " copy",
    )

    fun blendMode(mode: BlendMode): String = when (mode) {
        BlendMode.NORMAL -> "Normal"
        BlendMode.MULTIPLY -> "Multiply"
        BlendMode.SCREEN -> "Screen"
        BlendMode.OVERLAY -> "Overlay"
        BlendMode.DARKEN -> "Darken"
        BlendMode.LIGHTEN -> "Lighten"
        BlendMode.ADD -> "Add"
        BlendMode.DIFFERENCE -> "Difference"
    }

    /**
     * Why an operation did nothing. [Refusal] is a value precisely so the
     * panel can say this instead of failing silently.
     */
    fun refusal(reason: Refusal, layerCap: Int): String = when (reason) {
        Refusal.LAST_LAYER -> "A painting keeps at least one layer."
        Refusal.AT_CAP -> "This canvas holds at most $layerCap layers."
        Refusal.LOCKED -> "That layer is locked."
        Refusal.HIDDEN_PARTNER -> "Both layers must be visible to merge."
        Refusal.NO_LAYER_BELOW -> "There is no layer below this one."
        Refusal.NOOP -> "Nothing changed."
    }
}

/**
 * A refusal the shell still has to show. [revision] rises on every reported
 * refusal, so two identical ones in a row are two distinct values and the
 * panel's hint re-appears instead of a button looking dead.
 */
internal data class DesktopRefusal(val reason: Refusal, val revision: Long)
