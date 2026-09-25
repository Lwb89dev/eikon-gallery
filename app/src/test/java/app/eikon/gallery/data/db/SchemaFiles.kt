package app.eikon.gallery.data.db

import java.io.File

/** The CREATE statements of the newest schema Room exported, for tests that run SQL on a real SQLite. */
object SchemaFiles {
    fun newest(): List<String> {
        val directory = File("schemas/app.eikon.gallery.data.db.EikonDatabase")
        val newest = directory.listFiles { file -> file.extension == "json" }!!.maxByOrNull { it.nameWithoutExtension.toInt() }!!
        val token = Regex("\"tableName\":\\s*\"(\\w+)\"|\"createSql\":\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
        var table = ""
        val statements = mutableListOf<String>()
        for (match in token.findAll(newest.readText())) {
            if (match.groupValues[1].isNotEmpty()) {
                table = match.groupValues[1]
                continue
            }
            val sql = match.groupValues[2].replace("\\\"", "\"").replace("\${TABLE_NAME}", table)
            if (sql.startsWith("CREATE")) statements += sql
        }
        return statements
    }
}
