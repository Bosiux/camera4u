package it.bosiux.camera4u.rendering

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.SurfaceRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Owns the EGL context and the film renderer. Camera frames arrive in a
 * `SurfaceTexture` (zero-copy OES texture) and are drawn straight into the
 * viewfinder's surface. Photos are rendered on the same thread and context.
 */
class FilmGlThread {
    private val thread = HandlerThread("FilmGL").apply { start() }
    private val handler = Handler(thread.looper)

    /** Runs work on the GL thread; hand this to CameraX for surface requests. */
    val executor = Executor { handler.post(it) }

    private val renderer = FilmRenderer()
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE

    private var output: EGLSurface = EGL14.EGL_NO_SURFACE
    private var outputWidth = 0
    private var outputHeight = 0

    /** One camera stream; a new one is created per CameraX surface request. */
    private inner class CameraInput(val resolution: Size) {
        val texture = genExternalTexture()
        val surfaceTexture = SurfaceTexture(texture).apply {
            setDefaultBufferSize(resolution.width, resolution.height)
        }
        val surface = Surface(surfaceTexture)
        var info: SurfaceRequest.TransformationInfo? = null
        val texMatrix = FloatArray(16)

        fun release() {
            surface.release()
            surfaceTexture.release()
            GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        }
    }

    private var input: CameraInput? = null

    /** A still image shown in place of the camera (debug sample scene). */
    private class StillInput(val texture: Int, val width: Int, val height: Int)
    private var still: StillInput? = null

    /** Re-renders the still image at 30 fps so its grain moves like the live camera's. */
    private val stillLoop = object : Runnable {
        override fun run() {
            if (still == null) return
            drawFrame()
            handler.postDelayed(this, 33)
        }
    }

    /** The display's `Surface.ROTATION_*`, relative to its natural orientation. */
    @Volatile var displayRotation: Int = Surface.ROTATION_0

    /** Why the renderer couldn't start, if it couldn't. Read after construction. */
    @Volatile var failure: String? = null
        private set

    /** Size of the most recent upright preview frame, used to map taps to the sensor. */
    @Volatile var latestFrameSize: Size? = null
        private set

    init {
        runBlocking {
            try {
                setUpEgl()
                renderer.init()
            } catch (e: Exception) {
                Log.e(TAG, "GL setup failed", e)
                failure = e.message ?: "OpenGL ES 3 is not available on this device."
            }
        }
    }

    var adjustments by renderer::adjustments

    fun setLut(cube: CubeLut?) {
        renderer.setLut(cube)
        handler.post { if (failure == null) drawFrame() }
    }

    // MARK: - Camera input

    /** CameraX surface provider callback. Must run on [executor]. */
    fun provideSurface(request: SurfaceRequest) {
        if (failure != null) {
            request.willNotProvideSurface()
            return
        }
        makeCurrent()
        val camera = CameraInput(request.resolution)
        camera.surfaceTexture.setOnFrameAvailableListener({ onFrameAvailable(camera) }, handler)
        request.setTransformationInfoListener(executor) { camera.info = it }
        request.provideSurface(camera.surface, executor) {
            makeCurrent()
            camera.release()
            if (input === camera) input = null
        }
        input = camera
    }

    private fun onFrameAvailable(camera: CameraInput) {
        if (camera !== input) return
        makeCurrent()
        camera.surfaceTexture.updateTexImage()
        drawFrame()
    }

    private fun drawFrame() {
        if (output == EGL14.EGL_NO_SURFACE) return
        val source = still?.let {
            // Bitmaps upload with their top row at t = 0, so the upright image needs no transform.
            FilmRenderer.Source(it.texture, external = false, UvTransform.identity(), it.width, it.height)
        } ?: run {
            val camera = input ?: return
            val info = camera.info ?: return
            camera.surfaceTexture.getTransformMatrix(camera.texMatrix)
            cameraSource(camera, info)
        }
        latestFrameSize = Size(source.width, source.height)
        if (!EGL14.eglMakeCurrent(display, output, output, context)) return
        renderer.drawPreview(source, outputWidth, outputHeight)
        EGL14.eglSwapBuffers(display, output)
    }

