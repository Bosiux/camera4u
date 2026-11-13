package it.bosiux.camera4u

import it.bosiux.camera4u.model.AdjustmentControl
import it.bosiux.camera4u.model.FilmAdjustments
import it.bosiux.camera4u.rendering.UvTransform
import it.bosiux.camera4u.ui.camera.zoomLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilmAdjustmentsTest {
    @Test
    fun roundTripsThroughJson() {
        val value = FilmAdjustments(exposure = 1.5f, contrast = -0.25f, grain = 0f, vignette = 0.8f)
        assertEquals(value, FilmAdjustments.fromJson(value.toJson()))
    }

    @Test
    fun skinProtectRoundTripsAndDefaultsToHalf() {
        assertEquals(0.5f, FilmAdjustments.Default.skinProtect)
        val value = FilmAdjustments(skinProtect = 0.9f)
        assertEquals(0.9f, FilmAdjustments.fromJson(value.toJson()).skinProtect)
        assertEquals(1f, FilmAdjustments.fromJson("""{"skin": 7}""").skinProtect)
    }

    @Test
    fun missingAndOutOfRangeValuesAreSanitized() {
        val value = FilmAdjustments.fromJson("""{"exposure": 9, "tint": -0.5}""")
        assertEquals(2f, value.exposure)
        assertEquals(-0.5f, value.tint)
        assertEquals(FilmAdjustments.Default.grain, value.grain)
    }

    @Test
    fun formatsValues() {
        val exposure = AdjustmentControl.all.first { it.id == "exposure" }
        val contrast = AdjustmentControl.all.first { it.id == "contrast" }
        val grain = AdjustmentControl.all.first { it.id == "grain" }
        assertEquals("+0.5 EV", exposure.formatted(0.5f))
        assertEquals("+25", contrast.formatted(0.25f))
        assertEquals("-25", contrast.formatted(-0.25f))
        assertEquals("30", grain.formatted(0.3f))
    }

    @Test
    fun formatsZoomLabels() {
        assertEquals(".5", zoomLabel(0.5f, isActive = false))
        assertEquals("1×", zoomLabel(1f, isActive = true))
        assertEquals("2", zoomLabel(2f, isActive = false))
        assertEquals("1.4×", zoomLabel(1.4f, isActive = true))
    }
}

class UvTransformTest {
    private fun map(m: FloatArray, u: Float, v: Float) = UvTransform.apply(m, u, v)

    @Test
    fun rotationMapsUprightCornersToTheSensorBuffer() {
        // A 90° clockwise rotation makes the buffer upright, so the upright
        // top-left comes from the buffer's bottom-left.
        assertEquals(0f to 1f, map(UvTransform.uprightToSource(90), 0f, 0f))
        assertEquals(0f to 0f, map(UvTransform.uprightToSource(90), 1f, 0f))
        assertEquals(1f to 0f, map(UvTransform.uprightToSource(180), 0f, 1f))
        assertEquals(1f to 0f, map(UvTransform.uprightToSource(270), 0f, 0f))
        assertEquals(0.2f to 0.7f, map(UvTransform.uprightToSource(0), 0.2f, 0.7f))
    }

    @Test
    fun concatAppliesTheLastTransformFirst() {
        // Mirror first, then rotate: the upright top-left becomes the top-right, then (0, 0).
        val m = UvTransform.concat(UvTransform.uprightToSource(90), UvTransform.mirrorX())
        assertEquals(0f to 0f, map(m, 0f, 0f))
    }
}

class PhotoWriterTest {
    @Test
    fun baseNameIsStableAndPrefixed() {
        val name = it.bosiux.camera4u.camera.PhotoWriter.baseName(0L)
        assertTrue(name.startsWith("C4U_"))
        assertEquals(name, it.bosiux.camera4u.camera.PhotoWriter.baseName(0L))
        assertEquals("C4U_".length + "yyyyMMdd_HHmmss_SSS".length, name.length)
    }
}
