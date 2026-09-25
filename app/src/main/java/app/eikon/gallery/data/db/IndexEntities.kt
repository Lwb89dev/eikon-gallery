package app.eikon.gallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Index
import androidx.room.PrimaryKey

/** The analysis steps a photo goes through. Each is tracked separately, per photo. */
enum class IndexStage { GEO, OCR }

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
