package app.eikon.gallery.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A person as the user sees them in People: a group of faces that look alike, which the user can name, rename,
 * merge with another, hide or mark as favorite. [name] is null until the user names them. Groups that are only one
 * photo (passers-by in the background) are not listed unless the user named, favorited or created them.
 */
@Entity(tableName = "person")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String?,
    val isFavorite: Boolean = false,
    val isHidden: Boolean = false,
    /** Made or confirmed by the user (a split), so it is listed even with a single photo. */
    val isPinned: Boolean = false,
    val createdAt: Long,
)

/**
 * One face found in one photo: where it is (a box as fractions of the upright picture, so it survives resizing),
 * how sure the detector was, and its 128-number description, stored as 128 little-endian floats. [personId] is
 * the person it was grouped with; [ignored] is set when the user says it is not a real face or does not
 * want it in People.
 */
@Entity(tableName = "face", indices = [Index("mediaId"), Index("personId")])
class FaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mediaId: Long,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val score: Float,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val vector: ByteArray,
    val personId: Long?,
    val ignored: Boolean = false,
)

/** A row of the People list: who, how many photos they are in, and the best face to show as their picture. */
class PersonSummary(
    val id: Long,
    val name: String?,
    val isFavorite: Boolean,
    val isHidden: Boolean,
    val photoCount: Int,
    val coverMediaId: Long,
    val coverModifiedAt: Long,
    val coverLeft: Float,
    val coverTop: Float,
    val coverRight: Float,
    val coverBottom: Float,
)

/** A face of the photo being looked at, with the person it was grouped with if any. */
class FaceOfPhoto(val faceId: Long, val personId: Long?, val name: String?)

/** A stored face vector with the person it belongs to, read back to rebuild the clustering. */
class AssignedVector(val id: Long, val personId: Long, val vector: ByteArray)

/** Named people, for turning a word in a search into a person. */
class PersonName(val id: Long, val name: String)
