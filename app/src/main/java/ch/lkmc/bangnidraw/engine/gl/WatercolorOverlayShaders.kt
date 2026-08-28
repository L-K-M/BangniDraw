package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.WatercolorKernel
import ch.lkmc.bangnidraw.engine.core.WatercolorOverlayKernel

/** Composites transient wet state as a premultiplied sheen. */
internal object WatercolorOverlayShaders {

    private val vertex = """
        ${Shaders.VERSION_LINE}
        precision highp float;
        #define WATER_CELL_PX ${WatercolorKernel.CELL_SIZE}
        layout(location = ${Shaders.ATTR_POS}) in vec2 a_canvas;
        layout(location = ${Shaders.ATTR_UV}) in vec3 a_uvw;
        uniform vec4 u_screenBasis;
        uniform vec2 u_screenTranslation;
        uniform mat4 u_projection;
        uniform mat4 u_bufferTransform;
        out vec3 v_uvw;
        out vec2 v_canvas;

        void main() {
            vec2 p = vec2(
                u_screenBasis.x * a_canvas.x + u_screenBasis.y * a_canvas.y,
                u_screenBasis.z * a_canvas.x + u_screenBasis.w * a_canvas.y
            ) + u_screenTranslation;
            gl_Position = u_projection * u_bufferTransform * vec4(p, 0.0, 1.0);
            v_uvw = a_uvw;
            v_canvas = a_canvas * float(WATER_CELL_PX);
        }
    """.trimIndent()

    private val fragment = listOf(
        Shaders.COMPOSITE_HEAD,
        """
        precision highp int;
        #define TICK_CHANNEL_MAX ${WatercolorKernel.CHANNEL_MAX}
        #define TICK_RADIX ${WatercolorKernel.TICK_RADIX}
        #define TICK_MODULUS ${WatercolorKernel.TICK_MODULUS}
        #define DRY_TICKS ${WatercolorKernel.DRY_TICKS}
        #define OVERLAY_MAX_ALPHA ${WatercolorOverlayKernel.MAX_ALPHA}
        const vec3 CUE_COLOR = vec3(${WatercolorOverlayKernel.CUE_RED},
            ${WatercolorOverlayKernel.CUE_GREEN},
            ${WatercolorOverlayKernel.CUE_BLUE});
        uniform sampler2DArray u_tiles;
        uniform sampler2D u_backdrop;
        uniform int u_blend;
        uniform float u_opacity;
        uniform int u_taps;
        uniform vec2 u_canvasPerScreen;
        uniform int u_nowTick;
        uniform vec2 u_canvasSize;
        in vec3 v_uvw;
        in vec2 v_canvas;
        out vec4 o_color;

        bool outsideCanvas() {
            return any(lessThan(v_canvas, vec2(0.0))) ||
                any(greaterThanEqual(v_canvas, u_canvasSize));
        }

        vec4 cueAt(vec3 uvw) {
            // Decode stored tick bytes before averaging cues. Interpolating
            // the bytes themselves invents timestamps and false wetness.
            ivec2 size = textureSize(u_tiles, 0).xy;
            ivec2 texel = clamp(
                ivec2(floor(uvw.xy * vec2(size))),
                ivec2(0),
                size - ivec2(1)
            );
            int slice = int(floor(uvw.z + 0.5));
            vec4 state = texelFetch(u_tiles, ivec3(texel, slice), 0);
            ivec2 bytes = ivec2(
                floor(state.gb * float(TICK_CHANNEL_MAX) + 0.5)
            );
            int updatedTick = bytes.x * TICK_RADIX + bytes.y;
            int ageTicks =
                (u_nowTick - updatedTick + TICK_MODULUS) % TICK_MODULUS;
            float retention = clamp(
                1.0 - float(ageTicks) / float(DRY_TICKS),
                0.0,
                1.0
            );
            float water = state.r + state.a * (1.0 - state.r);
            float alpha = water * retention * OVERLAY_MAX_ALPHA;
            return vec4(CUE_COLOR * alpha, alpha);
        }

        vec4 sampleLayer() {
            if (outsideCanvas()) discard;

            int taps = clamp(u_taps, 1, MAX_TAPS);
            if (taps == 1) return cueAt(v_uvw);

            vec4 acc = vec4(0.0);
            float n = float(taps);
            for (int j = 0; j < MAX_TAPS; j++) {
                if (j >= taps) break;
                for (int i = 0; i < MAX_TAPS; i++) {
                    if (i >= taps) break;
                    vec2 offset = (
                        (vec2(float(i), float(j)) + 0.5) / n - 0.5
                    ) * u_canvasPerScreen;
                    acc += cueAt(vec3(
                        v_uvw.xy + offset / float(TILE_PX),
                        v_uvw.z
                    ));
                }
            }
            return acc / (n * n);
        }
        """.trimIndent(),
        Shaders.COMPOSITE_TAIL,
    ).joinToString("\n")

    val SOURCE = Shaders.Source(
        name = "watercolor-overlay",
        vertex = vertex,
        fragment = fragment,
        uniforms = Shaders.COMPOSITE.uniforms + listOf(
            Shaders.Uniform("u_nowTick", "int"),
            Shaders.Uniform("u_canvasSize", "vec2"),
        ),
    )
}

internal val Shaders.WATERCOLOR_OVERLAY: Shaders.Source
    get() = WatercolorOverlayShaders.SOURCE
