package ch.lkmc.bangnidraw.engine.core

/**
 * The eight separable blend modes v1 ships (`docs/plan/05-layers.md` §4).
 *
 * [shaderId] is explicit and is what the GLSL `blendLayer` branches on: an
 * enum reorder must never silently swap two modes, and `GlShaderContractTest`
 * greps the shader source for every id declared here.
 */
enum class BlendMode(val shaderId: Int) {
    NORMAL(0),
    MULTIPLY(1),
    SCREEN(2),
    OVERLAY(3),
    DARKEN(4),
    LIGHTEN(5),
    ADD(6),
    DIFFERENCE(7),
    ;

    companion object {
        /** Decodes a persisted `BlendMode.name`; anything unknown is [NORMAL] (`docs/plan/06-document-and-persistence.md` §3). */
        fun fromNameOrNormal(name: String): BlendMode = entries.firstOrNull { it.name == name } ?: NORMAL

        fun fromShaderId(id: Int): BlendMode =
            entries.firstOrNull { it.shaderId == id }
                ?: throw IllegalArgumentException("no BlendMode with shaderId $id")
    }
}
