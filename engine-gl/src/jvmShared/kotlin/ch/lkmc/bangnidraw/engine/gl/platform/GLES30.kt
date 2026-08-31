package ch.lkmc.bangnidraw.engine.gl.platform

/**
 * The engine's complete GLES 3.0 surface, behind one seam
 * (`docs/plan/03-canvas-engine.md` §3, DESKTOP.md "The JVM binding").
 *
 * Every engine file calls `GLES30.glXxx` / `GLES30.GL_XXX` through this
 * object, never through the platform binding. The signatures are exactly
 * Android's overload shapes — including the `(count, array, offset)` forms
 * the engine actually uses — so the engine code compiles unchanged against
 * both actuals:
 *
 *  - Android: one-line delegations to `android.opengl.GLES30`.
 *  - Desktop: delegations to `org.lwjgl.opengles.GLES30`, adapting the
 *    array overloads (buffer views), the explicit byte sizes, and the
 *    `glGet*` return shapes.
 *
 * The surface is closed: everything here exists because some engine file
 * references it (grep `GLES30\.(gl|GL_)[A-Za-z0-9_]+`). Adding a call in
 * engine code without adding it here is a compile error, and adding it
 * here without a call site is dead API — both directions are wanted.
 */
expect object GLES30 {

    // ------------------------------------------------------------ constants

    val GL_ALREADY_SIGNALED: Int
    val GL_ARRAY_BUFFER: Int
    val GL_BLEND: Int
    val GL_CLAMP_TO_EDGE: Int
    val GL_COLOR_ATTACHMENT0: Int
    val GL_COLOR_BUFFER_BIT: Int
    val GL_COMPILE_STATUS: Int
    val GL_CONDITION_SATISFIED: Int
    val GL_DITHER: Int
    val GL_DRAW_FRAMEBUFFER: Int
    val GL_DYNAMIC_DRAW: Int
    val GL_EXTENSIONS: Int
    val GL_FALSE: Int
    val GL_FLOAT: Int
    val GL_FRAGMENT_SHADER: Int
    val GL_FRAMEBUFFER: Int
    val GL_FRAMEBUFFER_COMPLETE: Int
    val GL_FUNC_ADD: Int
    val GL_INVALID_ENUM: Int
    val GL_INVALID_FRAMEBUFFER_OPERATION: Int
    val GL_INVALID_OPERATION: Int
    val GL_INVALID_VALUE: Int
    val GL_LINEAR: Int
    val GL_LINK_STATUS: Int
    val GL_MAP_READ_BIT: Int
    val GL_MAX: Int
    val GL_MAX_ARRAY_TEXTURE_LAYERS: Int
    val GL_MAX_RENDERBUFFER_SIZE: Int
    val GL_MAX_TEXTURE_SIZE: Int
    val GL_MAX_VIEWPORT_DIMS: Int
    val GL_NEAREST: Int
    val GL_NO_ERROR: Int
    val GL_ONE: Int
    val GL_ONE_MINUS_SRC_ALPHA: Int
    val GL_OUT_OF_MEMORY: Int
    val GL_PACK_ALIGNMENT: Int
    val GL_PIXEL_PACK_BUFFER: Int
    val GL_READ_FRAMEBUFFER: Int
    val GL_RENDERER: Int
    val GL_RGBA: Int
    val GL_RGBA8: Int
    val GL_SCISSOR_TEST: Int
    val GL_STATIC_DRAW: Int
    val GL_STREAM_DRAW: Int
    val GL_STREAM_READ: Int
    val GL_SYNC_FLUSH_COMMANDS_BIT: Int
    val GL_SYNC_GPU_COMMANDS_COMPLETE: Int
    val GL_TEXTURE0: Int
    val GL_TEXTURE_2D: Int
    val GL_TEXTURE_2D_ARRAY: Int
    val GL_TEXTURE_MAG_FILTER: Int
    val GL_TEXTURE_MAX_LEVEL: Int
    val GL_TEXTURE_MIN_FILTER: Int
    val GL_TEXTURE_WRAP_S: Int
    val GL_TEXTURE_WRAP_T: Int
    val GL_TIMEOUT_EXPIRED: Int
    val GL_TRIANGLES: Int
    val GL_TRIANGLE_STRIP: Int
    val GL_TRUE: Int
    val GL_UNPACK_ALIGNMENT: Int
    val GL_UNSIGNED_BYTE: Int
    val GL_VENDOR: Int
    val GL_VERSION: Int
    val GL_VERTEX_SHADER: Int

    // ------------------------------------------------------------ functions

    fun glActiveTexture(texture: Int)

    fun glAttachShader(program: Int, shader: Int)

    fun glBindBuffer(target: Int, buffer: Int)

    fun glBindFramebuffer(target: Int, framebuffer: Int)

    fun glBindTexture(target: Int, texture: Int)

    fun glBindVertexArray(array: Int)

    fun glBlendEquation(mode: Int)

    fun glBlendFunc(sfactor: Int, dfactor: Int)

    fun glBlitFramebuffer(
        srcX0: Int,
        srcY0: Int,
        srcX1: Int,
        srcY1: Int,
        dstX0: Int,
        dstY0: Int,
        dstX1: Int,
        dstY1: Int,
        mask: Int,
        filter: Int,
    )

    fun glBufferData(target: Int, size: Int, data: java.nio.Buffer?, usage: Int)

    fun glBufferSubData(target: Int, offset: Int, size: Int, data: java.nio.Buffer)

    fun glCheckFramebufferStatus(target: Int): Int

    fun glClear(mask: Int)

    fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float)

    fun glClientWaitSync(sync: Long, flags: Int, timeoutNanos: Long): Int

    fun glColorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean)

    fun glCompileShader(shader: Int)

    fun glCreateProgram(): Int

    fun glCreateShader(type: Int): Int

    fun glDeleteBuffers(n: Int, buffers: IntArray, offset: Int)

    fun glDeleteFramebuffers(n: Int, framebuffers: IntArray, offset: Int)

    fun glDeleteProgram(program: Int)

    fun glDeleteShader(shader: Int)

    fun glDeleteSync(sync: Long)

    fun glDeleteTextures(n: Int, textures: IntArray, offset: Int)

    fun glDeleteVertexArrays(n: Int, arrays: IntArray, offset: Int)

    fun glDetachShader(program: Int, shader: Int)

    fun glDisable(cap: Int)

    fun glDrawArrays(mode: Int, first: Int, count: Int)

    fun glDrawArraysInstanced(mode: Int, first: Int, count: Int, instanceCount: Int)

    fun glEnable(cap: Int)

    fun glEnableVertexAttribArray(index: Int)

    fun glFenceSync(condition: Int, flags: Int): Long

    fun glFramebufferTexture2D(target: Int, attachment: Int, textarget: Int, texture: Int, level: Int)

    fun glFramebufferTextureLayer(target: Int, attachment: Int, texture: Int, level: Int, layer: Int)

    fun glGenBuffers(n: Int, buffers: IntArray, offset: Int)

    fun glGenFramebuffers(n: Int, framebuffers: IntArray, offset: Int)

    fun glGenTextures(n: Int, textures: IntArray, offset: Int)

    fun glGenVertexArrays(n: Int, arrays: IntArray, offset: Int)

    fun glGetError(): Int

    fun glGetIntegerv(pname: Int, params: IntArray, offset: Int)

    fun glGetProgramInfoLog(program: Int): String?

    fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int)

    fun glGetShaderInfoLog(shader: Int): String?

    fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int)

    fun glGetString(name: Int): String?

    fun glGetUniformLocation(program: Int, name: String): Int

    fun glLinkProgram(program: Int)

    fun glMapBufferRange(target: Int, offset: Int, length: Int, access: Int): java.nio.Buffer?

    fun glPixelStorei(pname: Int, param: Int)

    /** Client-memory form; the buffer's `[position, position+size)` range is read. */
    fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, pixels: java.nio.Buffer)

    /** Bound-PBO form: [offset] addresses the pixel-pack buffer. */
    fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, offset: Int)

    fun glScissor(x: Int, y: Int, width: Int, height: Int)

    fun glShaderSource(shader: Int, source: String)

    fun glTexImage2D(
        target: Int,
        level: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        border: Int,
        format: Int,
        type: Int,
        pixels: java.nio.Buffer?,
    )

    fun glTexParameteri(target: Int, pname: Int, param: Int)

    fun glTexStorage2D(target: Int, levels: Int, internalformat: Int, width: Int, height: Int)

    fun glTexStorage3D(target: Int, levels: Int, internalformat: Int, width: Int, height: Int, depth: Int)

    fun glTexSubImage3D(
        target: Int,
        level: Int,
        xoffset: Int,
        yoffset: Int,
        zoffset: Int,
        width: Int,
        height: Int,
        depth: Int,
        format: Int,
        type: Int,
        pixels: java.nio.Buffer,
    )

    fun glUniform1f(location: Int, v0: Float)

    fun glUniform1i(location: Int, v0: Int)

    fun glUniform2f(location: Int, v0: Float, v1: Float)

    fun glUniform3f(location: Int, v0: Float, v1: Float, v2: Float)

    fun glUniform4f(location: Int, v0: Float, v1: Float, v2: Float, v3: Float)

    fun glUniformMatrix4fv(location: Int, count: Int, transpose: Boolean, value: FloatArray, offset: Int)

    fun glUnmapBuffer(target: Int): Boolean

    fun glUseProgram(program: Int)

    fun glVertexAttribDivisor(index: Int, divisor: Int)

    fun glVertexAttribPointer(index: Int, size: Int, type: Int, normalized: Boolean, stride: Int, offset: Int)

    fun glViewport(x: Int, y: Int, width: Int, height: Int)
}
