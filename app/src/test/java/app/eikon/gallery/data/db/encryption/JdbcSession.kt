package app.eikon.gallery.data.db.encryption

import java.sql.Connection

/** [SqlSession] on a plain SQLite connection (the JVM has no SQLCipher): the copy logic is the same, only the encryption is missing. */
class JdbcSession(private val connection: Connection) : SqlSession {
    override fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun strings(sql: String): List<String> = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
    }

    override fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        try {
            return block().also { connection.commit() }
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }
}
