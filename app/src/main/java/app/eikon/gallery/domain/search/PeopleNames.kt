package app.eikon.gallery.domain.search

/**
 * The people the user has named, as a [PersonMatcher]. A query word matches a person by their whole name
 * ("marco rossi") or by any one of its words ("marco"), ignoring case and accents, so a first name is enough;
 * if several people share it, all of them match.
 */
class PeopleNames(names: List<Pair<Long, String>>) : PersonMatcher {
    private val index: Map<String, Set<Long>> = buildMap<String, MutableSet<Long>> {
        for ((id, name) in names) {
            val normalized = TextNormalizer.name(name)
            if (normalized.isEmpty()) continue
            getOrPut(normalized) { mutableSetOf() } += id
            normalized.split(' ').filter { it.length >= MIN_WORD }.forEach { getOrPut(it) { mutableSetOf() } += id }
        }
    }

    override fun match(normalizedName: String): PersonMatch? = index[normalizedName]?.let { PersonMatch(it) }

    private companion object {
        /** A one-letter word ("A" in "Anna B.") would match far too much. */
        const val MIN_WORD = 2
    }
}
