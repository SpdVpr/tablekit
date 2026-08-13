package com.kitworks.tablekit.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonFormatTest {

    @Test
    fun `objects and arrays are recognised, other text is not`() {
        assertTrue(JsonFormat.looksLikeJson("""{"a":1}"""))
        assertTrue(JsonFormat.looksLikeJson(" [1, 2] "))
        assertFalse(JsonFormat.looksLikeJson("plain text"))
        assertFalse(JsonFormat.looksLikeJson("{"))
        assertFalse(JsonFormat.looksLikeJson(""))
    }

    @Test
    fun `nested values are indented`() {
        val pretty = JsonFormat.pretty("""{"city":"Prague","zip":11000,"tags":[1,2]}""")

        assertEquals(
            """
            {
              "city": "Prague",
              "zip": 11000,
              "tags": [
                1,
                2
              ]
            }
            """.trimIndent(),
            pretty,
        )
    }

    @Test
    fun `empty containers stay on one line`() {
        assertEquals("{}", JsonFormat.pretty("{}"))
        assertEquals("""{
  "a": []
}""", JsonFormat.pretty("""{"a":[]}"""))
    }

    /** Punctuation inside a string is text, not structure. */
    @Test
    fun `strings are copied through untouched`() {
        val pretty = JsonFormat.pretty("""{"note":"a,b: {c} [d]","escaped":"say \"hi\""}""")

        assertEquals(
            """
            {
              "note": "a,b: {c} [d]",
              "escaped": "say \"hi\""
            }
            """.trimIndent(),
            pretty,
        )
    }

    @Test
    fun `already indented json is re-indented, not doubled`() {
        val once = JsonFormat.pretty("""{"a":[1]}""")
        assertEquals(once, JsonFormat.pretty(once))
    }
}
