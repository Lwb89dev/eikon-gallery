package app.eikon.gallery.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {
    @Query(MemoryQueries.DAY_COUNTS)
    suspend fun dayCounts(): List<DayCountRow>

    @Query(MemoryQueries.PERSON_YEARS)
    suspend fun personYears(): List<PersonYearRow>

    /** The SQL comes from [MemoryQueries.candidates], which binds every value. */
    @RawQuery
    suspend fun candidates(query: SupportSQLiteQuery): List<KeyCandidateRow>

    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun media(ids: List<Long>): List<MediaEntity>

    @Query("SELECT * FROM memory_preference")
    fun observePreferences(): Flow<List<MemoryPreferenceEntity>>

    @Query("SELECT * FROM memory_preference")
    suspend fun preferences(): List<MemoryPreferenceEntity>

    @Upsert
    suspend fun put(preference: MemoryPreferenceEntity)

    @Query("SELECT value FROM memory_preference WHERE `key` = :key")
    suspend fun valueOf(key: String): Int?

    @Query("DELETE FROM memory_preference WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM memory_preference")
    suspend fun clear()
}
