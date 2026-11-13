package it.bosiux.camera4u

import it.bosiux.camera4u.camera.ProControls
import org.junit.Assert.assertEquals
import org.junit.Test

class ProControlsTest {
    @Test
    fun logSliderMapsEndsAndMiddle() {
        assertEquals(50.0, ProControls.sliderToLog(0f, 50.0, 9846.0), 1e-6)
        assertEquals(9846.0, ProControls.sliderToLog(1f, 50.0, 9846.0), 1e-6)
        assertEquals(0.5f, ProControls.logToSlider(Math.sqrt(50.0 * 9846.0), 50.0, 9846.0), 1e-4f)
    }

    @Test
    fun shutterLabels() {
        assertEquals("1/100", ProControls.shutterLabel(10_000_000L))
        assertEquals("1/16696", ProControls.shutterLabel(59_896L))
        assertEquals("0.5 s", ProControls.shutterLabel(500_000_000L))
        assertEquals("16 s", ProControls.shutterLabel(16_000_000_000L))
    }

    @Test
    fun evLabel() {
        assertEquals("+1.0", ProControls.evLabel(6, 1.0 / 6))
        assertEquals("-0.5", ProControls.evLabel(-3, 1.0 / 6))
        assertEquals("0.0", ProControls.evLabel(0, 1.0 / 6))
    }
}
