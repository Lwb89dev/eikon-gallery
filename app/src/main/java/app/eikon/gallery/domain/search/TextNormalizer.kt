package app.eikon.gallery.domain.search

import java.text.Normalizer
import java.util.Locale

/**
 * The single way text is folded for matching, used for what the user types, for place names and for
 * file names, so that "Città", "citta" and "CITTÀ" are all the same word.
 */
object TextNormalizer {
    private val marks = Regex("\\p{M}+")
    private val separators = Regex("[^\\p{L}\\p{N}]+")

    /** Lowercase, accents removed, every run of punctuation or spaces turned into one space. */
    fun name(text: String): String =
        Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(marks, "")
            .replace(separators, " ")
            .trim()

    /** The words of [text], normalized. */
    fun words(text: String): List<String> = name(text).split(' ').filter { it.isNotEmpty() }
}
