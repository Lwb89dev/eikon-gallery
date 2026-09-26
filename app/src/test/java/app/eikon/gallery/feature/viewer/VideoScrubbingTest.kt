package app.eikon.gallery.feature.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScrubbingTest {
    /** Remembers what the scrubber asked of the player, in order. */
    private class RecordingPlayer(playing: Boolean) : ScrubTarget {
        val calls = mutableListOf<String>()
        override var playWhenReady: Boolean = playing
            set(value) {
                calls += "play=$value"
                field = value
            }

        override fun setScrubbing(enabled: Boolean) {
            calls += "scrubbing=$enabled"
        }

        override fun seekTo(positionMs: Long) {
            calls += "seek=$positionMs"
        }
    }

    @Test
    fun everyPositionOfTheThumbIsSoughtAtOnceWhileItMoves() {
        val player = RecordingPlayer(playing = true)
        val scrubber = Scrubber(player)
        scrubber.move(1_000)
        scrubber.move(2_000)
        scrubber.move(3_500)
        assertEquals(listOf("play=false", "scrubbing=true", "seek=1000", "seek=2000", "seek=3500"), player.calls)
    }

    @Test
    fun letGoOfTheThumbLeavesScrubbingSeeksExactlyAndPlaysOnAgain() {
        val player = RecordingPlayer(playing = true)
        val scrubber = Scrubber(player)
        scrubber.move(1_000)
        player.calls.clear()

        scrubber.finish(1_200)

        assertEquals(listOf("scrubbing=false", "seek=1200", "play=true"), player.calls)
        assertFalse(scrubber.active)
    }

    @Test
    fun aVideoThatWasPausedStaysPausedAfterTheScrub() {
        val player = RecordingPlayer(playing = false)
        val scrubber = Scrubber(player)
        scrubber.move(500)
        scrubber.finish(600)
        assertFalse(player.playWhenReady)
        assertFalse("nothing to resume, so the player is not told to play", player.calls.contains("play=true"))
    }

    @Test
    fun aTapOnTheBarIsAScrubOfOnePosition() {
        val player = RecordingPlayer(playing = true)
        val scrubber = Scrubber(player)
        scrubber.move(9_000)
        scrubber.finish(9_000)
        assertEquals(listOf("play=false", "scrubbing=true", "seek=9000", "scrubbing=false", "seek=9000", "play=true"), player.calls)
    }

    @Test
    fun finishingWithoutHavingMovedDoesNothing() {
        val player = RecordingPlayer(playing = true)
        Scrubber(player).finish(1_000)
        assertTrue(player.calls.isEmpty())
    }

    @Test
    fun aBarThatGoesAwayMidScrubLeavesThePlayerPlayingAndNotScrubbing() {
        val player = RecordingPlayer(playing = true)
        val scrubber = Scrubber(player)
        scrubber.move(4_000)
        player.calls.clear()

        scrubber.cancel()

        assertEquals(listOf("scrubbing=false", "play=true"), player.calls)
        scrubber.cancel()
        assertEquals("a second cancel changes nothing", 2, player.calls.size)
    }

    @Test
    fun aSecondScrubStartsFromWhatThePlayerIsDoingThen() {
        val player = RecordingPlayer(playing = true)
        val scrubber = Scrubber(player)
        scrubber.move(1_000)
        scrubber.finish(1_000)
        player.playWhenReady = false // paused by the user in between
        player.calls.clear()

        scrubber.move(2_000)
        scrubber.finish(2_000)

        assertFalse(player.calls.contains("play=true"))
    }

    // --- the picture's shape --------------------------------------------------------------------------

    @Test
    fun aPlainVideoIsAsWideAsThePlayerSaysItIs() {
        assertEquals(16f / 9f, VideoAspect.of(1920, 1080, 1f), 0.001f)
        assertEquals("a portrait video, as reported", 9f / 16f, VideoAspect.of(1080, 1920, 1f), 0.001f)
    }

    @Test
    fun aVideoWithNonSquarePixelsIsStretchedByTheirShape() {
        assertEquals(720f * 1.5f / 576f, VideoAspect.of(720, 576, 1.5f), 0.001f)
    }

    @Test
    fun anUnknownSizeOrPixelShapeIsHandledWithoutDividingByZero() {
        assertEquals(0f, VideoAspect.of(0, 0, 1f), 0f)
        assertEquals(0f, VideoAspect.of(1920, 0, 1f), 0f)
        assertEquals("a pixel shape of 0 means not given", 2f, VideoAspect.of(200, 100, 0f), 0.001f)
    }
}
