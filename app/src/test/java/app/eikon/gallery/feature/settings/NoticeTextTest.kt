package app.eikon.gallery.feature.settings

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** NOTICE.md as the licenses screen shows it. */
class NoticeTextTest {
    private fun texts(markdown: String) = NoticeText.lines(markdown).map { it.text }

    @Test
    fun aHeadingLosesItsHashesAndIsMarked() {
        val lines = NoticeText.lines("# Third-party notices\n\ntext")

        assertEquals(NoticeLine("Third-party notices", heading = true), lines.first())
    }

    @Test
    fun aTableBecomesAListOfItsRowsAndTheHeaderAndBarsAreDropped() {
        val markdown = "| What | Where | License |\n| --- | --- | --- |\n| Model | `assets/models/` | [MIT](https://example.org/mit) |\n| Data | `places/` | **CC BY** |"

        assertEquals(
            listOf("• Model", "    assets/models/", "    MIT (https://example.org/mit)", "", "• Data", "    places/", "    CC BY"),
            texts(markdown),
        )
    }

    @Test
    fun aLinkKeepsItsAddressOnlyWhenItGoesToTheWeb() {
        assertEquals(listOf("see LICENSE and OkHttp (https://square.github.io/okhttp/)"), texts("see [LICENSE](LICENSE) and [OkHttp](https://square.github.io/okhttp/)"))
    }

    @Test
    fun blankLinesAreNeverDoubledAndNeverOpenOrCloseTheText() {
        assertEquals(listOf("a", "", "b"), texts("\n\na\n\n\n\nb\n\n\n"))
    }

    @Test
    fun theRealNoticeReadsWellAndCarriesTheLicenseThatMustTravelWithTheApp() {
        val lines = texts(File("../NOTICE.md").readText())

        assertTrue("SQLCipher license is in the file", lines.any { it.contains("ZETETIC LLC") })
        assertTrue(lines.any { it.startsWith("Redistributions in binary form must reproduce") || it.contains("Redistributions in binary form must reproduce") })
        assertFalse("no table bars are left", lines.any { it.startsWith("|") })
        assertFalse("no emphasis marks are left", lines.any { "**" in it || "`" in it })
    }
}
