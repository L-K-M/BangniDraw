package ch.lkmc.bangnidraw.engine.gl

import ch.lkmc.bangnidraw.engine.core.DabStamp
import ch.lkmc.bangnidraw.engine.core.PerfConstants.TILE_SIZE
import ch.lkmc.bangnidraw.engine.core.SmudgeKernel
import ch.lkmc.bangnidraw.engine.core.TipShape
import ch.lkmc.bangnidraw.engine.core.WatercolorKernel
import ch.lkmc.bangnidraw.engine.core.WatercolorDabPlan

/** GLES 3.0 fragment passes for proposal 0002's coarse wet medium. */
internal object WatercolorShaders {

    private val mask = """
        float waterDistance(vec2 canvas, vec4 dab, vec2 tip) {
            vec2 delta = canvas - dab.xy;
            float c = cos(tip.x);
            float s = sin(tip.x);
            vec2 major = vec2(c, s);
            vec2 minor = vec2(-s, c);
            return length(vec2(
                dot(delta, major),
                dot(delta, minor) / max(tip.y, ${DabStamp.GRADIENT_EPSILON})
            ));
        }

        float waterMask(vec2 canvas, vec4 dab, vec2 tip) {
            float d = waterDistance(canvas, dab, tip);
            float feather = max(fwidth(d), ${DabStamp.GRADIENT_EPSILON});
            float inner = clamp(min(dab.z * dab.w, dab.z - feather), 0.0, dab.z);
            return 1.0 - smoothstep(inner, dab.z, d);
        }
    """.trimIndent().prependIndent("        ")

    private val sampling = """
        vec2 clampWaterUv(vec2 uv, vec2 texel, vec2 logicalScale) {
            vec2 halfTexel = texel * 0.5;
            return clamp(uv, halfTexel, logicalScale - halfTexel);
        }
    """.trimIndent().prependIndent("        ")

