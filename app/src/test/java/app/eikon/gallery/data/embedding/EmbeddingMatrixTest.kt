package app.eikon.gallery.data.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingMatrixTest {
    /** A unit vector pointing mostly along axis [axis] with a little of [other]. */
    private fun vector(axis: Int, other: Int = axis, mix: Float = 0f): FloatArray {
        val v = FloatArray(Embeddings.DIMENSIONS)
        v[axis] += 1f - mix
        v[other] += mix
        return Embeddings.normalized(v)
    }

    private fun matrix(vararg entries: Pair<Long, FloatArray>): EmbeddingMatrix {
        val builder = EmbeddingMatrix.Builder(entries.size)
        entries.forEach { (id, v) -> builder.add(id, Embeddings.quantize(v)) }
        return builder.build()
    }

    @Test
    fun normalizingGivesUnitLength() {
        val unit = Embeddings.normalized(floatArrayOf(3f, 4f))
        assertEquals(0.6f, unit[0], 1e-6f)
        assertEquals(0.8f, unit[1], 1e-6f)
    }

    @Test
    fun aZeroVectorStaysZero() {
        assertTrue(Embeddings.normalized(FloatArray(4)).all { it == 0f })
    }

    @Test
    fun quantizingKeepsTheCosineWithinAFractionOfAPercent() {
        val v = Embeddings.normalized(FloatArray(Embeddings.DIMENSIONS) { (it * 7919 % 101 - 50).toFloat() })
        val stored = Embeddings.quantize(v)
        assertEquals(1f, Embeddings.similarity(v, stored, 0), 0.005f)
    }

    @Test
    fun theBestMatchIsFirstAndDissimilarPhotosAreLeftOut() {
        val photos = matrix(1L to vector(0), 2L to vector(0, 1, 0.1f), 3L to vector(5))

        val hits = photos.search(vector(0), SemanticCutoff(floor = 0.2f, margin = 0.2f))

        assertEquals(listOf(1L, 2L), hits.map { it.mediaId })
        assertTrue(hits[0].score > hits[1].score)
    }

    @Test
    fun nothingClearsTheFloorWhenNoPhotoResemblesTheQuery() {
        val photos = matrix(1L to vector(3), 2L to vector(4))
        assertTrue(photos.search(vector(0)).isEmpty())
    }

    @Test
    fun theMarginDropsPhotosFarBehindTheBestOne() {
        val photos = matrix(1L to vector(0), 2L to vector(0, 1, 0.5f), 3L to vector(0, 1, 0.3f))

        val hits = photos.search(vector(0), SemanticCutoff(floor = 0.1f, margin = 0.05f))

        assertEquals(listOf(1L), hits.map { it.mediaId })
    }

    @Test
    fun theNumberOfHitsIsCapped() {
        val photos = matrix(*Array(10) { (it + 1L) to vector(0) })
        assertEquals(4, photos.search(vector(0), SemanticCutoff(floor = 0.1f, margin = 0.5f, maxHits = 4)).size)
    }

    // --- classification (pets) --------------------------------------------------------------------

    private val prompts = listOf(vector(0), vector(1), vector(2)) // "dog", "cat", "anything else"

    @Test
    fun aPhotoIsClassifiedAsTheDescriptionItResemblesMost() {
        val photos = matrix(1L to vector(0), 2L to vector(1), 3L to vector(2))

        assertEquals(listOf(1L), photos.classify(prompts, target = 0, minProbability = 0.6f).map { it.mediaId })
        assertEquals(listOf(2L), photos.classify(prompts, target = 1, minProbability = 0.6f).map { it.mediaId })
    }

    @Test
    fun aPhotoBetweenTwoDescriptionsIsNotClaimedByEitherWhenItIsNotSureEnough() {
        val photos = matrix(1L to vector(0, 1, 0.5f)) // exactly halfway between "dog" and "cat"
        assertTrue(photos.classify(prompts, target = 0, minProbability = 0.6f).isEmpty())
        assertTrue(photos.classify(prompts, target = 1, minProbability = 0.6f).isEmpty())
    }

    @Test
    fun classificationDoesNotDependOnHowManyOtherPhotosThereAre() {
        val alone = matrix(1L to vector(0, 2, 0.2f))
        val crowd = matrix(1L to vector(0, 2, 0.2f), 2L to vector(0), 3L to vector(0), 4L to vector(1))
        assertEquals(
            alone.classify(prompts, 0, 0.5f).map { it.mediaId },
            crowd.classify(prompts, 0, 0.5f).map { it.mediaId }.filter { it == 1L },
        )
    }

    @Test
    fun anEmptyLibraryHasNoHits() {
        assertTrue(EmbeddingMatrix.Builder(0).build().search(vector(0)).isEmpty())
        assertTrue(EmbeddingMatrix.Builder(0).build().classify(prompts, 0, 0.6f).isEmpty())
    }

    @Test
    fun theBuilderGrowsWhenMoreRowsArriveThanAnnounced() {
        val builder = EmbeddingMatrix.Builder(1)
        repeat(3) { builder.add(it + 1L, Embeddings.quantize(vector(it))) }
        assertEquals(3, builder.build().size)
    }
}
