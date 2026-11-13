package it.bosiux.camera4u.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.CameraRoll
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Exposure
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Flare
import androidx.compose.material.icons.outlined.Gradient
import androidx.compose.material.icons.outlined.Grain
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Thermostat
import androidx.compose.material.icons.outlined.Vignette
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Image adjustments applied on top of the film LUT. All values are normalized
 * so the shaders can consume them directly.
 */
data class FilmAdjustments(
    val lutIntensity: Float = 1f,
    val exposure: Float = 0f,     // EV, -2..2
    val contrast: Float = 0f,     // -1..1
    val highlights: Float = 0f,   // -1..1
    val shadows: Float = 0f,      // -1..1
    val saturation: Float = 0f,   // -1..1
    val temperature: Float = 0f,  // -1..1
    val tint: Float = 0f,         // -1..1
    val fade: Float = 0f,         // 0..1
    val grain: Float = 0.3f,      // 0..1
    val grainSize: Float = 0.35f, // 0..1
    val glow: Float = 0f,         // 0..1
    val halation: Float = 0f,     // 0..1
    val vignette: Float = 0.2f,   // 0..1
    val skinProtect: Float = 0.5f, // 0..1, keeps skin hue and saturation through the look
) {
    fun toJson(): String = JSONObject().apply {
        AdjustmentControl.all.forEach { put(it.id, it.get(this@FilmAdjustments).toDouble()) }
    }.toString()

    companion object {
        val Default = FilmAdjustments()

        /** Missing or invalid keys fall back to their defaults. */
        fun fromJson(json: String): FilmAdjustments {
            val obj = JSONObject(json)
            return AdjustmentControl.all.fold(Default) { acc, control ->
                val value = obj.optDouble(control.id)
                if (value.isNaN()) acc else control.set(acc, value.toFloat().coerceIn(control.range))
            }
        }
    }
}

/** Describes one slider in the adjustments panel. */
class AdjustmentControl(
    val id: String,
    val title: String,
    val icon: ImageVector,
    val group: Group,
    val range: ClosedFloatingPointRange<Float>,
    val get: (FilmAdjustments) -> Float,
    val set: (FilmAdjustments, Float) -> FilmAdjustments,
) {
    enum class Group(val title: String) { Film("Film"), Light("Light"), Color("Color"), Effects("Effects") }

    val defaultValue: Float get() = get(FilmAdjustments.Default)
    val isBipolar: Boolean get() = range.start < 0f

    fun isModified(value: Float) = abs(value - defaultValue) > 0.001f

    fun formatted(value: Float): String {
        if (id == "exposure") return String.format(Locale.US, "%+.1f EV", value)
        val percent = (value * 100).roundToInt()
        return if (isBipolar && percent > 0) "+$percent" else "$percent"
    }

    companion object {
        val all: List<AdjustmentControl> = listOf(
            AdjustmentControl("intensity", "Film Strength", Icons.Outlined.CameraRoll, Group.Film, 0f..1f,
                { it.lutIntensity }, { a, v -> a.copy(lutIntensity = v) }),
            AdjustmentControl("exposure", "Exposure", Icons.Outlined.Exposure, Group.Light, -2f..2f,
                { it.exposure }, { a, v -> a.copy(exposure = v) }),
            AdjustmentControl("contrast", "Contrast", Icons.Outlined.Contrast, Group.Light, -1f..1f,
                { it.contrast }, { a, v -> a.copy(contrast = v) }),
            AdjustmentControl("highlights", "Highlights", Icons.Outlined.LightMode, Group.Light, -1f..1f,
                { it.highlights }, { a, v -> a.copy(highlights = v) }),
            AdjustmentControl("shadows", "Shadows", Icons.Outlined.DarkMode, Group.Light, -1f..1f,
                { it.shadows }, { a, v -> a.copy(shadows = v) }),
            AdjustmentControl("fade", "Fade", Icons.Outlined.Gradient, Group.Light, 0f..1f,
                { it.fade }, { a, v -> a.copy(fade = v) }),
            AdjustmentControl("saturation", "Saturation", Icons.Outlined.WaterDrop, Group.Color, -1f..1f,
                { it.saturation }, { a, v -> a.copy(saturation = v) }),
            AdjustmentControl("temperature", "Warmth", Icons.Outlined.Thermostat, Group.Color, -1f..1f,
                { it.temperature }, { a, v -> a.copy(temperature = v) }),
            AdjustmentControl("tint", "Tint", Icons.Outlined.Colorize, Group.Color, -1f..1f,
                { it.tint }, { a, v -> a.copy(tint = v) }),
            AdjustmentControl("skin", "Skin", Icons.Outlined.Face, Group.Color, 0f..1f,
                { it.skinProtect }, { a, v -> a.copy(skinProtect = v) }),
            AdjustmentControl("grain", "Grain", Icons.Outlined.Grain, Group.Effects, 0f..1f,
                { it.grain }, { a, v -> a.copy(grain = v) }),
            AdjustmentControl("grainSize", "Grain Size", Icons.Outlined.BlurOn, Group.Effects, 0f..1f,
                { it.grainSize }, { a, v -> a.copy(grainSize = v) }),
            AdjustmentControl("glow", "Glow", Icons.Outlined.AutoAwesome, Group.Effects, 0f..1f,
                { it.glow }, { a, v -> a.copy(glow = v) }),
            AdjustmentControl("halation", "Halation", Icons.Outlined.Flare, Group.Effects, 0f..1f,
                { it.halation }, { a, v -> a.copy(halation = v) }),
            AdjustmentControl("vignette", "Vignette", Icons.Outlined.Vignette, Group.Effects, 0f..1f,
                { it.vignette }, { a, v -> a.copy(vignette = v) }),
        )
    }
}
