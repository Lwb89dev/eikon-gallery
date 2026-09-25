package app.eikon.gallery.data.embedding

import app.eikon.gallery.domain.PetKind
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * Runs the real models (int8 CLIP image tower, multilingual text tower) through ONNX Runtime on the JVM,
 * with the same Kotlin code the app uses. Three checks: the output matches what the reference tooling
 * (Python) produced for the same input, so preprocessing, tokenizing and pooling are right; the vectors
 * are unit length; and, end to end, a phrase in Italian or English finds the right one of four photos.
 */
class ClipModelsTest {
    @Test
    fun theImageVectorMatchesTheReference() {
        val reference = ModelTestSupport.floats(ModelTestSupport.resource("image-golden.txt"))

        val vector = image.embed(ModelTestSupport.png("cat-224"))

        assertEquals(1f, ModelTestSupport.cosine(vector, vector), 1e-4f)
        assertTrue("cosine to reference was ${ModelTestSupport.cosine(vector, reference)}", ModelTestSupport.cosine(vector, reference) > MATCH)
    }

    @Test
    fun theTextVectorsMatchTheReferenceForItalianAndEnglishPhrases() {
        val lines = ModelTestSupport.resource("text-golden.tsv").lines().filter { it.isNotBlank() }
        assertTrue(lines.size >= 5)
        for (line in lines) {
            val (phrase, numbers) = line.split('\t')
            val vector = text.embed(phrase)
            val cosine = ModelTestSupport.cosine(vector, ModelTestSupport.floats(numbers))
            assertTrue("cosine for '$phrase' was $cosine", cosine > MATCH)
        }
    }

    @Test
    fun aPhraseFindsTheRightPhotoInItalianAndEnglish() {
        val matrix = photoMatrix()
        val expectations = mapOf(
            "gatto" to 1L, "cat" to 1L, "astronauta" to 2L, "astronaut" to 2L,
            "tazza di caffè" to 3L, "cup of coffee" to 3L, "razzo che decolla" to 4L, "rocket launch" to 4L,
        )
        for ((words, expected) in expectations) {
            val hits = matrix.search(text.embed(SemanticQuery.phrase(words)), SemanticCutoff(floor = 0f, margin = 1f))
            assertEquals("best photo for '$words'", expected, hits.first().mediaId)
        }
    }

    @Test
    fun anUnrelatedPhraseStaysBelowTheFloorForEveryPhoto() {
        val hits = photoMatrix().search(text.embed(SemanticQuery.phrase("un treno sotto la neve")))
        assertTrue("unexpected hits: $hits", hits.isEmpty())
    }

    @Test
    fun theCatPhotoIsACatAndNothingIsADogOrTheOtherWayRound() {
        val matrix = photoMatrix()
        val prompts = PetPrompts.PHRASES.map { text.embed(it) }

        val cats = matrix.classify(prompts, PetPrompts.index(PetKind.CAT), PetPrompts.MIN_PROBABILITY).map { it.mediaId }
        val dogs = matrix.classify(prompts, PetPrompts.index(PetKind.DOG), PetPrompts.MIN_PROBABILITY).map { it.mediaId }

        assertEquals(listOf(1L), cats) // the cat; not the astronaut, the coffee or the rocket
        assertTrue("no dog among these photos: $dogs", dogs.isEmpty())
    }

    private fun photoMatrix(): EmbeddingMatrix {
        val builder = EmbeddingMatrix.Builder(PHOTOS.size)
        PHOTOS.forEachIndexed { index, name -> builder.add(index + 1L, Embeddings.quantize(image.embed(ModelTestSupport.photo(name)))) }
        return builder.build()
    }

    private companion object {
        /**
         * The reference vectors come from ONNX Runtime 1.28.0 for Python, the version the app uses. The models are
         * int8-quantized and other ONNX Runtime versions differ by about half a percent of cosine similarity (1.30
         * gave 0.994 on the same input), so the reference must stay on this version. A wrong channel order, layout
         * or tokenization drops the value far below this threshold.
         */
        const val MATCH = 0.999f
        val PHOTOS = listOf("cat", "astronaut", "coffee", "rocket")
        lateinit var image: ClipImageEncoder
        lateinit var text: ClipTextEncoder

        @JvmStatic
        @BeforeClass
        fun load() {
            val store = ModelTestSupport.store()
            image = ClipImageEncoder(store.map(IMAGE_MODEL), threads = 2)
            text = ClipTextEncoder.create(store)
        }

        @JvmStatic
        @AfterClass
        fun unload() {
            image.close()
            text.close()
        }
    }
}
