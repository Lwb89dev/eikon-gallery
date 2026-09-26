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
 * The manual migrations must (a) run on a real database of the previous version without losing data and
 * (b) create exactly the tables Room expects. Room itself only notices a mismatch on a phone at first launch, which is
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

    // --- 3 to 4 ----------------------------------------------------------------------------------

    @Test
    fun migration3To4StatementsAreExactlyWhatRoomExportedForVersion4() {
        val exportedNew = statementsOf(4).filter { sql -> listOf("media_embedding", "search_hit", "person", "face").any { "`$it`" in sql } }
        assertEquals(exportedNew.toSet(), DatabaseMigrations.STATEMENTS_3_4.toSet())
    }

    @Test
    fun migration3To4KeepsEveryRowAndProducesTheSameObjectsAsAFreshVersion4() {
        val v3 = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(3).forEach { v3.createStatement().use { s -> s.execute(it) } }
        v3.createStatement().use { it.execute("INSERT INTO index_state VALUES (1, 'OCR', 1, 0, 0)") }
        v3.createStatement().use { it.execute("INSERT INTO media_search (rowid, filename, ocr) VALUES (1, 'a', 'ricevuta')") }

        DatabaseMigrations.STATEMENTS_3_4.forEach { v3.createStatement().use { s -> s.execute(it) } }

        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(4).forEach { fresh.createStatement().use { s -> s.execute(it) } }
        assertEquals(userObjects(fresh), userObjects(v3))
        assertEquals(1, v3.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM index_state").use { rs -> rs.next(); rs.getInt(1) } })
        assertTrue("table:media_embedding" in userObjects(v3))
        fresh.close()
        v3.close()
    }

    @Test
    fun aVectorRoundTripsThroughTheEmbeddingTable() {
        DatabaseMigrations.STATEMENTS_2_3.forEach(::execute)
        DatabaseMigrations.STATEMENTS_3_4.forEach(::execute)
        val vector = ByteArray(512) { (it - 256).toByte() }
        db.prepareStatement("INSERT INTO media_embedding VALUES (1, 'm', ?)").use { st ->
            st.setBytes(1, vector)
            st.executeUpdate()
        }
        val stored = db.createStatement().use { st -> st.executeQuery("SELECT vector FROM media_embedding WHERE mediaId = 1").use { rs -> rs.next(); rs.getBytes(1) } }
        assertTrue(vector.contentEquals(stored))
    }

    // --- 4 to 5 ----------------------------------------------------------------------------------

    @Test
    fun migration4To5StatementsAreExactlyWhatRoomExportedForVersion5() {
        val newTables = listOf("content_hash", "perceptual_hash", "duplicate_dismissed", "memory_preference")
        val exportedNew = statementsOf(5).filter { sql -> newTables.any { "`$it`" in sql } }
        assertEquals(exportedNew.toSet(), DatabaseMigrations.STATEMENTS_4_5.toSet())
    }

    @Test
    fun migration4To5KeepsEveryRowAndProducesTheSameObjectsAsAFreshVersion5() {
        val v4 = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(4).forEach { v4.createStatement().use { s -> s.execute(it) } }
        v4.createStatement().use { it.execute("INSERT INTO person (id, name, isFavorite, isHidden, isPinned, createdAt) VALUES (1, 'Marco', 0, 0, 0, 0)") }

        DatabaseMigrations.STATEMENTS_4_5.forEach { v4.createStatement().use { s -> s.execute(it) } }

        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(5).forEach { fresh.createStatement().use { s -> s.execute(it) } }
        assertEquals(userObjects(fresh), userObjects(v4))
        assertEquals(1, v4.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM person").use { rs -> rs.next(); rs.getInt(1) } })
        fresh.close()
        v4.close()
    }

    // --- 5 to 6 ----------------------------------------------------------------------------------

    @Test
    fun migration5To6StatementsAreExactlyWhatRoomExportedForVersion6() {
        val exportedNew = statementsOf(6).filter { sql -> "`edit_recipe`" in sql }
        assertEquals(exportedNew.toSet(), DatabaseMigrations.STATEMENTS_5_6.toSet())
    }

    @Test
    fun migration5To6KeepsEveryRowAndProducesTheSameObjectsAsAFreshVersion6() {
        val v5 = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(5).forEach { v5.createStatement().use { s -> s.execute(it) } }
        v5.createStatement().use { it.execute("INSERT INTO person (id, name, isFavorite, isHidden, isPinned, createdAt) VALUES (1, 'Marco', 0, 0, 0, 0)") }

        DatabaseMigrations.STATEMENTS_5_6.forEach { v5.createStatement().use { s -> s.execute(it) } }

        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(6).forEach { fresh.createStatement().use { s -> s.execute(it) } }
        assertEquals(userObjects(fresh), userObjects(v5))
        assertTrue("table:edit_recipe" in userObjects(v5))
        assertEquals(1, v5.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM person").use { rs -> rs.next(); rs.getInt(1) } })
        fresh.close()
        v5.close()
    }

    // --- 6 to 7 ----------------------------------------------------------------------------------

    @Test
    fun migration6To7StatementsAreExactlyTheIndexesRoomExportedForVersion7() {
        val exportedNew = statementsOf(7).filter { sql -> "relativePath" in sql && sql.startsWith("CREATE INDEX") }
        assertEquals(exportedNew.toSet(), DatabaseMigrations.STATEMENTS_6_7.toSet())
    }

    @Test
    fun migration6To7KeepsEveryRowAndProducesTheSameObjectsAsAFreshVersion7() {
        val v6 = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(6).forEach { v6.createStatement().use { s -> s.execute(it) } }
        v6.createStatement().use { it.execute("INSERT INTO media VALUES (1, 'a.jpg', 'image/jpeg', 0, 5, 5, 5, 10, 10, 0, 7, 'DCIM/Camera/', 'Camera', 1, 0, 0, 0, 0)") }

        DatabaseMigrations.STATEMENTS_6_7.forEach { v6.createStatement().use { s -> s.execute(it) } }

        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(7).forEach { fresh.createStatement().use { s -> s.execute(it) } }
        assertEquals(userObjects(fresh), userObjects(v6))
        assertTrue("index:index_media_relativePath_takenAt" in userObjects(v6))
        assertEquals(1, v6.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM media").use { rs -> rs.next(); rs.getInt(1) } })
        fresh.close()
        v6.close()
    }

    // --- 7 to 8 ----------------------------------------------------------------------------------

    @Test
    fun migration7To8StatementsAreExactlyWhatRoomExportedForVersion8() {
        val exportedNew = statementsOf(8).filter { sql -> "`backup_item`" in sql }
        assertEquals(exportedNew.toSet(), DatabaseMigrations.STATEMENTS_7_8.toSet())
    }

    @Test
    fun migration7To8KeepsEveryRowAndProducesTheSameObjectsAsAFreshVersion8() {
        val v7 = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(7).forEach { v7.createStatement().use { s -> s.execute(it) } }
        v7.createStatement().use { it.execute("INSERT INTO media VALUES (1, 'a.jpg', 'image/jpeg', 0, 5, 5, 5, 10, 10, 0, 7, 'DCIM/Camera/', 'Camera', 1, 0, 0, 0, 0)") }

        DatabaseMigrations.STATEMENTS_7_8.forEach { v7.createStatement().use { s -> s.execute(it) } }

        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(8).forEach { fresh.createStatement().use { s -> s.execute(it) } }
        assertEquals(userObjects(fresh), userObjects(v7))
        assertTrue("table:backup_item" in userObjects(v7))
        assertEquals(1, v7.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM media").use { rs -> rs.next(); rs.getInt(1) } })
        fresh.close()
        v7.close()
    }

    // --- 8 to 9 ----------------------------------------------------------------------------------

    @Test
    fun migration8To9StatementsAreExactlyWhatRoomExportedForVersion9() {
        val exportedNew = statementsOf(9).filter { sql -> "`media_caption`" in sql || "`metadata_original`" in sql || "index_media_sizeBytes_isVideo" in sql }
        assertEquals(exportedNew.toSet(), DatabaseMigrations.STATEMENTS_8_9.toSet())
    }

    @Test
    fun migration8To9KeepsEveryRowAndProducesTheSameObjectsAsAFreshVersion9() {
        val v8 = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(8).forEach { v8.createStatement().use { s -> s.execute(it) } }
        v8.createStatement().use { it.execute("INSERT INTO media VALUES (1, 'a.jpg', 'image/jpeg', 0, 5, 5, 5, 10, 10, 0, 7, 'DCIM/Camera/', 'Camera', 1, 0, 0, 0, 0)") }

        DatabaseMigrations.STATEMENTS_8_9.forEach { v8.createStatement().use { s -> s.execute(it) } }

        val fresh = DriverManager.getConnection("jdbc:sqlite::memory:")
        statementsOf(9).forEach { fresh.createStatement().use { s -> s.execute(it) } }
        assertEquals(userObjects(fresh), userObjects(v8))
        assertTrue("index:index_media_sizeBytes_isVideo" in userObjects(v8))
        assertEquals(1, v8.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM media").use { rs -> rs.next(); rs.getInt(1) } })
        v8.createStatement().use { it.execute("INSERT INTO media_caption (rowid, caption) VALUES (1, 'Nonna a Napoli')") }
        assertEquals(1, v8.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM media_caption WHERE media_caption MATCH 'napoli'").use { rs -> rs.next(); rs.getInt(1) } })
        fresh.close()
        v8.close()
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
