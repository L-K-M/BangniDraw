package ch.lkmc.bangnidraw.engine.gl.platform

import org.lwjgl.opengles.GLES30 as LwjglGles30
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * The desktop actual: delegations to LWJGL's GLES30 bindings
 * (DESKTOP.md "The JVM binding — with a mandatory (thin) adapter").
 *
 * LWJGL has no `(count, array, offset)` overloads, derives every buffer
 * transfer's length from `[position, limit)`, and answers `glGet*` with
 * return values instead of out-parameters. The adapters below bridge those
 * three differences while honoring the facade's Android semantics exactly:
 *
 *  - Array forms become `IntBuffer.wrap(array, offset, count)` views —
 *    LWJGL then reads or writes exactly `count` elements.
 *  - Explicit byte sizes (`glBufferData`, `glBufferSubData`) become a
 *    temporary `limit` pinned to `position + size`, restored after the
 *    call. LWJGL would otherwise transfer the whole remaining range,
 *    which the Android call never does (`uploadInstances` deliberately
 *    uploads `n` dabs of a larger staging buffer).
 *  - `glVertexAttribPointer`'s offset widens to LWJGL's `long`.
 *
 * LWJGL binds lazily against the context's EGL library; nothing here may
 * touch the class until a context is current on this thread.
 */
