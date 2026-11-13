package it.bosiux.camera4u.model

/** A look is a LUT plus the adjustments that give it its character. */
data class Look(
    val id: String,
    val name: String,
    /** Film id (`luts/...cube`) or `null` for no LUT. */
    val lutId: String?,
    val adjustments: FilmAdjustments,
)

object Looks {
    // ponytail: hand-tuned constants; no editor for user-defined looks until someone asks.
    val all: List<Look> = listOf(
        Look("none", "None", null, FilmAdjustments(grain = 0f, vignette = 0f)),
        Look(
            "cinema", "Cinema Print", "luts/print/kodak_2383_cuspclip.cube",
            FilmAdjustments(contrast = 0.1f, glow = 0.15f, halation = 0.35f, grain = 0.2f, vignette = 0.25f),
        ),
        Look(
            "cinema_fuji", "Cinema Print Fuji", "luts/print/fuji_3513_cuspclip.cube",
            FilmAdjustments(contrast = 0.1f, temperature = -0.1f, glow = 0.15f, halation = 0.3f, grain = 0.2f, vignette = 0.25f),
        ),
        Look(
            "natural", "Natural", "luts/negative_new/kodak_portra_400.cube",
            FilmAdjustments(lutIntensity = 0.8f, grain = 0.15f, vignette = 0.1f),
        ),
        Look(
            "chrome", "Chrome", "luts/fujixtransiii/fuji_xtrans_iii_classic_chrome.cube",
            FilmAdjustments(contrast = 0.15f, saturation = -0.1f, grain = 0.25f, vignette = 0.2f),
        ),
        Look(
            "noir", "Noir", "luts/bw/kodak_tri-x_400.cube",
            FilmAdjustments(contrast = 0.25f, grain = 0.45f, vignette = 0.35f),
        ),
    )

    fun byId(id: String?): Look? = all.firstOrNull { it.id == id }
}
