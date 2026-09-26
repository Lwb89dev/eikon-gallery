package app.eikon.gallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** The analysis steps a photo goes through. Each is tracked separately, per photo. */
enum class IndexStage { GEO, OCR, EMBED, FACES, PHASH, FILEHASH }

/** Outcome of one stage for one photo. A photo with no row for a stage has not been tried yet. */
object IndexStatus {
    const val DONE = 1
    const val FAILED = 2

    /** Nothing to extract (no GPS, no text). Not retried. */
    const val SKIPPED = 3
}

/**
 * Per-photo progress of the background analysis. Kept in the database so work survives the app being
 * killed, the phone rebooting or a run being interrupted: the next run simply continues with whatever
 * has no row yet (or failed fewer than the allowed number of times).
 */
@Entity(
    tableName = "index_state",
    primaryKeys = ["mediaId", "stage"],
    indices = [Index("stage", "status")],
)
data class IndexStateEntity(
    val mediaId: Long,
    val stage: String,
    val status: Int,
    val attempts: Int,
    val updatedAt: Long,
)

/**
 * Where a photo was taken, resolved offline to the nearest known city. The raw coordinates are kept
 * here (private app storage, excluded from backups) so places and trips can be built later without
 * re-reading every file; nothing about them is ever sent anywhere.
 */
@Entity(
    tableName = "media_geo",
    indices = [Index("cityId"), Index("countryCode"), Index("regionKey")],
)
data class MediaGeoEntity(
    @PrimaryKey val mediaId: Long,
    val latitude: Double,
    val longitude: Double,
    val cityId: Long?,
    val countryCode: String?,
    val regionKey: String?,
)

/**
 * Full-text index over the words of a photo: its file name and the text found inside it (OCR). The
 * tokenizer ignores case and accents, so "citta" finds "città". `rowid` is the media id.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=2"])
@Entity(tableName = "media_search")
data class MediaSearchEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val filename: String,
    val ocr: String,
)

/**
 * What a photo looks like to the image model: a 512-value vector, one signed byte per value (see
 * `Embeddings`). [model] says which model made it, because vectors of different models are not comparable.
 */
@Entity(tableName = "media_embedding")
class MediaEmbeddingEntity(
    @PrimaryKey val mediaId: Long,
    val model: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
)

/** How many vectors of a model are stored, and the sum of their photo ids (see `IndexDao.embeddingStats`). */
class EmbeddingStats(val count: Int, val idSum: Long)

/** One stored vector as read back for searching. */
class EmbeddingRow(val mediaId: Long, val vector: ByteArray)

/**
 * The photos that matched one semantic query, written just before the list query that reads them is built.
 * Each query gets its own [queryId], so a list still paging in the results of an older query is not
 * disturbed by a newer one. Scratch data: a handful of recent queries are kept, never user data.
 */
@Entity(tableName = "search_hit", primaryKeys = ["queryId", "mediaId"])
data class SearchHitEntity(
    val queryId: Long,
    val mediaId: Long,
    val score: Float,
)
