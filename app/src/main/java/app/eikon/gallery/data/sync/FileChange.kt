package app.eikon.gallery.data.sync

import app.eikon.gallery.data.db.MediaEntity

/** Whether a sync found that a photo's file was rewritten, as opposed to merely touched. */
object FileChange {
    /**
     * A file was rewritten when its modification time moved *and* its size or its dimensions did. The time alone is not enough: Android may move it for things that leave the picture as it is
     * (marking a favorite), and redoing the analysis of a photo for that would waste the battery. A rewrite that changes neither size nor dimensions is not something a photo editor does.
     */
    fun rewritten(before: MediaEntity, after: MediaEntity): Boolean =
        before.modifiedAt != after.modifiedAt &&
            (before.sizeBytes != after.sizeBytes || before.width != after.width || before.height != after.height)
}