    private val wetFragment = """
        ${Shaders.VERSION_LINE}
        precision highp float;
        precision highp sampler2D;
        precision highp int;
        #define WATER_CELL_PX ${WatercolorKernel.CELL_SIZE}
        #define MAX_WATER_SPREAD_PX ${WatercolorDabPlan.MAX_SPREAD_PX}
        #define MAX_WATER_DIFFUSION ${WatercolorKernel.MAX_DIFFUSION}
        #define WATER_ABSORPTION ${WatercolorKernel.ABSORPTION_PER_STEP}
        #define PAPER_ABSORPTION_MIN ${WatercolorKernel.PAPER_ABSORPTION_MIN}
        #define PAPER_ABSORPTION_RANGE ${WatercolorKernel.PAPER_ABSORPTION_RANGE}
        #define PAPER_CAPACITY_MIN ${WatercolorKernel.PAPER_CAPACITY_MIN}
        #define PAPER_CAPACITY_RANGE ${WatercolorKernel.PAPER_CAPACITY_RANGE}
        #define SPREAD_RADIUS_FRACTION ${WatercolorDabPlan.SPREAD_RADIUS_FRACTION}
        #define TICK_CHANNEL_MAX ${WatercolorKernel.CHANNEL_MAX}
        #define TICK_RADIX ${WatercolorKernel.TICK_RADIX}
        #define TICK_MODULUS ${WatercolorKernel.TICK_MODULUS}
        #define DRY_TICKS ${WatercolorKernel.DRY_TICKS}
        uniform sampler2D u_before;
        uniform vec2 u_wetOrigin;
        uniform vec2 u_wetTexel;
        uniform vec2 u_wetScale;
        uniform vec4 u_dab;
        uniform vec2 u_tip;
        uniform float u_waterLoad;
        uniform float u_spread;
        uniform float u_nowTick;
        uniform bool u_ageOnly;
        uniform bool u_epochRollover;
        in vec2 v_uv;
        out vec4 o_color;

        $mask
        $sampling

        float proceduralPaper(vec2 canvas) {
            uvec2 cell = uvec2(floor(max(canvas, vec2(0.0))));
            uint h = cell.x * ${DabStamp.GRAIN_HASH_X}u + cell.y * ${DabStamp.GRAIN_HASH_Y}u;
            h = h ^ (h >> ${DabStamp.GRAIN_HASH_SHIFT}u);
            return float(h & ${DabStamp.GRAIN_HASH_MASK}u) / float(${DabStamp.GRAIN_HASH_MASK});
        }

        float decodeTick(vec4 state) {
            float high = floor(state.g * float(TICK_CHANNEL_MAX) + 0.5);
            float low = floor(state.b * float(TICK_CHANNEL_MAX) + 0.5);
            return high * float(TICK_RADIX) + low;
        }

        vec2 encodeTick(float tick) {
            float wrapped = mod(tick, float(TICK_MODULUS));
            float high = floor(wrapped / float(TICK_RADIX));
            float low = wrapped - high * float(TICK_RADIX);
            return vec2(high, low) / float(TICK_CHANNEL_MAX);
        }

        float ageFactor(vec4 state) {
            float updatedTick = decodeTick(state);
            float age = u_epochRollover && updatedTick <= u_nowTick
                ? float(DRY_TICKS)
                : mod(
                    u_nowTick - updatedTick + float(TICK_MODULUS),
                    float(TICK_MODULUS)
                );
            return clamp(1.0 - age / float(DRY_TICKS), 0.0, 1.0);
        }

        vec4 sampleState(vec2 uv) {
            return texture(u_before, clampWaterUv(uv, u_wetTexel, u_wetScale));
        }

        float sampleWet(vec2 uv) {
            vec4 state = sampleState(uv);
            return state.r * ageFactor(state);
        }

        float wetCoverageMask(vec2 canvas, vec4 dab, vec2 tip) {
            float halfCell = float(WATER_CELL_PX) * 0.5;
            float aspect = max(tip.y, ${TipShape.Flat.MIN_ASPECT});
            float cellReach = halfCell * sqrt(2.0) / aspect;
            return waterMask(canvas, vec4(dab.xy, dab.z + cellReach, dab.w), tip);
        }

        float suppliedWet(vec2 uv, vec2 canvas) {
            float wet = sampleWet(uv);
            float source = clamp(
                u_waterLoad * wetCoverageMask(canvas, u_dab, u_tip),
                0.0,
                1.0
            );
            return wet + source * (1.0 - wet);
        }

        void main() {
            vec2 wetPixel = u_wetOrigin + v_uv * (u_wetScale / u_wetTexel);
            vec2 canvas = wetPixel * float(WATER_CELL_PX);
            vec2 wetUv = v_uv * u_wetScale;
            vec4 previous = sampleState(wetUv);
            vec2 stamp = encodeTick(u_nowTick);
            if (u_ageOnly) {
                float age = ageFactor(previous);
                o_color = vec4(
                    previous.r * age, stamp.x, stamp.y, previous.a * age
                );
                return;
            }

            float cell = float(WATER_CELL_PX);
            float center = suppliedWet(wetUv, canvas);
            float average = (
                suppliedWet(wetUv + vec2(u_wetTexel.x, 0.0),
                    canvas + vec2(cell, 0.0)
                ) +
                suppliedWet(wetUv - vec2(u_wetTexel.x, 0.0),
                    canvas - vec2(cell, 0.0)
                ) +
                suppliedWet(wetUv + vec2(0.0, u_wetTexel.y),
                    canvas + vec2(0.0, cell)
                ) +
                suppliedWet(wetUv - vec2(0.0, u_wetTexel.y),
                    canvas - vec2(0.0, cell)
                )
            ) * 0.25;
            float spreadPx = min(u_dab.z * u_spread * SPREAD_RADIUS_FRACTION, float(MAX_WATER_SPREAD_PX));
            float flowRadius = u_dab.z + spreadPx;
            float flowMask = wetCoverageMask(
                canvas, vec4(u_dab.xy, flowRadius, u_dab.w), u_tip
            );
            float diffusion = 4.0 * MAX_WATER_DIFFUSION * u_spread * flowMask;
            float wet = clamp(center + diffusion * (average - center), 0.0, 1.0);

            float saturation = previous.a * ageFactor(previous);
            float paperPocket = 1.0 - proceduralPaper(canvas);
            float paperCapacity = PAPER_CAPACITY_MIN + PAPER_CAPACITY_RANGE * paperPocket;
            float paperAbsorption = PAPER_ABSORPTION_MIN + PAPER_ABSORPTION_RANGE * paperPocket;
            float absorbed = min(
                wet,
                WATER_ABSORPTION * paperAbsorption * paperCapacity *
                    (1.0 - saturation) * flowMask
            );
            wet -= absorbed;
            saturation = clamp(saturation + absorbed, 0.0, 1.0);

            o_color = vec4(wet, stamp.x, stamp.y, saturation);
        }
    """.trimIndent()

