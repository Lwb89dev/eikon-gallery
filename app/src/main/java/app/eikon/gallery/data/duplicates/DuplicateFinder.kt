package app.eikon.gallery.data.duplicates

import app.eikon.gallery.data.db.HashCandidate

/** How sure we are that the members are copies of one another. */
enum class DuplicateKind {
    /** The files are byte for byte the same. */
    EXACT,

    /** The pictures look the same (recompressed, resized) but the files differ. */
    VISUAL,
}

/**
 * A set of photos or videos that are copies of each other. [members] are ordered best first (see [DuplicateMerge]); the
 * first is the one to keep. [key] names the group's members so a group the user dismissed is not offered again.
 */
class DuplicateGroup(val kind: DuplicateKind, val members: List<HashCandidate>) {
    val key: String = "dup:" + members.map { it.id }.sorted().joinToString(",")
}

/** Which copy to keep. The choice is a suggestion: the user always confirms. */
object DuplicateMerge {
    /**
     * Best first: the most pixels, then the biggest file (less compression), then the one that arrived first, which is
     * usually the original and not a copy that was downloaded later.
     */
    fun bestFirst(items: List<HashCandidate>): List<HashCandidate> = items.sortedWith(
        compareByDescending<HashCandidate> { it.width.toLong() * it.height }
            .thenByDescending { it.sizeBytes }
            .thenBy { it.addedAt }
            .thenBy { it.id },
    )
}

/**
 * Groups copies: files with the same fingerprint of their bytes are exact copies; photos whose pictures are within
 * [PerceptualHash.DUPLICATE_DISTANCE] bits are visual copies. Both link photos into groups (a copy of a copy joins), and a
 * group is [DuplicateKind.EXACT] only if every member has the same byte fingerprint. Nothing is ever changed by finding
 * them: deciding what to do is the user's.
 */
object DuplicateFinder {
    /** A bucket bigger than this is skipped: it is a run of near-blank pictures, not copies, and comparing them all is quadratic. */
    private const val MAX_BUCKET = 300
    private const val BANDS = 8
    private const val BAND_BITS = 8

    fun find(items: List<HashCandidate>, maxDistance: Int = PerceptualHash.DUPLICATE_DISTANCE): List<DuplicateGroup> {
        require(maxDistance < BANDS) { "with $BANDS bands of $BAND_BITS bits, distances above ${BANDS - 1} could be missed" }
        val parents = IntArray(items.size) { it }
        linkExact(items, parents)
        linkVisual(items, parents, maxDistance)
        return items.indices.groupBy { find(parents, it) }.values
            .filter { it.size > 1 }
            .map { group -> group(group.map { items[it] }) }
            .sortedByDescending { it.members.size }
    }

    private fun group(members: List<HashCandidate>): DuplicateGroup {
        val hashes = members.map { it.contentHash }
        val exact = hashes.all { it != null } && hashes.toSet().size == 1
        return DuplicateGroup(if (exact) DuplicateKind.EXACT else DuplicateKind.VISUAL, DuplicateMerge.bestFirst(members))
    }

    private fun linkExact(items: List<HashCandidate>, parents: IntArray) {
        items.indices.filter { items[it].contentHash != null }
            .groupBy { items[it].contentHash to items[it].isVideo }
            .values.forEach { same -> same.drop(1).forEach { union(parents, same.first(), it) } }
    }

    /** Photos only. Any two fingerprints within [maxDistance] bits agree exactly on at least one of eight bytes, so bytes are the buckets. */
    private fun linkVisual(items: List<HashCandidate>, parents: IntArray, maxDistance: Int) {
        val buckets = HashMap<Int, MutableList<Int>>()
        for (i in items.indices) {
            val hash = items[i].perceptual ?: continue
            if (items[i].isVideo) continue
            for (band in 0 until BANDS) {
                val part = ((hash ushr (band * BAND_BITS)) and BAND_MASK).toInt()
                buckets.getOrPut(band * BAND_VALUES + part) { mutableListOf() } += i
            }
        }
        for (bucket in buckets.values) {
            if (bucket.size < 2 || bucket.size > MAX_BUCKET) continue
            for (a in bucket.indices) for (b in a + 1 until bucket.size) linkIfClose(items, parents, bucket[a], bucket[b], maxDistance)
        }
    }

    private fun linkIfClose(items: List<HashCandidate>, parents: IntArray, a: Int, b: Int, maxDistance: Int) {
        if (find(parents, a) == find(parents, b)) return
        if (PerceptualHash.distance(items[a].perceptual!!, items[b].perceptual!!) <= maxDistance) union(parents, a, b)
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

    private const val BAND_MASK = 0xFFL
    private const val BAND_VALUES = 256
}
