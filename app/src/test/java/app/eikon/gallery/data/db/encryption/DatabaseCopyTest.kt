package app.eikon.gallery.data.db.encryption

import app.eikon.gallery.data.db.SchemaFiles
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The copy that moves an existing library into the encrypted database, on a real SQLite. The schema is the newest one Room exported, so every table the app has is in it: a table added
 * later is covered by these tests without anyone touching them.
 */
class DatabaseCopyTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val opened = mutableListOf<Connection>()

    @After
    fun closeAll() = opened.forEach { it.close() }

    private fun connect(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.path}").also { opened += it }

    private fun run(connection: Connection, sql: String) = connection.createStatement().use { it.execute(sql) }

    private fun query(connection: Connection, sql: String): List<List<String?>> = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            val width = rows.metaData.columnCount
            buildList { while (rows.next()) add((1..width).map { rows.getString(it) }) }
        }
    }

    private lateinit var oldFile: File
    private lateinit var newFile: File
    private lateinit var old: Connection

    @Before
    fun twoDatabases() {
        oldFile = folder.newFile("old.db")
        newFile = folder.newFile("new.db")
        old = connect(oldFile)
    }

    private fun copy(): Map<String, Long> = DatabaseCopy(JdbcSession(old)).copyTo(newFile.path, "")

    private fun tablesOf(connection: Connection) = query(connection, "SELECT name, sql FROM sqlite_master WHERE type = 'table'")
        .map { it[0]!! to it[1]!! }
        .filterNot { (name, _) -> name.startsWith("sqlite_") || name == "room_master_table" }

    private fun userTables(connection: Connection): List<String> {
        val all = tablesOf(connection)
        val virtual = all.filter { it.second.startsWith("CREATE VIRTUAL TABLE") }.map { it.first }
        val hidden = virtual.flatMap { v -> listOf("_content", "_segments", "_segdir", "_docsize", "_stat").map { v + it } }.toSet()
        return all.map { it.first }.filterNot { it in hidden }.sorted()
    }

    private fun createSchema(connection: Connection) = SchemaFiles.newest().forEach { run(connection, it) }

    /** Two rows in [table], with a value of the right kind in every column and no two rows alike (so unique indices hold). */
    private fun fill(connection: Connection, table: String, virtual: Boolean) {
        val columns = query(connection, "PRAGMA table_info(\"$table\")")
        for (row in 1..ROWS) {
            val values = columns.map { valueFor(it[2]!!.uppercase(), row) }
            val names = columns.map { "\"${it[1]}\"" }
            val list = (if (virtual) listOf("rowid") else emptyList()) + names
            // A full-text row's id is the id of the photo it describes, so it must survive the copy: 7 and 14 are not what SQLite would number the rows itself (1 and 2).
            val literals = (if (virtual) listOf((row * 7).toString()) else emptyList()) + values
            run(connection, "INSERT INTO \"$table\" (${list.joinToString()}) VALUES (${literals.joinToString()})")
        }
    }

    private fun valueFor(type: String, row: Int) = when {
        "INT" in type -> row.toString()
        "REAL" in type -> "$row.5"
        "BLOB" in type -> "x'0${row}ff'"
        else -> "'text $row ünï'"
    }

    private fun schemaWithData(connection: Connection) {
        createSchema(connection)
        val tables = tablesOf(connection)
        val virtual = tables.filter { it.second.startsWith("CREATE VIRTUAL TABLE") }.map { it.first }.toSet()
        userTables(connection).forEach { fill(connection, it, it in virtual) }
    }

    @Test
    fun everyTableOfTheSchemaIsCopiedWithItsRowsIntact() {
        schemaWithData(old)
        val fresh = connect(newFile)
        createSchema(fresh)
        run(fresh, "CREATE TABLE room_master_table (id INTEGER PRIMARY KEY, identity_hash TEXT)")
        run(fresh, "INSERT INTO room_master_table VALUES (42, 'hash-of-the-new-database')")
        run(old, "CREATE TABLE android_metadata (locale TEXT)")
        run(old, "INSERT INTO android_metadata VALUES ('it_IT')")

        val counts = copy()

        assertEquals(userTables(fresh), counts.keys.sorted())
        assertTrue("the schema has few tables", counts.size > 15)
        counts.forEach { (table, n) -> assertEquals(table, ROWS.toLong(), n) }
        for (table in counts.keys) {
            val columns = query(fresh, "PRAGMA table_info(\"$table\")").joinToString { "quote(\"${it[1]}\")" }
            val order = if (table in virtualTables(fresh)) "rowid" else "1"
            assertEquals(table, query(old, "SELECT $columns FROM \"$table\" ORDER BY $order"), query(fresh, "SELECT $columns FROM \"$table\" ORDER BY $order"))
        }
        assertEquals("Room's own record of the new database is left alone", listOf(listOf("42", "hash-of-the-new-database")), query(fresh, "SELECT * FROM room_master_table"))
        assertFalse("android_metadata belongs to the old file", tablesOf(fresh).any { it.first == "android_metadata" })
    }

    private fun virtualTables(connection: Connection) = tablesOf(connection).filter { it.second.startsWith("CREATE VIRTUAL TABLE") }.map { it.first }.toSet()

    @Test
    fun theCopiedFullTextTablesAreSearchable() {
        schemaWithData(old)
        val fresh = connect(newFile)
        createSchema(fresh)

        copy()

        assertEquals(listOf(listOf("7"), listOf("14")), query(fresh, "SELECT rowid FROM media_search WHERE media_search MATCH 'text' ORDER BY rowid"))
        assertEquals("accents are ignored, as they are for a search", listOf(listOf("14")), query(fresh, "SELECT rowid FROM media_caption WHERE media_caption MATCH '2 uni'"))
    }

    @Test
    fun columnsAreMatchedByNameNotByPosition() {
        // A database that went through ALTER TABLE ... ADD COLUMN has its columns in another order than a fresh one.
        run(old, "CREATE TABLE t (b TEXT, a TEXT, extra TEXT)")
        run(old, "INSERT INTO t VALUES ('b1', 'a1', 'gone')")
        val fresh = connect(newFile)
        run(fresh, "CREATE TABLE t (a TEXT, b TEXT, added TEXT DEFAULT 'default')")

        copy()

        assertEquals(listOf(listOf("a1", "b1", "default")), query(fresh, "SELECT a, b, added FROM t"))
    }

    @Test
    fun aTableTheOldDatabaseLacksIsAnErrorNamingIt() {
        run(old, "CREATE TABLE present (x TEXT)")
        val fresh = connect(newFile)
        run(fresh, "CREATE TABLE present (x TEXT)")
        run(fresh, "CREATE TABLE absent (x TEXT)")

        try {
            copy()
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, "absent" in e.message!!)
        }
    }

    @Test
    fun aFailurePartWayLeavesTheNewDatabaseEmpty() {
        run(old, "CREATE TABLE a (x TEXT)")
        run(old, "INSERT INTO a VALUES ('one')")
        run(old, "CREATE TABLE b (y TEXT)")
        run(old, "INSERT INTO b VALUES ('two')")
        val fresh = connect(newFile)
        run(fresh, "CREATE TABLE a (x TEXT)")
        run(fresh, "CREATE TABLE b (y TEXT, z TEXT NOT NULL)")

        try {
            copy()
            fail("expected an error")
        } catch (_: java.sql.SQLException) {
            // b cannot be filled: z has no value to take.
        }

        assertEquals("what was copied before the failure is undone", 0, query(fresh, "SELECT count(*) FROM a")[0][0]!!.toInt())
        assertEquals("the old database is untouched", listOf(listOf("one")), query(old, "SELECT x FROM a"))
        assertEquals("the new one is not left attached", emptyList<Any>(), query(old, "SELECT name FROM pragma_database_list WHERE name = 'sealed'"))
    }

    @Test
    fun rowsThatDidNotArriveAreCaught() {
        run(old, "CREATE TABLE a (x TEXT)")
        run(old, "INSERT INTO a VALUES ('one')")
        val fresh = connect(newFile)
        run(fresh, "CREATE TABLE a (x TEXT)")
        // A table that swallows what is put into it stands for any way the count could come out wrong.
        run(fresh, "CREATE TRIGGER swallow AFTER INSERT ON a BEGIN DELETE FROM a; END")

        try {
            copy()
            fail("expected an error")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, "table a: 1 rows before the copy, 0 after" in e.message!!)
        }
    }

    @Test
    fun idsThatWereUsedAndDeletedAreNotHandedOutAgain() {
        val fresh = connect(newFile)
        for (db in listOf(old, fresh)) {
            run(db, "CREATE TABLE person (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
            run(db, "CREATE TABLE face (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
        }
        repeat(3) { run(old, "INSERT INTO person (name) VALUES ('p$it')") }
        run(old, "DELETE FROM person WHERE id = 3")
        repeat(5) { run(old, "INSERT INTO face (name) VALUES ('f$it')") }
        run(old, "DELETE FROM face")

        copy()

        run(fresh, "INSERT INTO person (name) VALUES ('new')")
        run(fresh, "INSERT INTO face (name) VALUES ('new')")
        assertEquals("the next id after 1, 2 and the deleted 3", listOf(listOf("4")), query(fresh, "SELECT id FROM person WHERE name = 'new'"))
        assertEquals("a table that is empty now still remembers 5", listOf(listOf("6")), query(fresh, "SELECT id FROM face WHERE name = 'new'"))
    }

    @Test
    fun aQuoteInThePathAndAnEmptyDatabaseAreFine() {
        val odd = folder.newFile("it's here.db")
        val fresh = connect(odd)
        run(fresh, "CREATE TABLE t (x TEXT)")
        run(old, "CREATE TABLE t (x TEXT)")

        val counts = DatabaseCopy(JdbcSession(old)).copyTo(odd.path, "")

        assertEquals(mapOf("t" to 0L), counts)
    }

    private companion object {
        const val ROWS = 2
    }
}
