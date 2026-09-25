package app.eikon.gallery.feature.viewer

import org.junit.Assert.assertFalse
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
}
