package com.kitworks.tablekit.data.avro

class AvroException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The part of Avro's type system a viewer needs.
 *
 * Named types are resolved through a registry so recursive schemas - a record
 * that refers back to itself - parse without looping.
 */
sealed class AvroSchema {

    object Null : AvroSchema()
    object Boolean : AvroSchema()
    object Int : AvroSchema()
    object Long : AvroSchema()
    object Float : AvroSchema()
    object Double : AvroSchema()
    object Bytes : AvroSchema()
    object Text : AvroSchema()

    class Fixed(val name: kotlin.String, val size: kotlin.Int, val logical: Logical?) : AvroSchema()
    class Enum(val name: kotlin.String, val symbols: List<kotlin.String>) : AvroSchema()
    class Array(val items: AvroSchema) : AvroSchema()
    class Map(val values: AvroSchema) : AvroSchema()
    class Union(val branches: List<AvroSchema>) : AvroSchema() {

        /** The common `["null", X]` shape, which is just a nullable X. */
        val nullableOf: AvroSchema?
            get() = branches.singleOrNull { it != Null }?.takeIf { branches.size == 2 && branches.contains(Null) }
    }

    class Field(val name: kotlin.String, val schema: AvroSchema)
    class Record(val name: kotlin.String, val fields: List<Field>) : AvroSchema()

    /** Decorations Avro puts on primitives to mean dates, times and decimals. */
    class Logical(val name: kotlin.String, val precision: kotlin.Int = 0, val scale: kotlin.Int = 0)

    class Decorated(val underlying: AvroSchema, val logical: Logical) : AvroSchema()

    companion object {

        fun parse(json: kotlin.String): AvroSchema = read(Json.parse(json), mutableMapOf())

        private fun read(node: Any?, named: MutableMap<kotlin.String, AvroSchema>): AvroSchema = when (node) {
            is kotlin.String -> primitive(node, named)
            is List<*> -> Union(node.map { read(it, named) })
            is kotlin.collections.Map<*, *> -> readObject(node, named)
            else -> throw AvroException("Unsupported schema node: $node")
        }

        private fun primitive(name: kotlin.String, named: kotlin.collections.Map<kotlin.String, AvroSchema>): AvroSchema =
            when (name) {
                "null" -> Null
                "boolean" -> Boolean
                "int" -> Int
                "long" -> Long
                "float" -> Float
                "double" -> Double
                "bytes" -> Bytes
                "string" -> Text
                else -> named[name] ?: throw AvroException("Unknown type in the schema: $name")
            }

        private fun readObject(
            node: kotlin.collections.Map<*, *>,
            named: MutableMap<kotlin.String, AvroSchema>,
        ): AvroSchema {
            val type = node["type"]
            if (type is List<*> || type is kotlin.collections.Map<*, *>) return read(type, named)

            val typeName = type as? kotlin.String ?: throw AvroException("A schema node has no type.")
            val logical = (node["logicalType"] as? kotlin.String)?.let {
                Logical(it, (node["precision"] as? kotlin.Long)?.toInt() ?: 0, (node["scale"] as? kotlin.Long)?.toInt() ?: 0)
            }

            val schema = when (typeName) {
                "record", "error" -> {
                    val name = fullName(node)
                    val fields = mutableListOf<Field>()
                    val record = Record(name, fields)
                    // Registered before its fields are read, so a record that
                    // refers to itself resolves instead of recursing forever.
                    named[name] = record
                    (node["fields"] as? List<*> ?: emptyList<Any?>()).forEach { entry ->
                        val field = entry as? kotlin.collections.Map<*, *> ?: throw AvroException("Malformed record field.")
                        val fieldName = field["name"] as? kotlin.String ?: throw AvroException("A record field has no name.")
                        fields += Field(fieldName, read(field["type"], named))
                    }
                    record
                }

                "enum" -> Enum(
                    fullName(node),
                    (node["symbols"] as? List<*>).orEmpty().map { it.toString() },
                ).also { named[it.name] = it }

                "fixed" -> Fixed(
                    fullName(node),
                    (node["size"] as? kotlin.Long)?.toInt() ?: throw AvroException("A fixed type has no size."),
                    logical,
                ).also { named[it.name] = it }

                "array" -> Array(read(node["items"], named))
                "map" -> Map(read(node["values"], named))
                else -> primitive(typeName, named)
            }

            return if (logical != null && schema !is Fixed) Decorated(schema, logical) else schema
        }

        private fun fullName(node: kotlin.collections.Map<*, *>): kotlin.String {
            val name = node["name"] as? kotlin.String ?: throw AvroException("A named type has no name.")
            val namespace = node["namespace"] as? kotlin.String
            return if (namespace.isNullOrEmpty() || name.contains('.')) name else "$namespace.$name"
        }
    }
}
