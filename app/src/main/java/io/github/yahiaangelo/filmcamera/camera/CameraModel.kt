package io.github.yahiaangelo.filmcamera.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.DisplayOrientedMeteringPointFactory
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.scale
import androidx.lifecycle.LifecycleOwner
import io.github.yahiaangelo.filmcamera.model.Film
import io.github.yahiaangelo.filmcamera.model.FilmAdjustments
import io.github.yahiaangelo.filmcamera.rendering.CubeLut
import io.github.yahiaangelo.filmcamera.rendering.FilmGlThread
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Describes the active camera, for building the zoom controls. */
data class CameraDeviceInfo(
    val lensFacing: Int,
    /** Zoom ratios for the lens buttons (e.g. ultra wide, wide, tele). */
    val zoomStops: List<Float>,
    val minZoom: Float,
    val maxZoom: Float,
    val hasFlash: Boolean,
)

/** UI-facing camera state. Bridges Compose to CameraX and the film renderer. */
class CameraModel(private val context: Context, private val scope: CoroutineScope) {
    sealed interface Status {
        data object Idle : Status
        data object Running : Status
        data object Unauthorized : Status
        data class Failed(val message: String) : Status
    }

    enum class FlashMode(val title: String, val cameraX: Int) {
        Off("Off", ImageCapture.FLASH_MODE_OFF),
        Auto("Auto", ImageCapture.FLASH_MODE_AUTO),
        On("On", ImageCapture.FLASH_MODE_ON),
    }

    class LastPhoto(val thumbnail: ImageBitmap, val uri: Uri)

    private val prefs = context.getSharedPreferences("camera", Context.MODE_PRIVATE)

    var status: Status by mutableStateOf(Status.Idle)
        private set
    var lensFacing by mutableIntStateOf(CameraSelector.LENS_FACING_BACK)
        private set
    var deviceInfo: CameraDeviceInfo? by mutableStateOf(null)
        private set
    /** Current zoom ratio (1 = the main wide lens). */
    var zoomRatio by mutableFloatStateOf(1f)
        private set
    var lastPhoto: LastPhoto? by mutableStateOf(null)
        private set
    var isCapturing by mutableStateOf(false)
        private set
    /** Incremented on every shutter press; drives haptics and the shutter flash. */
    var shutterCount by mutableIntStateOf(0)
        private set
    var captureError: String? by mutableStateOf(null)

    var flashMode by mutableStateOf(FlashMode.Off)
        private set
    var showsGrid by mutableStateOf(false)

    /** The film currently loaded into the renderer (`null` = no film). */
    var selectedFilmId: String? by mutableStateOf(prefs.getString(KEY_SELECTED_FILM, null))
        private set

    var adjustments: FilmAdjustments by mutableStateOf(savedAdjustments())
        private set

    val renderer = FilmGlThread()

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var lifecycleOwner: LifecycleOwner? = null
    private var zoomJob: Job? = null
    private var lutJob: Job? = null
    private var sampleScene: Bitmap? = null

