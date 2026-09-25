package app.eikon.gallery.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-made album. Albums are virtual: they only reference MediaStore ids, so creating, filling or
 * deleting one never moves, copies or deletes a file, and one photo can be in any number of albums.
 * Do not confuse them with device folders (MediaStore buckets), which are the files' real location.
 */
@Entity(tableName = "album")
data class AlbumEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    /** Manual order of albums in the collections screen, ascending. */
    val position: Int,
)

/**
 * Membership of a media item in an album. Deliberately no foreign key to `media`: that table is a
 * rebuildable cache that sync may empty (access revoked, "selected photos" changed), and such a
 * refresh must never destroy the user's albums. Rows whose media is not currently visible are simply
 * not shown.
 */
@Entity(
    tableName = "album_item",
    primaryKeys = ["albumId", "mediaId"],
    foreignKeys = [
        ForeignKey(
            entity = AlbumEntity::class,
            parentColumns = ["id"],
            childColumns = ["albumId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("mediaId")],
)
data class AlbumItemEntity(
    val albumId: Long,
    val mediaId: Long,
    val addedAt: Long,
)

/** Media hidden from the library, search and collections; visible only in the (locked) Hidden section. */
@Entity(tableName = "hidden_media")
data class HiddenMediaEntity(
    @PrimaryKey val mediaId: Long,
    val hiddenAt: Long,
)
