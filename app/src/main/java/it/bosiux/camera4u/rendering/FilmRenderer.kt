package it.bosiux.camera4u.rendering

import android.graphics.Bitmap
import android.opengl.GLES11Ext
import android.opengl.GLES30.*
import android.opengl.GLUtils
import androidx.core.graphics.createBitmap
import it.bosiux.camera4u.model.FilmAdjustments
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Renders camera frames through the film pipeline. The live preview and the
 * full-resolution photo go through the exact same shaders, so what you see is
 * what you get.
 *
 * Threading: [setLut] and [adjustments] may be called from any thread. Every
 * other method must run on the GL thread that owns the current EGL context.
 */
internal class FilmRenderer {
    /** A texture to grade, plus how to map the upright image onto it. */
    class Source(
        val texture: Int,
        val external: Boolean,
        /** Upright image uv → texture coordinates. */
        val uvTransform: FloatArray,
        /** Size of the upright image. */
        val width: Int,
        val height: Int,
    )

    private class LutTexture(val texture: Int, val size: Int, val domainMin: FloatArray, val domainMax: FloatArray)

    private class Intermediates(val graded: RenderTarget, val brightA: RenderTarget, val brightB: RenderTarget) {
        val width get() = graded.width
        val height get() = graded.height
        fun release() { graded.release(); brightA.release(); brightB.release() }
    }

    /** Wraps the requested LUT so "no film" (`null`) can be told apart from "no change". */
    private class LutRequest(val cube: CubeLut?)

    @Volatile var adjustments: FilmAdjustments = FilmAdjustments.Default
    private val pendingLut = AtomicReference<LutRequest?>()

    private lateinit var gradeExternal: GlProgram
    private lateinit var grade2D: GlProgram
    private lateinit var bright: GlProgram
    private lateinit var blur: GlProgram
    private lateinit var composite: GlProgram
    private var vertexArray = 0
    private var blackTexture = 0
    private lateinit var identityLut: LutTexture
    private var lut: LutTexture? = null
    private var bloomHalfFloat = false
    var maxTextureSize = 4096
        private set

    private var previewTargets: Intermediates? = null
    private var frameIndex = 0

    fun setLut(cube: CubeLut?) = pendingLut.set(LutRequest(cube))

    // MARK: - Lifecycle

    fun init() {
        gradeExternal = GlProgram(FilmShaders.VERTEX, FilmShaders.grade(external = true))
        grade2D = GlProgram(FilmShaders.VERTEX, FilmShaders.grade(external = false))
        bright = GlProgram(FilmShaders.VERTEX, FilmShaders.BRIGHT)
        blur = GlProgram(FilmShaders.VERTEX, FilmShaders.BLUR)
        composite = GlProgram(FilmShaders.VERTEX, FilmShaders.COMPOSITE)

        val ids = IntArray(1)
        glGenVertexArrays(1, ids, 0)
        vertexArray = ids[0]

        val size = IntArray(1)
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, size, 0)
        maxTextureSize = size[0]

