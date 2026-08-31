package ch.lkmc.bangnidraw.engine.core

enum class TouchDrawingMode {
    ENABLED,
    STYLUS_ONLY;

    companion object {
        fun fromStored(value: String?): TouchDrawingMode =
            entries.firstOrNull { it.name == value } ?: ENABLED
    }
}

enum class HapticsMode {
    ENABLED,
    DISABLED;

    companion object {
        fun fromStored(value: String?): HapticsMode =
            entries.firstOrNull { it.name == value } ?: ENABLED
    }
}
