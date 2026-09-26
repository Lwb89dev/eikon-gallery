package app.eikon.gallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A caption the user wrote for a photo. It lives only in eikon's database: the photo's file is never written for it. Full-text indexed (case and accents ignored) so Search finds it;
 * `rowid` is the media id. User data: kept when the media cache is cleared.
 */
@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61, tokenizerArgs = ["remove_diacritics=2"])
@Entity(tableName = "media_caption")
data class MediaCaptionEntity(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Long,
    val caption: String,
)

/**
 * What a photo's file said before eikon first changed it: the value of one field ([FIELD_DATE], [FIELD_LOCATION]) as text, or null if the file had none. Kept so the change can be undone
 * and so nothing about the original is ever lost silently. Written once per field (the first change); later changes keep the true original.
 */
@Entity(tableName = "metadata_original", primaryKeys = ["mediaId", "field"])
data class MetadataOriginalEntity(
    val mediaId: Long,
    val field: String,
    val value: String?,
    val savedAt: Long,
) {
    companion object {
        const val FIELD_DATE = "DATE"
        const val FIELD_LOCATION = "LOCATION"
    }
}

@Dao
interface MetadataDao {
    @Query("SELECT caption FROM media_caption WHERE rowid = :mediaId")
    suspend fun caption(mediaId: Long): String?

    @Query("INSERT OR REPLACE INTO media_caption (rowid, caption) VALUES (:mediaId, :caption)")
    suspend fun setCaption(mediaId: Long, caption: String)

    @Query("DELETE FROM media_caption WHERE rowid = :mediaId")
    suspend fun clearCaption(mediaId: Long)

    @Query("SELECT * FROM metadata_original WHERE mediaId = :mediaId")
    suspend fun originals(mediaId: Long): List<MetadataOriginalEntity>

    /** Keeps the first original of a field; a later change does not replace it. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun keepOriginal(original: MetadataOriginalEntity)

    @Query("DELETE FROM metadata_original WHERE mediaId = :mediaId AND field = :field")
    suspend fun forgetOriginal(mediaId: Long, field: String)
}
