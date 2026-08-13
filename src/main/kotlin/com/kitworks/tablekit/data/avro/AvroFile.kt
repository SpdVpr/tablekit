package com.kitworks.tablekit.data.avro

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.zip.Inflater

/** A column as the file declares it, with the engine type its values fit into. */
data class AvroColumn(val name: String, val duckDbType: String)

/**
 * Reads Avro object container files.
 *
 * Written here rather than pulled in, for the same reason as the .xlsx reader:
 * the reference implementation depends on Jackson, commons-compress and slf4j,
 * all of which the IntelliJ Platform already bundles in versions we do not
 * control.
 *
 * Values arrive as text, one string per cell, and the column types come from
 * the schema - so unlike a spreadsheet, an Avro file needs no guessing.
 */
class AvroFile private constructor(
    private val stream: InputStream,
    private val record: AvroSchema.Record,
    private val codec: String,
    private val sync: ByteArray,
) : Closeable {

    val columns: List<AvroColumn> = record.fields.map { AvroColumn(it.name, duckDbTypeOf(it.schema)) }

    /** Streams every row; each cell is already rendered to text. */
    fun readRows(consumer: (Array<String?>) -> Unit) {
        while (true) {
            val count = readBlockHeader() ?: return
            val block = readBlock()
            val decoder = BinaryDecoder(block)
            repeat(count.toInt()) {
                val row = arrayOfNulls<String>(record.fields.size)
                record.fields.forEachIndexed { index, field ->
                    row[index] = render(decoder.read(field.schema))
                }
                consumer(row)
            }
        }
    }

    override fun close() = stream.close()

    // --- container ----------------------------------------------------------

    private fun readBlockHeader(): Long? {
        val first = stream.read()
        if (first < 0) return null
        return BinaryDecoder(byteArrayOf(first.toByte()) + readVarintRest(first)).readLong()
    }

    /** A varint may span bytes; the first one has already been consumed. */
    private fun readVarintRest(first: Int): ByteArray {
        val rest = ByteArrayOutputStream()
        var current = first
        while (current and 0x80 != 0) {
            current = stream.read()
            if (current < 0) throw AvroException("The file ends inside a number.")
            rest.write(current)
        }
        return rest.toByteArray()
    }

    private fun readBlock(): ByteArray {
        val size = readLongFromStream().toInt()
        if (size < 0) throw AvroException("The file declares a negative block size.")
        val raw = stream.readNBytes(size)
        if (raw.size != size) throw AvroException("The file ends inside a block.")

        val marker = stream.readNBytes(SYNC_SIZE)
        if (!marker.contentEquals(sync)) throw AvroException("The file is damaged: a block marker does not match.")

        return decompress(raw)
    }

    private fun readLongFromStream(): Long {
        val first = stream.read()
        if (first < 0) throw AvroException("The file ends where a number was expected.")
        return BinaryDecoder(byteArrayOf(first.toByte()) + readVarintRest(first)).readLong()
    }

    private fun decompress(raw: ByteArray): ByteArray = when (codec) {
        "null" -> raw
        "deflate" -> inflate(raw)
        else -> throw AvroException(
            "This file is compressed with the $codec codec, which TableKit cannot read yet.",
        )
    }

    private fun inflate(raw: ByteArray): ByteArray {
        val inflater = Inflater(true)
        return try {
            inflater.setInput(raw)
            val output = ByteArrayOutputStream(raw.size * 3)
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val produced = inflater.inflate(buffer)
                if (produced == 0 && inflater.needsInput()) break
                output.write(buffer, 0, produced)
            }
            output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    // --- values -------------------------------------------------------------

    private fun render(value: Any?): String? = when (value) {
        null -> null
        is String -> value
        is ByteArray -> value.joinToString("") { "%02x".format(it) }
        is Map<*, *>, is List<*> -> JsonWriter.write(value)
        else -> value.toString()
    }

    companion object {
        private const val SYNC_SIZE = 16
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'b'.code.toByte(), 'j'.code.toByte(), 1)

        fun open(path: Path): AvroFile {
            val stream = Files.newInputStream(path).buffered()
            return try {
                read(stream)
            } catch (e: AvroException) {
                stream.close()
                throw e
            } catch (e: Exception) {
                stream.close()
                throw AvroException("This does not look like a valid Avro file.", e)
            }
        }

        private fun read(stream: InputStream): AvroFile {
            val magic = stream.readNBytes(MAGIC.size)
            if (!magic.contentEquals(MAGIC)) throw AvroException("This does not look like a valid Avro file.")

            val metadata = readMetadata(stream)
            val sync = stream.readNBytes(SYNC_SIZE)
            if (sync.size != SYNC_SIZE) throw AvroException("The file ends inside its header.")

            val schemaJson = metadata["avro.schema"]?.toString(Charsets.UTF_8)
                ?: throw AvroException("The file carries no schema.")
            val schema = AvroSchema.parse(schemaJson)
            val record = schema as? AvroSchema.Record
                ?: throw AvroException("The file's schema is not a record, which a table needs.")

            val codec = metadata["avro.codec"]?.toString(Charsets.UTF_8) ?: "null"
            return AvroFile(stream, record, codec, sync)
        }

        /** The header map is itself Avro binary, so it is read the same way. */
        private fun readMetadata(stream: InputStream): Map<String, ByteArray> {
            val metadata = mutableMapOf<String, ByteArray>()
            while (true) {
                var count = readLong(stream)
                if (count == 0L) return metadata
                if (count < 0) {
                    count = -count
                    readLong(stream) // block size in bytes, which we do not need
                }
                repeat(count.toInt()) {
                    val key = String(stream.readNBytes(readLong(stream).toInt()), Charsets.UTF_8)
                    metadata[key] = stream.readNBytes(readLong(stream).toInt())
                }
            }
        }

        private fun readLong(stream: InputStream): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = stream.read()
                if (byte < 0) throw AvroException("The file ends where a number was expected.")
                result = result or ((byte.toLong() and 0x7F) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
                if (shift > 63) throw AvroException("The file contains a malformed number.")
            }
            return (result ushr 1) xor -(result and 1)
        }

        /** What each Avro type becomes once it reaches the engine. */
        internal fun duckDbTypeOf(schema: AvroSchema): String = when (schema) {
            AvroSchema.Boolean -> "BOOLEAN"
            AvroSchema.Int, AvroSchema.Long -> "BIGINT"
            AvroSchema.Float, AvroSchema.Double -> "DOUBLE"
            is AvroSchema.Union -> schema.nullableOf?.let(::duckDbTypeOf) ?: "VARCHAR"
            is AvroSchema.Decorated -> logicalType(schema)
            is AvroSchema.Fixed -> schema.logical?.let { decimalType(it) } ?: "VARCHAR"
            else -> "VARCHAR"
        }

        private fun logicalType(schema: AvroSchema.Decorated): String = when (schema.logical.name) {
            "date" -> "DATE"
            "time-millis", "time-micros" -> "TIME"
            "timestamp-millis", "timestamp-micros", "local-timestamp-millis", "local-timestamp-micros" -> "TIMESTAMP"
            "decimal" -> decimalType(schema.logical)
            else -> duckDbTypeOf(schema.underlying)
        }

        private fun decimalType(logical: AvroSchema.Logical): String =
            if (logical.name == "decimal" && logical.precision in 1..38) {
                "DECIMAL(${logical.precision}, ${logical.scale})"
            } else {
                "VARCHAR"
            }
    }

    /**
     * Decodes Avro's binary encoding: variable length zigzag integers, little
     * endian floats, and everything else built out of those.
     */
    private class BinaryDecoder(private val bytes: ByteArray) {

        private var position = 0

        fun read(schema: AvroSchema): Any? = when (schema) {
            AvroSchema.Null -> null
            AvroSchema.Boolean -> readByte().toInt() != 0
            AvroSchema.Int -> readLong()
            AvroSchema.Long -> readLong()
            AvroSchema.Float -> java.lang.Float.intBitsToFloat(readFixedInt(4).toInt()).toDouble()
            AvroSchema.Double -> java.lang.Double.longBitsToDouble(readFixedInt(8))
            AvroSchema.Bytes -> readBytes()
            AvroSchema.Text -> readString()
            is AvroSchema.Fixed -> readRaw(schema.size).let { raw ->
                schema.logical?.takeIf { it.name == "decimal" }?.let { decimal(raw, it.scale) } ?: raw
            }

            is AvroSchema.Enum -> schema.symbols.getOrNull(readLong().toInt()) ?: ""
            is AvroSchema.Array -> readArray(schema)
            is AvroSchema.Map -> readMap(schema)
            is AvroSchema.Union -> readUnion(schema)
            is AvroSchema.Record -> readRecord(schema)
            is AvroSchema.Decorated -> readDecorated(schema)
        }

        private fun readDecorated(schema: AvroSchema.Decorated): Any? {
            val raw = read(schema.underlying)
            return when (schema.logical.name) {
                "date" -> LocalDate.ofEpochDay((raw as Long)).toString()
                "time-millis" -> LocalTime.ofNanoOfDay((raw as Long) * 1_000_000).toString()
                "time-micros" -> LocalTime.ofNanoOfDay((raw as Long) * 1_000).toString()
                "timestamp-millis", "local-timestamp-millis" -> timestamp(raw as Long, 1_000_000L)
                "timestamp-micros", "local-timestamp-micros" -> timestamp(raw as Long, 1_000L)
                "decimal" -> decimal(raw as? ByteArray ?: return raw, schema.logical.scale)
                else -> raw
            }
        }

        private fun timestamp(value: Long, nanosPerUnit: Long): String {
            val unitsPerSecond = 1_000_000_000L / nanosPerUnit
            val seconds = Math.floorDiv(value, unitsPerSecond)
            val remainder = Math.floorMod(value, unitsPerSecond) * nanosPerUnit
            return Instant.ofEpochSecond(seconds, remainder)
                .atOffset(ZoneOffset.UTC)
                .toLocalDateTime()
                .toString()
                .replace('T', ' ')
        }

        private fun decimal(raw: ByteArray, scale: Int): String =
            BigDecimal(BigInteger(raw), scale).toPlainString()

        private fun readRecord(schema: AvroSchema.Record): Map<String, Any?> {
            val values = LinkedHashMap<String, Any?>(schema.fields.size)
            schema.fields.forEach { values[it.name] = read(it.schema) }
            return values
        }

        private fun readUnion(schema: AvroSchema.Union): Any? {
            val branch = readLong().toInt()
            val chosen = schema.branches.getOrNull(branch)
                ?: throw AvroException("The file selects a union branch that its schema does not have.")
            return read(chosen)
        }

        private fun readArray(schema: AvroSchema.Array): List<Any?> {
            val items = mutableListOf<Any?>()
            forEachBlock { items += read(schema.items) }
            return items
        }

        private fun readMap(schema: AvroSchema.Map): Map<String, Any?> {
            val entries = LinkedHashMap<String, Any?>()
            forEachBlock { entries[readString()] = read(schema.values) }
            return entries
        }

        /** Arrays and maps arrive in blocks, ended by a count of zero. */
        private inline fun forEachBlock(readItem: () -> Unit) {
            while (true) {
                var count = readLong()
                if (count == 0L) return
                if (count < 0) {
                    count = -count
                    readLong() // block size in bytes, which we do not need
                }
                repeat(count.toInt()) { readItem() }
            }
        }

        fun readLong(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val byte = readByte().toInt() and 0xFF
                result = result or ((byte.toLong() and 0x7F) shl shift)
                if (byte and 0x80 == 0) break
                shift += 7
                if (shift > 63) throw AvroException("The file contains a malformed number.")
            }
            return (result ushr 1) xor -(result and 1)
        }

        private fun readString(): String = String(readBytes(), Charsets.UTF_8)

        private fun readBytes(): ByteArray = readRaw(readLong().toInt())

        private fun readRaw(length: Int): ByteArray {
            if (length < 0 || position + length > bytes.size) throw AvroException("The file ends inside a value.")
            val slice = bytes.copyOfRange(position, position + length)
            position += length
            return slice
        }

        private fun readFixedInt(size: Int): Long {
            var result = 0L
            for (index in 0 until size) {
                result = result or ((readByte().toLong() and 0xFF) shl (8 * index))
            }
            return result
        }

        private fun readByte(): Byte {
            if (position >= bytes.size) throw AvroException("The file ends inside a value.")
            return bytes[position++]
        }
    }
}

/** Renders decoded nested values as JSON, so the grid can show them. */
internal object JsonWriter {

    fun write(value: Any?): String = StringBuilder().also { append(it, value) }.toString()

    private fun append(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is String -> quote(out, value)
            is Boolean, is Number -> out.append(value.toString())
            is ByteArray -> quote(out, value.joinToString("") { "%02x".format(it) })
            is Map<*, *> -> {
                out.append('{')
                value.entries.forEachIndexed { index, entry ->
                    if (index > 0) out.append(',')
                    quote(out, entry.key.toString())
                    out.append(':')
                    append(out, entry.value)
                }
                out.append('}')
            }

            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    append(out, item)
                }
                out.append(']')
            }

            else -> quote(out, value.toString())
        }
    }

    private fun quote(out: StringBuilder, text: String) {
        out.append('"')
        for (character in text) {
            when (character) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (character < ' ') out.append("\\u%04x".format(character.code)) else out.append(character)
            }
        }
        out.append('"')
    }
}
