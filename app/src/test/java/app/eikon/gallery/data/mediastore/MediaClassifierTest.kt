package app.eikon.gallery.data.mediastore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaClassifierTest {
    private fun photo(
        name: String = "IMG_0001.jpg",
        mime: String = "image/jpeg",
        path: String? = "DCIM/Camera/",
        bucket: String? = "Camera",
        width: Int = 4000,
        height: Int = 3000,
    ) = MediaClassifier.classify(false, mime, name, path, bucket, width, height)

    private fun video(name: String, path: String?, bucket: String? = null) =
        MediaClassifier.classify(true, "video/mp4", name, path, bucket, 1920, 1080)

    @Test
    fun ordinaryPhotoHasNoSpecialCategory() {
        assertEquals(MediaCategories(false, false, false, false), photo())
    }

    @Test
    fun screenshotIsDetectedFromFolderOrName() {
        assertTrue(photo(name = "a.png", path = "Pictures/Screenshots/", bucket = "Screenshots").isScreenshot)
        assertTrue(photo(name = "Screenshot_20250814-153210.png", path = null, bucket = null).isScreenshot)
        assertTrue(photo(name = "x.png", path = "Immagini/Schermate/", bucket = null).isScreenshot)
    }

    @Test
    fun screenRecordingRequiresVideo() {
        assertTrue(video("Screen_Recording_20250814.mp4", "Movies/").isScreenRecording)
        assertTrue(video("a.mp4", "DCIM/Screen recordings/").isScreenRecording)
        assertTrue(video("a.mp4", "Movies/ScreenRecorder/").isScreenRecording)
        assertFalse(video("holiday.mp4", "DCIM/Camera/").isScreenRecording)
        assertFalse(photo(name = "Screen_Recording_1.png").isScreenRecording)
    }

    @Test
    fun panoramaIsDetectedFromNameOrWideShape() {
        assertTrue(photo(name = "PANO_20250814_101010.jpg").isPanorama)
        assertTrue(photo(width = 12000, height = 3000).isPanorama)
        assertTrue(photo(width = 3000, height = 12000).isPanorama)
    }

    @Test
    fun wideButSmallOrModerateImagesAreNotPanoramas() {
        assertFalse(photo(width = 1600, height = 400).isPanorama)
        assertFalse(photo(width = 4000, height = 3000).isPanorama)
        assertFalse(photo(width = 6000, height = 3000).isPanorama)
    }

    @Test
    fun tallScreenshotIsNotAPanorama() {
        val categories = photo(name = "Screenshot_long.png", path = "Pictures/Screenshots/", width = 1080, height = 9000)
        assertTrue(categories.isScreenshot)
        assertFalse(categories.isPanorama)
    }

    @Test
    fun rawIsDetectedFromExtensionOrMime() {
        assertTrue(photo(name = "DSC_0001.DNG", mime = "image/x-adobe-dng").isRaw)
        assertTrue(photo(name = "IMG_1.CR3", mime = "application/octet-stream").isRaw)
        assertTrue(photo(name = "a.nef", mime = "image/x-nikon-nef").isRaw)
        assertFalse(photo(name = "a.jpg", mime = "image/jpeg").isRaw)
        assertFalse(photo(name = "a.heic", mime = "image/heic").isRaw)
    }
}
