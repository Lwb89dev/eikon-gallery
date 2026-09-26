package app.eikon.gallery.feature.settings

/** One line of the licenses screen; [heading] lines are drawn larger. */
data class NoticeLine(val text: String, val heading: Boolean = false)

/**
 * Turns NOTICE.md into what the licenses screen shows. The file is Markdown, written to be read on a repository page: tables, links and emphasis. Shown as it is, a table
 * would be a wall of bars, so a row becomes a short list, a link keeps its address only when it points to the web, and the marks are dropped.
 */
object NoticeText {
    fun lines(markdown: String): List<NoticeLine> {
        val source = markdown.lines()
        val out = mutableListOf<NoticeLine>()
        source.forEachIndexed { index, raw ->
            val nextIsSeparator = source.getOrNull(index + 1)?.let(::isTableSeparator) == true
            when {
                isTableSeparator(raw) || nextIsSeparator -> Unit
                raw.startsWith("|") -> out += tableRow(raw)
                raw.startsWith("#") -> out += NoticeLine(clean(raw.trimStart('#').trim()), heading = true)
                else -> out += NoticeLine(clean(raw))
            }
        }
        return collapseBlankLines(out)
    }

    private fun isTableSeparator(line: String) = line.startsWith("|") && line.all { it in "|- :" }

    private fun tableRow(line: String): List<NoticeLine> {
        val cells = line.trim('|').split('|').map { clean(it.trim()) }.filter { it.isNotEmpty() }
        val first = cells.firstOrNull() ?: return emptyList()
        return listOf(NoticeLine("• $first")) + cells.drop(1).map { NoticeLine("    $it") } + NoticeLine("")
    }

    private val LINK = Regex("""\[([^\]]+)]\(([^)]+)\)""")

    private fun clean(text: String): String =
        LINK.replace(text) { match ->
            val (label, address) = match.destructured
            if (address.startsWith("http")) "$label ($address)" else label
        }.replace("**", "").replace("`", "")

    /** Two or more empty lines in a row are one, and none open or close the text. */
    private fun collapseBlankLines(lines: List<NoticeLine>): List<NoticeLine> {
        val out = mutableListOf<NoticeLine>()
        for (line in lines) {
            if (line.text.isBlank() && (out.isEmpty() || out.last().text.isBlank())) continue
            out += line
        }
        while (out.isNotEmpty() && out.last().text.isBlank()) out.removeAt(out.lastIndex)
        return out
    }
}
