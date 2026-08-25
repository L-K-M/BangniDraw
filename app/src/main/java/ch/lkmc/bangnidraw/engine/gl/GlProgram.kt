package ch.lkmc.bangnidraw.engine.gl

import android.opengl.GLES30

/**
 * A compiled, linked program with its uniform locations resolved once
 * (`docs/plan/03-canvas-engine.md` §15, `Shaders` "program cache, uniform
 * locations").
 *
 * Locations are resolved at link time from the [Shaders.Source]'s own uniform
 * list — the same list `GlShaderContractTest` reads — so a rename that reaches
 * only one side fails here, loudly, at startup, instead of leaving a silent −1
 * and a uniform that never takes effect.
 *
 * Every method is GL-thread-only.
 */
class GlProgram private constructor(
    val name: String,
    val id: Int,
    private val locations: Map<String, Int>,
) {

    /** The location of [uniform]; throws for a name the source never declared. */
    fun location(uniform: String): Int = locations[uniform]
        ?: throw IllegalArgumentException("program $name has no uniform $uniform")

    fun use() {
        GLES30.glUseProgram(id)
    }

    fun uniform1i(uniform: String, v: Int) = GLES30.glUniform1i(location(uniform), v)

    fun uniform1f(uniform: String, v: Float) = GLES30.glUniform1f(location(uniform), v)

    fun uniform2f(uniform: String, x: Float, y: Float) =
        GLES30.glUniform2f(location(uniform), x, y)

    fun uniform4f(uniform: String, x: Float, y: Float, z: Float, w: Float) =
        GLES30.glUniform4f(location(uniform), x, y, z, w)

    fun uniformMatrix4(uniform: String, m: FloatArray) {
        require(m.size >= 16) { "$uniform needs 16 floats, got ${m.size}" }
        GLES30.glUniformMatrix4fv(location(uniform), 1, false, m, 0)
    }

    fun release() {
        GLES30.glDeleteProgram(id)
    }

    companion object {

        /**
         * Compiles and links [source], resolving every uniform it declares.
         *
         * A compile or link failure is fatal for the Canvas and throws
         * [GlProgramException]: the shaders are ES 3.0 baseline strings from
         * [Shaders], so a failure here is a bug worth a crash report, not a
         * device condition (§13). The driver's log is carried in the message,
         * because without it the report says only "link failed".
         */
        fun link(source: Shaders.Source): GlProgram {
            val vs = compile(GLES30.GL_VERTEX_SHADER, source.vertex, "${source.name}.vert")
            val fs = try {
                compile(GLES30.GL_FRAGMENT_SHADER, source.fragment, "${source.name}.frag")
            } catch (e: GlProgramException) {
                // The vertex shader linked into nothing yet, so nothing else
                // will ever delete it.
                GLES30.glDeleteShader(vs)
                throw e
            }
            val id = GLES30.glCreateProgram()
            GLES30.glAttachShader(id, vs)
            GLES30.glAttachShader(id, fs)
            GLES30.glLinkProgram(id)
            // Detach and delete regardless of the outcome: once linked, the
            // program owns its own copy, and on failure they are garbage.
            GLES30.glDetachShader(id, vs)
            GLES30.glDetachShader(id, fs)
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)

            val status = IntArray(1)
            GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(id)
                GLES30.glDeleteProgram(id)
                throw GlProgramException("${source.name} failed to link: $log")
            }
            GlErrors.checkAllocation("link ${source.name}")

            val locations = HashMap<String, Int>(source.uniforms.size * 2)
            for (u in source.uniforms) {
                val location = GLES30.glGetUniformLocation(id, u.name)
                if (location < 0) {
                    GLES30.glDeleteProgram(id)
                    // −1 means "declared but optimized out" as often as it
                    // means "misspelled", and both are bugs in this file's
                    // pairing of source and uniform list. Naming which is why
                    // the message says both possibilities.
                    throw GlProgramException(
                        "${source.name} has no active uniform ${u.name} (${u.type}) — " +
                            "renamed, or declared and never read",
                    )
                }
                locations[u.name] = location
            }
            return GlProgram(source.name, id, locations)
        }

        private fun compile(type: Int, source: String, what: String): Int {
            val id = GLES30.glCreateShader(type)
            GLES30.glShaderSource(id, source)
            GLES30.glCompileShader(id)
            val status = IntArray(1)
            GLES30.glGetShaderiv(id, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(id)
                GLES30.glDeleteShader(id)
                throw GlProgramException("$what failed to compile: $log")
            }
            return id
        }
    }
}