actual object GLES30 {

    actual val GL_ALREADY_SIGNALED: Int get() = LwjglGles30.GL_ALREADY_SIGNALED
    actual val GL_ARRAY_BUFFER: Int get() = LwjglGles30.GL_ARRAY_BUFFER
    actual val GL_BLEND: Int get() = LwjglGles30.GL_BLEND
    actual val GL_CLAMP_TO_EDGE: Int get() = LwjglGles30.GL_CLAMP_TO_EDGE
    actual val GL_COLOR_ATTACHMENT0: Int get() = LwjglGles30.GL_COLOR_ATTACHMENT0
    actual val GL_COLOR_BUFFER_BIT: Int get() = LwjglGles30.GL_COLOR_BUFFER_BIT
    actual val GL_COMPILE_STATUS: Int get() = LwjglGles30.GL_COMPILE_STATUS
    actual val GL_CONDITION_SATISFIED: Int get() = LwjglGles30.GL_CONDITION_SATISFIED
    actual val GL_DITHER: Int get() = LwjglGles30.GL_DITHER
    actual val GL_DRAW_FRAMEBUFFER: Int get() = LwjglGles30.GL_DRAW_FRAMEBUFFER
    actual val GL_DYNAMIC_DRAW: Int get() = LwjglGles30.GL_DYNAMIC_DRAW
    actual val GL_EXTENSIONS: Int get() = LwjglGles30.GL_EXTENSIONS
    actual val GL_FALSE: Int get() = LwjglGles30.GL_FALSE
    actual val GL_FLOAT: Int get() = LwjglGles30.GL_FLOAT
    actual val GL_FRAGMENT_SHADER: Int get() = LwjglGles30.GL_FRAGMENT_SHADER
    actual val GL_FRAMEBUFFER: Int get() = LwjglGles30.GL_FRAMEBUFFER
    actual val GL_FRAMEBUFFER_COMPLETE: Int get() = LwjglGles30.GL_FRAMEBUFFER_COMPLETE
    actual val GL_FUNC_ADD: Int get() = LwjglGles30.GL_FUNC_ADD
    actual val GL_INVALID_ENUM: Int get() = LwjglGles30.GL_INVALID_ENUM
    actual val GL_INVALID_FRAMEBUFFER_OPERATION: Int get() = LwjglGles30.GL_INVALID_FRAMEBUFFER_OPERATION
    actual val GL_INVALID_OPERATION: Int get() = LwjglGles30.GL_INVALID_OPERATION
    actual val GL_INVALID_VALUE: Int get() = LwjglGles30.GL_INVALID_VALUE
    actual val GL_LINEAR: Int get() = LwjglGles30.GL_LINEAR
    actual val GL_LINK_STATUS: Int get() = LwjglGles30.GL_LINK_STATUS
    actual val GL_MAP_READ_BIT: Int get() = LwjglGles30.GL_MAP_READ_BIT
    actual val GL_MAX: Int get() = LwjglGles30.GL_MAX
    actual val GL_MAX_ARRAY_TEXTURE_LAYERS: Int get() = LwjglGles30.GL_MAX_ARRAY_TEXTURE_LAYERS
    actual val GL_MAX_RENDERBUFFER_SIZE: Int get() = LwjglGles30.GL_MAX_RENDERBUFFER_SIZE
    actual val GL_MAX_TEXTURE_SIZE: Int get() = LwjglGles30.GL_MAX_TEXTURE_SIZE
    actual val GL_MAX_VIEWPORT_DIMS: Int get() = LwjglGles30.GL_MAX_VIEWPORT_DIMS
    actual val GL_NEAREST: Int get() = LwjglGles30.GL_NEAREST
    actual val GL_NO_ERROR: Int get() = LwjglGles30.GL_NO_ERROR
    actual val GL_ONE: Int get() = LwjglGles30.GL_ONE
    actual val GL_ONE_MINUS_SRC_ALPHA: Int get() = LwjglGles30.GL_ONE_MINUS_SRC_ALPHA
    actual val GL_OUT_OF_MEMORY: Int get() = LwjglGles30.GL_OUT_OF_MEMORY
    actual val GL_PACK_ALIGNMENT: Int get() = LwjglGles30.GL_PACK_ALIGNMENT
    actual val GL_PIXEL_PACK_BUFFER: Int get() = LwjglGles30.GL_PIXEL_PACK_BUFFER
    actual val GL_READ_FRAMEBUFFER: Int get() = LwjglGles30.GL_READ_FRAMEBUFFER
    actual val GL_RENDERER: Int get() = LwjglGles30.GL_RENDERER
    actual val GL_RGBA: Int get() = LwjglGles30.GL_RGBA
    actual val GL_RGBA8: Int get() = LwjglGles30.GL_RGBA8
    actual val GL_SCISSOR_TEST: Int get() = LwjglGles30.GL_SCISSOR_TEST
    actual val GL_STATIC_DRAW: Int get() = LwjglGles30.GL_STATIC_DRAW
    actual val GL_STREAM_DRAW: Int get() = LwjglGles30.GL_STREAM_DRAW
    actual val GL_STREAM_READ: Int get() = LwjglGles30.GL_STREAM_READ
    actual val GL_SYNC_FLUSH_COMMANDS_BIT: Int get() = LwjglGles30.GL_SYNC_FLUSH_COMMANDS_BIT
    actual val GL_SYNC_GPU_COMMANDS_COMPLETE: Int get() = LwjglGles30.GL_SYNC_GPU_COMMANDS_COMPLETE
    actual val GL_TEXTURE0: Int get() = LwjglGles30.GL_TEXTURE0
    actual val GL_TEXTURE_2D: Int get() = LwjglGles30.GL_TEXTURE_2D
    actual val GL_TEXTURE_2D_ARRAY: Int get() = LwjglGles30.GL_TEXTURE_2D_ARRAY
    actual val GL_TEXTURE_MAG_FILTER: Int get() = LwjglGles30.GL_TEXTURE_MAG_FILTER
    actual val GL_TEXTURE_MAX_LEVEL: Int get() = LwjglGles30.GL_TEXTURE_MAX_LEVEL
    actual val GL_TEXTURE_MIN_FILTER: Int get() = LwjglGles30.GL_TEXTURE_MIN_FILTER
    actual val GL_TEXTURE_WRAP_S: Int get() = LwjglGles30.GL_TEXTURE_WRAP_S
    actual val GL_TEXTURE_WRAP_T: Int get() = LwjglGles30.GL_TEXTURE_WRAP_T
    actual val GL_TIMEOUT_EXPIRED: Int get() = LwjglGles30.GL_TIMEOUT_EXPIRED
    actual val GL_TRIANGLES: Int get() = LwjglGles30.GL_TRIANGLES
    actual val GL_TRIANGLE_STRIP: Int get() = LwjglGles30.GL_TRIANGLE_STRIP
    actual val GL_TRUE: Int get() = LwjglGles30.GL_TRUE
    actual val GL_UNPACK_ALIGNMENT: Int get() = LwjglGles30.GL_UNPACK_ALIGNMENT
    actual val GL_UNSIGNED_BYTE: Int get() = LwjglGles30.GL_UNSIGNED_BYTE
    actual val GL_VENDOR: Int get() = LwjglGles30.GL_VENDOR
    actual val GL_VERSION: Int get() = LwjglGles30.GL_VERSION
    actual val GL_VERTEX_SHADER: Int get() = LwjglGles30.GL_VERTEX_SHADER

    actual fun glActiveTexture(texture: Int) = LwjglGles30.glActiveTexture(texture)

    actual fun glAttachShader(program: Int, shader: Int) = LwjglGles30.glAttachShader(program, shader)

    actual fun glBindBuffer(target: Int, buffer: Int) = LwjglGles30.glBindBuffer(target, buffer)

    actual fun glBindFramebuffer(target: Int, framebuffer: Int) =
        LwjglGles30.glBindFramebuffer(target, framebuffer)

    actual fun glBindTexture(target: Int, texture: Int) = LwjglGles30.glBindTexture(target, texture)

    actual fun glBindVertexArray(array: Int) = LwjglGles30.glBindVertexArray(array)

    actual fun glBlendEquation(mode: Int) = LwjglGles30.glBlendEquation(mode)

    actual fun glBlendFunc(sfactor: Int, dfactor: Int) = LwjglGles30.glBlendFunc(sfactor, dfactor)

    actual fun glBlitFramebuffer(
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
    ) = LwjglGles30.glBlitFramebuffer(
        srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter,
    )

    actual fun glBufferData(target: Int, size: Int, data: java.nio.Buffer?, usage: Int) {
        when (data) {
            // LWJGL splits the null case into a size-only overload; the
            // buffer forms derive size from [position, limit), which the
            // pinned limit makes equal to `size`.
            null -> LwjglGles30.glBufferData(target, size.toLong(), usage)
            is ByteBuffer -> withByteLimit(data, size) { b ->
                LwjglGles30.glBufferData(target, b, usage)
            }
            is FloatBuffer -> withFloatLimit(data, size) { f ->
                LwjglGles30.glBufferData(target, f, usage)
            }
            else -> throw IllegalArgumentException("unsupported buffer type: ${data::class.java}")
        }
    }

    actual fun glBufferSubData(target: Int, offset: Int, size: Int, data: java.nio.Buffer) {
        when (data) {
            is ByteBuffer -> withByteLimit(data, size) { b ->
                LwjglGles30.glBufferSubData(target, offset.toLong(), b)
            }
            is FloatBuffer -> withFloatLimit(data, size) { f ->
                LwjglGles30.glBufferSubData(target, offset.toLong(), f)
            }
            else -> throw IllegalArgumentException("unsupported buffer type: ${data::class.java}")
        }
    }

    actual fun glCheckFramebufferStatus(target: Int): Int =
        LwjglGles30.glCheckFramebufferStatus(target)

    actual fun glClear(mask: Int) = LwjglGles30.glClear(mask)

    actual fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) =
        LwjglGles30.glClearColor(red, green, blue, alpha)

    actual fun glClientWaitSync(sync: Long, flags: Int, timeoutNanos: Long): Int =
        LwjglGles30.glClientWaitSync(sync, flags, timeoutNanos)

    actual fun glColorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) =
        LwjglGles30.glColorMask(red, green, blue, alpha)

    actual fun glCompileShader(shader: Int) = LwjglGles30.glCompileShader(shader)

    actual fun glCreateProgram(): Int = LwjglGles30.glCreateProgram()

    actual fun glCreateShader(type: Int): Int = LwjglGles30.glCreateShader(type)

    actual fun glDeleteBuffers(n: Int, buffers: IntArray, offset: Int) =
        LwjglGles30.glDeleteBuffers(IntBuffer.wrap(buffers, offset, n))

    actual fun glDeleteFramebuffers(n: Int, framebuffers: IntArray, offset: Int) =
        LwjglGles30.glDeleteFramebuffers(IntBuffer.wrap(framebuffers, offset, n))

    actual fun glDeleteProgram(program: Int) = LwjglGles30.glDeleteProgram(program)

    actual fun glDeleteShader(shader: Int) = LwjglGles30.glDeleteShader(shader)

    actual fun glDeleteSync(sync: Long) = LwjglGles30.glDeleteSync(sync)

    actual fun glDeleteTextures(n: Int, textures: IntArray, offset: Int) =
        LwjglGles30.glDeleteTextures(IntBuffer.wrap(textures, offset, n))

    actual fun glDeleteVertexArrays(n: Int, arrays: IntArray, offset: Int) =
        LwjglGles30.glDeleteVertexArrays(IntBuffer.wrap(arrays, offset, n))

    actual fun glDetachShader(program: Int, shader: Int) = LwjglGles30.glDetachShader(program, shader)

    actual fun glDisable(cap: Int) = LwjglGles30.glDisable(cap)

    actual fun glDrawArrays(mode: Int, first: Int, count: Int) =
        LwjglGles30.glDrawArrays(mode, first, count)

    actual fun glDrawArraysInstanced(mode: Int, first: Int, count: Int, instanceCount: Int) =
        LwjglGles30.glDrawArraysInstanced(mode, first, count, instanceCount)

    actual fun glEnable(cap: Int) = LwjglGles30.glEnable(cap)

    actual fun glEnableVertexAttribArray(index: Int) = LwjglGles30.glEnableVertexAttribArray(index)

    actual fun glFenceSync(condition: Int, flags: Int): Long =
        LwjglGles30.glFenceSync(condition, flags)

    actual fun glFramebufferTexture2D(
        target: Int,
        attachment: Int,
        textarget: Int,
        texture: Int,
        level: Int,
    ) = LwjglGles30.glFramebufferTexture2D(target, attachment, textarget, texture, level)

    actual fun glFramebufferTextureLayer(
        target: Int,
        attachment: Int,
        texture: Int,
        level: Int,
        layer: Int,
    ) = LwjglGles30.glFramebufferTextureLayer(target, attachment, texture, level, layer)

    actual fun glGenBuffers(n: Int, buffers: IntArray, offset: Int) =
        LwjglGles30.glGenBuffers(IntBuffer.wrap(buffers, offset, n))

    actual fun glGenFramebuffers(n: Int, framebuffers: IntArray, offset: Int) =
        LwjglGles30.glGenFramebuffers(IntBuffer.wrap(framebuffers, offset, n))

    actual fun glGenTextures(n: Int, textures: IntArray, offset: Int) =
        LwjglGles30.glGenTextures(IntBuffer.wrap(textures, offset, n))

    actual fun glGenVertexArrays(n: Int, arrays: IntArray, offset: Int) =
        LwjglGles30.glGenVertexArrays(IntBuffer.wrap(arrays, offset, n))

    actual fun glGetError(): Int = LwjglGles30.glGetError()

    actual fun glGetIntegerv(pname: Int, params: IntArray, offset: Int) =
        LwjglGles30.glGetIntegerv(pname, IntBuffer.wrap(params, offset, params.size - offset))

    actual fun glGetProgramInfoLog(program: Int): String? =
        LwjglGles30.glGetProgramInfoLog(program)

    actual fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) {
        val view = IntBuffer.wrap(params, offset, params.size - offset)
        LwjglGles30.glGetProgramiv(program, pname, view)
    }

    actual fun glGetShaderInfoLog(shader: Int): String? =
        LwjglGles30.glGetShaderInfoLog(shader)

    actual fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) {
        val view = IntBuffer.wrap(params, offset, params.size - offset)
        LwjglGles30.glGetShaderiv(shader, pname, view)
    }

    actual fun glGetString(name: Int): String? = LwjglGles30.glGetString(name)

    actual fun glGetUniformLocation(program: Int, name: String): Int =
        LwjglGles30.glGetUniformLocation(program, name)

    actual fun glLinkProgram(program: Int) = LwjglGles30.glLinkProgram(program)

    actual fun glMapBufferRange(target: Int, offset: Int, length: Int, access: Int): java.nio.Buffer? =
        LwjglGles30.glMapBufferRange(target, offset.toLong(), length.toLong(), access)

    actual fun glPixelStorei(pname: Int, param: Int) = LwjglGles30.glPixelStorei(pname, param)

    actual fun glReadPixels(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: java.nio.Buffer,
    ) = LwjglGles30.glReadPixels(x, y, width, height, format, type, pixels as ByteBuffer)

    actual fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, offset: Int) =
        LwjglGles30.glReadPixels(x, y, width, height, format, type, offset.toLong())

    actual fun glScissor(x: Int, y: Int, width: Int, height: Int) =
        LwjglGles30.glScissor(x, y, width, height)

    actual fun glShaderSource(shader: Int, source: String) =
        LwjglGles30.glShaderSource(shader, source)

    actual fun glTexImage2D(
        target: Int,
        level: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        border: Int,
        format: Int,
        type: Int,
        pixels: java.nio.Buffer?,
    ) = LwjglGles30.glTexImage2D(
        target, level, internalformat, width, height, border, format, type, pixels as ByteBuffer?,
    )

    actual fun glTexParameteri(target: Int, pname: Int, param: Int) =
        LwjglGles30.glTexParameteri(target, pname, param)

    actual fun glTexStorage2D(target: Int, levels: Int, internalformat: Int, width: Int, height: Int) =
        LwjglGles30.glTexStorage2D(target, levels, internalformat, width, height)

    actual fun glTexStorage3D(
        target: Int,
        levels: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        depth: Int,
    ) = LwjglGles30.glTexStorage3D(target, levels, internalformat, width, height, depth)

    actual fun glTexSubImage3D(
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
    ) = LwjglGles30.glTexSubImage3D(
        target, level, xoffset, yoffset, zoffset, width, height, depth, format, type,
        pixels as ByteBuffer,
    )

    actual fun glUniform1f(location: Int, v0: Float) = LwjglGles30.glUniform1f(location, v0)

    actual fun glUniform1i(location: Int, v0: Int) = LwjglGles30.glUniform1i(location, v0)

    actual fun glUniform2f(location: Int, v0: Float, v1: Float) =
        LwjglGles30.glUniform2f(location, v0, v1)

    actual fun glUniform3f(location: Int, v0: Float, v1: Float, v2: Float) =
        LwjglGles30.glUniform3f(location, v0, v1, v2)

    actual fun glUniform4f(location: Int, v0: Float, v1: Float, v2: Float, v3: Float) =
        LwjglGles30.glUniform4f(location, v0, v1, v2, v3)

    actual fun glUniformMatrix4fv(
        location: Int,
        count: Int,
        transpose: Boolean,
        value: FloatArray,
        offset: Int,
    ) = LwjglGles30.glUniformMatrix4fv(
        location, transpose, FloatBuffer.wrap(value, offset, count * FLOATS_PER_MAT4),
    )

    actual fun glUnmapBuffer(target: Int): Boolean = LwjglGles30.glUnmapBuffer(target)

    actual fun glUseProgram(program: Int) = LwjglGles30.glUseProgram(program)

    actual fun glVertexAttribDivisor(index: Int, divisor: Int) =
        LwjglGles30.glVertexAttribDivisor(index, divisor)

    actual fun glVertexAttribPointer(
        index: Int,
        size: Int,
        type: Int,
        normalized: Boolean,
        stride: Int,
        offset: Int,
    ) = LwjglGles30.glVertexAttribPointer(index, size, type, normalized, stride, offset.toLong())

    actual fun glViewport(x: Int, y: Int, width: Int, height: Int) =
        LwjglGles30.glViewport(x, y, width, height)

    // ------------------------------------------------------------- adapting

    /**
     * Pins a byte buffer's `limit` to `position + size` for one LWJGL
     * transfer, restoring the caller's limit afterwards. The engine sizes
     * every transfer explicitly (Android semantics); LWJGL would otherwise
     * read the whole remaining range.
     */
    private inline fun withByteLimit(buffer: ByteBuffer, size: Int, block: (ByteBuffer) -> Unit) {
        val saved = buffer.limit()
        buffer.limit(buffer.position() + size)
        try {
            block(buffer)
        } finally {
            buffer.limit(saved)
        }
    }

    /** The float-typed twin of [withByteLimit]; `size` is bytes. */
    private inline fun withFloatLimit(buffer: FloatBuffer, size: Int, block: (FloatBuffer) -> Unit) {
        val saved = buffer.limit()
        buffer.limit(buffer.position() + size / FLOAT_BYTES)
        try {
            block(buffer)
        } finally {
            buffer.limit(saved)
        }
    }

    private const val FLOATS_PER_MAT4 = 16
    private const val FLOAT_BYTES = 4
}