    val WET = Shaders.Source(
        name = "watercolor-wet",
        vertex = Shaders.TILE_VERT,
        fragment = wetFragment,
        uniforms = listOf(
            Shaders.Uniform("u_before", "sampler2D"),
            Shaders.Uniform("u_wetOrigin", "vec2"),
            Shaders.Uniform("u_wetTexel", "vec2"),
            Shaders.Uniform("u_wetScale", "vec2"),
            Shaders.Uniform("u_dab", "vec4"),
            Shaders.Uniform("u_tip", "vec2"),
            Shaders.Uniform("u_waterLoad", "float"),
            Shaders.Uniform("u_spread", "float"),
            Shaders.Uniform("u_nowTick", "float"),
            Shaders.Uniform("u_ageOnly", "bool"),
            Shaders.Uniform("u_epochRollover", "bool"),
        ),
    )

    private val colorFragment = """
        ${Shaders.VERSION_LINE}
        precision highp float;
        precision highp sampler2D;
        precision highp int;
        #define MIXLERP mix
        #define WATER_CELL_PX ${WatercolorKernel.CELL_SIZE}
        #define MAX_WATER_SPREAD_PX ${WatercolorDabPlan.MAX_SPREAD_PX}
        #define PIGMENT_DEPOSIT 1
        #define SPREAD_RADIUS_FRACTION ${WatercolorDabPlan.SPREAD_RADIUS_FRACTION}
        #define ABSORBED_FLOW_WEIGHT ${WatercolorKernel.ABSORBED_FLOW_WEIGHT}
        #define PAPER_MOBILITY_MIN ${WatercolorKernel.PAPER_MOBILITY_MIN}
        #define PAPER_MOBILITY_RANGE ${WatercolorKernel.PAPER_MOBILITY_RANGE}
        #define RIM_INNER_RADIUS ${WatercolorKernel.RIM_INNER_RADIUS}
        #define RIM_OUTER_RADIUS ${WatercolorKernel.RIM_OUTER_RADIUS}
        #define RIM_DEPOSIT_GAIN ${WatercolorKernel.RIM_DEPOSIT_GAIN}
        uniform sampler2D u_before;
        uniform sampler2D u_wet;
        uniform vec2 u_tileOrigin;
        uniform vec2 u_scratchOrigin;
        uniform vec2 u_beforeTexel;
        uniform vec2 u_beforeScale;
        uniform vec2 u_wetOrigin;
        uniform vec2 u_wetTexel;
        uniform vec2 u_wetScale;
        uniform vec4 u_dab;
        uniform vec2 u_tip;
        uniform vec3 u_color;
        uniform float u_strength;
        uniform float u_spread;
        uniform float u_granulation;
        uniform float u_edgeDarkening;
        uniform float u_dilution;
        uniform int u_depositMode;
        uniform bool u_alphaLock;
        in vec2 v_uv;
        out vec4 o_color;

        $mask
        $sampling

        float proceduralPaper(vec2 canvas) {
            uvec2 cell = uvec2(floor(max(canvas, vec2(0.0))));
            uint h = cell.x * ${DabStamp.GRAIN_HASH_X}u + cell.y * ${DabStamp.GRAIN_HASH_Y}u;
            h = h ^ (h >> ${DabStamp.GRAIN_HASH_SHIFT}u);
            return float(h & ${DabStamp.GRAIN_HASH_MASK}u) / float(${DabStamp.GRAIN_HASH_MASK});
        }

        vec4 sampleColor(vec2 canvas) {
            vec2 uv = (canvas - u_scratchOrigin) * u_beforeTexel;
            uv = clampWaterUv(uv, u_beforeTexel, u_beforeScale);
            return texture(u_before, uv);
        }

        vec4 mixPigment(vec4 center, vec4 average, float t) {
            float alpha = mix(center.a, average.a, t);
            if (alpha <= ${SmudgeKernel.ALPHA_EPSILON}) return vec4(0.0);
            if (center.a <= ${SmudgeKernel.ALPHA_EPSILON}) {
                return vec4(average.rgb / max(average.a, ${SmudgeKernel.ALPHA_EPSILON}) * alpha, alpha);
            }
            if (average.a <= ${SmudgeKernel.ALPHA_EPSILON}) {
                return vec4(center.rgb / center.a * alpha, alpha);
            }
            vec3 cCenter = center.rgb / center.a;
            vec3 cAverage = average.rgb / average.a;
            vec3 mixed = MIXLERP(cCenter, cAverage, clamp(t, 0.0, 1.0));
            return vec4(mixed * alpha, alpha);
        }

        vec4 depositPigment(vec4 layer, float sourceAlpha) {
            if (sourceAlpha <= 0.0) return layer;
            float alpha = sourceAlpha + layer.a * (1.0 - sourceAlpha);
            float t = alpha > 0.0 ? sourceAlpha / alpha : 0.0;
            if (layer.a > 0.0) t *= 1.0 - u_dilution;
            if (u_alphaLock) {
                if (layer.a <= 0.0) return layer;
                t = sourceAlpha;
                alpha = layer.a;
            }
            if (layer.a <= ${SmudgeKernel.ALPHA_EPSILON} && !u_alphaLock) {
                return vec4(u_color * sourceAlpha, sourceAlpha);
            }
            vec3 current = layer.rgb / layer.a;
            vec3 mixed = MIXLERP(current, u_color, clamp(t, 0.0, 1.0));
            return vec4(mixed * alpha, alpha);
        }

        void main() {
            vec2 canvas = u_tileOrigin + v_uv * float($TILE_SIZE);
            vec4 center = sampleColor(canvas);
            vec4 average = (
                sampleColor(canvas + vec2(1.0, 0.0)) +
                sampleColor(canvas - vec2(1.0, 0.0)) +
                sampleColor(canvas + vec2(0.0, 1.0)) +
                sampleColor(canvas - vec2(0.0, 1.0))
            ) * 0.25;
            vec2 wetUv = (canvas / float(WATER_CELL_PX) - u_wetOrigin) * u_wetTexel;
            wetUv = clampWaterUv(wetUv, u_wetTexel, u_wetScale);
            vec4 wetState = texture(u_wet, wetUv);
            float spreadPx = min(u_dab.z * u_spread * SPREAD_RADIUS_FRACTION, float(MAX_WATER_SPREAD_PX));
            float flowRadius = u_dab.z + spreadPx;
            float flowMask = waterMask(canvas, vec4(u_dab.xy, flowRadius, u_dab.w), u_tip);
            float paper = mix(1.0, PAPER_MOBILITY_MIN + PAPER_MOBILITY_RANGE * proceduralPaper(canvas), u_granulation);
            float t = min(${WatercolorKernel.MAX_DIFFUSION},
                (wetState.r + wetState.a * ABSORBED_FLOW_WEIGHT) * u_spread * flowMask * paper);
            vec4 flowed = mixPigment(center, average, t);

            if (u_alphaLock) {
                vec3 straight = flowed.a > ${SmudgeKernel.ALPHA_EPSILON}
                    ? flowed.rgb / flowed.a : vec3(0.0);
                flowed = vec4(straight * center.a, center.a);
            }

            float dabMask = waterMask(canvas, u_dab, u_tip);
            float normalizedRadius = waterDistance(canvas, u_dab, u_tip) / max(u_dab.z, 1.0);
            float rim = smoothstep(RIM_INNER_RADIUS, RIM_OUTER_RADIUS, normalizedRadius) * dabMask;
            float deposit = clamp(
                u_strength * dabMask * paper * (1.0 + RIM_DEPOSIT_GAIN * u_edgeDarkening * rim),
                0.0,
                1.0
            );
            vec4 result = u_depositMode == PIGMENT_DEPOSIT
                ? depositPigment(flowed, deposit) : flowed;
            float alpha = clamp(result.a, 0.0, 1.0);
            vec3 rgb = clamp(result.rgb, vec3(0.0), vec3(1.0));
            rgb = min(rgb, vec3(alpha));
            o_color = vec4(rgb, alpha);
        }
    """.trimIndent()

