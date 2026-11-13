package it.bosiux.camera4u.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.util.Range
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Manual exposure, white balance and focus on top of CameraX, through the
 * Camera2 interop. Ranges come from the bound camera, never from constants.
 */
@OptIn(ExperimentalCamera2Interop::class)
class ProControls {
    class Ranges(
        val iso: Range<Int>,
        val exposureNs: Range<Long>,
        val ev: Range<Int>,
        val evStep: Double,
        /** 0 when the lens has fixed focus. */
        val maxDiopters: Float,
        val awbModes: List<Int>,
    )

    class WhiteBalance(val mode: Int, val label: String)

    var ranges: Ranges? by mutableStateOf(null)
        private set

    var manualExposure by mutableStateOf(false)
    var iso by mutableIntStateOf(400)
    var exposureNs by mutableLongStateOf(10_000_000L) // 1/100 s
    var evIndex by mutableIntStateOf(0)
    var awbMode by mutableIntStateOf(CameraMetadata.CONTROL_AWB_MODE_AUTO)
    var manualFocus by mutableStateOf(false)
    var focusDiopters by mutableFloatStateOf(0f)
    /** AE and AWB lock, toggled by a long press on the viewfinder. */
    var locked by mutableStateOf(false)

    private var camera: Camera? = null

    /** Reads the bound camera's ranges and re-applies the current state. */
    fun bind(camera: Camera) {
        this.camera = camera
        val c = Camera2CameraInfo.from(camera.cameraInfo)
        val iso = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(100, 800)
        val exposure = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: Range(1_000_000L, 100_000_000L)
        val ev = c.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
        val step = c.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toDouble() ?: 0.0
        val maxDiopters = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val awb = c.getCameraCharacteristic(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.toList() ?: listOf(1)
        ranges = Ranges(iso, exposure, ev, step, maxDiopters, awb)
        this.iso = this.iso.coerceIn(iso.lower, iso.upper)
        exposureNs = exposureNs.coerceIn(exposure.lower, exposure.upper)
        evIndex = evIndex.coerceIn(ev.lower, ev.upper)
        if (awbMode !in awb) awbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO
        if (maxDiopters == 0f) manualFocus = false
        focusDiopters = focusDiopters.coerceIn(0f, maxDiopters)
        locked = false
        apply()
    }

    /** Pushes the current state to the camera as capture request options. */
    fun apply() {
        val camera = camera ?: return
        val r = ranges ?: return
        val options = CaptureRequestOptions.Builder()
        if (manualExposure) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.coerceIn(r.iso.lower, r.iso.upper))
            options.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs.coerceIn(r.exposureNs.lower, r.exposureNs.upper))
        } else {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, locked)
        }
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        options.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, locked && awbMode == CameraMetadata.CONTROL_AWB_MODE_AUTO)
        if (manualFocus && r.maxDiopters > 0f) {
            options.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_OFF)
            options.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focusDiopters.coerceIn(0f, r.maxDiopters))
        }
        // setCaptureRequestOptions replaces everything set before, so a cleared key goes back to CameraX's default.
        Camera2CameraControl.from(camera.cameraControl).setCaptureRequestOptions(options.build())
        if (!manualExposure) camera.cameraControl.setExposureCompensationIndex(evIndex.coerceIn(r.ev.lower, r.ev.upper))
    }

    val whiteBalances: List<WhiteBalance>
        get() = listOf(
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_AUTO, "Auto"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, "Sun"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "Cloud"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_SHADE, "Shade"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, "Bulb"),
            WhiteBalance(CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, "Tube"),
        ).filter { ranges?.awbModes?.contains(it.mode) ?: false }

    companion object {
        /** Maps a 0..1 slider to a log-spaced value between [min] and [max]. */
        fun sliderToLog(t: Float, min: Double, max: Double): Double = min * (max / min).pow(t.toDouble())

        fun logToSlider(value: Double, min: Double, max: Double): Float =
            (ln(value / min) / ln(max / min)).toFloat().coerceIn(0f, 1f)

        /** "1/250" below a third of a second, "0.5 s" / "16 s" above. */
        fun shutterLabel(ns: Long): String {
            val s = ns / 1e9
            return when {
                s < 0.3 -> "1/" + (1.0 / s).roundToInt()
                s < 10 -> String.format(Locale.US, "%.1f s", s)
                else -> "${s.roundToInt()} s"
            }
        }

        fun evLabel(index: Int, step: Double): String = String.format(Locale.US, "%+.1f", index * step).replace("+0.0", "0.0")
    }
}
