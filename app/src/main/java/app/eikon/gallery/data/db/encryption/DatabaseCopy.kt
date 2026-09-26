package app.eikon.gallery.data.db.encryption

/**
 * Copies every table of the database [session] has open (the old, readable one) into another database that already has the same tables (Room made it), and checks the result.
 *
 * Tables are found by asking the new database what it has, so a table added to the schema later is copied without anyone remembering to list it. Columns are matched by name, because a
 * database that went through migrations does not keep its columns in the order a fresh one has. Full-text tables are copied as themselves (their index is rebuilt by the insert); the
 * hidden tables SQLite keeps for them are not, they come with it.
 *
 * Nothing is copied unless everything is: the copy is one transaction, and afterwards each table must have as many rows as it had, and the new file must pass SQLite's own check.
 * A problem is an exception, and the old database has not been touched.
 */
class DatabaseCopy(private val session: SqlSession) {
    /** [keyClause] is what follows `AS target` in ATTACH to give the new database's key (empty when it has none). Returns the number of rows copied, per table. */
    fun copyTo(path: String, keyClause: String): Map<String, Long> {
        session.execute("ATTACH DATABASE ${quoteText(path)} AS $TARGET$keyClause")
        try {
            val tables = tablesToCopy()
            session.transaction {
                tables.tables.forEach { copyTable(it, it in tables.virtual) }
                copySequences()
            }
            return verified(tables.tables)
        } finally {
            session.execute("DETACH DATABASE $TARGET")
        }
    }

    private class Tables(val tables: List<String>, val virtual: Set<String>)

    private fun tablesToCopy(): Tables {
        val inTarget = names("$TARGET.sqlite_master", "type = 'table'")
        val virtual = names("$TARGET.sqlite_master", "type = 'table' AND sql LIKE 'CREATE VIRTUAL TABLE%'").toSet()
        val hidden = virtual.flatMap { table -> HIDDEN_SUFFIXES.map { table + it } }.toSet()
        val wanted = inTarget.filterNot { it.startsWith("sqlite_") || it in IGNORED || it in hidden }.sorted()
        val inSource = names("main.sqlite_master", "type = 'table'").toSet()
        val missing = wanted.filterNot { it in inSource }
        check(missing.isEmpty()) { "the old database lacks tables the new one has: $missing" }
        return Tables(wanted, virtual)
    }

    private fun names(from: String, where: String) = session.strings("SELECT name FROM $from WHERE $where")

    private fun columns(schema: String, table: String) =
        session.strings("SELECT name FROM pragma_table_info(${quoteText(table)}, ${quoteText(schema)}) ORDER BY cid")

    private fun copyTable(table: String, virtual: Boolean) {
        val inSource = columns("main", table).toSet()
        val shared = columns(TARGET, table).filter { it in inSource }
        // A full-text table has no primary key column to carry the id of the row it indexes: that is its rowid.
        val list = ((if (virtual) listOf("rowid") else emptyList()) + shared).joinToString { quoteName(it) }
        session.execute("INSERT INTO $TARGET.${quoteName(table)} ($list) SELECT $list FROM main.${quoteName(table)}")
    }

    /** The counters behind AUTOINCREMENT, so an id that was used and deleted is not handed out again (a stored preference could still point at it). */
    private fun copySequences() {
        val sequence = "sqlite_sequence"
        val both = names("main.sqlite_master", "name = '$sequence'").isNotEmpty() && names("$TARGET.sqlite_master", "name = '$sequence'").isNotEmpty()
        if (!both) return
        session.execute(
            "UPDATE $TARGET.$sequence AS t SET seq = max(seq, coalesce((SELECT s.seq FROM main.$sequence s WHERE s.name = t.name), 0))",
        )
        session.execute(
            "INSERT INTO $TARGET.$sequence(name, seq) SELECT name, seq FROM main.$sequence " +
                "WHERE name IN (SELECT name FROM $TARGET.sqlite_master WHERE type = 'table') " +
                "AND name NOT IN (SELECT name FROM $TARGET.$sequence)",
        )
    }

    private fun verified(tables: List<String>): Map<String, Long> {
        val counts = tables.associateWith { table ->
            val before = count("main", table)
            val after = count(TARGET, table)
            check(before == after) { "table $table: $before rows before the copy, $after after" }
            after
        }
        val outcome = session.strings("PRAGMA $TARGET.quick_check")
        check(outcome == listOf("ok")) { "the new database failed SQLite's own check" }
        return counts
    }

    private fun count(schema: String, table: String) = session.strings("SELECT count(*) FROM $schema.${quoteName(table)}").single().toLong()

    private companion object {
        const val TARGET = "sealed"

        /** Tables SQLite, Android or Room keep for themselves and make on their own. */
        val IGNORED = setOf("room_master_table", "android_metadata")

        /** The hidden tables of full-text tables (FTS3/4 and FTS5). */
        val HIDDEN_SUFFIXES = listOf("_content", "_segments", "_segdir", "_docsize", "_stat", "_data", "_idx", "_config")
    }
}
