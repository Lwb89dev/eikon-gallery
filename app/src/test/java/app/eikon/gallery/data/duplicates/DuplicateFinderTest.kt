package app.eikon.gallery.data.duplicates

import app.eikon.gallery.data.db.HashCandidate
import app.eikon.gallery.data.embedding.EmbeddingMatrix
import app.eikon.gallery.data.embedding.Embeddings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuplicateFinderTest {
    private fun item(
        id: Long,
        width: Int = 4000,
        height: Int = 3000,
        size: Long = 4_000_000,
        added: Long = id,
        video: Boolean = false,
        content: String? = null,
        perceptual: Long? = null,
    ) = HashCandidate(id, width, height, size, takenAt = 0, addedAt = added, isVideo = video, isFavorite = false, contentHash = content, perceptual = perceptual)

    private val base = 0x1234_5678_9ABC_DEF0

    @Test
    fun filesWithTheSameBytesAreAnExactGroup() {
        val groups = DuplicateFinder.find(listOf(item(1, content = "a"), item(2, content = "a"), item(3, content = "b")))
        val group = groups.single()
        assertEquals(DuplicateKind.EXACT, group.kind)
        assertEquals(setOf(1L, 2L), group.members.map { it.id }.toSet())
    }

    @Test
    fun aPhotoAndItsRecompressedCopyAreAVisualGroup() {
        val groups = DuplicateFinder.find(listOf(item(1, perceptual = base), item(2, perceptual = base xor 0b101), item(3, perceptual = base.inv())))
        assertEquals(DuplicateKind.VISUAL, groups.single().kind)
        assertEquals(setOf(1L, 2L), groups.single().members.map { it.id }.toSet())
    }

    @Test
    fun theDistanceLimitIsTheFingerprintsBits() {
        val six = base xor 0b111111
        val seven = base xor 0b1111111
        assertEquals(1, DuplicateFinder.find(listOf(item(1, perceptual = base), item(2, perceptual = six))).size)
        assertTrue(DuplicateFinder.find(listOf(item(1, perceptual = base), item(2, perceptual = seven))).isEmpty())
    }

    @Test
    fun copiesFarApartInTheirBitsButAnywhereInTheHashAreStillFound() {
        // The differing bits sit in different bytes of the fingerprint: the buckets must not depend on where they are.
        val spread = base xor (1L shl 3) xor (1L shl 20) xor (1L shl 41) xor (1L shl 58)
        assertEquals(1, DuplicateFinder.find(listOf(item(1, perceptual = base), item(2, perceptual = spread))).size)
    }

    @Test
    fun aCopyOfACopyJoinsTheGroupEvenIfTheEndsAreFarApart() {
        val a = base
        val b = base xor 0b111111
        val c = b xor (0b111111L shl 30)
        val groups = DuplicateFinder.find(listOf(item(1, perceptual = a), item(2, perceptual = b), item(3, perceptual = c)))
        assertEquals(setOf(1L, 2L, 3L), groups.single().members.map { it.id }.toSet())
    }

    @Test
    fun anExactGroupThatAlsoLooksLikeAThirdBecomesVisual() {
        val groups = DuplicateFinder.find(listOf(item(1, content = "a", perceptual = base), item(2, content = "a", perceptual = base), item(3, content = "z", perceptual = base xor 1)))
        assertEquals(DuplicateKind.VISUAL, groups.single().kind)
        assertEquals(3, groups.single().members.size)
    }

    @Test
    fun videosAreOnlyEverExactCopies() {
        val videos = listOf(item(1, video = true, content = "v", perceptual = base), item(2, video = true, content = "v", perceptual = base), item(3, video = true, perceptual = base))
        val groups = DuplicateFinder.find(videos)
        assertEquals(setOf(1L, 2L), groups.single().members.map { it.id }.toSet())
    }

    @Test
    fun aVideoAndAPhotoWithTheSameBytesSizeAreNotMatched() {
        assertTrue(DuplicateFinder.find(listOf(item(1, content = "x"), item(2, video = true, content = "x"))).isEmpty())
    }

    @Test
    fun nothingIsGroupedWithoutAnyFingerprint() {
        assertTrue(DuplicateFinder.find(listOf(item(1), item(2))).isEmpty())
        assertTrue(DuplicateFinder.find(emptyList()).isEmpty())
    }

    @Test
    fun aBucketOfNearBlankPicturesDoesNotBlowUp() {
        val blanks = (1L..2_000L).map { item(it, perceptual = 0L) }
        // 2,000 pictures with the same fingerprint fall in every bucket; they are skipped rather than compared in pairs.
        assertTrue(DuplicateFinder.find(blanks).isEmpty())
    }

    @Test
    fun theBestCopyComesFirstByPixelsThenSizeThenAge() {
        val members = listOf(
            item(1, width = 1600, height = 1200, size = 900_000, added = 5),
            item(2, width = 4000, height = 3000, size = 2_000_000, added = 9),
            item(3, width = 4000, height = 3000, size = 3_000_000, added = 8),
            item(4, width = 4000, height = 3000, size = 3_000_000, added = 2),
        )
        assertEquals(listOf(4L, 3L, 2L, 1L), DuplicateMerge.bestFirst(members).map { it.id })
    }

    @Test
    fun aGroupsKeyDoesNotDependOnOrder() {
        val a = DuplicateGroup(DuplicateKind.EXACT, listOf(item(3), item(1), item(2)))
        val b = DuplicateGroup(DuplicateKind.EXACT, listOf(item(2), item(3), item(1)))
        assertEquals("dup:1,2,3", a.key)
        assertEquals(a.key, b.key)
    }

    // --- similar shots ---------------------------------------------------------------------------

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
    fun photosSecondsApartThatLookAlikeAreSimilarShots() {
        val photos = matrix(1L to vector(0), 2L to vector(0, 1, 0.1f), 3L to vector(5))
        val taken = mapOf(1L to 0L, 2L to 3_000L, 3L to 6_000L)

        val group = SimilarShotFinder.find(photos, taken).single()

        assertEquals(listOf(1L, 2L), group.members.sorted())
    }

    @Test
    fun theSameLookMonthsApartIsNotOneMoment() {
        val photos = matrix(1L to vector(0), 2L to vector(0))
        assertTrue(SimilarShotFinder.find(photos, mapOf(1L to 0L, 2L to 3_600_000L)).isEmpty())
    }

    @Test
    fun aBurstChainsIntoOneGroupEvenWhenTheEndsDiffer() {
        val photos = matrix(1L to vector(0), 2L to vector(0, 1, 0.2f), 3L to vector(0, 1, 0.4f))
        val group = SimilarShotFinder.find(photos, mapOf(1L to 0L, 2L to 1_000L, 3L to 2_000L), threshold = 0.85f).single()
        assertEquals(setOf(1L, 2L, 3L), group.members.toSet())
    }

    @Test
    fun aPhotoWithoutADateIsLeftOut() {
        val photos = matrix(1L to vector(0), 2L to vector(0))
        assertTrue(SimilarShotFinder.find(photos, mapOf(1L to 0L)).isEmpty())
    }

    @Test
    fun similarityBetweenStoredVectorsIsTheirCosine() {
        val photos = matrix(1L to vector(0), 2L to vector(0, 1, 0.5f), 3L to vector(0))
        assertEquals(1f, photos.similarity(0, 2), 0.01f)
        assertEquals(0.707f, photos.similarity(0, 1), 0.02f)
    }
}
