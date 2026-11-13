package it.bosiux.camera4u

import it.bosiux.camera4u.library.FilmLibrary
import it.bosiux.camera4u.model.Film
import it.bosiux.camera4u.rendering.CubeLut
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CubeLutTest {
    @Test
    fun parsesHeaderCommentsAndData() {
        val text = """
            # Created by: G'MIC
            TITLE "test"

            LUT_3D_SIZE 2
            DOMAIN_MIN 0.0 0.0 0.0
            DOMAIN_MAX 1.0 1.0 1.0
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
        """.trimIndent()
        val lut = CubeLut.parse(text)
        assertEquals(2, lut.size)
        assertArrayEquals(CubeLut.identity.values, lut.values, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.domainMax, 0f)
    }

    @Test
    fun rejectsTruncatedData() {
        assertThrows(CubeLut.ParseException::class.java) { CubeLut.parse("LUT_3D_SIZE 2\n0 0 0\n1 1 1\n") }
    }

    @Test
    fun rejectsExtraData() {
        val tooMany = "LUT_3D_SIZE 2\n" + "0 0 0\n".repeat(9)
        assertThrows(CubeLut.ParseException::class.java) { CubeLut.parse(tooMany) }
    }

    @Test
    fun rejects1DLuts() {
        assertThrows(CubeLut.ParseException::class.java) { CubeLut.parse("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n") }
    }

    @Test
    fun parsesEveryBundledFilm() {
        for (id in FilmLibrary.bundledIds) {
            val stem = id.substringAfterLast('/').removeSuffix(".cube")
            val file = File("src/main/assets/films/$stem.cube")
            assertTrue("Missing bundled $stem", file.exists())
            assertTrue("Missing thumbnail for $stem", File("src/main/assets/films/$stem.jpg").exists())
            val lut = CubeLut.parse(file.readText())
            assertEquals(lut.size * lut.size * lut.size * 3, lut.values.size)
        }
    }
}

class FilmCatalogTest {
    @Test
    fun bundledCatalogDecodesAndContainsBundledFilms() {
        val films = Film.parseCatalog(File("src/main/assets/film_luts.json").readText())
        assertTrue(films.size > 200)
        val ids = films.map { it.id }.toSet()
        for (id in FilmLibrary.bundledIds) assertTrue("Catalog is missing $id", id in ids)
    }

    @Test
    fun prettifiesNames() {
        assertEquals("Fuji X-Trans III Classic Chrome", Film.prettify("Fuji Xtrans Iii Classic Chrome"))
        assertEquals("Ilford HP5 Plus 400", Film.prettify("Ilford Hp 5 Plus 400"))
        assertEquals("Black & White", Film.prettyCategory("Bw"))
    }

    @Test
    fun sortsByCategoryThenNaturally() {
        val films = listOf(
            Film("Kodak Portra 400", "a", "Negative New", ""),
            Film("Kodak Tri-X 400", "b", "Bw", ""),
            Film("Kodak Portra 1600", "c", "Negative New", ""),
            Film("Kodak Portra 160", "d", "Negative New", ""),
        )
        assertEquals(
            listOf("Kodak Portra 160", "Kodak Portra 400", "Kodak Portra 1600", "Kodak Tri-X 400"),
            FilmLibrary.sorted(films).map { it.name },
        )
    }
}
