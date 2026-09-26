package app.eikon.gallery.data.sync

import app.eikon.gallery.data.db.MediaEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Telling a photo whose file was rewritten from one that was only touched. */
class FileChangeTest {
    private val photo = MediaEntity(
        id = 1, displayName = "a.jpg", mimeType = "image/jpeg", isVideo = false, takenAt = 1, addedAt = 1, modifiedAt = 100, width = 4000, height = 3000, durationMs = 0,
        sizeBytes = 5_000_000, relativePath = "DCIM/Camera/", bucketName = "Camera", isFavorite = false, isScreenshot = false, isScreenRecording = false, isPanorama = false, isRaw = false,
    )

    @Test
    fun aNewTimeAndANewSizeIsARewrite() {
        assertTrue(FileChange.rewritten(photo, photo.copy(modifiedAt = 200, sizeBytes = 4_900_000)))
    }

    @Test
    fun aNewTimeAndNewDimensionsIsARewrite() {
        assertTrue(FileChange.rewritten(photo, photo.copy(modifiedAt = 200, width = 3000, height = 4000)))
        assertTrue(FileChange.rewritten(photo, photo.copy(modifiedAt = 200, height = 2000)))
    }

    @Test
    fun aNewTimeAloneIsATouchAsMarkingAFavoriteMayDo() {
        assertFalse(FileChange.rewritten(photo, photo.copy(modifiedAt = 200, isFavorite = true)))
    }

    @Test
    fun aNewSizeWithTheSameTimeIsNotWhatSyncSees() {
        assertFalse(FileChange.rewritten(photo, photo.copy(sizeBytes = 1)))
    }

    @Test
    fun nothingChangedIsNotARewrite() {
        assertFalse(FileChange.rewritten(photo, photo.copy()))
    }
}
