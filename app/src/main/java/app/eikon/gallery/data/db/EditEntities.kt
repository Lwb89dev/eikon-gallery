package app.eikon.gallery.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The edit of one photo, as the text of an `EditRecipe`. The photo's file is never changed by an edit; this row is the whole edit.
 * [baseModifiedAt] is the file's modification time when the edit was made, so an edit made to an older version of a file that has since
 * changed can be recognised. User data: it is kept when the media cache is cleared, because it cannot be rebuilt.
 */
@Entity(tableName = "edit_recipe")
data class EditRecipeEntity(
    @PrimaryKey val mediaId: Long,
    val recipe: String,
    val updatedAt: Long,
    val baseModifiedAt: Long,
)

@Dao
interface EditDao {
    @Query("SELECT * FROM edit_recipe")
    fun observeAll(): Flow<List<EditRecipeEntity>>

    @Query("SELECT * FROM edit_recipe WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): EditRecipeEntity?

    @Upsert
    suspend fun upsert(entity: EditRecipeEntity)

    @Upsert
    suspend fun upsertAll(entities: List<EditRecipeEntity>)

    @Query("DELETE FROM edit_recipe WHERE mediaId IN (:ids)")
    suspend fun delete(ids: List<Long>)

    @Query("SELECT * FROM edit_recipe WHERE mediaId IN (:ids)")
    suspend fun get(ids: List<Long>): List<EditRecipeEntity>
}
