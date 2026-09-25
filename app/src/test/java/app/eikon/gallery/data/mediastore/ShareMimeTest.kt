package app.eikon.gallery.data.mediastore

import org.junit.Assert.assertEquals
import org.junit.Test

class ShareMimeTest {
    @Test
    fun singleItemKeepsItsExactType() {
        assertEquals("image/heic", ShareMime.of(listOf("image/heic")))
    }

    @Test
    fun sameTypeRepeatedKeepsExactType() {
        assertEquals("image/jpeg", ShareMime.of(listOf("image/jpeg", "IMAGE/JPEG")))
    }

    @Test
    fun differentImageTypesWidenToImageWildcard() {
        assertEquals("image/*", ShareMime.of(listOf("image/jpeg", "image/png")))
    }

    @Test
    fun photosAndVideosWidenToEverything() {
        assertEquals("*/*", ShareMime.of(listOf("image/jpeg", "video/mp4")))
    }

    @Test
    fun videosOnlyWidenToVideoWildcard() {
        assertEquals("video/*", ShareMime.of(listOf("video/mp4", "video/webm")))
    }
}
