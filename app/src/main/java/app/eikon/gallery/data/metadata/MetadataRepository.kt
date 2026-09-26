package app.eikon.gallery.data.metadata

import app.eikon.gallery.data.Clock
import app.eikon.gallery.data.db.MetadataDao
import app.eikon.gallery.data.db.MetadataOriginalEntity
import app.eikon.gallery.domain.ExifSnapshot
import javax.inject.Inject
import javax.inject.Singleton

/** Captions, and what a photo's date and location said before eikon changed them. Both are user data in eikon's own database; neither touches a photo's file. */
@Singleton
class MetadataRepository @Inject constructor(
    private val dao: MetadataDao,
    private val clock: Clock,
) {
    suspend fun caption(mediaId: Long): String? = dao.caption(mediaId)?.takeIf { it.isNotBlank() }

    /** Stores [text] as the caption of [mediaId] (trimmed, and cut at [MAX_CAPTION]); a blank text removes the caption. */
    suspend fun setCaption(mediaId: Long, text: String) {
        val caption = text.trim().take(MAX_CAPTION)
        if (caption.isEmpty()) dao.clearCaption(mediaId) else dao.setCaption(mediaId, caption)
    }

    /** What [field] said in the file before the first change eikon made to it, or null if eikon never changed it. The tags are as the file spelled them. */
    suspend fun original(mediaId: Long, field: String): Map<String, String>? =
        dao.originals(mediaId).firstOrNull { it.field == field }?.let { ExifSnapshot.decode(it.value.orEmpty()) }

    /** Keeps the original of [field] (only the first time: a later change does not replace the true original). */
    suspend fun keepOriginal(mediaId: Long, field: String, tags: Map<String, String>) {
        dao.keepOriginal(MetadataOriginalEntity(mediaId, field, ExifSnapshot.encode(tags), clock.nowMillis()))
    }

    suspend fun forgetOriginal(mediaId: Long, field: String) = dao.forgetOriginal(mediaId, field)

    suspend fun hasOriginal(mediaId: Long, field: String): Boolean = dao.originals(mediaId).any { it.field == field }

    companion object {
        const val MAX_CAPTION = 500
    }
}
