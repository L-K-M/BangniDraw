package ch.lkmc.bangnidraw.engine.gl.platform

import android.opengl.GLES30 as AndroidGles30

/**
 * The Android actual: a one-line delegation per member of the facade.
 * Nothing here adapts anything — the facade's signatures are Android's own
 * overload shapes, so this file is the reason that property is worth having.
 */
actual object GLES30 {

    actual val GL_ALREADY_SIGNALED: Int get() = AndroidGles30.GL_ALREADY_SIGNALED
    actual val GL_ARRAY_BUFFER: Int get() = AndroidGles30.GL_ARRAY_BUFFER
    actual val GL_BLEND: Int get() = AndroidGles30.GL_BLEND
    actual val GL_CLAMP_TO_EDGE: Int get() = AndroidGles30.GL_CLAMP_TO_EDGE
    actual val GL_COLOR_ATTACHMENT0: Int get() = AndroidGles30.GL_COLOR_ATTACHMENT0
    actual val GL_COLOR_BUFFER_BIT: Int get() = AndroidGles30.GL_COLOR_BUFFER_BIT
    actual val GL_COMPILE_STATUS: Int get() = AndroidGles30.GL_COMPILE_STATUS
    actual val GL_CONDITION_SATISFIED: Int get() = AndroidGles30.GL_CONDITION_SATISFIED
    actual val GL_DITHER: Int get() = AndroidGles30.GL_DITHER
    actual val GL_DRAW_FRAMEBUFFER: Int get() = AndroidGles30.GL_DRAW_FRAMEBUFFER
    actual val GL_DYNAMIC_DRAW: Int get() = AndroidGles30.GL_DYNAMIC_DRAW
    actual val GL_EXTENSIONS: Int get() = AndroidGles30.GL_EXTENSIONS
    actual val GL_FALSE: Int get() = AndroidGles30.GL_FALSE
    actual val GL_FLOAT: Int get() = AndroidGles30.GL_FLOAT
    actual val GL_FRAGMENT_SHADER: Int get() = AndroidGles30.GL_FRAGMENT_SHADER
    actual val GL_FRAMEBUFFER: Int get() = AndroidGles30.GL_FRAMEBUFFER
    actual val GL_FRAMEBUFFER_COMPLETE: Int get() = AndroidGles30.GL_FRAMEBUFFER_COMPLETE
    actual val GL_FUNC_ADD: Int get() = AndroidGles30.GL_FUNC_ADD
    actual val GL_INVALID_ENUM: Int get() = AndroidGles30.GL_INVALID_ENUM
    actual val GL_INVALID_FRAMEBUFFER_OPERATION: Int get() = AndroidGles30.GL_INVALID_FRAMEBUFFER_OPERATION
    actual val GL_INVALID_OPERATION: Int get() = AndroidGles30.GL_INVALID_OPERATION
    actual val GL_INVALID_VALUE: Int get() = AndroidGles30.GL_INVALID_VALUE
    actual val GL_LINEAR: Int get() = AndroidGles30.GL_LINEAR
    actual val GL_LINK_STATUS: Int get() = AndroidGles30.GL_LINK_STATUS
    actual val GL_MAP_READ_BIT: Int get() = AndroidGles30.GL_MAP_READ_BIT
    actual val GL_MAX: Int get() = AndroidGles30.GL_MAX
    actual val GL_MAX_ARRAY_TEXTURE_LAYERS: Int get() = AndroidGles30.GL_MAX_ARRAY_TEXTURE_LAYERS
    actual val GL_MAX_RENDERBUFFER_SIZE: Int get() = AndroidGles30.GL_MAX_RENDERBUFFER_SIZE
    actual val GL_MAX_TEXTURE_SIZE: Int get() = AndroidGles30.GL_MAX_TEXTURE_SIZE
    actual val GL_MAX_VIEWPORT_DIMS: Int get() = AndroidGles30.GL_MAX_VIEWPORT_DIMS
    actual val GL_NEAREST: Int get() = AndroidGles30.GL_NEAREST
    actual val GL_NO_ERROR: Int get() = AndroidGles30.GL_NO_ERROR
    actual val GL_ONE: Int get() = AndroidGles30.GL_ONE
    actual val GL_ONE_MINUS_SRC_ALPHA: Int get() = AndroidGles30.GL_ONE_MINUS_SRC_ALPHA
    actual val GL_OUT_OF_MEMORY: Int get() = AndroidGles30.GL_OUT_OF_MEMORY
    actual val GL_PACK_ALIGNMENT: Int get() = AndroidGles30.GL_PACK_ALIGNMENT
    actual val GL_PIXEL_PACK_BUFFER: Int get() = AndroidGles30.GL_PIXEL_PACK_BUFFER
    actual val GL_READ_FRAMEBUFFER: Int get() = AndroidGles30.GL_READ_FRAMEBUFFER
    actual val GL_RENDERER: Int get() = AndroidGles30.GL_RENDERER
    actual val GL_RGBA: Int get() = AndroidGles30.GL_RGBA
    actual val GL_RGBA8: Int get() = AndroidGles30.GL_RGBA8
    actual val GL_SCISSOR_TEST: Int get() = AndroidGles30.GL_SCISSOR_TEST
    actual val GL_STATIC_DRAW: Int get() = AndroidGles30.GL_STATIC_DRAW
    actual val GL_STREAM_DRAW: Int get() = AndroidGles30.GL_STREAM_DRAW
    actual val GL_STREAM_READ: Int get() = AndroidGles30.GL_STREAM_READ
    actual val GL_SYNC_FLUSH_COMMANDS_BIT: Int get() = AndroidGles30.GL_SYNC_FLUSH_COMMANDS_BIT
    actual val GL_SYNC_GPU_COMMANDS_COMPLETE: Int get() = AndroidGles30.GL_SYNC_GPU_COMMANDS_COMPLETE
    actual val GL_TEXTURE0: Int get() = AndroidGles30.GL_TEXTURE0
    actual val GL_TEXTURE_2D: Int get() = AndroidGles30.GL_TEXTURE_2D
    actual val GL_TEXTURE_2D_ARRAY: Int get() = AndroidGles30.GL_TEXTURE_2D_ARRAY
    actual val GL_TEXTURE_MAG_FILTER: Int get() = AndroidGles30.GL_TEXTURE_MAG_FILTER
    actual val GL_TEXTURE_MAX_LEVEL: Int get() = AndroidGles30.GL_TEXTURE_MAX_LEVEL
    actual val GL_TEXTURE_MIN_FILTER: Int get() = AndroidGles30.GL_TEXTURE_MIN_FILTER
    actual val GL_TEXTURE_WRAP_S: Int get() = AndroidGles30.GL_TEXTURE_WRAP_S
    actual val GL_TEXTURE_WRAP_T: Int get() = AndroidGles30.GL_TEXTURE_WRAP_T
    actual val GL_TIMEOUT_EXPIRED: Int get() = AndroidGles30.GL_TIMEOUT_EXPIRED
    actual val GL_TRIANGLES: Int get() = AndroidGles30.GL_TRIANGLES
    actual val GL_TRIANGLE_STRIP: Int get() = AndroidGles30.GL_TRIANGLE_STRIP
    actual val GL_TRUE: Int get() = AndroidGles30.GL_TRUE
    actual val GL_UNPACK_ALIGNMENT: Int get() = AndroidGles30.GL_UNPACK_ALIGNMENT
    actual val GL_UNSIGNED_BYTE: Int get() = AndroidGles30.GL_UNSIGNED_BYTE
    actual val GL_VENDOR: Int get() = AndroidGles30.GL_VENDOR
    actual val GL_VERSION: Int get() = AndroidGles30.GL_VERSION
    actual val GL_VERTEX_SHADER: Int get() = AndroidGles30.GL_VERTEX_SHADER

    actual fun glActiveTexture(texture: Int) = AndroidGles30.glActiveTexture(texture)

    actual fun glAttachShader(program: Int, shader: Int) = AndroidGles30.glAttachShader(program, shader)

    actual fun glBindBuffer(target: Int, buffer: Int) = AndroidGles30.glBindBuffer(target, buffer)

    actual fun glBindFramebuffer(target: Int, framebuffer: Int) =
        AndroidGles30.glBindFramebuffer(target, framebuffer)

    actual fun glBindTexture(target: Int, texture: Int) = AndroidGles30.glBindTexture(target, texture)

    actual fun glBindVertexArray(array: Int) = AndroidGles30.glBindVertexArray(array)

    actual fun glBlendEquation(mode: Int) = AndroidGles30.glBlendEquation(mode)

    actual fun glBlendFunc(sfactor: Int, dfactor: Int) = AndroidGles30.glBlendFunc(sfactor, dfactor)

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
    ) = AndroidGles30.glBlitFramebuffer(
        srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter,
    )

    actual fun glBufferData(target: Int, size: Int, data: java.nio.Buffer?, usage: Int) =
        AndroidGles30.glBufferData(target, size, data, usage)

    actual fun glBufferSubData(target: Int, offset: Int, size: Int, data: java.nio.Buffer) =
        AndroidGles30.glBufferSubData(target, offset, size, data)

    actual fun glCheckFramebufferStatus(target: Int): Int =
        AndroidGles30.glCheckFramebufferStatus(target)

    actual fun glClear(mask: Int) = AndroidGles30.glClear(mask)

    actual fun glClearColor(red: Float, green: Float, blue: Float, alpha: Float) =
        AndroidGles30.glClearColor(red, green, blue, alpha)

    actual fun glClientWaitSync(sync: Long, flags: Int, timeoutNanos: Long): Int =
        AndroidGles30.glClientWaitSync(sync, flags, timeoutNanos)

    actual fun glColorMask(red: Boolean, green: Boolean, blue: Boolean, alpha: Boolean) =
        AndroidGles30.glColorMask(red, green, blue, alpha)

    actual fun glCompileShader(shader: Int) = AndroidGles30.glCompileShader(shader)

    actual fun glCreateProgram(): Int = AndroidGles30.glCreateProgram()

    actual fun glCreateShader(type: Int): Int = AndroidGles30.glCreateShader(type)

    actual fun glDeleteBuffers(n: Int, buffers: IntArray, offset: Int) =
        AndroidGles30.glDeleteBuffers(n, buffers, offset)

    actual fun glDeleteFramebuffers(n: Int, framebuffers: IntArray, offset: Int) =
        AndroidGles30.glDeleteFramebuffers(n, framebuffers, offset)

    actual fun glDeleteProgram(program: Int) = AndroidGles30.glDeleteProgram(program)

    actual fun glDeleteShader(shader: Int) = AndroidGles30.glDeleteShader(shader)

    actual fun glDeleteSync(sync: Long) = AndroidGles30.glDeleteSync(sync)

    actual fun glDeleteTextures(n: Int, textures: IntArray, offset: Int) =
        AndroidGles30.glDeleteTextures(n, textures, offset)

    actual fun glDeleteVertexArrays(n: Int, arrays: IntArray, offset: Int) =
        AndroidGles30.glDeleteVertexArrays(n, arrays, offset)

    actual fun glDetachShader(program: Int, shader: Int) = AndroidGles30.glDetachShader(program, shader)

    actual fun glDisable(cap: Int) = AndroidGles30.glDisable(cap)

    actual fun glDrawArrays(mode: Int, first: Int, count: Int) =
        AndroidGles30.glDrawArrays(mode, first, count)

    actual fun glDrawArraysInstanced(mode: Int, first: Int, count: Int, instanceCount: Int) =
        AndroidGles30.glDrawArraysInstanced(mode, first, count, instanceCount)

    actual fun glEnable(cap: Int) = AndroidGles30.glEnable(cap)

    actual fun glEnableVertexAttribArray(index: Int) = AndroidGles30.glEnableVertexAttribArray(index)

    actual fun glFenceSync(condition: Int, flags: Int): Long =
        AndroidGles30.glFenceSync(condition, flags)

    actual fun glFramebufferTexture2D(
        target: Int,
        attachment: Int,
        textarget: Int,
        texture: Int,
        level: Int,
    ) = AndroidGles30.glFramebufferTexture2D(target, attachment, textarget, texture, level)

    actual fun glFramebufferTextureLayer(
        target: Int,
        attachment: Int,
        texture: Int,
        level: Int,
        layer: Int,
    ) = AndroidGles30.glFramebufferTextureLayer(target, attachment, texture, level, layer)

    actual fun glGenBuffers(n: Int, buffers: IntArray, offset: Int) =
        AndroidGles30.glGenBuffers(n, buffers, offset)

    actual fun glGenFramebuffers(n: Int, framebuffers: IntArray, offset: Int) =
        AndroidGles30.glGenFramebuffers(n, framebuffers, offset)

    actual fun glGenTextures(n: Int, textures: IntArray, offset: Int) =
        AndroidGles30.glGenTextures(n, textures, offset)

    actual fun glGenVertexArrays(n: Int, arrays: IntArray, offset: Int) =
        AndroidGles30.glGenVertexArrays(n, arrays, offset)

    actual fun glGetError(): Int = AndroidGles30.glGetError()

    actual fun glGetIntegerv(pname: Int, params: IntArray, offset: Int) =
        AndroidGles30.glGetIntegerv(pname, params, offset)

    actual fun glGetProgramInfoLog(program: Int): String? = AndroidGles30.glGetProgramInfoLog(program)

    actual fun glGetProgramiv(program: Int, pname: Int, params: IntArray, offset: Int) =
        AndroidGles30.glGetProgramiv(program, pname, params, offset)

    actual fun glGetShaderInfoLog(shader: Int): String? = AndroidGles30.glGetShaderInfoLog(shader)

    actual fun glGetShaderiv(shader: Int, pname: Int, params: IntArray, offset: Int) =
        AndroidGles30.glGetShaderiv(shader, pname, params, offset)

    actual fun glGetString(name: Int): String? = AndroidGles30.glGetString(name)

    actual fun glGetUniformLocation(program: Int, name: String): Int =
        AndroidGles30.glGetUniformLocation(program, name)

    actual fun glLinkProgram(program: Int) = AndroidGles30.glLinkProgram(program)

    actual fun glMapBufferRange(target: Int, offset: Int, length: Int, access: Int): java.nio.Buffer? =
        AndroidGles30.glMapBufferRange(target, offset, length, access)

    actual fun glPixelStorei(pname: Int, param: Int) = AndroidGles30.glPixelStorei(pname, param)

    actual fun glReadPixels(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        format: Int,
        type: Int,
        pixels: java.nio.Buffer,
    ) = AndroidGles30.glReadPixels(x, y, width, height, format, type, pixels)

    actual fun glReadPixels(x: Int, y: Int, width: Int, height: Int, format: Int, type: Int, offset: Int) =
        AndroidGles30.glReadPixels(x, y, width, height, format, type, offset)

    actual fun glScissor(x: Int, y: Int, width: Int, height: Int) =
        AndroidGles30.glScissor(x, y, width, height)

    actual fun glShaderSource(shader: Int, source: String) =
        AndroidGles30.glShaderSource(shader, source)

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
    ) = AndroidGles30.glTexImage2D(target, level, internalformat, width, height, border, format, type, pixels)

    actual fun glTexParameteri(target: Int, pname: Int, param: Int) =
        AndroidGles30.glTexParameteri(target, pname, param)

    actual fun glTexStorage2D(target: Int, levels: Int, internalformat: Int, width: Int, height: Int) =
        AndroidGles30.glTexStorage2D(target, levels, internalformat, width, height)

    actual fun glTexStorage3D(
        target: Int,
        levels: Int,
        internalformat: Int,
        width: Int,
        height: Int,
        depth: Int,
    ) = AndroidGles30.glTexStorage3D(target, levels, internalformat, width, height, depth)

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
    ) = AndroidGles30.glTexSubImage3D(
        target, level, xoffset, yoffset, zoffset, width, height, depth, format, type, pixels,
    )

    actual fun glUniform1f(location: Int, v0: Float) = AndroidGles30.glUniform1f(location, v0)

    actual fun glUniform1i(location: Int, v0: Int) = AndroidGles30.glUniform1i(location, v0)

    actual fun glUniform2f(location: Int, v0: Float, v1: Float) =
        AndroidGles30.glUniform2f(location, v0, v1)

    actual fun glUniform3f(location: Int, v0: Float, v1: Float, v2: Float) =
        AndroidGles30.glUniform3f(location, v0, v1, v2)

    actual fun glUniform4f(location: Int, v0: Float, v1: Float, v2: Float, v3: Float) =
        AndroidGles30.glUniform4f(location, v0, v1, v2, v3)

    actual fun glUniformMatrix4fv(
        location: Int,
        count: Int,
        transpose: Boolean,
        value: FloatArray,
        offset: Int,
    ) = AndroidGles30.glUniformMatrix4fv(location, count, transpose, value, offset)

    actual fun glUnmapBuffer(target: Int): Boolean = AndroidGles30.glUnmapBuffer(target)

    actual fun glUseProgram(program: Int) = AndroidGles30.glUseProgram(program)

    actual fun glVertexAttribDivisor(index: Int, divisor: Int) =
        AndroidGles30.glVertexAttribDivisor(index, divisor)

    actual fun glVertexAttribPointer(
        index: Int,
        size: Int,
        type: Int,
        normalized: Boolean,
        stride: Int,
        offset: Int,
    ) = AndroidGles30.glVertexAttribPointer(index, size, type, normalized, stride, offset)

    actual fun glViewport(x: Int, y: Int, width: Int, height: Int) =
        AndroidGles30.glViewport(x, y, width, height)
}
