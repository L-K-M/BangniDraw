package ch.lkmc.bangnidraw.engine.core

/** Runtime color-mixing choice; build-time stripping can remove pigment. */
enum class MixerChoice {
    PIGMENT,
    RGB;

    companion object {
        fun fromStored(value: String?, availability: PigmentAvailability): MixerChoice {
            val pigmentAvailable = availability == PigmentAvailability.AVAILABLE
            val parsed = entries.firstOrNull { it.name == value }
                ?: if (value == null && pigmentAvailable) PIGMENT else RGB

            return if (parsed == PIGMENT && !pigmentAvailable) RGB else parsed
        }
    }
}

enum class PigmentAvailability {
    AVAILABLE,
    ABSENT,
}

/** Applies the runtime choice without leaking build-source-set details. */
object ColorMixerResolver {
    fun resolve(choice: MixerChoice, availableMixer: ColorMixer): ColorMixer {
        if (choice == MixerChoice.PIGMENT && availableMixer.isPigment) return availableMixer

        return RgbMixer
    }
}
