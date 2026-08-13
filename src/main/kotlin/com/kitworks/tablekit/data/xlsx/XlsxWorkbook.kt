package com.kitworks.tablekit.data.xlsx

import java.io.Closeable
import java.io.InputStream
import java.math.BigDecimal
import java.math.RoundingMode
import java.nio.file.Path
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.zip.ZipFile
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamReader

/** What a cell holds, which is what decides the column's type later. */
enum class CellKind { TEXT, INTEGER, DECIMAL, BOOLEAN, TIMESTAMP }

/**
 * A cell as text plus the kind it was stored as. Everything reaches the engine
 * as text and is cast once the whole column has been seen.
 */
data class CellValue(val text: String, val kind: CellKind)

class XlsxException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A minimal .xlsx reader built on the JDK alone - zip plus streaming XML.
 *
 * Every Excel library worth using drags in commons-compress and a StAX
 * implementation, both of which the IntelliJ Platform already bundles in
 * versions we do not control. Shipping a second copy is how plugins earn
 * NoSuchMethodError bug reports on IDE versions their author never tested, so
 * TableKit parses the format itself. Only the javax.xml.stream API is used, so
 * whichever parser the IDE provides is fine.
 *
 * Reads values, which is what a viewer needs: shared and inline strings,
 * numbers, booleans, dates (via the workbook's number formats) and the cached
 * results of formulas. Charts, images and styling are ignored by design.
 */
class XlsxWorkbook private constructor(
    private val zip: ZipFile,
    val sheets: List<XlsxSheet>,
    private val sharedStrings: List<String>,
    private val dateStyles: Set<Int>,
    private val date1904: Boolean,
) : Closeable {

    /**
     * Streams one sheet. Rows arrive in file order; [cells] is indexed by column
     * and holds null where the sheet has no cell at all.
     */
    fun readSheet(sheetIndex: Int, consumer: (cells: List<CellValue?>) -> Unit) {
        val sheet = sheets.getOrNull(sheetIndex) ?: throw XlsxException("The workbook has no sheet #$sheetIndex.")
        parse(sheet.entryName) { reader ->
            var row = ArrayList<CellValue?>()
            var column = 0
            var cellKind: String? = null
            var styleIndex = 0
            var inValue = false
            var inInlineString = false
            val value = StringBuilder()

            while (reader.hasNext()) {
                when (reader.next()) {
                    XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                        "row" -> {
                            row = ArrayList()
                            column = 0
                        }

                        "c" -> {
                            column = columnIndexOf(reader.getAttributeValue(null, "r"), column)
                            cellKind = reader.getAttributeValue(null, "t")
                            styleIndex = reader.getAttributeValue(null, "s")?.toIntOrNull() ?: 0
                            value.setLength(0)
                        }

                        "v" -> inValue = true
                        "is" -> inInlineString = true
                        "t" -> if (inInlineString) inValue = true
                    }

                    XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                        if (inValue) value.append(reader.text)

                    XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                        "v" -> inValue = false
                        "t" -> if (inInlineString) inValue = false
                        "is" -> inInlineString = false

                        "c" -> {
                            while (row.size <= column) row.add(null)
                            row[column] = cellValueOf(value.toString(), cellKind, styleIndex)
                            column++
                        }

                        "row" -> consumer(row)
                    }
                }
            }
        }
    }

    override fun close() = zip.close()

    // --- cell decoding ------------------------------------------------------

    private fun cellValueOf(raw: String, type: String?, styleIndex: Int): CellValue? {
        if (raw.isEmpty()) return null
        return when (type) {
            "s" -> raw.toIntOrNull()?.let { sharedStrings.getOrNull(it) }?.let { text(it) }
            "b" -> CellValue(if (raw == "1" || raw.equals("true", ignoreCase = true)) "true" else "false", CellKind.BOOLEAN)
            "e", "str", "inlineStr" -> text(raw)
            "d" -> CellValue(raw, CellKind.TIMESTAMP)
            else -> number(raw, styleIndex)
        }
    }

    private fun text(value: String): CellValue? = value.ifEmpty { null }?.let { CellValue(it, CellKind.TEXT) }

    private fun number(raw: String, styleIndex: Int): CellValue {
        val decimal = raw.toBigDecimalOrNull() ?: return CellValue(raw, CellKind.TEXT)
        if (styleIndex in dateStyles) {
            return CellValue(toDateTime(decimal).toString(), CellKind.TIMESTAMP)
        }
        val plain = decimal.stripTrailingZeros()
        return CellValue(plain.toPlainString(), if (plain.scale() <= 0) CellKind.INTEGER else CellKind.DECIMAL)
    }

    /**
     * Excel counts days from an epoch that depends on the workbook, and the 1900
     * calendar contains a February 29th that never existed. Both quirks are part
     * of the file format, so both are honoured here.
     */
    private fun toDateTime(serial: BigDecimal): LocalDateTime {
        val wholeDays = serial.setScale(0, RoundingMode.FLOOR).toInt()
        val date = if (date1904) {
            LocalDate.of(1904, 1, 1).plusDays(wholeDays.toLong())
        } else {
            LocalDate.of(1899, 12, 31).plusDays(wholeDays.toLong() + if (wholeDays >= 61) -1 else 0)
        }
        val secondsInDay = serial.subtract(BigDecimal(wholeDays))
            .multiply(SECONDS_PER_DAY)
            .setScale(3, RoundingMode.HALF_UP)
        val millis = secondsInDay.movePointRight(3).toLong()
        return date.atStartOfDay().plusNanos(millis * 1_000_000)
    }

    private fun columnIndexOf(reference: String?, fallback: Int): Int {
        if (reference == null) return fallback
        var index = 0
        for (character in reference) {
            if (!character.isLetter()) break
            index = index * 26 + (character.uppercaseChar() - 'A' + 1)
        }
        return if (index == 0) fallback else index - 1
    }

    // --- parsing ------------------------------------------------------------

    private fun parse(entryName: String, block: (XMLStreamReader) -> Unit) {
        val entry = zip.getEntry(entryName) ?: throw XlsxException("The workbook is missing $entryName.")
        zip.getInputStream(entry).use { stream ->
            val reader = XML.createXMLStreamReader(stream)
            try {
                block(reader)
            } finally {
                reader.close()
            }
        }
    }

    companion object {
        private val SECONDS_PER_DAY = BigDecimal(86_400)

        /**
         * External entities and DTDs are turned off: a worksheet is data from an
         * untrusted file, not a document allowed to reach out to the network or
         * expand itself a billion times.
         */
        private val XML: XMLInputFactory = XMLInputFactory.newInstance().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_COALESCING, false)
        }

        /** Number formats Excel reserves for dates and times. */
        private val BUILTIN_DATE_FORMATS = (14..22).toSet() + (45..47).toSet()

        fun open(path: Path): XlsxWorkbook {
            val zip = try {
                ZipFile(path.toFile())
            } catch (e: Exception) {
                throw XlsxException("This does not look like a valid .xlsx workbook.", e)
            }
            return try {
                build(zip)
            } catch (e: XlsxException) {
                zip.close()
                throw e
            } catch (e: Exception) {
                zip.close()
                throw XlsxException("The workbook could not be read.", e)
            }
        }

        private fun build(zip: ZipFile): XlsxWorkbook {
            val relations = readRelations(zip)
            val (sheets, date1904) = readWorkbook(zip, relations)
            if (sheets.isEmpty()) throw XlsxException("The workbook contains no sheets.")
            return XlsxWorkbook(zip, sheets, readSharedStrings(zip), readDateStyles(zip), date1904)
        }

        private fun readRelations(zip: ZipFile): Map<String, String> {
            val relations = mutableMapOf<String, String>()
            forEachElement(zip, "xl/_rels/workbook.xml.rels", required = false) { reader ->
                if (reader.localName == "Relationship") {
                    val id = reader.getAttributeValue(null, "Id")
                    val target = reader.getAttributeValue(null, "Target")
                    if (id != null && target != null) relations[id] = normalizeTarget(target)
                }
            }
            return relations
        }

        private fun normalizeTarget(target: String): String =
            if (target.startsWith("/")) target.removePrefix("/") else "xl/" + target.removePrefix("./")

        private fun readWorkbook(zip: ZipFile, relations: Map<String, String>): Pair<List<XlsxSheet>, Boolean> {
            val sheets = mutableListOf<XlsxSheet>()
            var date1904 = false
            forEachElement(zip, "xl/workbook.xml", required = true) { reader ->
                when (reader.localName) {
                    "sheet" -> {
                        val name = reader.getAttributeValue(null, "name") ?: "Sheet${sheets.size + 1}"
                        val id = (0 until reader.attributeCount)
                            .firstOrNull { reader.getAttributeLocalName(it) == "id" }
                            ?.let(reader::getAttributeValue)
                        val entry = id?.let(relations::get) ?: "xl/worksheets/sheet${sheets.size + 1}.xml"
                        sheets += XlsxSheet(name, entry)
                    }

                    "workbookPr" -> {
                        val value = reader.getAttributeValue(null, "date1904")
                        date1904 = value == "1" || value.equals("true", ignoreCase = true)
                    }
                }
            }
            return sheets to date1904
        }

        /** Most text in a workbook lives here once and is referenced by index. */
        private fun readSharedStrings(zip: ZipFile): List<String> {
            val entry = zip.getEntry("xl/sharedStrings.xml") ?: return emptyList()
            val strings = mutableListOf<String>()
            val current = StringBuilder()
            var inText = false
            var inPhonetic = false

            zip.getInputStream(entry).use { stream ->
                val reader = XML.createXMLStreamReader(stream)
                try {
                    while (reader.hasNext()) {
                        when (reader.next()) {
                            XMLStreamConstants.START_ELEMENT -> when (reader.localName) {
                                "si" -> current.setLength(0)
                                "rPh" -> inPhonetic = true
                                "t" -> inText = !inPhonetic
                            }

                            XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA ->
                                if (inText) current.append(reader.text)

                            XMLStreamConstants.END_ELEMENT -> when (reader.localName) {
                                "t" -> inText = false
                                "rPh" -> inPhonetic = false
                                "si" -> strings += current.toString()
                            }
                        }
                    }
                } finally {
                    reader.close()
                }
            }
            return strings
        }

        /**
         * Dates are numbers wearing a format, so the only way to tell 45000 from
         * a date is to look up the style the cell points at.
         */
        private fun readDateStyles(zip: ZipFile): Set<Int> {
            val customDateFormats = mutableSetOf<Int>()
            val styleFormats = mutableListOf<Int>()
            var inCellXfs = false

            forEachElement(zip, "xl/styles.xml", required = false) { reader ->
                when (reader.localName) {
                    "numFmt" -> {
                        val id = reader.getAttributeValue(null, "numFmtId")?.toIntOrNull()
                        val code = reader.getAttributeValue(null, "formatCode")
                        if (id != null && code != null && looksLikeDate(code)) customDateFormats += id
                    }

                    "cellXfs" -> inCellXfs = true
                    "xf" -> if (inCellXfs) styleFormats += reader.getAttributeValue(null, "numFmtId")?.toIntOrNull() ?: 0
                }
            }

            return styleFormats.withIndex()
                .filter { (_, format) -> format in BUILTIN_DATE_FORMATS || format in customDateFormats }
                .map { (index, _) -> index }
                .toSet()
        }

        /** A format code is a date format if it uses date fields outside quoted text. */
        private fun looksLikeDate(formatCode: String): Boolean {
            var quoted = false
            var bracketed = false
            for (character in formatCode) {
                when {
                    character == '"' -> quoted = !quoted
                    character == '[' -> bracketed = true
                    character == ']' -> bracketed = false
                    quoted || bracketed -> Unit
                    character in "yYdD" -> return true
                    character in "mMhHsS" -> return true
                }
            }
            return false
        }

        private fun forEachElement(zip: ZipFile, entryName: String, required: Boolean, onElement: (XMLStreamReader) -> Unit) {
            val entry = zip.getEntry(entryName)
            if (entry == null) {
                if (required) throw XlsxException("The workbook is missing $entryName.")
                return
            }
            zip.getInputStream(entry).use { stream: InputStream ->
                val reader = XML.createXMLStreamReader(stream)
                try {
                    while (reader.hasNext()) {
                        if (reader.next() == XMLStreamConstants.START_ELEMENT) onElement(reader)
                    }
                } finally {
                    reader.close()
                }
            }
        }
    }
}

data class XlsxSheet(val name: String, val entryName: String)
