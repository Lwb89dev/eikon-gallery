package app.eikon.gallery.domain.memories

/** What is known about a photo when choosing the ones that tell a memory's story. */
class KeyCandidate(
    val id: Long,
    val takenAt: Long,
    val width: Int,
    val height: Int,
    val isFavorite: Boolean,
    /** How many faces were found in it (0 if people analysis is off). */
    val faces: Int,
)

/**
 * Picks up to [max] photos to show for a memory, spread across its time span so the story is told from beginning to end,
 * preferring the ones that were favorited, are larger (a rough stand-in for a proper camera shot), and show people. This is the whole
 * "photo quality indicator": there is no blur, exposure or eye-closed detection, so a favorite is always chosen over a better-looking non-favorite.
 */
object KeyPhotoPicker {
    /** Chronological. */
    fun pick(candidates: List<KeyCandidate>, max: Int): List<Long> {
        if (max <= 0 || candidates.isEmpty()) return emptyList()
        val byTime = candidates.sortedBy { it.takenAt }
        if (byTime.size <= max) return byTime.map { it.id }
        val picked = ArrayList<Long>(max)
        for (bucket in 0 until max) {
            val from = bucket * byTime.size / max
            val to = (bucket + 1) * byTime.size / max
            byTime.subList(from, to).maxByOrNull { score(it) }?.let { picked += it.id }
        }
        return picked
    }

    /** Higher is better. Favorites dominate; size counts up to 12 megapixels; people add a little. */
    fun score(candidate: KeyCandidate): Double {
        val megapixels = candidate.width.toDouble() * candidate.height / MEGAPIXEL
        return (if (candidate.isFavorite) FAVORITE else 0.0) + minOf(megapixels, MAX_MEGAPIXELS) / MAX_MEGAPIXELS * SIZE_WEIGHT +
            (if (candidate.faces > 0) FACES else 0.0) + (if (candidate.faces > 1) MORE_FACES else 0.0)
    }

    private const val MEGAPIXEL = 1_000_000.0
    private const val MAX_MEGAPIXELS = 12.0
    /** More than everything else together (size 2 + faces 1.5), so a favorite always beats a photo that merely looks better on paper. */
    private const val FAVORITE = 5.0
    private const val SIZE_WEIGHT = 2.0
    private const val FACES = 1.0
    private const val MORE_FACES = 0.5
}