    private fun cameraSource(camera: CameraInput, info: SurfaceRequest.TransformationInfo): FilmRenderer.Source {
        val bufferWidth = camera.resolution.width
        val bufferHeight = camera.resolution.height

        if (info.hasCameraTransform()) {
            // The camera already rotated (and, for selfies, mirrored) the buffer to the
            // device's natural orientation through the SurfaceTexture matrix, which is how
            // SurfaceView and TextureView show it. Like PreviewView, only the display's
            // rotation away from its natural orientation is left to undo.
            val remaining = (360 - surfaceRotationDegrees(displayRotation)) % 360
            val swapsAxes = abs(UvTransform.concat(camera.texMatrix, UvTransform.flipY())[0]) < 0.5f
            val swapped = swapsAxes != (remaining % 180 != 0)
            return FilmRenderer.Source(
                camera.texture, external = true,
                UvTransform.concat(camera.texMatrix, UvTransform.flipY(), UvTransform.uprightToSource(remaining)),
                width = if (swapped) bufferHeight else bufferWidth,
                height = if (swapped) bufferWidth else bufferHeight,
            )
        }

        // No camera transform (e.g. behind a CameraX effect): rotate, mirror and crop ourselves.
        // Upright uv → mirrored → buffer (rotated back) → crop → GL texture space.
        val crop = info.cropRect
        val rotated = info.rotationDegrees % 180 != 0
        val transform = UvTransform.concat(
            camera.texMatrix,
            UvTransform.flipY(),
            UvTransform.affine(
                crop.width() / bufferWidth.toFloat(), 0f, 0f, crop.height() / bufferHeight.toFloat(),
                crop.left / bufferWidth.toFloat(), crop.top / bufferHeight.toFloat(),
            ),
            UvTransform.uprightToSource(info.rotationDegrees),
            if (info.isMirroring) UvTransform.mirrorX() else UvTransform.identity(),
        )
        return FilmRenderer.Source(
            camera.texture, external = true, transform,
            width = if (rotated) crop.height() else crop.width(),
            height = if (rotated) crop.width() else crop.height(),
        )
    }

    private fun surfaceRotationDegrees(rotation: Int) = when (rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    /** Shows [bitmap] in place of the camera, for the debug sample scene. */
    fun setStillFrame(bitmap: Bitmap) = handler.post {
        if (failure != null) return@post
        makeCurrent()
        releaseStill()
        val texture = genTexture(GLES30.GL_TEXTURE_2D, GLES30.GL_LINEAR)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        still = StillInput(texture, bitmap.width, bitmap.height)
        stillLoop.run()
    }

    private fun releaseStill() {
        val texture = still?.texture ?: return
        still = null
        handler.removeCallbacks(stillLoop)
        GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    // MARK: - Output

    /** Attaches the viewfinder's surface. */
    fun setOutput(surfaceTexture: SurfaceTexture, width: Int, height: Int) = handler.post {
        if (failure != null) return@post
        releaseOutput()
        output = EGL14.eglCreateWindowSurface(display, config, surfaceTexture, intArrayOf(EGL14.EGL_NONE), 0)
        outputWidth = width
        outputHeight = height
        drawFrame()
    }

    fun resizeOutput(width: Int, height: Int) = handler.post {
        outputWidth = width
        outputHeight = height
    }

    /** Detaches the viewfinder's surface. Blocks until the GL thread lets go of it. */
    fun clearOutput() = runBlocking { releaseOutput() }

    private fun releaseOutput() {
        if (output == EGL14.EGL_NO_SURFACE) return
        makeCurrent()
        EGL14.eglDestroySurface(display, output)
        output = EGL14.EGL_NO_SURFACE
    }

    // MARK: - Photo

    /** Renders a photo through the current film on the GL thread. */
    suspend fun renderPhoto(bitmap: Bitmap, rotationDegrees: Int, mirrored: Boolean): Bitmap =
        suspendCancellableCoroutine { continuation ->
            handler.post {
                val result = runCatching {
                    check(failure == null) { failure!! }
                    makeCurrent()
                    renderer.renderPhoto(bitmap, rotationDegrees, mirrored)
                }
                result.fold(continuation::resume, continuation::resumeWithException)
            }
        }

    /** The largest photo dimension the GPU can process. */
    val maxPhotoDimension: Int get() = renderer.maxTextureSize

    fun release() = handler.post {
        input?.release()
        input = null
        if (failure == null) releaseStill()
        releaseOutput()
        if (failure == null) renderer.release()
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, pbuffer)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        thread.quitSafely()
    }

    // MARK: - EGL

    private fun setUpEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL is not available." }

        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) {
            "OpenGL ES 3 is not available on this device."
        }
        config = configs[0]
        context = EGL14.eglCreateContext(
            display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0,
        )
        check(context != EGL14.EGL_NO_CONTEXT) { "Could not create an OpenGL ES 3 context." }
        pbuffer = EGL14.eglCreatePbufferSurface(
            display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0,
        )
        makeCurrent()
    }

    /** Makes the context current on the offscreen surface (window surfaces are bound when drawing). */
    private fun makeCurrent() {
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
    }

    private fun runBlocking(block: () -> Unit) {
        if (Thread.currentThread() === thread) return block()
        val latch = CountDownLatch(1)
        handler.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await()
    }

    private companion object {
        const val TAG = "FilmGlThread"
    }
}
