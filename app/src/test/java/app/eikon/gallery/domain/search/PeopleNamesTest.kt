package app.eikon.gallery.domain.search

import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleNamesTest {
    private val names = PeopleNames(listOf(1L to "Marco Rossi", 2L to "Marco Bianchi", 3L to "Giulia", 4L to "Zoë", 5L to "A"))

    @Test
    fun aFirstNameMatchesEveryoneWhoHasIt() {
        assertEquals(setOf(1L, 2L), names.match("marco")!!.personIds)
    }

    @Test
    fun aFullNameMatchesOnlyThatPerson() {
        assertEquals(setOf(1L), names.match("marco rossi")!!.personIds)
        assertEquals(setOf(2L), names.match("bianchi")!!.personIds)
    }

    @Test
    fun caseAndAccentsDoNotMatter() {
        assertEquals(setOf(4L), names.match(TextNormalizer.name("ZOE"))!!.personIds)
        assertEquals(setOf(4L), names.match("zoe")!!.personIds)
    }

    @Test
    fun aOneLetterNameIsNotSearchableByItsWordsButIsByItsWholeName() {
        assertEquals(setOf(5L), names.match("a")!!.personIds) // whole name "a"
        assertNull(names.match("luca"))
    }

    private val parser = SearchQueryParser(ZoneId.of("Europe/Rome"), { LocalDate.of(2026, 9, 25) }, PlaceMatcher.None, names)

    @Test
    fun theParserTurnsANameIntoAPersonTermAndKeepsTheOtherWordsForTheirOwnSearch() {
        val spec = parser.parse("foto di Giulia con il cane")

        val person = spec.terms.single { it.person != null }
        assertEquals(setOf(3L), person.person!!.personIds)
        assertEquals("cane", spec.semanticText) // the name is not something to look for in the pictures
    }

    @Test
    fun theLongestNameWinsSoAFullNameIsOnePerson() {
        val spec = parser.parse("marco rossi")
        assertEquals(1, spec.terms.size)
        assertEquals(setOf(1L), spec.terms.single().person!!.personIds)
    }

    @Test
    fun anUnknownWordStaysAPlainTerm() {
        val spec = parser.parse("luca")
        assertTrue(spec.terms.single().person == null)
        assertEquals("luca", spec.semanticText)
    }

    @Test
    fun withoutNamedPeopleNothingChanges() {
        val plain = SearchQueryParser(ZoneId.of("Europe/Rome"), { LocalDate.of(2026, 9, 25) }).parse("giulia cane")
        assertEquals(listOf("giulia", "cane"), plain.terms.map { it.text })
        assertTrue(plain.terms.all { it.person == null })
    }
}
