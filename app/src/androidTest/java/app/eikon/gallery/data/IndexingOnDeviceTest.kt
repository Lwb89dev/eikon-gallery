package app.eikon.gallery.data

import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.eikon.gallery.data.db.EikonDatabase
import app.eikon.gallery.data.db.HiddenMediaEntity
import app.eikon.gallery.data.db.IndexStage
import app.eikon.gallery.data.db.LibraryQueryBuilder
import app.eikon.gallery.data.db.MediaGeoEntity
import app.eikon.gallery.data.indexing.IndexingRepository
import app.eikon.gallery.data.sync.RoomMediaIndex
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.search.PlaceMatch
import app.eikon.gallery.domain.search.SearchSpec
import app.eikon.gallery.domain.search.SearchTerm
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The analysis queue, the full-text index and its upkeep, on the real Room + Android SQLite stack. */
@RunWith(AndroidJUnit4::class)
class IndexingOnDeviceTest {
    private lateinit var db: EikonDatabase
    private lateinit var repository: IndexingRepository
    private lateinit var index: RoomMediaIndex
    private var now = 1_000L

    @Before
    fun setUp() {
        db = inMemoryDatabase()
        repository = IndexingRepository(db.indexDao(), db.duplicatesDao()) { now++ }
        index = RoomMediaIndex(db, db.mediaDao(), db.indexDao(), db.peopleDao(), db.duplicatesDao())
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seed(vararg items: app.eikon.gallery.data.db.MediaEntity) = index.upsertAll(items.toList())

    private suspend fun searchIds(spec: SearchSpec): List<Long> {
        val query = LibraryQueryBuilder.media(LibraryQuery(scope = LibraryScope.Search(spec)))
        val page = db.mediaDao().pagingSource(query.toSupportQuery())
            .load(PagingSource.LoadParams.Refresh(null, 50, false)) as PagingSource.LoadResult.Page
        return page.data.map { it.id }
    }

    @Test
    fun pendingListsPhotosNewestFirstAndSkipsVideosAndFinishedOnes() = runTest {
        seed(media(1, day = 1), media(2, day = 3), media(3, day = 2), media(4, day = 9, video = true))
        assertEquals(listOf(2L, 3L, 1L), repository.pending(IndexStage.OCR, 10).map { it.id })

        repository.markDone(2, IndexStage.OCR)
        repository.markSkipped(3, IndexStage.OCR)
        assertEquals(listOf(1L), repository.pending(IndexStage.OCR, 10).map { it.id })
        // Another stage has its own progress.
        assertEquals(listOf(2L, 3L, 1L), repository.pending(IndexStage.GEO, 10).map { it.id })
    }

    @Test
    fun aPhotoThatKeepsFailingIsGivenUpOnAfterTheLimit() = runTest {
        seed(media(1))
        repeat(IndexingRepository.MAX_ATTEMPTS - 1) {
            repository.markFailed(1, IndexStage.OCR)
            assertEquals(1, repository.pending(IndexStage.OCR, 10).size) // still eligible
        }
        repository.markFailed(1, IndexStage.OCR)
        assertTrue(repository.pending(IndexStage.OCR, 10).isEmpty())
    }

    @Test
    fun progressCountsFinishedSkippedAndGivenUpPhotos() = runTest {
        seed(media(1), media(2), media(3), media(4), media(5, video = true))
        repository.markDone(1, IndexStage.OCR)
        repository.markSkipped(2, IndexStage.OCR)
        repeat(IndexingRepository.MAX_ATTEMPTS) { repository.markFailed(3, IndexStage.OCR) }
        val progress = repository.progress(IndexStage.OCR).first()
        assertEquals(3, progress.done)
        assertEquals(4, progress.total) // the video is not part of photo analysis
    }

    @Test
    fun newPhotosAreSearchableByFileNameAtOnceAndRenamesAreFollowed() = runTest {
        seed(media(1).copy(displayName = "IMG_20250814_101010.jpg"))
        assertEquals(listOf(1L), searchIds(SearchSpec(listOf(SearchTerm("2025")))))

        seed(media(1).copy(displayName = "Holiday.jpg")) // the same photo, renamed
        assertEquals(emptyList<Long>(), searchIds(SearchSpec(listOf(SearchTerm("2025")))))
        assertEquals(listOf(1L), searchIds(SearchSpec(listOf(SearchTerm("holiday")))))
    }

    @Test
    fun recognizedTextIsSearchableAndSurvivesTheNextSync() = runTest {
        seed(media(1))
        repository.saveText(1, "Ricevuta IKEA Città di Milano")
        assertEquals(listOf(1L), searchIds(SearchSpec(listOf(SearchTerm("citta")))))

        seed(media(1)) // an unchanged re-sync must not wipe what analysis learned
        assertEquals(listOf(1L), searchIds(SearchSpec(listOf(SearchTerm("ikea")))))
        assertEquals("Ricevuta IKEA Città di Milano", repository.text(1))
    }

    @Test
    fun placesAreSearchableByCityRegionAndCountry() = runTest {
        seed(media(1), media(2))
        repository.saveGeo(MediaGeoEntity(1, 41.9, 12.5, 3169070, "IT", "IT.07"))
        repository.saveGeo(MediaGeoEntity(2, 48.8, 2.3, 2988507, "FR", "FR.11"))

        assertEquals(listOf(1L), searchIds(SearchSpec(listOf(SearchTerm("roma", PlaceMatch(cityIds = setOf(3169070L)))))))
        assertEquals(listOf(1L), searchIds(SearchSpec(listOf(SearchTerm("lazio", PlaceMatch(regionKeys = setOf("IT.07")))))))
        assertEquals(listOf(2L), searchIds(SearchSpec(listOf(SearchTerm("francia", PlaceMatch(countryCodes = setOf("FR")))))))
    }

    @Test
    fun hiddenPhotosNeverShowUpInSearch() = runTest {
        seed(media(1), media(2))
        repository.saveText(1, "ikea scontrino")
        repository.saveText(2, "ikea scontrino")
        db.hiddenDao().hide(listOf(HiddenMediaEntity(2, 0)))
        assertEquals(listOf(1L), searchIds(SearchSpec(listOf(SearchTerm("ikea")))))
    }

    @Test
    fun deletingAPhotoRemovesEverythingLearnedAboutIt() = runTest {
        seed(media(1), media(2))
        repository.saveText(1, "scontrino")
        repository.saveGeo(MediaGeoEntity(1, 1.0, 2.0, null, null, null))
        repository.markDone(1, IndexStage.OCR)

        index.deleteByIds(listOf(1))

        assertNull(db.indexDao().geo(1))
        assertNull(repository.text(1))
        assertEquals(emptyList<Long>(), searchIds(SearchSpec(listOf(SearchTerm("scontrino")))))
        assertNull(db.indexDao().attempts(1, IndexStage.OCR.name))
        assertEquals(listOf(2L), db.indexDao().existingSearchRows(listOf(1L, 2L)))
    }

    @Test
    fun revokingAccessClearsDerivedDataButKeepsAlbumsAndHiddenFlags() = runTest {
        seed(media(1))
        repository.saveText(1, "scontrino")
        repository.saveGeo(MediaGeoEntity(1, 1.0, 2.0, null, "IT", null))
        val albumId = db.albumDao().create("Trip", 0)
        db.hiddenDao().hide(listOf(HiddenMediaEntity(1, 0)))
        db.albumDao().addItems(listOf(app.eikon.gallery.data.db.AlbumItemEntity(albumId, 1, 0)))

        index.clear()

        assertTrue(db.mediaDao().allIds().isEmpty())
        assertNull(db.indexDao().geo(1))
        assertEquals(emptyList<Long>(), db.indexDao().existingSearchRows(listOf(1L)))
        assertEquals(1, db.albumDao().observeAlbums().first().size)
    }

    @Test
    fun manyPhotosInOneBatchStayWithinSqliteVariableLimits() = runTest {
        val items = (1L..1_500L).map { media(it, day = (it % 300).toInt()) }
        index.upsertAll(items)
        val indexed = items.map { it.id }.chunked(500).sumOf { db.indexDao().existingSearchRows(it).size }
        assertEquals(1_500, indexed)
    }
}
