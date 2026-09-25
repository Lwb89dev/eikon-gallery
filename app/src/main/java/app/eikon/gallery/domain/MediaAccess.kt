package app.eikon.gallery.domain

/** How much of the media library the OS lets eikon read right now. */
enum class MediaAccess {
    /** Photos and videos (of the types granted) are fully readable. */
    FULL,

    /** Android 14+ "selected photos" access: only what the user hand-picked is visible. */
    LIMITED,

    NONE,
}
