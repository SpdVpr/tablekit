package com.kitworks.tablekit.data.avro

/**
 * A JSON reader for one job: parsing the schema an Avro file carries in its
 * header.
 *
 * The IDE bundles Jackson, and so does every Avro library - in versions we do
 * not control. Rather than ship a second copy for the sake of one document per
 * file, TableKit reads it here. Values come back as [Map], [List], [String],
 * [Double], [Long], [Boolean] or null.
 */
internal object Json {

    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.readValue()
        reader.skipWhitespace()
        if (!reader.atEnd) throw AvroException("Trailing content in the schema at position ${reader.position}.")
        return value
    }

    private class Reader(private val text: String) {
        var position = 0
            private set

        val atEnd: Boolean get() = position >= text.length

        fun readValue(): Any? {
            skipWhitespace()
            if (atEnd) fail("a value")
            return when (val character = text[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't', 'f' -> readBoolean()
                'n' -> readNull()
                else ->
                    if (character == '-' || character.isDigit()) readNumber() else fail("a value")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            position++
            skipWhitespace()
            if (peek() == '}') {
                position++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    '}' -> {
                        position++
                        return result
                    }

                    else -> fail("',' or '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = mutableListOf<Any?>()
            position++
            skipWhitespace()
            if (peek() == ']') {
                position++
                return result
            }
            while (true) {
                result += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> position++
                    ']' -> {
                        position++
                        return result
                    }

                    else -> fail("',' or ']'")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val builder = StringBuilder()
            while (true) {
                if (atEnd) fail("a closing quote")
                when (val character = text[position++]) {
                    '"' -> return builder.toString()
                    '\\' -> builder.append(readEscape())
                    else -> builder.append(character)
                }
            }
        }

        private fun readEscape(): Char {
            if (atEnd) fail("an escape")
            return when (val character = text[position++]) {
                '"', '\\', '/' -> character
                'b' -> '\b'
                'f' -> ''
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (position + 4 > text.length) fail("four hex digits")
                    val code = text.substring(position, position + 4).toIntOrNull(16) ?: fail("four hex digits")
                    position += 4
                    code.toChar()
                }

                else -> fail("a valid escape")
            }
        }

        private fun readNumber(): Any {
            val start = position
            if (peek() == '-') position++
            while (!atEnd && (text[position].isDigit() || text[position] in ".eE+-")) position++
            val literal = text.substring(start, position)
            return literal.toLongOrNull() ?: literal.toDoubleOrNull() ?: fail("a number")
        }

        private fun readBoolean(): Boolean = when {
            text.startsWith("true", position) -> {
                position += 4
                true
            }

            text.startsWith("false", position) -> {
                position += 5
                false
            }

            else -> fail("a boolean")
        }

        private fun readNull(): Any? {
            if (!text.startsWith("null", position)) fail("null")
            position += 4
            return null
        }

        fun skipWhitespace() {
            while (!atEnd && text[position].isWhitespace()) position++
        }

        private fun peek(): Char = if (atEnd) fail("more input") else text[position]

        private fun expect(character: Char) {
            if (peek() != character) fail("'$character'")
            position++
        }

        private fun fail(expected: String): Nothing =
            throw AvroException("Malformed schema: expected $expected at position $position.")
    }
}
