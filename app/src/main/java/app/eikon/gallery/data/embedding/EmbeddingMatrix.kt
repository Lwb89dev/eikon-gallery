package app.eikon.gallery.data.embedding

import app.eikon.gallery.data.db.EmbeddingRow

/** One photo that matched a semantic query, with its cosine similarity to it. */
data class ScoredMedia(val mediaId: Long, val score: Float)

/**
 * Which photos count as a match. CLIP scores are only meaningful relative to each other, so two rules
 * apply together: a match must clear an absolute [floor], and must be within [margin] of the best match.
 * They were chosen on 1,000 labelled photos with Italian and English queries (see docs/ML.md): at these
 * values about 93% of photos that clearly show the searched thing were kept while about 2% of photos
 * without it got through. [maxHits] bounds the size of a result on very large libraries.
 */
data class SemanticCutoff(val floor: Float = 0.23f, val margin: Float = 0.07f, val maxHits: Int = 3_000)

/**
 * Every stored embedding of the library in one contiguous block, searched by brute force. 100,000 photos
 * are about 50 MB and one query is a few tens of milliseconds, which is why there is no index structure.
 */
class EmbeddingMatrix(private val ids: LongArray, private val vectors: ByteArray) {
    init {
        require(vectors.size == ids.size * Embeddings.DIMENSIONS) { "vectors do not match the ids" }
    }

    val size: Int get() = ids.size

    /** The sum of the photo ids, which with [size] tells whether the stored vectors are the ones held here (see `IndexDao.embeddingStats`). */
    val idSum: Long get() = ids.sum()

    /**
     * A copy in which the vectors of [rows] replace those of the same photos and, for photos not held yet, are inserted at their place in id order. The matrix has to be
     * in id order already, which is how it is read. Faster than reading everything again when analysis has stored a few more.
     */
    fun withRows(rows: List<EmbeddingRow>): EmbeddingMatrix {
        if (rows.isEmpty()) return this
        val changes = rows.asReversed().distinctBy { it.mediaId }.sortedBy { it.mediaId } // a photo given twice takes its last vector
        changes.forEach { require(it.vector.size == Embeddings.DIMENSIONS) { "stored vector has ${it.vector.size} bytes" } }
        val added = changes.count { java.util.Arrays.binarySearch(ids, it.mediaId) < 0 }
        val builder = Builder(ids.size + added)
        var next = 0
        for (i in ids.indices) {
            while (next < changes.size && changes[next].mediaId < ids[i]) builder.add(changes[next].mediaId, changes[next++].vector)
            if (next < changes.size && changes[next].mediaId == ids[i]) {
                builder.add(ids[i], changes[next++].vector)
            } else {
                builder.addFrom(ids[i], vectors, i * Embeddings.DIMENSIONS)
            }
        }
        while (next < changes.size) builder.add(changes[next].mediaId, changes[next++].vector)
        return builder.build()
    }

    /** The media id of the [index]th vector. */
    fun idAt(index: Int): Long = ids[index]

    /** Cosine similarity between the [a]th and [b]th photos, from their stored (quantized) vectors. */
    fun similarity(a: Int, b: Int): Float {
        var sum = 0
        val oa = a * Embeddings.DIMENSIONS
        val ob = b * Embeddings.DIMENSIONS
        for (i in 0 until Embeddings.DIMENSIONS) sum += vectors[oa + i] * vectors[ob + i]
        return sum / (QUANT * QUANT)
    }

    /** Photos matching [query] (a unit vector), best first. Empty when nothing clears the cutoff. */
    fun search(query: FloatArray, cutoff: SemanticCutoff = SemanticCutoff()): List<ScoredMedia> {
        if (ids.isEmpty()) return emptyList()
        val scores = FloatArray(ids.size) { Embeddings.similarity(query, vectors, it * Embeddings.DIMENSIONS) }
        val threshold = maxOf(cutoff.floor, scores.max() - cutoff.margin)
        val hits = ArrayList<ScoredMedia>()
        for (i in scores.indices) if (scores[i] >= threshold) hits += ScoredMedia(ids[i], scores[i])
        hits.sortByDescending { it.score }
        return if (hits.size > cutoff.maxHits) hits.subList(0, cutoff.maxHits).toList() else hits
    }

    /**
     * Photos whose most likely description among [prompts] is the one at [target], with a probability of at least
     * [minProbability]. Each photo's similarities to all the prompts go through a softmax with CLIP's usual scale
     * of 100, so the result does not depend on how many other photos there are (unlike [search]).
     */
    fun classify(prompts: List<FloatArray>, target: Int, minProbability: Float): List<ScoredMedia> {
        val hits = ArrayList<ScoredMedia>()
        val logits = FloatArray(prompts.size)
        for (i in ids.indices) {
            for (p in prompts.indices) logits[p] = LOGIT_SCALE * Embeddings.similarity(prompts[p], vectors, i * Embeddings.DIMENSIONS)
            val probability = softmaxOf(logits, target)
            if (probability >= minProbability && logits.indices.all { it == target || logits[it] < logits[target] }) hits += ScoredMedia(ids[i], probability)
        }
        return hits.sortedByDescending { it.score }
    }

    private fun softmaxOf(logits: FloatArray, index: Int): Float {
        val top = logits.max()
        var sum = 0.0
        for (l in logits) sum += Math.exp((l - top).toDouble())
        return (Math.exp((logits[index] - top).toDouble()) / sum).toFloat()
    }

    /** Collects vectors one by one into the contiguous layout, growing if there are more than announced. */
    class Builder(expected: Int) {
        private var ids = LongArray(expected)
        private var vectors = ByteArray(expected * Embeddings.DIMENSIONS)
        private var count = 0

        fun add(mediaId: Long, vector: ByteArray) {
            require(vector.size == Embeddings.DIMENSIONS) { "stored vector has ${vector.size} bytes" }
            addFrom(mediaId, vector, 0)
        }

        /** Adds the vector that starts at [from] in [source] (copied straight into place, so a whole matrix can be carried over without a copy per photo). */
        fun addFrom(mediaId: Long, source: ByteArray, from: Int) {
            if (count == ids.size) grow()
            ids[count] = mediaId
            System.arraycopy(source, from, vectors, count * Embeddings.DIMENSIONS, Embeddings.DIMENSIONS)
            count++
        }

        /** The builder is not used again afterwards, so when it is exactly full its arrays are handed over rather than copied (a copy of a large matrix is 50 MB more on the heap). */
        fun build() =
            if (count == ids.size) EmbeddingMatrix(ids, vectors) else EmbeddingMatrix(ids.copyOf(count), vectors.copyOf(count * Embeddings.DIMENSIONS))

        private fun grow() {
            val size = maxOf(MIN_GROWTH, ids.size * 2)
            ids = ids.copyOf(size)
            vectors = vectors.copyOf(size * Embeddings.DIMENSIONS)
        }

        private companion object {
            const val MIN_GROWTH = 1_024
        }
    }

    private companion object {
        /** The stored bytes are `round(value * 127)`. */
        const val QUANT = 127f

        /** CLIP's learned temperature: similarities are multiplied by this before the softmax. */
        const val LOGIT_SCALE = 100f
    }
}
