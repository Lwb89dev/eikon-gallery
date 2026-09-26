package app.eikon.gallery.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MiniJsonTest {
    @Test
    fun readsWhatTheServersSay() {
        val parsed = MiniJson.parse("""{"res":"pong","major":3,"ok":true,"none":null,"list":[1,"a",false],"nested":{"x":-1.5e2}}""") as Map<*, *>
        assertEquals("pong", parsed["res"])
        assertEquals(3.0, parsed["major"])
        assertEquals(true, parsed["ok"])
        assertNull(parsed["none"])
        assertEquals(listOf(1.0, "a", false), parsed["list"])
        assertEquals(-150.0, (parsed["nested"] as Map<*, *>)["x"])
    }

    @Test
    fun readsEscapesAndUnicode() {
        assertEquals("a\"b\\c/d\n\t\u00e9\u65e5", MiniJson.parse(""""a\"b\\c\/d\n\t\u00e9\u65e5""""))
    }

    @Test
    fun readsEmptyContainersAndSurroundingSpace() {
        assertEquals(emptyMap<String, Any?>(), MiniJson.parse("  {  }  "))
        assertEquals(emptyList<Any?>(), MiniJson.parse("[ ]"))
    }

    @Test
    fun refusesWhatIsNotJsonInsteadOfGuessing() {
        listOf("", "{", "{\"a\"}", "{\"a\":}", "[1,]", "[1 2]", "nul", "\"open", "{\"a\":1} extra", "<html>", "{'a':1}", "\"\\x\"", "\"\\u12\"").forEach { text ->
            try {
                MiniJson.parse(text)
                fail("accepted: $text")
            } catch (_: MiniJson.ParseException) {
            }
        }
    }

    @Test
    fun quotesEverythingThatNeedsIt() {
        assertEquals("\"a\\\"b\\\\c\\nd\\u0001\"", MiniJson.quote("a\"b\\c\nd\u0001"))
    }

    @Test
    fun whatIsQuotedReadsBackTheSame() {
        val text = "line1\nline2 \"quoted\" \\ back \u0000 nul é 日本"
        assertEquals(text, MiniJson.parse(MiniJson.quote(text)))
        assertTrue(MiniJson.quote(text).startsWith("\""))
    }
}
