package app.eikon.gallery.domain.search

/** How a photo's file name becomes searchable words. */
object SearchText {
    private val camelBoundary = Regex("(?<=[a-z0-9])(?=[A-Z])")

    /**
     * "IMG_20250814_101010.jpg" becomes "img 20250814 101010"; "MyHolidayPhoto.png" becomes
     * "my holiday photo". Search then finds names by the start of any word in them.
     */
    fun filename(displayName: String): String {
        val stem = displayName.substringBeforeLast('.', displayName)
        return TextNormalizer.words(stem.replace(camelBoundary, " ")).joinToString(" ")
    }
}
