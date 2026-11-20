package studio.acmenra.control.feature.events.domain.model

object CsvFormulaNeutralizer {
    /**
     * Checks if a code point is a Unicode White_Space or C0/C1 control character.
     * C0: 0x00..0x1F
     * C1: 0x7F..0x9F (includes 0x85 Next Line)
     * ASCII space: 0x20
     * Unicode whitespace:
     *   0x00A0 (No-break space)
     *   0x1680 (Ogham space mark)
     *   0x2000..0x200A (En quad through hair space)
     *   0x2028 (Line separator)
     *   0x2029 (Paragraph separator)
     *   0x202F (Narrow no-break space)
     *   0x205F (Medium mathematical space)
     *   0x3000 (Ideographic space)
     */
    fun isWhitespaceOrControl(codePoint: Int): Boolean {
        if (codePoint in 0x00..0x1F) return true
        if (codePoint in 0x7F..0x9F) return true
        if (codePoint == 0x20) return true
        if (codePoint == 0xA0) return true
        if (codePoint == 0x1680) return true
        if (codePoint in 0x2000..0x200A) return true
        if (codePoint == 0x2028 || codePoint == 0x2029) return true
        if (codePoint == 0x202F) return true
        if (codePoint == 0x205F) return true
        if (codePoint == 0x3000) return true
        return false
    }

    /**
     * Scans from the first code point, skipping leading Unicode White_Space and C0/C1 control characters.
     * If the first remaining code point is '=', '+', '-' or '@', prefixes one ASCII apostrophe `'`
     * to the original unmodified string without stripping or normalizing the value.
     */
    fun neutralize(raw: String): String {
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            val codePoint = ch.code
            if (isWhitespaceOrControl(codePoint)) {
                i++
            } else {
                break
            }
        }
        if (i < raw.length) {
            val firstNonWhitespace = raw[i]
            if (firstNonWhitespace == '=' ||
                firstNonWhitespace == '+' ||
                firstNonWhitespace == '-' ||
                firstNonWhitespace == '@'
            ) {
                return "'" + raw
            }
        }
        return raw
    }

    /**
     * Applies formula neutralization and then RFC 4180 CSV quoting for the selected delimiter.
     */
    fun quoteCsvCell(
        cell: String,
        delimiter: Char = ',',
    ): String {
        val neutralized = neutralize(cell)
        val needsQuotes =
            neutralized.contains('"') ||
                neutralized.contains(delimiter) ||
                neutralized.contains('\n') ||
                neutralized.contains('\r')
        return if (needsQuotes) {
            "\"" + neutralized.replace("\"", "\"\"") + "\""
        } else {
            neutralized
        }
    }
}
