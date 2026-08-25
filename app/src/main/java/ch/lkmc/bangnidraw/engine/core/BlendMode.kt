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
        /**
         * Every mode's [shaderId]. Uniqueness is asserted in `BlendModeTest`,
         * not here: a `check` in this initializer would run inside the
         * companion's `<clinit>`, and a failed class initializer poisons the
         * class — every later touch of [fromNameOrNormal] would throw
         * `NoClassDefFoundError`, breaking its documented promise to always
         * degrade to [NORMAL], with the real cause visible only in the first
         * crash. A duplicated id is a compile-time mistake over eight literal
         * constants; CI is where it should surface. The
         * explicit ids exist so an enum reorder cannot silently swap two
         * modes; a duplicated id would defeat that just as quietly, and
         * `GlShaderContractTest` cannot catch it — it checks that each
         * declared id appears in the shader, which a duplicate still does.
         * This fires the first time the class is touched instead.
         */
        private val byShaderId: Map<Int, BlendMode> = entries.associateBy(BlendMode::shaderId)

        /** Decodes a persisted `BlendMode.name`; anything unknown is [NORMAL] (`docs/plan/06-document-and-persistence.md` §3). */
        fun fromNameOrNormal(name: String): BlendMode = entries.firstOrNull { it.name == name } ?: NORMAL

        /**
         * Reverse lookup for [shaderId]. Unlike [fromNameOrNormal] this throws
         * rather than degrading: shader ids are internal constants shared with
         * the GLSL source and are never persisted or user-supplied, so an
         * unknown one is a programming error, not a corrupt file.
         */
        fun fromShaderId(id: Int): BlendMode =
            byShaderId[id] ?: throw IllegalArgumentException("no BlendMode with shaderId $id")
    }
}
