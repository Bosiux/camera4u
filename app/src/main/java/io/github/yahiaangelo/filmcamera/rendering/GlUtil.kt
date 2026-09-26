package io.github.yahiaangelo.filmcamera.rendering

import android.opengl.GLES11Ext
import android.opengl.GLES30.*

/** A linked GL program with cached uniform locations. */
internal class GlProgram(vertex: String, fragment: String) {
    val id: Int
    private val locations = HashMap<String, Int>()

    init {
        val vs = compile(GL_VERTEX_SHADER, vertex)
        val fs = compile(GL_FRAGMENT_SHADER, fragment)
        id = glCreateProgram()
        glAttachShader(id, vs)
        glAttachShader(id, fs)
        glLinkProgram(id)
        glDeleteShader(vs)
        glDeleteShader(fs)
        val status = IntArray(1)
        glGetProgramiv(id, GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = glGetProgramInfoLog(id)
            glDeleteProgram(id)
            throw IllegalStateException("Program link failed: $log")
        }
    }

    fun use() = glUseProgram(id)

    fun loc(name: String): Int = locations.getOrPut(name) { glGetUniformLocation(id, name) }

    fun set(name: String, value: Float) = glUniform1f(loc(name), value)
    fun set(name: String, value: Int) = glUniform1i(loc(name), value)
    fun set(name: String, value: Boolean) = glUniform1i(loc(name), if (value) 1 else 0)
    fun set(name: String, x: Float, y: Float) = glUniform2f(loc(name), x, y)
    fun set(name: String, v: FloatArray) = when (v.size) {
        3 -> glUniform3fv(loc(name), 1, v, 0)
        16 -> glUniformMatrix4fv(loc(name), 1, false, v, 0)
        else -> error("Unsupported uniform size ${v.size}")
    }
    fun setUnsigned(name: String, value: Int) = glUniform1ui(loc(name), value)

    fun release() = glDeleteProgram(id)

    private fun compile(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        val status = IntArray(1)
        glGetShaderiv(shader, GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = glGetShaderInfoLog(shader)
            glDeleteShader(shader)
            throw IllegalStateException("Shader compile failed: $log")
        }
        return shader
    }
}

/** A 2D texture with a framebuffer attached, used as a render target. */
internal class RenderTarget(val width: Int, val height: Int, internalFormat: Int, format: Int, type: Int) {
    val texture: Int
    val framebuffer: Int

    init {
        texture = genTexture(GL_TEXTURE_2D, GL_LINEAR)
        glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, null)
        val ids = IntArray(1)
        glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0)
        val status = glCheckFramebufferStatus(GL_FRAMEBUFFER)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            release()
            throw IllegalStateException("Framebuffer incomplete: 0x${status.toString(16)}")
        }
    }

    fun bind() {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        glViewport(0, 0, width, height)
    }

    fun release() {
        glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        glDeleteTextures(1, intArrayOf(texture), 0)
    }

    companion object {
        fun rgba8(width: Int, height: Int) =
            RenderTarget(width, height, GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE)

        fun rgba16f(width: Int, height: Int) =
            RenderTarget(width, height, GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT)
    }
}

internal fun genTexture(target: Int, filter: Int): Int {
    val ids = IntArray(1)
    glGenTextures(1, ids, 0)
    glBindTexture(target, ids[0])
    glTexParameteri(target, GL_TEXTURE_MIN_FILTER, filter)
    glTexParameteri(target, GL_TEXTURE_MAG_FILTER, filter)
    glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
    glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    if (target == GL_TEXTURE_3D) glTexParameteri(target, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE)
    return ids[0]
}

internal fun genExternalTexture(): Int = genTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GL_LINEAR)

/**
 * 2D affine transforms on (u, v) as column-major 4×4 matrices, the layout
 * `SurfaceTexture.getTransformMatrix` uses.
 */
internal object UvTransform {
    fun identity() = FloatArray(16).also { it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f }

    /** x' = a·x + c·y + tx, y' = b·x + d·y + ty. */
    fun affine(a: Float, b: Float, c: Float, d: Float, tx: Float, ty: Float) = identity().apply {
        this[0] = a; this[1] = b; this[4] = c; this[5] = d; this[12] = tx; this[13] = ty
    }

    fun mirrorX() = affine(-1f, 0f, 0f, 1f, 1f, 0f)
    fun flipY() = affine(1f, 0f, 0f, -1f, 0f, 1f)

    /**
     * Maps a point in the upright image to the source buffer, where the source
     * must be rotated clockwise by [degrees] to be upright.
     */
    fun uprightToSource(degrees: Int) = when (((degrees % 360) + 360) % 360) {
        90 -> affine(0f, -1f, 1f, 0f, 0f, 1f)    // (u, v) → (v, 1 − u)
        180 -> affine(-1f, 0f, 0f, -1f, 1f, 1f)  // (u, v) → (1 − u, 1 − v)
        270 -> affine(0f, 1f, -1f, 0f, 1f, 0f)   // (u, v) → (1 − v, u)
        else -> identity()
    }

    /** Right-to-left: the last matrix is applied to the point first. */
    fun concat(vararg matrices: FloatArray): FloatArray = matrices.reduce { a, b ->
        FloatArray(16) { i ->
            val col = i / 4
            val row = i % 4
            (0 until 4).sumOf { k -> (a[k * 4 + row] * b[col * 4 + k]).toDouble() }.toFloat()
        }
    }

    fun apply(m: FloatArray, u: Float, v: Float): Pair<Float, Float> =
        (m[0] * u + m[4] * v + m[12]) to (m[1] * u + m[5] * v + m[13])
}
