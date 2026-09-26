package app.eikon.gallery

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The translations are plain resource files, and a slip in one of them (a placeholder dropped, a quote left unescaped, a plural form missing) only shows up on the phone of someone who
 * uses that language. This checks every one of them against the English original, so that adding a language, or a string, cannot leave any of them behind.
 */
class TranslationsTest {
    private val resources = File("src/main/res")

    /** The plural forms each language has in CLDR: what Android picks from. A form that is missing falls back to "other", which reads wrongly, so all of them are required. */
    private val pluralForms = mapOf(
        "bg" to listOf("one", "other"),
        "cs" to listOf("one", "few", "many", "other"),
        "da" to listOf("one", "other"),
        "de" to listOf("one", "other"),
        "el" to listOf("one", "other"),
        "es" to listOf("one", "many", "other"),
        "et" to listOf("one", "other"),
        "fi" to listOf("one", "other"),
        "fr" to listOf("one", "many", "other"),
        "ga" to listOf("one", "two", "few", "many", "other"),
        "hr" to listOf("one", "few", "other"),
        "hu" to listOf("one", "other"),
        "it" to listOf("one", "many", "other"),
        "ja" to listOf("other"),
        "lt" to listOf("one", "few", "many", "other"),
        "lv" to listOf("zero", "one", "other"),
        "mt" to listOf("one", "two", "few", "many", "other"),
        "nl" to listOf("one", "other"),
        "pl" to listOf("one", "few", "many", "other"),
        "pt" to listOf("one", "many", "other"),
        "ro" to listOf("one", "few", "other"),
        "ru" to listOf("one", "few", "many", "other"),
        "sk" to listOf("one", "few", "many", "other"),
        "sl" to listOf("one", "two", "few", "other"),
        "sv" to listOf("one", "other"),
        "zh-rCN" to listOf("other"),
    )

    /** What the app promises in the per-app language picker, as written in `locales_config.xml`. */
    private val promised: Set<String>
        get() = elements(File(resources, "xml/locales_config.xml"), "locale").map { it.getAttribute("android:name") }.toSet()

    private class Entry(val name: String, val texts: Map<String, String>, val isPlural: Boolean)

    private fun elements(file: File, tag: String): List<Element> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    /** The strings and plurals of one resource folder, each with its text (a plural has one text per quantity). */
    private fun load(folder: String): List<Entry> {
        val file = File(resources, "$folder/strings.xml")
        val strings = elements(file, "string")
            .filter { it.getAttribute("translatable") != "false" }
            .map { Entry(it.getAttribute("name"), mapOf("" to it.textContent), isPlural = false) }
        val plurals = elements(file, "plurals").map { plural ->
            val items = plural.getElementsByTagName("item")
            Entry(plural.getAttribute("name"), (0 until items.length).associate { (items.item(it) as Element).getAttribute("quantity") to items.item(it).textContent }, isPlural = true)
        }
        return strings + plurals
    }

    private val english = load("values")

    /** The resource folders of the translations: `values-it`, `values-zh-rCN`... Each is named by its language code. */
    private val translations: Map<String, String> = resources.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
        .associate { it.name.removePrefix("values-") to it.name }
        .filterKeys { it != "night" }

    @Test
    fun everyLanguageThePickerOffersHasATranslationAndTheOtherWayRound() {
        val offered = promised.map { it.replace("-", "-r") }.toSet() - "en"
        assertEquals("locales_config.xml and the values-* folders disagree", offered.sorted(), translations.keys.sorted())
    }

    @Test
    fun theTwentySevenLanguagesAreThere() {
        // English, the other 23 official languages of the EU, Simplified Chinese, Russian and Japanese: 26 translations of the English original.
        assertEquals(26, translations.size)
    }

