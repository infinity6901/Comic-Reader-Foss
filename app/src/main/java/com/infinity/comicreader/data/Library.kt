package com.infinity.comicreader.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Favorites, read marks, reading history, bookmarks, per-series settings and
 * reading statistics, persisted as JSON documents in SharedPreferences.
 *
 * Parsed collections are cached in memory under [LOCK] so frequent queries
 * (such as [isBookmarked] during page turns or [historyMap] while filtering)
 * do not repeatedly re-parse JSON on the UI thread.
 */
class Library(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---------------- favorites ----------------
    fun favorites(): MutableList<String> = synchronized(LOCK) {
        val list = cachedFavorites ?: readStringList(KEY_FAVORITES).also { cachedFavorites = it }
        ArrayList(list)
    }

    fun favoriteSet(): Set<String> = synchronized(LOCK) {
        val list = cachedFavorites ?: readStringList(KEY_FAVORITES).also { cachedFavorites = it }
        list.toHashSet()
    }

    fun isFavorite(path: String): Boolean = synchronized(LOCK) {
        val list = cachedFavorites ?: readStringList(KEY_FAVORITES).also { cachedFavorites = it }
        list.contains(path)
    }

    /** Returns true when the path was added, false when it was removed. */
    fun toggleFavorite(path: String): Boolean = synchronized(LOCK) {
        val list = favorites()
        val added = if (list.contains(path)) {
            list.remove(path)
            false
        } else {
            list.add(0, path)
            true
        }
        writeStringList(KEY_FAVORITES, list)
        cachedFavorites = list
        added
    }

    // ---------------- read marks ----------------
    fun readSet(): MutableSet<String> = synchronized(LOCK) {
        val set = cachedRead ?: readStringList(KEY_READ).toHashSet().also { cachedRead = it }
        HashSet(set)
    }

    fun isRead(path: String): Boolean = synchronized(LOCK) {
        val set = cachedRead ?: readStringList(KEY_READ).toHashSet().also { cachedRead = it }
        set.contains(path)
    }

    fun setRead(path: String, read: Boolean) = setReadMany(listOf(path), read)

    fun setReadMany(paths: Collection<String>, read: Boolean) {
        synchronized(LOCK) {
            val set = readSet()
            val changed = if (read) set.addAll(paths) else set.removeAll(paths.toSet())
            if (changed) {
                writeStringList(KEY_READ, set.toList())
                cachedRead = set
            }
        }
    }

    // ---------------- history ----------------
    fun history(): MutableList<HistoryItem> = synchronized(LOCK) {
        val cached = cachedHistory
        if (cached != null) return@synchronized ArrayList(cached)
        val arr = readArray(KEY_HISTORY)
        val out = ArrayList<HistoryItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                HistoryItem(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    page = o.optInt("page"),
                    total = o.optInt("total"),
                    time = o.optLong("time")
                )
            )
        }
        cachedHistory = out
        ArrayList(out)
    }

    fun historyMap(): Map<String, HistoryItem> = history().associateBy { it.path }

    fun historyFor(path: String): HistoryItem? = history().firstOrNull { it.path == path }

    fun pageFor(path: String): Int = historyFor(path)?.page ?: 0

    fun addHistory(item: HistoryItem) {
        synchronized(LOCK) {
            val list = ArrayList(history().filterNot { it.path == item.path })
            list.add(0, item)
            while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
            writeHistory(list)
            cachedHistory = list
        }
    }

    /** Clear under the same lock as addHistory so a concurrent save cannot resurrect old entries. */
    fun clearHistory() {
        synchronized(LOCK) {
            writeHistory(emptyList())
            cachedHistory = emptyList()
        }
    }

    // ---------------- bookmarks ----------------
    fun bookmarks(): MutableList<Bookmark> = synchronized(LOCK) {
        val cached = cachedBookmarks
        if (cached != null) return@synchronized ArrayList(cached)
        val arr = readArray(KEY_BOOKMARKS)
        val out = ArrayList<Bookmark>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(
                Bookmark(
                    path = o.optString("path"),
                    name = o.optString("name"),
                    page = o.optInt("page"),
                    note = o.optString("note"),
                    time = o.optLong("time")
                )
            )
        }
        cachedBookmarks = out
        ArrayList(out)
    }

    fun bookmarksFor(path: String): List<Bookmark> =
        bookmarks().filter { it.path == path }.sortedBy { it.page }

    fun isBookmarked(path: String, page: Int): Boolean =
        bookmarks().any { it.path == path && it.page == page }

    fun addBookmark(bookmark: Bookmark) {
        synchronized(LOCK) {
            val list = ArrayList(bookmarks().filterNot { it.path == bookmark.path && it.page == bookmark.page })
            list.add(0, bookmark)
            writeBookmarks(list)
            cachedBookmarks = list
        }
    }

    fun removeBookmark(path: String, page: Int) {
        synchronized(LOCK) {
            val list = bookmarks().filterNot { it.path == path && it.page == page }
            writeBookmarks(list)
            cachedBookmarks = list
        }
    }

    /** Clear under the same lock as bookmark edits to avoid lost updates. */
    fun clearBookmarks() {
        synchronized(LOCK) {
            writeBookmarks(emptyList())
            cachedBookmarks = emptyList()
        }
    }

    // ---------------- path maintenance (rename / move / delete) ----------------
    private fun under(path: String, root: String) = path == root || path.startsWith("$root/")

    /** Re-points every stored path after a file or folder was renamed or moved. */
    fun replacePath(old: String, new: String) {
        synchronized(LOCK) {
            fun remap(p: String) = if (under(p, old)) new + p.removePrefix(old) else p
            val newFavs = favorites().map { remap(it) }
            writeStringList(KEY_FAVORITES, newFavs)
            cachedFavorites = newFavs

            val newRead = readSet().map { remap(it) }
            writeStringList(KEY_READ, newRead)
            cachedRead = newRead.toHashSet()

            val series = readObject(KEY_SERIES)
            val moved = JSONObject()
            series.keys().forEach { k -> moved.put(remap(k), series.get(k)) }
            sp.edit().putString(KEY_SERIES, moved.toString()).apply()

            val newHistory = history().map { h ->
                if (under(h.path, old)) {
                    val np = remap(h.path)
                    h.copy(path = np, name = np.substringAfterLast('/'))
                } else h
            }
            writeHistory(newHistory)
            cachedHistory = newHistory

            val newBookmarks = bookmarks().map { b ->
                if (under(b.path, old)) {
                    val np = remap(b.path)
                    b.copy(path = np, name = np.substringAfterLast('/'))
                } else b
            }
            writeBookmarks(newBookmarks)
            cachedBookmarks = newBookmarks
        }
    }

    /** Forgets everything stored for a deleted file or folder. */
    fun removePathsUnder(root: String) {
        synchronized(LOCK) {
            val newFavs = favorites().filterNot { under(it, root) }
            writeStringList(KEY_FAVORITES, newFavs)
            cachedFavorites = newFavs

            val newRead = readSet().filterNot { under(it, root) }
            writeStringList(KEY_READ, newRead)
            cachedRead = newRead.toHashSet()

            val series = readObject(KEY_SERIES)
            val kept = JSONObject()
            series.keys().forEach { k -> if (!under(k, root)) kept.put(k, series.get(k)) }
            sp.edit().putString(KEY_SERIES, kept.toString()).apply()

            val newHistory = history().filterNot { under(it.path, root) }
            writeHistory(newHistory)
            cachedHistory = newHistory

            val newBookmarks = bookmarks().filterNot { under(it.path, root) }
            writeBookmarks(newBookmarks)
            cachedBookmarks = newBookmarks
        }
    }

    // ---------------- per-series reader settings ----------------
    fun seriesFor(folder: String): SeriesSettings? = synchronized(LOCK) {
        val o = readObject(KEY_SERIES).optJSONObject(folder) ?: return null
        SeriesSettings(
            mode = o.optString("mode", "paged"),
            dir = o.optString("dir", "ltr"),
            fit = o.optString("fit", "screen"),
            twoPage = o.optBoolean("two", false),
            coverSolo = o.optBoolean("coverSolo", true)
        )
    }

    /** Saves the settings for [folder], or forgets them when [settings] is null. */
    fun setSeries(folder: String, settings: SeriesSettings?) {
        synchronized(LOCK) {
            val all = readObject(KEY_SERIES)
            if (settings == null) {
                all.remove(folder)
            } else {
                all.put(
                    folder,
                    JSONObject()
                        .put("mode", settings.mode)
                        .put("dir", settings.dir)
                        .put("fit", settings.fit)
                        .put("two", settings.twoPage)
                        .put("coverSolo", settings.coverSolo)
                )
            }
            sp.edit().putString(KEY_SERIES, all.toString()).apply()
        }
    }

    // ---------------- reading statistics ----------------
    /** Adds [seconds] of reading time and [pages] newly seen pages to today's totals. */
    fun addReading(seconds: Long, pages: Int) {
        synchronized(LOCK) {
            if (seconds <= 0 && pages <= 0) return@synchronized
            val o = readObject(KEY_STATS)
            val safeSeconds = seconds.coerceAtLeast(0)
            val safePages = pages.coerceAtLeast(0)
            o.put("seconds", o.optLong("seconds") + safeSeconds)
            o.put("pages", o.optLong("pages") + safePages)
            val today = LocalDate.now().toString()
            // Daily counters roll over lazily on the first saved session of a new day.
            if (o.optString("dailyDate") != today) {
                o.put("dailyDate", today).put("todayPages", 0).put("todaySeconds", 0)
            }
            o.put("todayPages", o.optLong("todayPages") + safePages)
            o.put("todaySeconds", o.optLong("todaySeconds") + safeSeconds)

            // Keep per-day totals so the dashboard can show a real weekly trend.
            val dailyLog = o.optJSONObject("dailyLog") ?: JSONObject()
            val existingDay = dailyLog.optJSONObject(today)
            val dayTotals = existingDay ?: JSONObject().apply {
                if (o.optString("dailyDate") == today) {
                    put("pages", (o.optLong("todayPages") - safePages).coerceAtLeast(0))
                    put("seconds", (o.optLong("todaySeconds") - safeSeconds).coerceAtLeast(0))
                }
            }
            dayTotals.put("pages", dayTotals.optLong("pages") + safePages)
            dayTotals.put("seconds", dayTotals.optLong("seconds") + safeSeconds)
            dailyLog.put(today, dayTotals)
            val oldestKept = LocalDate.now().minusDays((MAX_DAILY_LOG_DAYS - 1).toLong()).toString()
            val staleDates = dailyLog.keys().asSequence().filter { it < oldestKept }.toList()
            staleDates.forEach { dailyLog.remove(it) }
            o.put("dailyLog", dailyLog)

            val days = o.optJSONArray("days") ?: JSONArray()
            var has = false
            for (i in 0 until days.length()) if (days.optString(i) == today) has = true
            if (!has) days.put(today)
            val trimmed = JSONArray()
            for (i in (days.length() - MAX_DAYS).coerceAtLeast(0) until days.length()) trimmed.put(days.get(i))
            o.put("days", trimmed)
            sp.edit().putString(KEY_STATS, o.toString()).apply()
        }
    }

    fun stats(): ReadingStats = synchronized(LOCK) {
        val o = readObject(KEY_STATS)
        val days = HashSet<LocalDate>()
        o.optJSONArray("days")?.let { arr ->
            for (i in 0 until arr.length()) {
                runCatching { days.add(LocalDate.parse(arr.getString(i))) }
            }
        }
        var streak = 0
        var d = LocalDate.now()
        if (d !in days) d = d.minusDays(1)
        while (d in days) {
            streak++
            d = d.minusDays(1)
        }
        var best = 0
        for (day in days) {
            if (day.minusDays(1) in days) continue
            var len = 0
            var cur = day
            while (cur in days) {
                len++
                cur = cur.plusDays(1)
            }
            if (len > best) best = len
        }
        ReadingStats(
            seconds = o.optLong("seconds"),
            pages = o.optLong("pages"),
            chapters = readSet().size,
            daysRead = days.size,
            streak = streak,
            bestStreak = best,
            todayPages = if (o.optString("dailyDate") == LocalDate.now().toString()) o.optLong("todayPages") else 0,
            todaySeconds = if (o.optString("dailyDate") == LocalDate.now().toString()) o.optLong("todaySeconds") else 0,
            dailyPageGoal = o.optInt("dailyPageGoal", 0).coerceAtLeast(0),
            dailyMinuteGoal = o.optInt("dailyMinuteGoal", 0).coerceAtLeast(0)
        )
    }

    /** Returns recent daily totals, including zero-activity days, oldest first. */
    fun dailyActivity(days: Int = 7): List<DailyReading> = synchronized(LOCK) {
        val count = days.coerceIn(1, 365)
        val o = readObject(KEY_STATS)
        val log = o.optJSONObject("dailyLog") ?: JSONObject()
        val today = LocalDate.now()
        (count - 1 downTo 0).map { offset ->
            val date = today.minusDays(offset.toLong())
            val key = date.toString()
            val totals = log.optJSONObject(key)
            DailyReading(
                date = date.format(DateTimeFormatter.ofPattern("EEE, MMM d")),
                pages = totals?.optLong("pages")?.coerceAtLeast(0) ?: 0,
                seconds = totals?.optLong("seconds")?.coerceAtLeast(0) ?: 0
            )
        }
    }

    /** Configure optional daily targets. Zero disables a target. */
    fun setDailyGoals(pages: Int, minutes: Int) {
        synchronized(LOCK) {
            val o = readObject(KEY_STATS)
            o.put("dailyPageGoal", pages.coerceIn(0, 5000))
            o.put("dailyMinuteGoal", minutes.coerceIn(0, 1440))
            sp.edit().putString(KEY_STATS, o.toString()).apply()
        }
    }

    fun clearStats() = synchronized(LOCK) {
        val current = readObject(KEY_STATS)
        val goals = JSONObject()
            .put("dailyPageGoal", current.optInt("dailyPageGoal", 0))
            .put("dailyMinuteGoal", current.optInt("dailyMinuteGoal", 0))
        sp.edit().putString(KEY_STATS, goals.toString()).apply()
    }

    // ---------------- backup ----------------
    /** The stored documents, keyed by name, as parsed JSON (for backup). */
    fun exportAll(): JSONObject = synchronized(LOCK) {
        val out = JSONObject()
        for (k in ARRAY_KEYS) out.put(k, readArray(k))
        for (k in OBJECT_KEYS) out.put(k, readObject(k))
        out
    }

    /**
     * Replaces the stored documents with those in [data]. Only known keys of the
     * right JSON type are taken; everything else is ignored.
     */
    fun importAll(data: JSONObject) {
        synchronized(LOCK) {
            val e = sp.edit()
            for (k in ARRAY_KEYS) data.optJSONArray(k)?.let { e.putString(k, sanitizeArray(k, it).toString()) }
            for (k in OBJECT_KEYS) data.optJSONObject(k)?.let { e.putString(k, it.toString()) }
            e.apply()
            invalidateCaches()
        }
    }

    /** Drops malformed rows so a hand-edited backup cannot break the lists later. */
    private fun sanitizeArray(key: String, arr: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until arr.length()) {
            when (key) {
                KEY_FAVORITES, KEY_READ -> arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { out.put(it) }
                else -> arr.optJSONObject(i)?.takeIf { it.optString("path").isNotEmpty() }?.let { out.put(it) }
            }
        }
        return out
    }

    // ---------------- helpers ----------------
    private fun readArray(key: String): JSONArray =
        try {
            JSONArray(sp.getString(key, "[]") ?: "[]")
        } catch (e: JSONException) {
            JSONArray()
        }

    private fun readObject(key: String): JSONObject =
        try {
            JSONObject(sp.getString(key, "{}") ?: "{}")
        } catch (e: JSONException) {
            JSONObject()
        }

    private fun readStringList(key: String): MutableList<String> {
        val arr = readArray(key)
        val out = ArrayList<String>(arr.length())
        for (i in 0 until arr.length()) arr.optString(i, "").takeIf { it.isNotEmpty() }?.let { out.add(it) }
        return out
    }

    private fun writeStringList(key: String, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        sp.edit().putString(key, arr.toString()).apply()
    }

    private fun writeHistory(list: List<HistoryItem>) {
        val arr = JSONArray()
        list.forEach { h ->
            arr.put(
                JSONObject()
                    .put("path", h.path).put("name", h.name)
                    .put("page", h.page).put("total", h.total).put("time", h.time)
            )
        }
        sp.edit().putString(KEY_HISTORY, arr.toString()).apply()
    }

    private fun writeBookmarks(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { b ->
            arr.put(
                JSONObject().put("path", b.path).put("name", b.name)
                    .put("page", b.page).put("note", b.note).put("time", b.time)
            )
        }
        sp.edit().putString(KEY_BOOKMARKS, arr.toString()).apply()
    }

    private companion object {
        const val FILE = "library"
        const val KEY_FAVORITES = "favorites"
        const val KEY_READ = "read"
        const val KEY_HISTORY = "history"
        const val KEY_BOOKMARKS = "bookmarks"
        const val KEY_SERIES = "series"
        const val KEY_STATS = "stats"
        const val MAX_HISTORY = 200
        const val MAX_DAYS = 3650
        const val MAX_DAILY_LOG_DAYS = 365
        val ARRAY_KEYS = listOf(KEY_FAVORITES, KEY_READ, KEY_HISTORY, KEY_BOOKMARKS)
        val OBJECT_KEYS = listOf(KEY_SERIES, KEY_STATS)
        val LOCK = Any()

        private var cachedFavorites: List<String>? = null
        private var cachedRead: Set<String>? = null
        private var cachedHistory: List<HistoryItem>? = null
        private var cachedBookmarks: List<Bookmark>? = null

        private fun invalidateCaches() {
            cachedFavorites = null
            cachedRead = null
            cachedHistory = null
            cachedBookmarks = null
        }
    }
}
