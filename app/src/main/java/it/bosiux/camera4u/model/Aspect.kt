package it.bosiux.camera4u.model

import kotlin.math.roundToInt

/** A crop window; plain Kotlin so it works in JVM unit tests. */
data class Crop(val left: Int, val top: Int, val width: Int, val height: Int)

/** Framing applied as a centred crop of the long side's ratio to the short side. */
enum class Aspect(val label: String, val ratio: Float) {
    FourThree("4:3", 4f / 3f),
    ThreeTwo("3:2", 3f / 2f),
    Wide("16:9", 16f / 9f),
    Scope("2.39:1", 2.39f);

    /** How much of the sensor's short side survives the crop (the sensor is 4:3). */
    val shortSideFraction: Float get() = (4f / 3f) / ratio

    /** Centred crop of an upright image of any orientation. */
    fun cropRect(width: Int, height: Int): Crop {
        val landscape = width >= height
        val long = if (landscape) width else height
        val short = (long / ratio).roundToInt().coerceAtMost(if (landscape) height else width)
        return if (landscape) {
            val top = (height - short) / 2
            Crop(0, top, width, short)
        } else {
            val left = (width - short) / 2
            Crop(left, 0, short, height)
        }
    }

    fun next(): Aspect = entries[(ordinal + 1) % entries.size]
}
