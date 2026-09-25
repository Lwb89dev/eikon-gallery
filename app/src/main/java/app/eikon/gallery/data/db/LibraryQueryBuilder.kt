package app.eikon.gallery.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.eikon.gallery.domain.CategoryFilter
import app.eikon.gallery.domain.LibraryFilters
import app.eikon.gallery.domain.LibraryQuery
import app.eikon.gallery.domain.LibraryScope
import app.eikon.gallery.domain.SortDirection
import app.eikon.gallery.domain.SortField
import app.eikon.gallery.domain.TimelineGrouping
import app.eikon.gallery.domain.TypeFilter

/** Plain SQL plus bind arguments, kept free of Room types so it can be unit tested on the JVM. */
data class SqlQuery(val sql: String, val args: List<Any> = emptyList()) {
    fun toSupportQuery(): SimpleSQLiteQuery = SimpleSQLiteQuery(sql, args.toTypedArray())
}

/**
 * Turns a [LibraryQuery] into SQL over `media` (alias `m`).
 *
 * Every fragment that reaches the SQL text comes from a `when` over an enum or sealed type; values
 * (album id, folder, timestamps) are always bound as arguments. There is nothing to inject into.
 *
 * Hidden items are excluded from every scope except [LibraryScope.Hidden], here in the single place
 * that builds the queries, so no screen can forget to apply it.
 */
object LibraryQueryBuilder {
    private const val MILLIS_PER_DAY = 86_400_000L

    fun media(query: LibraryQuery, nowMillis: Long = System.currentTimeMillis()): SqlQuery {
        val parts = Parts(query, nowMillis)
        return SqlQuery("SELECT m.* ${parts.from} WHERE ${parts.where} ORDER BY ${orderBy(query)}", parts.args)
    }

    fun count(query: LibraryQuery, nowMillis: Long = System.currentTimeMillis()): SqlQuery {
        val parts = Parts(query, nowMillis)
        return SqlQuery("SELECT COUNT(*) ${parts.from} WHERE ${parts.where}", parts.args)
    }

    /** The newest item of the slice, used as the cover of a collection tile. */
    fun cover(query: LibraryQuery, nowMillis: Long = System.currentTimeMillis()): SqlQuery {
        val parts = Parts(query, nowMillis)
        return SqlQuery("SELECT m.* ${parts.from} WHERE ${parts.where} ORDER BY ${orderBy(query)} LIMIT 1", parts.args)
    }

    /**
     * Item counts per local-time day or month, in the same order as [media]. `localtime` is what makes
     * a photo taken at 23:50 land under the day the user remembers; COALESCE keeps timestamps SQLite
     * cannot convert from producing a NULL bucket.
     */
    fun sections(query: LibraryQuery, grouping: TimelineGrouping, nowMillis: Long = System.currentTimeMillis()): SqlQuery {
        val parts = Parts(query, nowMillis)
        val format = if (grouping == TimelineGrouping.DAY) "%Y-%m-%d" else "%Y-%m"
        val column = sortColumn(query.sortField)
        val direction = if (query.direction == SortDirection.NEWEST_FIRST) "DESC" else "ASC"
        return SqlQuery(
            "SELECT COALESCE(strftime('$format', $column / 1000, 'unixepoch', 'localtime'), '0000') AS bucket, " +
                "COUNT(*) AS count ${parts.from} WHERE ${parts.where} " +
                "GROUP BY bucket ORDER BY bucket $direction",
            parts.args,
        )
    }

    /** FROM/JOIN and WHERE fragments plus their arguments, in placeholder order. */
    private class Parts(query: LibraryQuery, nowMillis: Long) {
        val args = mutableListOf<Any>()
        val from: String
        val where: String

        init {
            val conditions = mutableListOf<String>()
            from = when (val scope = query.scope) {
                is LibraryScope.Album -> {
                    conditions += "ai.albumId = ?"
                    args += scope.id
                    "FROM media m JOIN album_item ai ON ai.mediaId = m.id"
                }
                else -> "FROM media m"
            }
            conditions += scopeCondition(query.scope, nowMillis)
            conditions += filterConditions(query.filters)
            where = conditions.joinToString(" AND ")
        }

        private fun scopeCondition(scope: LibraryScope, nowMillis: Long): String = when (scope) {
            LibraryScope.Hidden -> "m.id IN (SELECT mediaId FROM hidden_media)"
            is LibraryScope.Folder -> {
                args += scope.relativePath
                "m.relativePath = ? AND $NOT_HIDDEN"
            }
            is LibraryScope.RecentlyAdded -> {
                args += nowMillis - scope.days * MILLIS_PER_DAY
                "m.addedAt >= ? AND $NOT_HIDDEN"
            }
            is LibraryScope.Album, LibraryScope.Everything -> NOT_HIDDEN
        }

        private fun filterConditions(filters: LibraryFilters): List<String> = buildList {
            when (filters.type) {
                TypeFilter.ALL -> Unit
                TypeFilter.PHOTOS -> add("m.isVideo = 0")
                TypeFilter.VIDEOS -> add("m.isVideo = 1")
            }
            if (filters.favoritesOnly) add("m.isFavorite = 1")
            filters.category?.let { add(categoryCondition(it)) }
        }
    }

    private const val NOT_HIDDEN = "m.id NOT IN (SELECT mediaId FROM hidden_media)"

    private fun categoryCondition(category: CategoryFilter): String = when (category) {
        CategoryFilter.SCREENSHOTS -> "m.isScreenshot = 1"
        CategoryFilter.SCREEN_RECORDINGS -> "m.isScreenRecording = 1"
        CategoryFilter.PANORAMAS -> "m.isPanorama = 1"
        CategoryFilter.RAW -> "m.isRaw = 1"
    }

    /** `id` is the rowid, so the composite order is served by the index on the date column alone. */
    private fun orderBy(query: LibraryQuery): String {
        val direction = if (query.direction == SortDirection.NEWEST_FIRST) "DESC" else "ASC"
        return "${sortColumn(query.sortField)} $direction, m.id $direction"
    }

    private fun sortColumn(field: SortField): String = when (field) {
        SortField.DATE_TAKEN -> "m.takenAt"
        SortField.DATE_ADDED -> "m.addedAt"
    }
}