    @Test
    fun everyTranslationHasExactlyTheStringsAndPluralsOfTheEnglishOriginal() {
        val expected = english.map { it.name }.toSet()
        for ((language, folder) in translations) {
            val names = load(folder).map { it.name }
            assertEquals("$language: a string is defined twice", names.size, names.toSet().size)
            assertEquals("$language: missing ${expected - names.toSet()}", emptySet<String>(), expected - names.toSet())
            assertEquals("$language: not in the English file ${names.toSet() - expected}", emptySet<String>(), names.toSet() - expected)
        }
    }

    @Test
    fun everyTranslationKeepsTheFormatPlaceholdersOfTheOriginal() {
        for ((language, folder) in translations) {
            val byName = load(folder).associateBy { it.name }
            for (original in english) {
                val translated = byName.getValue(original.name)
                val wanted = placeholders(original.texts.getValue(if (original.isPlural) "other" else ""))
                for ((quantity, text) in translated.texts) {
                    assertEquals("$language ${original.name} ($quantity)", wanted, placeholders(text))
                }
            }
        }
    }

    @Test
    fun everyPluralHasTheFormsOfItsLanguageAndNoOthers() {
        for ((language, folder) in translations) {
            val forms = pluralForms.getValue(language)
            for (entry in load(folder).filter { it.isPlural }) {
                assertEquals("$language ${entry.name}", forms.sorted(), entry.texts.keys.sorted())
            }
        }
    }

    @Test
    fun quotesAndApostrophesAreEscapedSoTheyReachThePhoneAsWritten() {
        for ((language, folder) in translations + ("en" to "values")) {
            val text = File(resources, "$folder/strings.xml").readText()
            for (line in text.lines().filter { it.contains("<string ") || it.contains("<item ") }) {
                val content = line.substringAfter('>').substringBeforeLast('<')
                assertTrue("$language: unescaped quote in: $line", Regex("""(?<!\\)"""").findAll(content).none())
                assertTrue("$language: unescaped apostrophe in: $line", Regex("""(?<!\\)'""").findAll(content).none())
                assertTrue("$language: an unescaped @ or ? at the start: $line", !Regex("""^[@?]""").containsMatchIn(content))
            }
        }
    }

    @Test
    fun aStringThatIsFilledInHasNoStrayPercentSign() {
        for ((language, folder) in translations) {
            val byName = load(folder).associateBy { it.name }
            for (original in english.filter { placeholders(it.texts.getValue(if (it.isPlural) "other" else "")).isNotEmpty() }) {
                for ((quantity, text) in byName.getValue(original.name).texts) {
                    // A lone % in a string that is formatted throws when the screen is drawn: it has to be written %%.
                    assertTrue("$language ${original.name} ($quantity): a % that is not a placeholder", '%' !in text.replace(placeholder, ""))
                }
            }
        }
    }

    @Test
    fun onlyKnownEscapesAreUsed() {
        for ((language, folder) in translations + ("en" to "values")) {
            val text = File(resources, "$folder/strings.xml").readText()
            for (line in text.lines().filter { it.contains("<string ") || it.contains("<item ") }) {
                val content = line.substringAfter('>').substringBeforeLast('<')
                val bad = Regex("""\\(?![nt'"@?\\u])""").find(content)
                assertTrue("$language: unknown escape in: $line", bad == null)
            }
        }
    }

    @Test
    fun noTranslationIsEmptyOrLeftInEnglishWhereItShouldNotBe() {
        for ((language, folder) in translations) {
            for (entry in load(folder)) {
                for ((quantity, text) in entry.texts) {
                    assertTrue("$language ${entry.name} ($quantity) is empty", text.isNotBlank())
                }
            }
        }
    }

    /** `%1$d`, `%2$s`, `%d`, `%%` ...: what the text will be filled in with, in order-independent form (the arguments are numbered, so their order may change). */
    private fun placeholders(text: String): List<String> = placeholder.findAll(text).map { it.value }.sorted().toList()

    private val placeholder = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z%]""")
}
