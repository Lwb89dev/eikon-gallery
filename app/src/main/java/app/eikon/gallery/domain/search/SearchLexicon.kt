package app.eikon.gallery.domain.search

import app.eikon.gallery.domain.CategoryFilter
import app.eikon.gallery.domain.TypeFilter
import java.text.Normalizer
import java.util.Locale

/** Words the search understands, in Italian and English. Everything here is already normalized. */
internal object SearchLexicon {
    /** Lowercase, accents removed, apostrophes and punctuation turned into spaces. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(MARKS, "")
            .replace(NOT_WORD_OR_DATE, " ")
            .trim()

    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD_OR_DATE = Regex("[^\\p{L}\\p{N}/.\\-]+")

    val monthsFull: Map<String, Int> = mapOf(
        "gennaio" to 1, "febbraio" to 2, "marzo" to 3, "aprile" to 4, "maggio" to 5, "giugno" to 6,
        "luglio" to 7, "agosto" to 8, "settembre" to 9, "ottobre" to 10, "novembre" to 11, "dicembre" to 12,
        "january" to 1, "february" to 2, "march" to 3, "april" to 4, "may" to 5, "june" to 6,
        "july" to 7, "august" to 8, "september" to 9, "october" to 10, "november" to 11, "december" to 12,
    )

    /** Short forms, only trusted when a day or year sits next to them ("ago 2025", not "ago" alone). */
    val monthsShort: Map<String, Int> = mapOf(
        "gen" to 1, "feb" to 2, "mar" to 3, "apr" to 4, "mag" to 5, "giu" to 6, "lug" to 7, "ago" to 8,
        "set" to 9, "sett" to 9, "ott" to 10, "nov" to 11, "dic" to 12,
        "jan" to 1, "jun" to 6, "jul" to 7, "aug" to 8, "sep" to 9, "sept" to 9, "oct" to 10, "dec" to 12,
    )

    /** Meteorological seasons of the northern hemisphere (winter is December to February). */
    val seasons: Map<String, Season> = mapOf(
        "primavera" to Season.SPRING, "spring" to Season.SPRING,
        "estate" to Season.SUMMER, "summer" to Season.SUMMER,
        "autunno" to Season.AUTUMN, "autumn" to Season.AUTUMN, "fall" to Season.AUTUMN,
        "inverno" to Season.WINTER, "winter" to Season.WINTER,
    )

    enum class Season(val months: Set<Int>) {
        SPRING(setOf(3, 4, 5)),
        SUMMER(setOf(6, 7, 8)),
        AUTUMN(setOf(9, 10, 11)),
        WINTER(setOf(12, 1, 2)),
    }

    enum class Relative { TODAY, YESTERDAY, THIS_WEEK, LAST_WEEK, THIS_MONTH, LAST_MONTH, THIS_YEAR, LAST_YEAR }

    /** Multi-word phrases first (longest match wins), keyed by their normalized token list. */
    val relativePhrases: Map<List<String>, Relative> = mapOf(
        listOf("oggi") to Relative.TODAY, listOf("today") to Relative.TODAY,
        listOf("ieri") to Relative.YESTERDAY, listOf("yesterday") to Relative.YESTERDAY,
        listOf("questa", "settimana") to Relative.THIS_WEEK, listOf("this", "week") to Relative.THIS_WEEK,
        listOf("settimana", "scorsa") to Relative.LAST_WEEK, listOf("last", "week") to Relative.LAST_WEEK,
        listOf("questo", "mese") to Relative.THIS_MONTH, listOf("this", "month") to Relative.THIS_MONTH,
        listOf("mese", "scorso") to Relative.LAST_MONTH, listOf("last", "month") to Relative.LAST_MONTH,
        listOf("quest", "anno") to Relative.THIS_YEAR, listOf("questo", "anno") to Relative.THIS_YEAR,
        listOf("this", "year") to Relative.THIS_YEAR,
        listOf("anno", "scorso") to Relative.LAST_YEAR, listOf("last", "year") to Relative.LAST_YEAR,
    )

    /** What a type word turns into: a type, "favorites only", or a kind (screenshots, RAW...). */
    sealed interface TypeWord {
        data class OfType(val type: TypeFilter) : TypeWord
        data object Favorites : TypeWord
        data class OfKind(val kind: CategoryFilter) : TypeWord
    }

    val typePhrases: Map<List<String>, TypeWord> = mapOf(
        listOf("video") to TypeWord.OfType(TypeFilter.VIDEOS), listOf("videos") to TypeWord.OfType(TypeFilter.VIDEOS),
        listOf("filmato") to TypeWord.OfType(TypeFilter.VIDEOS), listOf("filmati") to TypeWord.OfType(TypeFilter.VIDEOS),
        listOf("foto") to TypeWord.OfType(TypeFilter.PHOTOS), listOf("photo") to TypeWord.OfType(TypeFilter.PHOTOS),
        listOf("photos") to TypeWord.OfType(TypeFilter.PHOTOS), listOf("immagine") to TypeWord.OfType(TypeFilter.PHOTOS),
        listOf("immagini") to TypeWord.OfType(TypeFilter.PHOTOS), listOf("image") to TypeWord.OfType(TypeFilter.PHOTOS),
        listOf("images") to TypeWord.OfType(TypeFilter.PHOTOS), listOf("pictures") to TypeWord.OfType(TypeFilter.PHOTOS),
        listOf("preferiti") to TypeWord.Favorites, listOf("preferita") to TypeWord.Favorites,
        listOf("preferito") to TypeWord.Favorites, listOf("favorite") to TypeWord.Favorites,
        listOf("favorites") to TypeWord.Favorites, listOf("favourite") to TypeWord.Favorites,
        listOf("favourites") to TypeWord.Favorites,
        listOf("screenshot") to TypeWord.OfKind(CategoryFilter.SCREENSHOTS),
        listOf("screenshots") to TypeWord.OfKind(CategoryFilter.SCREENSHOTS),
        listOf("schermata") to TypeWord.OfKind(CategoryFilter.SCREENSHOTS),
        listOf("schermate") to TypeWord.OfKind(CategoryFilter.SCREENSHOTS),
        listOf("registrazione", "schermo") to TypeWord.OfKind(CategoryFilter.SCREEN_RECORDINGS),
        listOf("registrazioni", "schermo") to TypeWord.OfKind(CategoryFilter.SCREEN_RECORDINGS),
        listOf("screen", "recording") to TypeWord.OfKind(CategoryFilter.SCREEN_RECORDINGS),
        listOf("screen", "recordings") to TypeWord.OfKind(CategoryFilter.SCREEN_RECORDINGS),
        listOf("panorama") to TypeWord.OfKind(CategoryFilter.PANORAMAS),
        listOf("panoramica") to TypeWord.OfKind(CategoryFilter.PANORAMAS),
        listOf("panoramiche") to TypeWord.OfKind(CategoryFilter.PANORAMAS),
        listOf("panoramas") to TypeWord.OfKind(CategoryFilter.PANORAMAS),
        listOf("raw") to TypeWord.OfKind(CategoryFilter.RAW),
    )

    /** Glue words that carry no meaning of their own in a query. */
    val stopWords: Set<String> = setOf(
        "di", "del", "della", "dei", "delle", "degli", "dello", "da", "a", "al", "alla", "alle", "ai", "in",
        "nel", "nella", "nei", "con", "su", "sul", "per", "il", "lo", "la", "i", "gli", "le", "un", "una", "e", "ed",
        "the", "of", "at", "on", "with", "from", "to", "and", "a", "an", "my", "in",
    )
}
