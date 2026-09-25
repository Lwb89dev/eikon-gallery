package app.eikon.gallery.data.faces

import kotlin.math.sqrt

/**
 * Groups face vectors into people, one face at a time: a new face joins the person whose average face it
 * resembles most, if that is close enough, and otherwise starts a new person. Existing assignments are never
 * changed by it, so what the user has named, merged or split stays as they left it; only faces without a
 * person are ever placed.
 *
 * The [threshold] is a cosine similarity between the (unit) face vector and the person's average vector. On
 * 4,324 photos of 158 people (LFW) with the model in use, a threshold of 0.5 gave 99.4% of the same-person
 * pairs together with 0.6% of the joined pairs wrong (docs/ML.md); a lower value merges different people
 * sooner, a higher one splits one person into several. Wrongly joined people are worse for the user than a
 * person shown twice (they can merge those), so it errs towards splitting.
 */
class FaceClustering(private val threshold: Float = DEFAULT_THRESHOLD) {
    private class Person(val sum: FloatArray, var count: Int) {
        var norm: Float = 0f
    }

    private val people = LinkedHashMap<Long, Person>()

    val personCount: Int get() = people.size

    /** The person this face most resembles, or null if none is close enough. */
    fun match(vector: FloatArray): Long? {
        var best: Long? = null
        var bestScore = threshold
        for ((id, person) in people) {
            val score = cosine(vector, person)
            if (score >= bestScore) {
                best = id
                bestScore = score
            }
        }
        return best
    }

    /** Records that this face belongs to [personId], updating the person's average. */
    fun add(personId: Long, vector: FloatArray) {
        val person = people.getOrPut(personId) { Person(FloatArray(vector.size), 0) }
        for (i in vector.indices) person.sum[i] += vector[i]
        person.count++
        person.norm = norm(person.sum)
    }

    fun forget() = people.clear()

    private fun cosine(vector: FloatArray, person: Person): Float {
        if (person.norm == 0f) return 0f
        var dot = 0f
        for (i in vector.indices) dot += vector[i] * person.sum[i]
        return dot / person.norm
    }

    private fun norm(values: FloatArray): Float {
        var sum = 0.0
        for (v in values) sum += v.toDouble() * v
        return sqrt(sum).toFloat()
    }

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
    }
}
