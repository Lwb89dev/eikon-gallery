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
import app.eikon.gallery.domain.search.DateSpec
import app.eikon.gallery.domain.search.PlaceMatch
import app.eikon.gallery.domain.search.SearchSpec
import app.eikon.gallery.domain.search.SearchTerm
import app.eikon.gallery.domain.search.TextNormalizer

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

    /** Keeps a very common place name from producing hundreds of bound values. */
    private const val MAX_PLACE_IDS = 200

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
            conditions += scopeConditions(query.scope, nowMillis)
            conditions += filterConditions(query.filters)
            where = conditions.joinToString(" AND ")
        }

        private fun scopeConditions(scope: LibraryScope, nowMillis: Long): List<String> = when (scope) {
            LibraryScope.Hidden -> listOf("m.id IN (SELECT mediaId FROM hidden_media)")
            is LibraryScope.Folder -> {
                args += scope.relativePath
                listOf("m.relativePath = ?", NOT_HIDDEN)
            }
            is LibraryScope.RecentlyAdded -> {
                args += nowMillis - scope.days * MILLIS_PER_DAY
                listOf("m.addedAt >= ?", NOT_HIDDEN)
            }
            is LibraryScope.Search -> searchConditions(scope.spec)
            is LibraryScope.Album, LibraryScope.Everything -> listOf(NOT_HIDDEN)
        }

        /** Every term must match (AND); the parsed type/kind filters apply too; hidden items never do. */
        private fun searchConditions(spec: SearchSpec): List<String> = buildList {
            add(NOT_HIDDEN)
            spec.terms.forEach { add(termCondition(it)) }
            dateCondition(spec.dates)?.let { add(it) }
            addAll(filterConditions(spec.filters))
        }

        /**
         * One word matches a photo whose file name or recognized text starts with it, or, when it names a
         * place, a photo taken there. The FTS query holds only letters and digits plus `*`, so user text
         * cannot alter the query's structure.
         */
        private fun termCondition(term: SearchTerm): String {
            val alternatives = mutableListOf<String>()
            val fts = ftsPrefixQuery(term.text)
            if (fts != null) {
                alternatives += "m.id IN (SELECT rowid FROM media_search WHERE media_search MATCH ?)"
                args += fts
            }
            term.place?.let { alternatives += placeCondition(it) }
            return if (alternatives.isEmpty()) "0" else alternatives.joinToString(" OR ", "(", ")")
        }

        private fun placeCondition(place: PlaceMatch): String {
            val tests = mutableListOf<String>()
            if (place.cityIds.isNotEmpty()) tests += inList("g.cityId", place.cityIds.take(MAX_PLACE_IDS))
            if (place.regionKeys.isNotEmpty()) tests += inList("g.regionKey", place.regionKeys.toList())
            if (place.countryCodes.isNotEmpty()) tests += inList("g.countryCode", place.countryCodes.toList())
            return "EXISTS (SELECT 1 FROM media_geo g WHERE g.mediaId = m.id AND (${tests.joinToString(" OR ")}))"
        }

        private fun inList(column: String, values: List<Any>): String {
            args.addAll(values)
            return "$column IN (${values.joinToString(",") { "?" }})"
        }

        /** Several dates are alternatives: photos from any of them match. */
        private fun dateCondition(dates: List<DateSpec>): String? {
            if (dates.isEmpty()) return null
            return dates.joinToString(" OR ", "(", ")") { spec ->
                when (spec) {
                    is DateSpec.Range -> {
                        args += spec.range.startMillis
                        args += spec.range.endMillis
                        "(m.takenAt >= ? AND m.takenAt < ?)"
                    }
                    is DateSpec.Months -> {
                        val months = spec.months.filter { it in 1..12 }.joinToString(",")
                        "CAST(strftime('%m', m.takenAt / 1000, 'unixepoch', 'localtime') AS INTEGER) IN ($months)"
                    }
                    is DateSpec.MonthDay -> {
                        args += "%02d-%02d".format(spec.month, spec.day)
                        "strftime('%m-%d', m.takenAt / 1000, 'unixepoch', 'localtime') = ?"
                    }
                }
            }
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

    /** `roma*`, `new* york*`: each word a prefix, all of them required. Null if nothing searchable remains. */
    fun ftsPrefixQuery(text: String): String? {
        val words = TextNormalizer.words(text)
        return if (words.isEmpty()) null else words.joinToString(" ") { "$it*" }
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
