package app.eikon.gallery.data.duplicates

import app.eikon.gallery.data.embedding.EmbeddingMatrix

/** Photos of one moment that look alike: a burst, several takes of a selfie, a few shots of the same subject. */
class SimilarGroup(val members: List<Long>) {
    val key: String = "sim:" + members.sorted().joinToString(",")
}

/**
 * Finds similar shots by combining two things: taken within [windowMillis] of each other, and looking alike to the image
 * model (cosine similarity at least [threshold]). Neither alone is enough: photos of the same place months apart are not "shots
 * of one moment", and photos seconds apart can show different things. On 400 photos shifted, zoomed, rotated, darkened or
 * cropped, the copy's similarity to the original averaged 0.92 to 0.97 (5th percentile 0.86 to 0.95), while different photos
 * averaged 0.48 (99th percentile 0.72) (docs/ML.md), so the default is 0.88.
 */
object SimilarShotFinder {
    const val DEFAULT_THRESHOLD = 0.88f
    const val DEFAULT_WINDOW_MILLIS = 120_000L

    fun find(
        matrix: EmbeddingMatrix,
        takenAt: Map<Long, Long>,
        threshold: Float = DEFAULT_THRESHOLD,
        windowMillis: Long = DEFAULT_WINDOW_MILLIS,
    ): List<SimilarGroup> {
        val order = (0 until matrix.size).filter { matrix.idAt(it) in takenAt }.sortedBy { takenAt.getValue(matrix.idAt(it)) }
        val parents = IntArray(matrix.size) { it }
        for ((position, i) in order.withIndex()) {
            val start = takenAt.getValue(matrix.idAt(i))
            for (next in position + 1 until order.size) {
                val j = order[next]
                if (takenAt.getValue(matrix.idAt(j)) - start > windowMillis) break
                if (matrix.similarity(i, j) >= threshold) union(parents, i, j)
            }
        }
        return order.groupBy { find(parents, it) }.values
            .filter { it.size > 1 }
            .map { members -> SimilarGroup(members.map { matrix.idAt(it) }) }
            .sortedByDescending { takenAt.getValue(it.members.first()) }
    }

    private fun find(parents: IntArray, index: Int): Int {
        var root = index
        while (parents[root] != root) root = parents[root]
        var walker = index
        while (parents[walker] != root) walker = parents[walker].also { parents[walker] = root }
        return root
    }

    private fun union(parents: IntArray, a: Int, b: Int) {
        val ra = find(parents, a)
        val rb = find(parents, b)
        if (ra != rb) parents[rb] = ra
    }
}
