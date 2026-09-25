package app.eikon.gallery.data.memories

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.MemoryDao
import app.eikon.gallery.data.db.MemoryPreferenceEntity
import app.eikon.gallery.data.db.MemoryQueries
import app.eikon.gallery.data.db.toDomain
import app.eikon.gallery.data.faces.PeopleRepository
import app.eikon.gallery.data.places.TripsRepository
import app.eikon.gallery.domain.MediaItem
import app.eikon.gallery.domain.memories.KeyCandidate
import app.eikon.gallery.domain.memories.KeyPhotoPicker
import app.eikon.gallery.domain.memories.Memory
import app.eikon.gallery.domain.memories.MemoryId
import app.eikon.gallery.domain.memories.MemoryInputs
import app.eikon.gallery.domain.memories.MemoryKind
import app.eikon.gallery.domain.memories.MemoryPlanner
import app.eikon.gallery.domain.memories.MemoryPreferences
import app.eikon.gallery.domain.memories.PersonYear
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A memory to show on the Memories screen: what it is, its picture, and the name of the person it is about. */
class MemoryCard(val memory: Memory, val cover: MediaItem?, val personName: String?)

/**
 * Memories: what the planner proposes today from dates, trips and named people, what the user told it (hide, show fewer, show less of
 * a person, leave out a date), and the photos that tell each story. Everything comes from the local database.
 */
@Singleton
class MemoryRepository @Inject constructor(
    private val dao: MemoryDao,
    private val trips: TripsRepository,
    private val people: PeopleRepository,
    private val clock: Clock,
) {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    suspend fun cards(today: LocalDate = LocalDate.now()): List<MemoryCard> = withContext(Dispatchers.Default) {
        val preferences = preferences()
        val memories = MemoryPlanner(zone).plan(today, inputs(), preferences)
        val names = people.namedPeople().associate { it.id to it.name }
        memories.map { memory -> MemoryCard(memory, keyPhotos(memory.id, 1, preferences).firstOrNull(), memory.id.personId?.let(names::get)) }
    }

    /** Up to [max] photos to show for [id], chronologically. */
    suspend fun keyPhotos(id: MemoryId, max: Int, preferences: MemoryPreferences? = null): List<MediaItem> = withContext(Dispatchers.Default) {
        val prefs = preferences ?: preferences()
        val query = MemoryQueries.candidates(id.periods(zone), id.personId, prefs.lessOf, prefs.excludedDays.map { it.toString() }.toSet())
        val candidates = dao.candidates(query).map { KeyCandidate(it.id, it.takenAt, it.width, it.height, it.isFavorite, it.faces) }
        val ids = KeyPhotoPicker.pick(candidates, max)
        val byId = dao.media(ids).associate { it.id to it.toDomain() }
        ids.mapNotNull { byId[it] }
    }

    suspend fun personName(id: Long): String? = people.namedPeople().firstOrNull { it.id == id }?.name

    // --- What the user said ----------------------------------------------------------------------

    suspend fun hide(id: MemoryId) = put("memory:${id.toArg()}", 1)

    /** "Show fewer like this": each press takes one more of that kind away, until none is left. */
    suspend fun showFewer(kind: MemoryKind) = put("kind:${kind.name}", (dao.valueOf("kind:${kind.name}") ?: 0) + 1)

    suspend fun showLessOf(personId: Long) = put("person:$personId", 1)

    suspend fun excludeDay(day: LocalDate) = put("date:$day", 1)

    /** Undoes every choice. */
    suspend fun reset() = dao.clear()

    suspend fun preferences(): MemoryPreferences = parse(dao.preferences())

    private suspend fun put(key: String, value: Int) = dao.put(MemoryPreferenceEntity(key, value, clock.nowMillis()))

    private suspend fun inputs(): MemoryInputs {
        val dayCounts = dao.dayCounts().mapNotNull { row -> runCatching { LocalDate.parse(row.day) }.getOrNull()?.let { it to row.count } }.toMap()
        val personYears = dao.personYears().map { PersonYear(it.personId, it.isFavorite, it.year, it.count) }
        return MemoryInputs(dayCounts, trips.named(), personYears)
    }

    companion object {
        fun parse(rows: List<MemoryPreferenceEntity>): MemoryPreferences {
            val hidden = rows.filter { it.key.startsWith("memory:") }.map { it.key.removePrefix("memory:") }.toSet()
            val fewer = rows.filter { it.key.startsWith("kind:") }.mapNotNull { row ->
                MemoryKind.entries.firstOrNull { it.name == row.key.removePrefix("kind:") }?.let { it to row.value }
            }.toMap()
            val lessOf = rows.filter { it.key.startsWith("person:") }.mapNotNull { it.key.removePrefix("person:").toLongOrNull() }.toSet()
            val days = rows.filter { it.key.startsWith("date:") }.mapNotNull { runCatching { LocalDate.parse(it.key.removePrefix("date:")) }.getOrNull() }.toSet()
            return MemoryPreferences(hidden, fewer, lessOf, days)
        }
    }
}
