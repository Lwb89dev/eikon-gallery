package app.eikon.gallery.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.sync.RoomMediaIndex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Album and hidden-media behaviour on the real Room + Android SQLite stack. */
@RunWith(AndroidJUnit4::class)
class AlbumAndHiddenTest {
    private lateinit var db: EikonDatabase
    private lateinit var repository: AlbumRepository
    private var now = 1_000L

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repository = AlbumRepository(db.albumDao(), db.mediaDao(), db.hiddenDao()) { now++ }
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(vararg ids: Long) = db.mediaDao().upsertAll(ids.map { media(it, day = it.toInt()) })

    private suspend fun albums() = repository.albumSummaries.first()

    @Test
    fun createdAlbumsAppearInCreationOrderWithZeroItems() = runTest {
        repository.create("Trip")
        repository.create("Family")
        assertEquals(listOf("Trip", "Family"), albums().map { it.name })
        assertTrue(albums().all { it.itemCount == 0 && it.coverId == null })
    }

    @Test
    fun blankNamesCreateNothing() = runTest {
        assertNull(repository.create("   "))
        assertTrue(albums().isEmpty())
    }

    @Test
    fun addingPhotosCountsThemAndUsesTheNewestAsCover() = runTest {
        seed(1, 2, 3)
        val id = repository.create("Trip")!!
        repository.addToAlbum(id, listOf(1, 2))
        val album = albums().single()
        assertEquals(2, album.itemCount)
        assertEquals(2L, album.coverId) // day 2 is newer than day 1
    }

    @Test
    fun addingTheSamePhotoTwiceIsANoOp() = runTest {
        seed(1)
        val id = repository.create("Trip")!!
        repository.addToAlbum(id, listOf(1))
        repository.addToAlbum(id, listOf(1))
        assertEquals(1, albums().single().itemCount)
    }

    @Test
    fun aPhotoCanBeInSeveralAlbums() = runTest {
        seed(1)
        val a = repository.create("A")!!
        val b = repository.create("B")!!
        repository.addToAlbum(a, listOf(1))
        repository.addToAlbum(b, listOf(1))
        assertEquals(listOf(1, 1), albums().map { it.itemCount })
    }

    @Test
    fun removingFromAnAlbumLeavesThePhotoInTheLibrary() = runTest {
        seed(1, 2)
        val id = repository.create("Trip")!!
        repository.addToAlbum(id, listOf(1, 2))
        repository.removeFromAlbum(id, listOf(1))
        assertEquals(1, albums().single().itemCount)
        assertEquals(setOf(1L, 2L), db.mediaDao().allIds().toSet())
    }

    @Test
    fun deletingAnAlbumDeletesOnlyItsMembershipsNotThePhotos() = runTest {
        seed(1, 2)
        val id = repository.create("Trip")!!
        repository.addToAlbum(id, listOf(1, 2))
        repository.delete(id)
        assertTrue(albums().isEmpty())
        assertEquals(setOf(1L, 2L), db.mediaDao().allIds().toSet())
        assertNull(repository.album(id).first())
    }

    @Test
    fun renameKeepsItemsAndRejectsBlank() = runTest {
        seed(1)
        val id = repository.create("Old")!!
        repository.addToAlbum(id, listOf(1))
        assertTrue(repository.rename(id, "  New name "))
        assertEquals("New name", albums().single().name)
        assertEquals(false, repository.rename(id, " "))
        assertEquals("New name", albums().single().name)
        assertEquals(1, albums().single().itemCount)
    }

    @Test
    fun reorderingMovesOneStepAndClampsAtTheEnds() = runTest {
        val a = repository.create("A")!!
        val b = repository.create("B")!!
        val c = repository.create("C")!!
        repository.moveEarlier(c)
        assertEquals(listOf("A", "C", "B"), albums().map { it.name })
        repository.moveEarlier(a) // already first
        repository.moveLater(b) // already last
        assertEquals(listOf("A", "C", "B"), albums().map { it.name })
        repository.moveLater(a)
        assertEquals(listOf("C", "A", "B"), albums().map { it.name })
        assertEquals(listOf(0, 1, 2), albums().map { it.position })
        assertNotNull(b)
    }

    @Test
    fun hiddenItemsDisappearFromAlbumCountsAndCovers() = runTest {
        seed(1, 2)
        val id = repository.create("Trip")!!
        repository.addToAlbum(id, listOf(1, 2))
        repository.hide(listOf(2))
        val album = albums().single()
        assertEquals(1, album.itemCount)
        assertEquals(1L, album.coverId)
        repository.unhide(listOf(2))
        assertEquals(2, albums().single().itemCount)
    }

    @Test
    fun foldersExcludeHiddenItemsAndListNewestActivityFirst() = runTest {
        db.mediaDao().upsertAll(
            listOf(
                media(1, day = 1, path = "DCIM/Camera/"),
                media(2, day = 9, path = "Pictures/Screenshots/"),
                media(3, day = 5, path = "DCIM/Camera/"),
            ),
        )
        assertEquals(listOf("Pictures/Screenshots/", "DCIM/Camera/"), repository.folders.first().map { it.relativePath })
        repository.hide(listOf(2))
        assertEquals(listOf("DCIM/Camera/"), repository.folders.first().map { it.relativePath })
        assertEquals(2, repository.folders.first().single().itemCount)
    }

    @Test
    fun clearingTheMediaCacheKeepsAlbumsAndHiddenFlags() = runTest {
        seed(1, 2)
        val id = repository.create("Trip")!!
        repository.addToAlbum(id, listOf(1, 2))
        repository.hide(listOf(2))

        RoomMediaIndex(db.mediaDao()).clear() // what happens when photo access is revoked
        assertTrue(db.mediaDao().allIds().isEmpty())

        seed(1, 2) // access is granted again and sync refills the cache
        val album = albums().single()
        assertEquals("Trip", album.name)
        assertEquals(1, album.itemCount) // item 2 is still hidden
    }
}
