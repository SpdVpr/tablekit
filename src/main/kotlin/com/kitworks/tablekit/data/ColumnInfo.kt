package com.kitworks.tablekit.data

/**
 * One column of the opened relation, as reported by `DESCRIBE`.
 *
 * [nested] columns (STRUCT, LIST, MAP, UNION) are rendered as JSON instead of
 * relying on the driver's object mapping - broken nested type support is one of
 * the incumbent bugs we are here to fix.
 */
data class ColumnInfo(
    val index: Int,
    val name: String,
    val typeName: String,
    val nullable: Boolean,
) {
    val nested: Boolean
        get() = NESTED_PREFIXES.any { typeName.startsWith(it, ignoreCase = true) } ||
            typeName.endsWith("[]")

    /** Numbers are right aligned in the grid so digits line up. */
    val numeric: Boolean
        get() = !nested && NUMERIC_TYPES.any { typeName.startsWith(it, ignoreCase = true) }

    private companion object {
        val NESTED_PREFIXES = listOf("STRUCT", "MAP", "UNION", "LIST")

        val NUMERIC_TYPES = listOf(
            "TINYINT", "SMALLINT", "INTEGER", "BIGINT", "HUGEINT",
            "UTINYINT", "USMALLINT", "UINTEGER", "UBIGINT", "UHUGEINT",
            "FLOAT", "DOUBLE", "REAL", "DECIMAL",
        )
    }
}
