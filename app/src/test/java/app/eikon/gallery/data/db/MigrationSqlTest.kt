package app.eikon.gallery.data.db

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The 2 to 3 migration must (a) run on a real version 2 database without losing data and (b) create
 * exactly the tables Room expects. Room itself only notices a mismatch on a phone at first launch, which is
 * far too late, so this runs the migration SQL against real SQLite and compares it with the schema export.
 */
class MigrationSqlTest {
    private lateinit var db: Connection
    private val schemaDir = File("schemas/app.eikon.gallery.data.db.EikonDatabase")

    @Before
    fun openVersion2() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(2).forEach(::execute)
        execute("INSERT INTO media VALUES (1, 'a.jpg', 'image/jpeg', 0, 5, 5, 5, 10, 10, 0, 7, 'DCIM/Camera/', 'Camera', 1, 0, 0, 0, 0)")
        execute("INSERT INTO album (id, name, createdAt, position) VALUES (1, 'Trip', 0, 0)")
        execute("INSERT INTO album_item VALUES (1, 1, 0)")
        execute("INSERT INTO hidden_media VALUES (1, 0)")
    }

    @After
    fun close() = db.close()

    @Test
    fun migrationStatementsAreExactlyWhatRoomExportedForVersion3() {
        val exportedNew = statementsOf(3).filter { sql -> STATEMENTS_TABLES.any { "`$it`" in sql } }
        assertEquals(exportedNew.toSet(), DatabaseMigrations.STATEMENTS_2_3.toSet())
    }

    @Test
    fun migrationRunsOnAVersion2DatabaseAndKeepsEveryExistingRow() {
        DatabaseMigrations.STATEMENTS_2_3.forEach(::execute)

        assertEquals(1, count("media"))
        assertEquals(1, count("album"))
        assertEquals(1, count("album_item"))
        assertEquals(1, count("hidden_media"))
        listOf("index_state", "media_geo", "media_search").forEach { assertEquals(it, 0, count(it)) }
    }

    @Test
    fun theMigratedDatabaseHasTheSameObjectsAsAFreshVersion3() {
        DatabaseMigrations.STATEMENTS_2_3.forEach(::execute)
        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(3).forEach { fresh.createStatement().use { s -> s.execute(it) } }

        val expected = userObjects(fresh)
        val migrated = userObjects(db)
        assertEquals("only fresh: ${expected - migrated}; only migrated: ${migrated - expected}", expected, migrated)
        assertTrue("table:media_search" in expected)
        fresh.close()
    }

    @Test
    fun theFullTextIndexIgnoresCaseAndAccentsOnRealSqlite() {
        DatabaseMigrations.STATEMENTS_2_3.forEach(::execute)
        execute("INSERT INTO media_search (rowid, filename, ocr) VALUES (1, 'img 20250814 101010', 'Ricevuta IKEA Città di Milano')")

        assertEquals(listOf(1L), rowsMatching("citta"))
        assertEquals(listOf(1L), rowsMatching("CITTÀ"))
        assertEquals(listOf(1L), rowsMatching("ikea"))
        assertEquals(listOf(1L), rowsMatching("2025*"))
        assertEquals(listOf(1L), rowsMatching("ricev*"))
        assertEquals(emptyList<Long>(), rowsMatching("napoli"))
    }

    @Test
    fun updatingOneColumnKeepsTheOtherAndReindexes() {
        DatabaseMigrations.STATEMENTS_2_3.forEach(::execute)
        execute("INSERT INTO media_search (rowid, filename, ocr) VALUES (1, 'holiday', '')")
        execute("UPDATE media_search SET ocr = 'scontrino farmacia' WHERE rowid = 1")

        assertEquals(listOf(1L), rowsMatching("holiday"))
        assertEquals(listOf(1L), rowsMatching("farmacia"))
        execute("UPDATE media_search SET filename = 'trip' WHERE rowid = 1")
        assertEquals(emptyList<Long>(), rowsMatching("holiday"))
        assertEquals(listOf(1L), rowsMatching("farmacia"))
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun execute(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun count(table: String): Int = db.createStatement().use { st ->
        st.executeQuery("SELECT COUNT(*) FROM `$table`").use { rs ->
            rs.next()
            rs.getInt(1)
        }
    }

    private fun rowsMatching(query: String): List<Long> = db.prepareStatement("SELECT rowid FROM media_search WHERE media_search MATCH ?").use { st ->
        st.setString(1, query)
        st.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getLong(1) else null }.toList() }
    }

    /** Names and types of everything the migration is responsible for, ignoring SQLite's internal tables. */
    private fun userObjects(connection: Connection): Set<String> = connection.createStatement().use { st ->
        st.executeQuery("SELECT type || ':' || name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%' AND name NOT LIKE 'media_search_%'").use { rs ->
            generateSequence { if (rs.next()) rs.getString(1) else null }.toSet()
        }
    }

    /** CREATE statements of one exported schema version, with the table-name placeholder resolved. */
    private fun statementsOf(version: Int): List<String> {
        val text = File(schemaDir, "$version.json").readText()
        val token = Regex("\"tableName\":\\s*\"(\\w+)\"|\"createSql\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        var table = ""
        val statements = mutableListOf<String>()
        for (match in token.findAll(text)) {
            if (match.groupValues[1].isNotEmpty()) {
                table = match.groupValues[1]
                continue
            }
            val sql = match.groupValues[2].replace("\\\"", "\"").replace("\${TABLE_NAME}", table)
            if (sql.startsWith("CREATE")) statements += sql
        }
        return statements
    }

    private companion object {
        val STATEMENTS_TABLES = listOf("index_state", "media_geo", "media_search")
    }
}