    /** Keeps photos upright when the phone is held sideways; the UI itself stays portrait. */
    private val orientationListener = object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return
            imageCapture?.targetRotation = when (orientation) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        }
    }

    init {
        renderer.adjustments = adjustments
        renderer.failure?.let { status = Status.Failed(it) }
    }

    fun updateAdjustments(value: FilmAdjustments) {
        adjustments = value
        renderer.adjustments = value
        prefs.edit { putString(KEY_ADJUSTMENTS, value.toJson()) }
    }

    private fun savedAdjustments(): FilmAdjustments =
        prefs.getString(KEY_ADJUSTMENTS, null)
            ?.let { runCatching { FilmAdjustments.fromJson(it) }.getOrNull() }
            ?: FilmAdjustments.Default

    // MARK: - Film

    /** Loads a film's LUT into the renderer. Pass `null` for no film. */
    fun selectFilm(film: Film?, openLut: (() -> String)?) {
        selectedFilmId = film?.id
        prefs.edit { putString(KEY_SELECTED_FILM, film?.id) }
        lutJob?.cancel()
        if (film == null || openLut == null) {
            renderer.setLut(null)
            return
        }
        lutJob = scope.launch {
            try {
                val cube = withContext(Dispatchers.Default) { CubeLut.parse(openLut()) }
                // Ignore stale loads if the user already picked another film.
                if (selectedFilmId == film.id) renderer.setLut(cube)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                captureError = "Couldn't load ${film.displayName}. ${e.message.orEmpty()}"
            }
        }
    }

    // MARK: - Lifecycle

    fun onPermissionDenied() {
        status = Status.Unauthorized
    }

    /**
     * Debug builds: feeds a still scene through the pipeline in place of the
     * camera, for screenshots and for working on the look without a camera.
     */
    fun startSampleScene(scene: Bitmap) {
        if (renderer.failure != null) return
        sampleScene = scene
        renderer.setStillFrame(scene)
        deviceInfo = CameraDeviceInfo(CameraSelector.LENS_FACING_BACK, listOf(0.5f, 1f, 2f), 0.5f, MAX_ZOOM, hasFlash = true)
        zoomRatio = 1f
        status = Status.Running
    }

    /** Binds the camera to [owner]; CameraX starts and stops it with the lifecycle. */
    suspend fun start(owner: LifecycleOwner) {
        if (renderer.failure != null || sampleScene != null) return
        lifecycleOwner = owner
        try {
            val provider = provider ?: ProcessCameraProvider.awaitInstance(context).also { provider = it }
            bind(provider, owner, lensFacing)
            orientationListener.enable()
        } catch (e: Exception) {
            status = Status.Failed(e.message ?: "The camera could not be opened.")
        }
    }

    fun stop() {
        orientationListener.disable()
    }

    fun flipCamera() {
        val provider = provider ?: return
        val owner = lifecycleOwner ?: return
        if (status != Status.Running) return
        val next = if (lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT
        else CameraSelector.LENS_FACING_BACK
        val previous = lensFacing
        try {
            bind(provider, owner, next)
        } catch (e: Exception) {
            captureError = e.message
            // Put the old camera back so the viewfinder keeps running.
            runCatching { bind(provider, owner, previous) }
        }
    }

    private fun bind(provider: ProcessCameraProvider, owner: LifecycleOwner, facing: Int) {
        val selector = CameraSelector.Builder().requireLensFacing(facing).build()
        if (!provider.hasCamera(selector)) throw IllegalStateException("No camera is available on this device.")

        // LUTs are authored for sRGB, 4:3 input; the viewfinder shows 3:4 portrait.
        val displayRotation = ContextCompat.getDisplayOrDefault(context).rotation
        renderer.displayRotation = displayRotation
        val preview = Preview.Builder()
            .setTargetRotation(displayRotation)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(1440, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                    )
                    .build(),
            )
            .build()
            .also { it.setSurfaceProvider(renderer.executor, renderer::provideSurface) }

        // 12 MP keeps full-resolution GPU processing fast and memory friendly.
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setFlashMode(flashMode.cameraX)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(4000, 3000), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                    )
                    .build(),
            )
            .build()

        provider.unbindAll()
        val camera = provider.bindToLifecycle(owner, selector, preview, capture)
        this.camera = camera
        imageCapture = capture
        lensFacing = facing

        val zoom = camera.cameraInfo.zoomState.value
        val minZoom = zoom?.minZoomRatio ?: 1f
        val maxZoom = minOf(zoom?.maxZoomRatio ?: 1f, MAX_ZOOM)
        deviceInfo = CameraDeviceInfo(
            lensFacing = facing,
            zoomStops = zoomStops(camera.cameraInfo, minZoom, maxZoom),
            minZoom = minZoom,
            maxZoom = maxZoom,
            hasFlash = camera.cameraInfo.hasFlashUnit(),
        )
        zoomJob?.cancel()
        zoomRatio = 1f.coerceIn(minZoom, maxZoom)
        camera.cameraControl.setZoomRatio(zoomRatio)
        status = Status.Running
    }

    // MARK: - Controls

    fun cycleFlash() {
        val modes = FlashMode.entries
        flashMode = modes[(flashMode.ordinal + 1) % modes.size]
        imageCapture?.flashMode = flashMode.cameraX
    }

    fun setZoom(ratio: Float, animated: Boolean = true) {
        val info = deviceInfo ?: return
        val control = camera?.cameraControl
        val target = ratio.coerceIn(info.minZoom, info.maxZoom)
        zoomJob?.cancel()
        if (!animated) {
            zoomRatio = target
            control?.setZoomRatio(target)
            return
        }
        val start = zoomRatio
        zoomJob = scope.launch {
            // CameraX has no zoom ramp, so ease between lenses over a few frames.
            val steps = 12
            for (step in 1..steps) {
                val t = step.toFloat() / steps
                val eased = 1 - (1 - t) * (1 - t) * (1 - t)
                zoomRatio = start + (target - start) * eased
                control?.setZoomRatio(zoomRatio)
                delay(16)
            }
        }
    }

    /** Focus and meter at a point in normalized viewfinder coordinates (0..1, top-left origin). */
    fun focus(x: Float, y: Float, previewWidth: Float, previewHeight: Float) {
        val camera = camera ?: return
        val frame = renderer.latestFrameSize ?: return

        // Undo the preview's aspect-fill crop to get a point in the upright frame.
        val scale = max(previewWidth / frame.width, previewHeight / frame.height)
        val visibleU = previewWidth / (frame.width * scale)
        val visibleV = previewHeight / (frame.height * scale)
        val u = (1 - visibleU) / 2 + x * visibleU
        val v = (1 - visibleV) / 2 + y * visibleV

        // The factory maps display-oriented (and, for selfies, mirrored) points to the sensor.
        val display = ContextCompat.getDisplayOrDefault(context)
        val point = DisplayOrientedMeteringPointFactory(display, camera.cameraInfo, 1f, 1f).createPoint(u, v)
        camera.cameraControl.startFocusAndMetering(
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE).build(),
        )
    }

    // MARK: - Capture

    fun capture() {
        val imageCapture = imageCapture
        val sample = sampleScene
        if (imageCapture == null && sample == null) return
        if (isCapturing || status != Status.Running) return
        isCapturing = true
        shutterCount++
        val mirrored = lensFacing == CameraSelector.LENS_FACING_FRONT

        scope.launch {
            try {
                val (jpeg, rotation) = if (imageCapture != null) takePicture(imageCapture) else null to 0
                val result = withContext(Dispatchers.Default) {
                    val source = jpeg?.let(::decode) ?: sample!!.copy(Bitmap.Config.ARGB_8888, false)
                    val processed = renderer.renderPhoto(source, rotation, mirrored)
                    source.recycle()
                    val file = File.createTempFile("capture", ".jpg", context.cacheDir)
                    try {
                        PhotoWriter.encode(processed, jpeg, file)
                        val thumbnail = processed.scale(240, max(1, 240 * processed.height / processed.width))
                        processed.recycle()
                        val uri = withContext(Dispatchers.IO) { PhotoWriter.saveToGallery(context, file) }
                        LastPhoto(thumbnail.asImageBitmap(), uri)
                    } finally {
                        file.delete()
                    }
                }
                lastPhoto = result
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                captureError = e.message ?: "The photo could not be saved."
            } finally {
                isCapturing = false
            }
        }
    }

    /** Returns the JPEG and the clockwise rotation that makes it upright. */
    private suspend fun takePicture(imageCapture: ImageCapture): Pair<ByteArray, Int> =
        suspendCancellableCoroutine { continuation ->
            imageCapture.takePicture(
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val result = image.use {
                            val buffer = it.planes[0].buffer
                            ByteArray(buffer.remaining()).also(buffer::get) to it.imageInfo.rotationDegrees
                        }
                        continuation.resume(result)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        continuation.resumeWithException(exception)
                    }
                },
            )
        }

    /** Decodes the JPEG's raw pixels (ignoring EXIF rotation), within the GPU's texture limit. */
    private fun decode(jpeg: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / sample > renderer.maxPhotoDimension) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
            ?: throw IllegalStateException("The camera did not return a photo.")
    }

    fun release() {
        orientationListener.disable()
        provider?.unbindAll()
        renderer.release()
    }

    // MARK: - Zoom stops

    /**
     * Lens buttons: the ultra wide (if the camera zooms below 1×), the main
     * lens, any telephoto lenses and a 2× crop when there's no telephoto.
     */
    @OptIn(ExperimentalCamera2Interop::class)
    private fun zoomStops(info: CameraInfo, minZoom: Float, maxZoom: Float): List<Float> {
        val stops = mutableListOf(1f)
        if (minZoom < 0.95f) stops += minZoom
        runCatching {
            val manager = context.getSystemService(CameraManager::class.java)
            val logical = manager.getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId)
            val main = logical.fieldOfViewFactor() ?: return@runCatching
            for (id in logical.physicalCameraIds) {
                val factor = manager.getCameraCharacteristics(id).fieldOfViewFactor() ?: continue
                val ratio = factor / main
                if (ratio > 1.8f) stops += if (ratio >= 2.5f) ratio.roundToInt().toFloat() else (ratio * 10).roundToInt() / 10f
            }
        }
        if (stops.none { abs(it - 2f) < 0.3f || it > 2f } && maxZoom >= 2f) stops += 2f
        return stops.filter { it in minZoom..maxZoom }.distinct().sorted()
    }

    /** Proportional to the 35 mm equivalent focal length. */
    private fun CameraCharacteristics.fieldOfViewFactor(): Float? {
        val focal = get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sensor = get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        return focal / sensor.width
    }

    private companion object {
        const val KEY_SELECTED_FILM = "selectedFilm"
        const val KEY_ADJUSTMENTS = "adjustments"
        const val MAX_ZOOM = 15f
    }
}
