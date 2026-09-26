package app.eikon.gallery.feature.viewer

/** What scrubbing asks of a player, so that the order of the calls can be checked without one. */
interface ScrubTarget {
    var playWhenReady: Boolean

    /** Fast, approximate seeks that skip frames along the way (the player's scrubbing mode) while true. */
    fun setScrubbing(enabled: Boolean)

    fun seekTo(positionMs: Long)
}

/**
 * Dragging the seek bar: the picture follows the thumb instead of waiting for the finger to lift. While the thumb moves the video is paused and every position goes to the
 * player in its scrubbing mode; when the thumb is let go the last position is sought exactly, and playback goes on if it was playing.
 */
class Scrubber(private val target: ScrubTarget) {
    private var resume = false

    var active = false
        private set

    /** The thumb is at [positionMs]. The first call starts the scrub. */
    fun move(positionMs: Long) {
        if (!active) begin()
        target.seekTo(positionMs)
    }

    /** The thumb was let go at [positionMs]. Does nothing if no scrub was going on. */
    fun finish(positionMs: Long) {
        if (!active) return
        end()
        target.seekTo(positionMs)
        resumeIfWasPlaying()
    }

    /** The bar went away in the middle of a scrub: leave the player as it was, wherever it has got to. */
    fun cancel() {
        if (!active) return
        end()
        resumeIfWasPlaying()
    }

    private fun begin() {
        active = true
        resume = target.playWhenReady
        target.playWhenReady = false
        target.setScrubbing(true)
    }

    private fun end() {
        active = false
        target.setScrubbing(false)
    }

    private fun resumeIfWasPlaying() {
        if (resume) target.playWhenReady = true
    }
}

/** How wide a video looks on screen, needed to know how far a zoomed picture may be dragged. */
internal object VideoAspect {
    /** Width over height of what is shown: the size the player reports (already turned the right way up), stretched by the pixel shape. 0 while the size is not known. */
    fun of(width: Int, height: Int, pixelWidthHeightRatio: Float): Float {
        if (width <= 0 || height <= 0) return 0f
        return width * (if (pixelWidthHeightRatio > 0f) pixelWidthHeightRatio else 1f) / height
    }
}
