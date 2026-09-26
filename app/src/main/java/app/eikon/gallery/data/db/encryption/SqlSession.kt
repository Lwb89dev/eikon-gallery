package app.eikon.gallery.data.db.encryption

/**
 * The little that copying a database needs from an open SQLite connection. On the phone it is SQLCipher's; the tests give it a plain SQLite on the JVM, so the copy is
 * checked without a device.
 */
interface SqlSession {
    fun execute(sql: String)

    /** The first column of every row, as text. */
    fun strings(sql: String): List<String>

    /** Runs [block] as one transaction: all of it or none of it. */
    fun <T> transaction(block: () -> T): T
}

/** [name] as an SQL identifier. */
internal fun quoteName(name: String) = "\"" + name.replace("\"", "\"\"") + "\""

/** [text] as an SQL string literal. */
internal fun quoteText(text: String) = "'" + text.replace("'", "''") + "'"
