package ch.lkmc.bangnidraw.engine.gl.platform

import java.nio.ByteBuffer

/**
 * The engine's asset seam: everything `engine/gl` (and the Mixbox source
 * set) reads at runtime arrives through one small interface instead of
 * Android's `AssetManager` (DESKTOP.md "Data layer — Assets").
 *
 * Paths are the asset-relative form Android has always used
 * (`"mixbox/mixbox.glsl"`); the desktop actual maps them onto classpath
 * resources rooted the same way.
 */
interface EngineAssets {

    /** Reads one whole text asset, or null when it does not exist. */
    fun readText(path: String): String?

    /**
     * Decodes one PNG as tightly-packed RGBA8888 with straight (never
     * premultiplied) alpha, or null when it does not exist or does not
     * decode. The engine's Mixbox LUT checks — square edge, probe texel,
     * tight rows — all read through [DecodedPng], so both actuals answer
     * the same questions the same way.
     */
    fun decodeRgbaPng(path: String): DecodedPng?
}

/** A decoded, engine-readable PNG. Owned by the caller for the call only. */
interface DecodedPng {
    val width: Int
    val height: Int

    /**
     * Bytes per row as stored; `width * 4` when rows are tight. Exposed so
     * callers can assert the tight-row guarantee rather than trust it.
     */
    val rowBytes: Int

    /**
     * Whether alpha is premultiplied into the color channels. Expected to
     * be `false` from [EngineAssets.decodeRgbaPng]; exposed so callers can
     * assert the straight-alpha guarantee instead of trusting it.
     */
    val premultiplied: Boolean

    /**
     * The pixel at (x, y) as a packed ARGB int with straight alpha — the
     * same shape `android.graphics.Bitmap.getPixel` returns. Throws for
     * coordinates outside the image in both actuals.
     */
    fun argbAt(x: Int, y: Int): Int

    /**
     * Copies the image into [out] as RGBA bytes, row-major from the top
     * row, exactly `width * height * 4` bytes, starting at the buffer's
     * current position. A buffer without that much remaining throws
     * rather than truncating, in both actuals.
     */
    fun copyRgbaInto(out: ByteBuffer)
}
