package it.bosiux.camera4u

import it.bosiux.camera4u.library.FilmLibrary
import it.bosiux.camera4u.model.Looks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LookTest {
    @Test
    fun everyLookLutIsBundled() {
        for (look in Looks.all) {
            val lut = look.lutId ?: continue
            assertTrue("${look.name} uses $lut which is not bundled", lut in FilmLibrary.bundledIds)
        }
    }

    @Test
    fun idsAreUniqueAndNoneComesFirst() {
        assertEquals(Looks.all.size, Looks.all.map { it.id }.toSet().size)
        assertNull(Looks.all.first().lutId)
        assertEquals(Looks.all.first(), Looks.byId("none"))
        assertNull(Looks.byId("missing"))
    }
}
