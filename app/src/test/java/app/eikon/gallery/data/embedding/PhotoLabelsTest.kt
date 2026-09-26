package app.eikon.gallery.data.embedding

import app.eikon.gallery.domain.PetKind
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** What is said about a photo from its stored vector: the arithmetic on made-up vectors, and, with the real models, on the four sample photos. */
class PhotoLabelsTest {
    // --- the arithmetic ---------------------------------------------------------------------------

    private fun basis(index: Int) = FloatArray(Embeddings.DIMENSIONS).also { it[index] = 1f }

    private fun photo(vararg weights: Pair<Int, Float>): ByteArray {
        val v = FloatArray(Embeddings.DIMENSIONS)
        weights.forEach { (i, w) -> v[i] = w }
        return Embeddings.quantize(Embeddings.normalized(v))
    }

    private val phrases = List(PhotoSubject.entries.size) { basis(it) }

    @Test
    fun theProbabilitiesAreAProbabilityDistribution() {
        val p = LabelScoring.probabilities(photo(3 to 1f, 7 to 0.5f), phrases)
        assertEquals(1f, p.sum(), 1e-4f)
        assertTrue(p.all { it in 0f..1f })
    }

    @Test
    fun aPhotoThatIsClearlyOneThingGetsThatLabelAlone() {
        val subjects = LabelScoring.subjects(LabelScoring.probabilities(photo(2 to 1f, 9 to 0.2f), phrases))
        assertEquals(listOf(PhotoSubject.entries[2]), subjects)
    }

    @Test
    fun aPhotoBetweenThreeThingsGetsAllThreeBestFirst() {
        val subjects = LabelScoring.subjects(LabelScoring.probabilities(photo(4 to 1f, 6 to 0.99f, 8 to 0.985f), phrases))
        assertEquals(3, subjects.size)
        assertEquals(PhotoSubject.entries[4], subjects.first())
        assertEquals(setOf(4, 6, 8), subjects.map { it.ordinal }.toSet())
    }

    @Test
    fun aPhotoLikeEverythingEquallyGetsNoLabelInsteadOfAnArbitraryOne() {
        val everything = photo(*PhotoSubject.entries.indices.map { it to 1f }.toTypedArray())
        assertEquals(emptyList<PhotoSubject>(), LabelScoring.subjects(LabelScoring.probabilities(everything, phrases)))
    }

    @Test
    fun nothingIsSaidWithNoPhrases() {
        assertEquals(emptyList<PhotoSubject>(), LabelScoring.subjects(FloatArray(0)))
    }

    @Test
    fun aPetIsNeverOfferedAsASubjectAndSilencesTheOthers() {
        val cat = PhotoSubject.CAT.ordinal
        val subjects = LabelScoring.subjects(LabelScoring.probabilities(photo(cat to 1f, PhotoSubject.PERSON.ordinal to 0.97f), phrases))
        assertEquals(emptyList<PhotoSubject>(), subjects)
    }

    @Test
    fun aPetIsReportedOnlyWhenItIsTheLikeliestDescriptionAndLikelyEnough() {
        val petPhrases = List(PetPrompts.PHRASES.size) { basis(it) }
        assertEquals(listOf(PetKind.CAT), LabelScoring.pets(photo(PetPrompts.index(PetKind.CAT) to 1f, 5 to 0.1f), petPhrases))
        assertEquals(listOf(PetKind.DOG), LabelScoring.pets(photo(PetPrompts.index(PetKind.DOG) to 1f), petPhrases))
        // Torn between a cat and something else: no pet is claimed.
        assertEquals(emptyList<PetKind>(), LabelScoring.pets(photo(PetPrompts.index(PetKind.CAT) to 1f, 5 to 0.995f), petPhrases))
        assertEquals(emptyList<PetKind>(), LabelScoring.pets(photo(9 to 1f), petPhrases))
    }

    // --- with the real models ---------------------------------------------------------------------

    private fun labelsOf(name: String): Pair<List<PhotoSubject>, List<PetKind>> {
        val vector = Embeddings.quantize(image.embed(ModelTestSupport.photo(name)))
        val subjects = LabelScoring.subjects(LabelScoring.probabilities(vector, subjectPhrases))
        return subjects to LabelScoring.pets(vector, petPhrases)
    }

    @Test
    fun theCatPhotoHasACatAndNothingElseIsAPet() {
        val (subjects, pets) = labelsOf("cat")
        assertEquals(listOf(PetKind.CAT), pets)
        assertEquals("a pet is not also offered as a subject", emptyList<PhotoSubject>(), subjects)
        for (other in listOf("astronaut", "coffee", "rocket")) assertEquals("no pet in $other", emptyList<PetKind>(), labelsOf(other).second)
    }

    @Test
    fun theCoffeePhotoIsADrinkAndTheAstronautIsAPerson() {
        val coffee = labelsOf("coffee").first
        val astronaut = labelsOf("astronaut").first
        val rocket = labelsOf("rocket").first
        assertTrue("coffee: $coffee", PhotoSubject.DRINK in coffee || PhotoSubject.FOOD in coffee)
        assertTrue("astronaut: $astronaut", PhotoSubject.PERSON in astronaut)
        assertTrue("rocket: $rocket", PhotoSubject.SKY in rocket || PhotoSubject.AIRPLANE in rocket)
    }

    private companion object {
        lateinit var image: ClipImageEncoder
        lateinit var text: ClipTextEncoder
        lateinit var subjectPhrases: List<FloatArray>
        lateinit var petPhrases: List<FloatArray>

        @JvmStatic
        @BeforeClass
        fun load() {
            val store = ModelTestSupport.store()
            image = ClipImageEncoder(store.map(IMAGE_MODEL), threads = 2)
            text = ClipTextEncoder.create(store)
            subjectPhrases = PhotoSubject.PHRASES.map { text.embed(it) }
            petPhrases = PetPrompts.PHRASES.map { text.embed(it) }
        }

        @JvmStatic
        @AfterClass
        fun unload() {
            image.close()
            text.close()
        }
    }
}
