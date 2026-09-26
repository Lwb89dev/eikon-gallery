package app.eikon.gallery.data.indexing

import javax.inject.Inject
import javax.inject.Singleton

/**
 * The photos the user is looking at right now, so the analysis can do them before the rest of the library (which it works through newest first). The screens
 * report what is on screen; the analysis asks between batches. Only a handful of ids, in memory, and forgotten when the screen goes away: nothing is stored.
 */
@Singleton
class AnalysisPriority @Inject constructor() {
    @Volatile
    private var visible: List<Long> = emptyList()

    /** Replaces what is on screen. Anything beyond [MAX] photos (a very dense grid) is ignored: those are the ones furthest from the finger anyway. */
    fun show(ids: Collection<Long>) {
        visible = ids.distinct().take(MAX)
    }

    fun clear() {
        visible = emptyList()
    }

    fun current(): List<Long> = visible

    companion object {
        /** Enough for a screen of the densest grid and the photo open in the viewer. */
        const val MAX = 200
    }
}
