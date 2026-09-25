package app.eikon.gallery.data.mediastore

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.provider.MediaStore
import app.eikon.gallery.domain.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Operations that modify or hand out media. Changes to files eikon does not own go through the
 * system's own confirmation dialog (MediaStore.create*Request), so the user always sees and approves
 * what is about to happen; eikon never gets silent write access.
 */
@Singleton
class MediaActions @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver: ContentResolver get() = context.contentResolver

    /**
     * Moves the items to the system trash (kept for about 30 days and restorable from any gallery
     * that supports it), after the system confirmation dialog.
     */
    fun trashRequest(items: Collection<MediaItem>): IntentSender =
        MediaStore.createTrashRequest(resolver, items.map { it.uri }, true).intentSender

    fun favoriteRequest(items: Collection<MediaItem>, favorite: Boolean): IntentSender =
        MediaStore.createFavoriteRequest(resolver, items.map { it.uri }, favorite).intentSender

    /** Android Sharesheet intent for one or many files, videos and photos mixed included. */
    fun shareIntent(entries: List<ShareEntry>): Intent {
        val uris = ArrayList<Uri>(entries.map { it.uri })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = ShareMime.of(entries.map { it.mimeType })
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, null)
    }
}

/** Something to hand to another app: the address of a file and what kind of file it is. */
class ShareEntry(val uri: Uri, val mimeType: String)

/** Picks the MIME type advertised to the Sharesheet so it lists apps that accept every item. */
object ShareMime {
    fun of(mimeTypes: List<String>): String {
        val distinct = mimeTypes.map { it.lowercase() }.distinct()
        if (distinct.size == 1) return distinct.first()
        val majors = distinct.map { it.substringBefore('/') }.distinct()
        return if (majors.size == 1) "${majors.first()}/*" else "*/*"
    }
}
