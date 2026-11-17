package com.infinity.comicreader.util

/**
 * Pure page-to-row mapping shared by the reader UI and its spread adapter.
 *
 * When [coverSolo] is true, physical page 0 (the cover) occupies spread item 0
 * by itself ([secondPage] returns [NO_PAGE]), and subsequent spreads pair pages
 * `(1, 2)`, `(3, 4)`, etc. so inside facing pages align properly.
 */
object SpreadLayout {
    const val NO_PAGE = -1

    fun itemCount(pageCount: Int, coverSolo: Boolean = false): Int {
        val safe = pageCount.coerceAtLeast(0)
        if (safe == 0) return 0
        return if (coverSolo) {
            1 + (safe) / 2
        } else {
            (safe + 1) / 2
        }
    }

    fun itemForPage(page: Int, coverSolo: Boolean = false): Int {
        val safe = page.coerceAtLeast(0)
        return if (coverSolo) {
            if (safe == 0) 0 else (safe + 1) / 2
        } else {
            safe / 2
        }
    }

    fun firstPage(item: Int, rtl: Boolean, coverSolo: Boolean = false): Int {
        val safe = item.coerceAtLeast(0)
        if (coverSolo) {
            if (safe == 0) return 0
            val base = safe * 2 - 1
            return base + if (rtl) 1 else 0
        }
        return safe * 2 + if (rtl) 1 else 0
    }

    fun secondPage(item: Int, rtl: Boolean, coverSolo: Boolean = false): Int {
        val safe = item.coerceAtLeast(0)
        if (coverSolo) {
            if (safe == 0) return NO_PAGE
            val base = safe * 2 - 1
            return base + if (rtl) 0 else 1
        }
        return safe * 2 + if (rtl) 0 else 1
    }

    fun firstPageForProgress(item: Int, coverSolo: Boolean = false): Int {
        val safe = item.coerceAtLeast(0)
        if (coverSolo) {
            return if (safe == 0) 0 else safe * 2 - 1
        }
        return safe * 2
    }

    /**
     * Computes the target physical page index when stepping one spread forward
     * or backward from [currentPage].
     */
    fun nextSpreadPage(currentPage: Int, forward: Boolean, coverSolo: Boolean = false): Int {
        val currentItem = itemForPage(currentPage, coverSolo)
        val targetItem = if (forward) currentItem + 1 else currentItem - 1
        if (targetItem < 0) return -1
        return firstPageForProgress(targetItem, coverSolo)
    }
}