        val extensions = glGetString(GL_EXTENSIONS).orEmpty()
        val version = glGetString(GL_VERSION).orEmpty()
        bloomHalfFloat = "GL_EXT_color_buffer_half_float" in extensions ||
            "GL_EXT_color_buffer_float" in extensions || "OpenGL ES 3.2" in version

        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glPixelStorei(GL_PACK_ALIGNMENT, 1)
        blackTexture = genTexture(GL_TEXTURE_2D, GL_NEAREST)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE,
            ByteBuffer.allocateDirect(4).put(byteArrayOf(0, 0, 0, -1)).rewind())
        identityLut = uploadLut(CubeLut.identity)
    }

    fun release() {
        previewTargets?.release()
        previewTargets = null
        lut?.let { glDeleteTextures(1, intArrayOf(it.texture), 0) }
        lut = null
        glDeleteTextures(2, intArrayOf(identityLut.texture, blackTexture), 0)
        glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
        listOf(gradeExternal, grade2D, bright, blur, composite).forEach { it.release() }
    }

    // MARK: - Preview

    /** Draws a camera frame into the current (window) surface, aspect-filling it. */
    fun drawPreview(source: Source, viewWidth: Int, viewHeight: Int) {
        applyPendingLut()
        val width = source.width
        val height = source.height
        val targets = previewTargets?.takeIf { it.width == width && it.height == height }
            ?: run {
                previewTargets?.release()
                makeIntermediates(width, height).also { previewTargets = it }
            }

        // Aspect-fill crop of the image into the view.
        val scale = max(viewWidth.toDouble() / width, viewHeight.toDouble() / height)
        val visibleU = (viewWidth / (width * scale)).toFloat()
        val visibleV = (viewHeight / (height * scale)).toFloat()

        // New grain every frame, so it dances like projected film.
        frameIndex = (frameIndex + 1) % 65_536
        val bloom = encodeGradeAndBloom(source, targets)
        glBindFramebuffer(GL_FRAMEBUFFER, 0)
        glViewport(0, 0, viewWidth, viewHeight)
        encodeComposite(
            targets.graded, bloom, width, height, seed = frameIndex, flipY = true,
            uvScale = visibleU to visibleV, uvOffset = (1 - visibleU) / 2 to (1 - visibleV) / 2,
        )
    }

    // MARK: - Photo

    /**
     * Renders a full-resolution photo through the film pipeline.
     * @param rotationDegrees clockwise rotation that makes [bitmap] upright.
     * @param mirrored flips the upright image horizontally (selfies).
     */
    fun renderPhoto(bitmap: Bitmap, rotationDegrees: Int, mirrored: Boolean): Bitmap {
        applyPendingLut()
        val input = genTexture(GL_TEXTURE_2D, GL_LINEAR)
        GLUtils.texImage2D(GL_TEXTURE_2D, 0, bitmap, 0)

        val rotated = rotationDegrees % 180 != 0
        val width = if (rotated) bitmap.height else bitmap.width
        val height = if (rotated) bitmap.width else bitmap.height
        // Bitmaps upload with their top row at t = 0, so the texture needs no flip.
        val transform = UvTransform.concat(
            UvTransform.uprightToSource(rotationDegrees),
            if (mirrored) UvTransform.mirrorX() else UvTransform.identity(),
        )
        val source = Source(input, external = false, transform, width, height)

        val targets = makeIntermediates(width, height)
        val output = RenderTarget.rgba8(width, height)
        try {
            val bloom = encodeGradeAndBloom(source, targets)
            output.bind()
            encodeComposite(
                targets.graded, bloom, width, height, seed = Random.nextInt(65_536), flipY = false,
                uvScale = 1f to 1f, uvOffset = 0f to 0f,
            )

            val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
            glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels)
            val error = glGetError()
            check(error == GL_NO_ERROR) { "Photo rendering failed (GL error 0x${error.toString(16)})." }
            return createBitmap(width, height).apply {
                copyPixelsFromBuffer(pixels.rewind())
            }
        } finally {
            glBindFramebuffer(GL_FRAMEBUFFER, 0)
            output.release()
            targets.release()
            glDeleteTextures(1, intArrayOf(input), 0)
        }
    }

    // MARK: - Encoding

    /** Grades [source] into `targets.graded` and returns the blurred bloom texture. */
    private fun encodeGradeAndBloom(source: Source, targets: Intermediates): Int {
        val a = adjustments
        val lut = lut ?: identityLut
        glBindVertexArray(vertexArray)

        val program = if (source.external) gradeExternal else grade2D
        targets.graded.bind()
        program.use()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(if (source.external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GL_TEXTURE_2D, source.texture)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_3D, lut.texture)
        program.set("uSource", 0)
        program.set("uLut", 1)
        program.set("uFlipY", false)
        program.set("uUvTransform", source.uvTransform)
        program.set("uExposure", a.exposure)
        program.set("uContrast", a.contrast)
        program.set("uHighlights", a.highlights)
        program.set("uShadows", a.shadows)
        program.set("uSaturation", a.saturation)
        program.set("uTemperature", a.temperature)
        program.set("uTint", a.tint)
        program.set("uFade", a.fade)
        program.set("uSkinProtect", a.skinProtect)
        program.set("uLutIntensity", if (this.lut != null) a.lutIntensity else 0f)
        program.set("uLutSize", lut.size.toFloat())
        program.set("uLutDomainMin", lut.domainMin)
        program.set("uLutDomainMax", lut.domainMax)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        if (a.glow <= 0.001f && a.halation <= 0.001f) return blackTexture

        val bw = targets.brightA.width
        val bh = targets.brightA.height
        targets.brightA.bind()
        bright.use()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, targets.graded.texture)
        bright.set("uSource", 0)
        bright.set("uFlipY", false)
        bright.set("uTexel", 1f / bw, 1f / bh)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        // Separable Gaussian: A → B horizontally, then B → A vertically.
        val sigma = max(1, (max(bw, bh) * 0.03f).roundToInt()).toFloat()
        val radius = ceil(sigma * 3).toInt().coerceAtMost(128)
        blur.use()
        blur.set("uSource", 0)
        blur.set("uFlipY", false)
        blur.set("uSigma", sigma)
        blur.set("uRadius", radius)

        targets.brightB.bind()
        glBindTexture(GL_TEXTURE_2D, targets.brightA.texture)
        blur.set("uStep", 1f / bw, 0f)
        glDrawArrays(GL_TRIANGLES, 0, 3)

        targets.brightA.bind()
        glBindTexture(GL_TEXTURE_2D, targets.brightB.texture)
        blur.set("uStep", 0f, 1f / bh)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        return targets.brightA.texture
    }

    /** Draws into the currently bound framebuffer and viewport. */
    private fun encodeComposite(
        graded: RenderTarget, bloom: Int, imageWidth: Int, imageHeight: Int, seed: Int, flipY: Boolean,
        uvScale: Pair<Float, Float>, uvOffset: Pair<Float, Float>,
    ) {
        val a = adjustments
        glBindVertexArray(vertexArray)
        composite.use()
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, graded.texture)
        glActiveTexture(GL_TEXTURE1)
        glBindTexture(GL_TEXTURE_2D, bloom)
        composite.set("uGraded", 0)
        composite.set("uBloom", 1)
        composite.set("uFlipY", flipY)
        composite.set("uGrain", a.grain)
        // Grain cells per image height: finer at 0, coarser at 1. Relative to the
        // image, so the preview matches the saved photo.
        composite.set("uGrainScale", 1500f / (1f + a.grainSize * 3f))
        composite.set("uGlow", a.glow)
        composite.set("uHalation", a.halation)
        composite.set("uVignette", a.vignette)
        // Keeps grain cells square regardless of the image's aspect ratio.
        composite.set("uAspect", imageWidth.toFloat() / imageHeight)
        composite.setUnsigned("uSeed", seed)
        composite.set("uUvScale", uvScale.first, uvScale.second)
        composite.set("uUvOffset", uvOffset.first, uvOffset.second)
        glDrawArrays(GL_TRIANGLES, 0, 3)
        glActiveTexture(GL_TEXTURE0)
    }

    // MARK: - Textures

    private fun makeIntermediates(width: Int, height: Int): Intermediates {
        val bw = max(1, width / 4)
        val bh = max(1, height / 4)
        val bloom = { if (bloomHalfFloat) RenderTarget.rgba16f(bw, bh) else RenderTarget.rgba8(bw, bh) }
        return Intermediates(RenderTarget.rgba8(width, height), bloom(), bloom())
    }

    private fun applyPendingLut() {
        val request = pendingLut.getAndSet(null) ?: return
        lut?.let { glDeleteTextures(1, intArrayOf(it.texture), 0) }
        lut = request.cube?.let(::uploadLut)
    }

    private fun uploadLut(cube: CubeLut): LutTexture {
        val n = cube.size
        val texture = genTexture(GL_TEXTURE_3D, GL_LINEAR)
        val data = ByteBuffer.allocateDirect(cube.values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        data.put(cube.values).rewind()
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F, n, n, n, 0, GL_RGB, GL_FLOAT, data)
        return LutTexture(texture, n, cube.domainMin, cube.domainMax)
    }
}
