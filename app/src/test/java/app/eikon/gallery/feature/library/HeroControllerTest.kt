package app.eikon.gallery.feature.library

import androidx.compose.runtime.MonotonicFrameClock
import app.eikon.gallery.domain.MediaItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The viewer must not show itself again once its photo has flown back into the grid (it used to, for the length of its fade-out). */
@OptIn(ExperimentalCoroutinesApi::class)
class HeroControllerTest {
    /** A screen that draws a frame every 16 ms of the test's own (virtual) time. */
    private class Frames : MonotonicFrameClock {
        private var now = 0L
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            delay(16)
            now += 16_000_000
            return onFrame(now)
        }
    }

    private val photo = MediaItem(1, "a.jpg", "image/jpeg", false, takenAt = 1, addedAt = 1, modifiedAt = 1, width = 4000, height = 3000, durationMs = 0, sizeBytes = 1, relativePath = null, bucketName = null, isFavorite = false)
    private val cell = Frame(0f, 0f, 100f, 100f)
    private val resting = Frame(0f, 500f, 1080f, 810f)

    private fun flight(opening: Boolean) = HeroFlight(photo, cell, resting, opening)

    @Test
    fun aPhotoFlyingBackIsHiddenFromTheViewerAfterItHasLanded() = runTest {
        val hero = HeroController()
        val flying = launch(Frames()) { hero.fly(flight(opening = false)) }
        advanceTimeBy(100)
        assertFalse("the viewer stays hidden while the photo is in the air", hero.viewerShown)
        flying.join()
        assertNull(hero.flight)
        assertTrue(hero.landedInGrid)
        assertFalse("nothing of the viewer comes back once the photo is in its cell", hero.viewerShown)
        assertEquals(0f, hero.backdrop(), 0f)
    }

    @Test
    fun aPhotoFlyingOutOfTheGridLeavesTheViewerShownWhenItLands() = runTest {
        val hero = HeroController()
        launch(Frames()) { hero.fly(flight(opening = true)) }.join()
        assertFalse(hero.landedInGrid)
        assertTrue(hero.viewerShown)
        assertEquals(1f, hero.backdrop(), 0f)
    }

    @Test
    fun aFlightBackThatIsCancelledHasNotLanded() = runTest {
        val hero = HeroController()
        val flying = launch(Frames()) { hero.fly(flight(opening = false)) }
        advanceTimeBy(100)
        flying.cancel()
        flying.join()
        assertFalse(hero.landedInGrid)
        assertTrue("the viewer is shown as usual when a flight is cancelled", hero.viewerShown)
    }

    @Test
    fun openingTheViewerAgainShowsItAgain() = runTest {
        val hero = HeroController()
        launch(Frames()) { hero.fly(flight(opening = false)) }.join()
        assertTrue(hero.landedInGrid)
        hero.viewerReopened()
        assertFalse(hero.landedInGrid)
        assertTrue(hero.viewerShown)
        assertEquals(1f, hero.backdrop(), 0f)
    }

    @Test
    fun aNewFlightAfterALandingStartsFromAHiddenViewerToo() = runTest {
        val hero = HeroController()
        launch(Frames()) { hero.fly(flight(opening = false)) }.join()
        val flying = launch(Frames()) { hero.fly(flight(opening = true)) }
        advanceTimeBy(50)
        assertFalse(hero.landedInGrid)
        assertFalse("the viewer waits for the photo to arrive", hero.viewerShown)
        flying.join()
        assertTrue(hero.viewerShown)
    }
}
