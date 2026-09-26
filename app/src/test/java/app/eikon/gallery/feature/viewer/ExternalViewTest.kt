package app.eikon.gallery.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which requests from other apps eikon agrees to show. */
class ExternalViewTest {
    private val view = "android.intent.action.VIEW"

    @Test
    fun aPictureHandedOverAsAContentAddressIsShown() {
        assertTrue(ExternalView.isImage(view, "content", "image/jpeg"))
        assertTrue(ExternalView.isImage(view, "content", "image/heic"))
        assertTrue(ExternalView.isImage(view, "content", "IMAGE/PNG"))
    }

    @Test
    fun onlyTheViewActionIsTaken() {
        assertFalse(ExternalView.isImage("android.intent.action.SEND", "content", "image/jpeg"))
        assertFalse(ExternalView.isImage("android.intent.action.EDIT", "content", "image/jpeg"))
        assertFalse(ExternalView.isImage(null, "content", "image/jpeg"))
    }

    @Test
    fun onlyPicturesAreTaken() {
        assertFalse(ExternalView.isImage(view, "content", "video/mp4"))
        assertFalse(ExternalView.isImage(view, "content", "application/pdf"))
        assertFalse(ExternalView.isImage(view, "content", null))
    }

    @Test
    fun onlyContentAddressesAreTakenNeverFilesOrTheWeb() {
        assertFalse(ExternalView.isImage(view, "file", "image/jpeg"))
        assertFalse(ExternalView.isImage(view, "https", "image/jpeg"))
        assertFalse(ExternalView.isImage(view, "http", "image/jpeg"))
        assertFalse(ExternalView.isImage(view, "android.resource", "image/jpeg"))
        assertFalse(ExternalView.isImage(view, null, "image/jpeg"))
    }

    @Test
    fun theReviewActionsOfCameraAppsAreTakenToo() {
        assertTrue(ExternalView.isImage("com.android.camera.action.REVIEW", "content", "image/jpeg"))
        assertTrue(ExternalView.isImage("android.provider.action.REVIEW", "content", "image/jpeg"))
        assertFalse(ExternalView.isImage("android.provider.action.REVIEW_SECURE", "content", "image/jpeg"))
    }

    @Test
    fun aMediaStoreAddressWithoutATypeIsAPictureButAnyOtherAddressWithoutOneIsNot() {
        assertTrue(ExternalView.isImage("com.android.camera.action.REVIEW", "content", null, mediaId = 42L))
        assertFalse(ExternalView.isImage("com.android.camera.action.REVIEW", "content", null, mediaId = null))
        // A stated type wins over the address: a video is not a picture, whatever address it has.
        assertFalse(ExternalView.isImage("com.android.camera.action.REVIEW", "content", "video/mp4", mediaId = 42L))
    }

    @Test
    fun theIdOfAMediaStorePictureIsReadFromItsAddress() {
        assertEquals(1234L, ExternalView.mediaStoreId("media", listOf("external", "images", "media", "1234")))
        assertEquals(7L, ExternalView.mediaStoreId("media", listOf("external_primary", "images", "media", "7")))
    }

    @Test
    fun onlyAPictureAddressOfTheMediaStoreHasAnId() {
        assertNull(ExternalView.mediaStoreId("media", listOf("external", "video", "media", "1234")))
        assertNull(ExternalView.mediaStoreId("media", listOf("external", "images", "thumbnails", "1234")))
        assertNull(ExternalView.mediaStoreId("media", listOf("external", "images", "media", "abc")))
        assertNull(ExternalView.mediaStoreId("media", listOf("external", "images", "media")))
        assertNull(ExternalView.mediaStoreId("com.example.files", listOf("external", "images", "media", "1234")))
        assertNull(ExternalView.mediaStoreId(null, emptyList()))
    }
}
