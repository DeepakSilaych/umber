package com.deepak.umber.io

/**
 * Minimal RFC 4180 CSV reader and writer.
 *
 * Hand-rolled rather than pulled from a library because bank exports are messy in ways a strict
 * parser rejects — ragged row lengths, preamble junk above the header, stray blank lines — and the
 * app has no network permission, so every dependency is dead weight shipped in the APK.
 *
 * Handles quoted fields, embedded delimiters, embedded newlines, and `""` escapes.
 */
object Csv {

    /** Delimiters seen in Indian bank exports, in preference order. */
    private val CANDIDATE_DELIMITERS = listOf(',', '\t', ';', '|')

    /**
     * Guesses the delimiter by which one yields the most consistent column count across the first
     * few non-blank lines. Counting only the first line would be fooled by a bank's title row.
     */
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (sample.isEmpty()) return ','

        return CANDIDATE_DELIMITERS.maxByOrNull { delimiter ->
            val counts = sample.map { line -> line.count { it == delimiter } }
            val median = counts.sorted()[counts.size / 2]
            // Reward a high, stable column count; a delimiter that never appears scores zero.
            if (median == 0) 0 else median * 100 - counts.count { it != median }
        } ?: ','
    }

    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = ArrayList<List<String>>()
        val row = ArrayList<String>()
        val field = StringBuilder()

        var inQuotes = false
        var i = 0

        fun endField() {
            row.add(field.toString().trim())
            field.setLength(0)
        }

        fun endRow() {
            endField()
            // Drop rows that are entirely empty — bank exports are full of spacer lines.
            if (row.any { it.isNotEmpty() }) rows.add(ArrayList(row))
            row.clear()
        }

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> endField()
                !inQuotes && (c == '\n' || c == '\r') -> {
                    // Swallow CRLF as one terminator.
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    endRow()
                }
                else -> field.append(c)
            }
            i++
        }

        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }

    /** Quotes only when necessary, which keeps exported files readable in a text editor. */
    fun escape(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }

    fun row(values: List<String>): String = values.joinToString(",") { escape(it) }
}
