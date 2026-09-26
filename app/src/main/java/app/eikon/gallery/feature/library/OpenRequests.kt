package app.eikon.gallery.feature.library

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A picture the user asked to see **in the library** (from the "Open in library" button over a picture another app handed over, such as the photo just taken with the camera).
 * The library screen picks the request up, waits until the index has the picture, and opens its viewer there; a request that is not met in a few seconds is dropped, so a picture
 * that is hidden, or filtered out, cannot leave the library trying for ever.
 */
@Singleton
class OpenRequests @Inject constructor() {
    private val current = MutableStateFlow<Long?>(null)

    /** The media id waiting to be opened, or null. */
    val pending: StateFlow<Long?> = current.asStateFlow()

    fun request(mediaId: Long) {
        current.value = mediaId
    }

    /** Ends the request for [mediaId], unless a newer one has replaced it. */
    fun done(mediaId: Long) {
        current.compareAndSet(mediaId, null)
    }
}
