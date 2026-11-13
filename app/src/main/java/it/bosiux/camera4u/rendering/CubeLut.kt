package it.bosiux.camera4u.rendering

/**
 * A parsed Adobe/Resolve `.cube` 3D LUT. Values are RGB triplets with red
 * varying fastest, then green, then blue — the same layout as a 3D texture
 * with x = red, y = green, z = blue.
 */
class CubeLut(
    val size: Int,
    /** Flat RGB values, `size³ * 3` floats. */
    val values: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    class ParseException(message: String) : Exception(message)

    companion object {
        fun parse(text: String): CubeLut {
            var size = 0
            var domainMin = floatArrayOf(0f, 0f, 0f)
            var domainMax = floatArrayOf(1f, 1f, 1f)
            var values = FloatArray(0)
            var count = 0

            for (rawLine in text.lineSequence()) {
                val line = rawLine.trim()
                val first = line.firstOrNull() ?: continue
                if (first == '#') continue

                val parts = line.split(' ', '\t').filter { it.isNotEmpty() }
                if (first.isLetter()) {
                    when (parts[0]) {
                        "LUT_3D_SIZE" -> {
                            size = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 2..256 }
                                ?: throw ParseException("Invalid LUT line: $line")
                            values = FloatArray(size * size * size * 3)
                        }
                        "LUT_1D_SIZE" -> throw ParseException("1D LUTs are not supported.")
                        "DOMAIN_MIN" -> domainMin = vector(parts, line)
                        "DOMAIN_MAX" -> domainMax = vector(parts, line)
                        // TITLE and other keywords.
                    }
                    continue
                }

                if (parts.size != 3) throw ParseException("Invalid LUT line: $line")
                if (size == 0) throw ParseException("The LUT file has no LUT_3D_SIZE.")
                if (count + 3 > values.size) {
                    throw ParseException("Expected ${size * size * size} LUT entries, found more.")
                }
                for (part in parts) {
                    values[count++] = part.toFloatOrNull() ?: throw ParseException("Invalid LUT line: $line")
                }
            }

            if (size < 2) throw ParseException("The LUT file has no LUT_3D_SIZE.")
            if (count != values.size) {
                throw ParseException("Expected ${size * size * size} LUT entries, found ${count / 3}.")
            }
            return CubeLut(size, values, domainMin, domainMax)
        }

        private fun vector(parts: List<String>, line: String): FloatArray {
            val floats = parts.drop(1).mapNotNull { it.toFloatOrNull() }
            if (floats.size != 3) throw ParseException("Invalid LUT line: $line")
            return floats.toFloatArray()
        }

        /** A 2×2×2 LUT that maps every color to itself. */
        val identity: CubeLut by lazy {
            val values = FloatArray(24)
            var i = 0
            for (b in 0..1) for (g in 0..1) for (r in 0..1) {
                values[i++] = r.toFloat(); values[i++] = g.toFloat(); values[i++] = b.toFloat()
            }
            CubeLut(2, values)
        }
    }
}