    val COLOR = Shaders.Source(
        name = "watercolor-color",
        vertex = Shaders.TILE_VERT,
        fragment = colorFragment,
        uniforms = listOf(
            Shaders.Uniform("u_before", "sampler2D"),
            Shaders.Uniform("u_wet", "sampler2D"),
            Shaders.Uniform("u_tileOrigin", "vec2"),
            Shaders.Uniform("u_scratchOrigin", "vec2"),
            Shaders.Uniform("u_beforeTexel", "vec2"),
            Shaders.Uniform("u_beforeScale", "vec2"),
            Shaders.Uniform("u_wetOrigin", "vec2"),
            Shaders.Uniform("u_wetTexel", "vec2"),
            Shaders.Uniform("u_wetScale", "vec2"),
            Shaders.Uniform("u_dab", "vec4"),
            Shaders.Uniform("u_tip", "vec2"),
            Shaders.Uniform("u_color", "vec3"),
            Shaders.Uniform("u_strength", "float"),
            Shaders.Uniform("u_spread", "float"),
            Shaders.Uniform("u_granulation", "float"),
            Shaders.Uniform("u_edgeDarkening", "float"),
            Shaders.Uniform("u_dilution", "float"),
            Shaders.Uniform("u_depositMode", "int"),
            Shaders.Uniform("u_alphaLock", "bool"),
        ),
    )
}

internal val Shaders.WATERCOLOR_WET: Shaders.Source
    get() = WatercolorShaders.WET

internal val Shaders.WATERCOLOR_COLOR: Shaders.Source
    get() = WatercolorShaders.COLOR

