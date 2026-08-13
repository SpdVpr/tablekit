package com.kitworks.tablekit

import com.kitworks.tablekit.format.TabularFormat
import com.kitworks.tablekit.icons.TableKitIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.PropertyResourceBundle

/**
 * The failures this catches are the quiet ones: a missing icon file draws a
 * blank square, a missing message key draws `!editor.status!`, and both survive
 * every other test in the suite because nothing throws.
 */
class ResourcesTest {

    @Test
    fun `every format has an icon, in both themes`() {
        for (format in TabularFormat.values().filter { it.binary }) {
            assertNotNull("no icon for $format", TableKitIcons.forFormat(format))
        }

        for (name in listOf("parquetFile", "excelFile", "avroFile")) {
            assertNotNull("missing /icons/$name.svg", resource("/icons/$name.svg"))
            assertNotNull("missing /icons/${name}_dark.svg", resource("/icons/${name}_dark.svg"))
        }
    }

    @Test
    fun `the plugin icon is packaged for the marketplace`() {
        assertNotNull("META-INF/pluginIcon.svg is what the listing shows", resource("/META-INF/pluginIcon.svg"))
        assertNotNull(resource("/META-INF/pluginIcon_dark.svg"))
    }

    /** A key that exists in the code but not the bundle renders as `!key!`. */
    @Test
    fun `every message the code asks for exists in the bundle`() {
        val bundle = bundleKeys()
        val missing = mutableListOf<String>()

        sourceFiles().forEach { file ->
            val text = Files.readString(file)
            KEY_PATTERN.findAll(text).forEach { match ->
                val key = match.groupValues[1]
                if (key !in bundle) missing += "$key (${file.fileName})"
            }
        }

        assertEquals("message keys used in code but missing from the bundle: $missing", 0, missing.size)
    }

    /** A key nobody asks for is either a typo or dead weight. */
    @Test
    fun `every message in the bundle is asked for somewhere`() {
        val used = sourceFiles()
            .flatMap { file -> KEY_PATTERN.findAll(Files.readString(file)).map { it.groupValues[1] }.toList() }
            .toSet()

        val unused = bundleKeys() - used
        assertEquals("message keys in the bundle that nothing uses: $unused", 0, unused.size)
    }

    @Test
    fun `the descriptor the export dialog offers matches the formats we can write`() {
        val extensions = com.kitworks.tablekit.data.ExportFormat.values().map { it.extension }
        assertEquals("extensions must be unique", extensions.size, extensions.toSet().size)
        assertTrue("csv is the fallback and has to be offered", "csv" in extensions)
    }

    private fun resource(path: String) = TableKitIcons::class.java.getResource(path)

    private fun bundleKeys(): Set<String> {
        val stream = checkNotNull(TableKitIcons::class.java.getResourceAsStream(BUNDLE_PATH)) {
            "the resource bundle is not on the classpath: $BUNDLE_PATH"
        }
        return stream.use { PropertyResourceBundle(it).keySet() }
    }

    private fun sourceFiles(): List<Path> {
        val root = Paths.get("src", "main", "kotlin")
        check(Files.isDirectory(root)) { "tests must run from the project directory; $root not found" }
        Files.walk(root).use { stream ->
            return stream.filter { it.toString().endsWith(".kt") }.toList()
        }
    }

    private companion object {
        const val BUNDLE_PATH = "/messages/TableKitBundle.properties"
        val KEY_PATTERN = Regex("""TableKitBundle\.message\(\s*"([^"]+)"""")
    }
}
