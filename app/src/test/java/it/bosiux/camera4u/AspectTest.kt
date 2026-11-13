package it.bosiux.camera4u

import it.bosiux.camera4u.model.Aspect
import org.junit.Assert.assertEquals
import org.junit.Test

class AspectTest {
    @Test
    fun fourThreeIsNoCrop() {
        val r = Aspect.FourThree.cropRect(4000, 3000)
        assertEquals(listOf(0, 0, 4000, 3000), listOf(r.left, r.top, r.width, r.height))
    }

    @Test
    fun landscapeCropsHeight() {
        val r = Aspect.Wide.cropRect(4000, 3000)
        assertEquals(4000, r.width)
        assertEquals(2250, r.height)
        assertEquals(375, r.top)
    }

    @Test
    fun portraitCropsWidth() {
        val r = Aspect.Scope.cropRect(3000, 4000)
        assertEquals(4000, r.height)
        assertEquals(1674, r.width)
        assertEquals(663, r.left)
    }

    @Test
    fun shortSideFraction() {
        assertEquals(0.75f, Aspect.Wide.shortSideFraction, 1e-4f)
        assertEquals(1f, Aspect.FourThree.shortSideFraction, 1e-4f)
    }
}
