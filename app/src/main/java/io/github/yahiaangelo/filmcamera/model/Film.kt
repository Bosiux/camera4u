package io.github.yahiaangelo.filmcamera.model

import org.json.JSONObject

/** A film emulation LUT from the Film-Luts catalog (`film_luts.json`). */
data class Film(
    val name: String,
    /** Repository-relative path to the `.cube` file, e.g. `luts/bw/kodak_tri-x_400.cube`. */
    val lutFile: String,
    val category: String,
    /** Repository-relative path to the preview thumbnail. */
    val thumbnail: String,
) {
    val id: String get() = lutFile

    /** Base name of the LUT file without extension, e.g. `kodak_tri-x_400`. */
    val fileStem: String get() = lutFile.substringAfterLast('/').removeSuffix(".cube")

    val displayName: String get() = prettify(name)
    val displayCategory: String get() = prettyCategory(category)

    companion object {
        fun prettyCategory(category: String): String = when (category) {
            "Bw" -> "Black & White"
            "Colorslide" -> "Color Slide"
            "Fujixtransiii" -> "Fuji X-Trans III"
            "Print" -> "Cinema Print"
            else -> category
        }

        private val nameFixes = listOf(
            "Xtrans Iii" to "X-Trans III",
            "Hp 5" to "HP5",
            "Hps" to "HPS",
            "Fp 4" to "FP4",
            "Xp 2" to "XP2",
            "Apx" to "APX",
            "Bw" to "B&W",
            "Cn" to "CN",
            "Hie Hs" to "HIE HS",
            " Nc" to " NC",
            " Vc" to " VC",
            " Uc" to " UC",
            " Hc" to " HC",
            " Hg" to " HG",
            "Ir " to "IR ",
            "Px-" to "PX-",
            "Uv" to "UV",
            "T-Max" to "T-MAX",
            "E-100 Gx" to "E100 GX",
            "Vs" to "VS",
            "Xpro" to "X-Pro",
            "Constlclip" to "Const. Clip",
            "Constlmap" to "Const. Map",
            "Cuspclip" to "Cusp Clip",
        )

        fun prettify(name: String): String =
            nameFixes.fold(name) { acc, (from, to) -> acc.replace(from, to) }

        /** Decodes the `{"filmLUTs": [...]}` catalog. */
        fun parseCatalog(json: String): List<Film> {
            val array = JSONObject(json).getJSONArray("filmLUTs")
            return List(array.length()) { i ->
                val item = array.getJSONObject(i)
                Film(
                    name = item.getString("name"),
                    lutFile = item.getString("lut_file"),
                    category = item.getString("category"),
                    thumbnail = item.getString("thumbnail"),
                )
            }
        }
    }
}
