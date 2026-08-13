package com.kitworks.tablekit.editor

/**
 * Just enough JSON formatting to make a nested value readable.
 *
 * Nested columns arrive as one long line of JSON, which is fine in a cell and
 * useless in a value viewer. Written by hand because the alternative is
 * bundling a JSON library next to the three the IDE already has.
 */
object JsonFormat {

    fun looksLikeJson(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.length >= 2 &&
            ((trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]")))
    }

    /** Re-indents JSON. Text inside strings is copied through untouched. */
    fun pretty(text: String, indent: String = "  "): String {
        val out = StringBuilder(text.length + text.length / 2)
        var depth = 0
        var pendingBreak = false

        fun flush() {
            if (!pendingBreak) return
            pendingBreak = false
            out.append('\n')
            repeat(depth) { out.append(indent) }
        }

        var index = 0
        while (index < text.length) {
            when (val character = text[index]) {
                '"' -> {
                    flush()
                    index = copyString(text, index, out)
                    continue
                }

                '{', '[' -> {
                    flush()
                    out.append(character)
                    depth++
                    pendingBreak = true
                }

                '}', ']' -> {
                    depth--
                    if (pendingBreak) {
                        // Nothing was written since the opener: an empty
                        // container stays on one line instead of gaining a
                        // blank one.
                        pendingBreak = false
                    } else {
                        out.append('\n')
                        repeat(depth) { out.append(indent) }
                    }
                    out.append(character)
                }

                ',' -> {
                    out.append(character)
                    pendingBreak = true
                }

                ':' -> out.append(": ")

                ' ', '\t', '\n', '\r' -> Unit

                else -> {
                    flush()
                    out.append(character)
                }
            }
            index++
        }
        return out.toString()
    }

    /** Copies a quoted string verbatim and returns the index just past it. */
    private fun copyString(text: String, start: Int, out: StringBuilder): Int {
        out.append('"')
        var index = start + 1
        while (index < text.length) {
            val character = text[index]
            out.append(character)
            when {
                character == '\\' -> {
                    if (index + 1 < text.length) {
                        out.append(text[index + 1])
                        index++
                    }
                }

                character == '"' -> return index + 1
            }
            index++
        }
        return index
    }
}
