package ch.lkmc.bangnidraw.engine.mixbox

import ch.lkmc.bangnidraw.engine.core.LatentColorMixer
import com.scrtwpns.Mixbox

/** CPU pigment mixer backed by Mixbox 2.0. */
class MixboxMixer : LatentColorMixer {
    override val isPigment = true
    override val latentSize: Int = Mixbox.LATENT_SIZE

    override fun mix(a: Int, b: Int, t: Float): Int {
        val amount = if (t.isFinite()) t.coerceIn(0f, 1f) else 0f

        return Mixbox.lerp(a or OPAQUE_ALPHA, b or OPAQUE_ALPHA, amount) or OPAQUE_ALPHA
    }

    override fun toLatent(argb: Int, out: FloatArray) {
        require(out.size == latentSize) { "Mixbox latent output must have $latentSize components" }

        Mixbox.rgbToLatent(argb or OPAQUE_ALPHA).copyInto(out)
    }

    override fun fromLatent(latent: FloatArray): Int {
        require(latent.size == latentSize) { "Mixbox latent input must have $latentSize components" }

        return Mixbox.latentToRgb(latent) or OPAQUE_ALPHA
    }

    private companion object {
        const val OPAQUE_ALPHA = 0xFF shl 24
    }
}
