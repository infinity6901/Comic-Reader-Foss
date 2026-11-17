package com.infinity.comicreader.util

import org.junit.Assert.assertEquals
import org.junit.Test

class SpreadLayoutTest {
    @Test fun oddPageCountKeepsFinalPageInItsOwnSpread() {
        assertEquals(0, SpreadLayout.itemCount(0))
        assertEquals(1, SpreadLayout.itemCount(1))
        assertEquals(2, SpreadLayout.itemCount(3))
        assertEquals(3, SpreadLayout.itemCount(5))
    }

    @Test fun leftToRightSpreadsMapConsecutivePages() {
        assertEquals(0, SpreadLayout.firstPage(0, rtl = false))
        assertEquals(1, SpreadLayout.secondPage(0, rtl = false))
        assertEquals(2, SpreadLayout.firstPage(1, rtl = false))
        assertEquals(3, SpreadLayout.secondPage(1, rtl = false))
    }

    @Test fun rightToLeftSpreadsReverseVisualOrderButNotProgress() {
        assertEquals(1, SpreadLayout.firstPage(0, rtl = true))
        assertEquals(0, SpreadLayout.secondPage(0, rtl = true))
        assertEquals(2, SpreadLayout.firstPageForProgress(1))
        assertEquals(1, SpreadLayout.itemForPage(3))
    }

    @Test fun coverSoloKeepsFirstPageAloneAndPairsFacingPages() {
        // 5 pages with coverSolo: item 0 = (0), item 1 = (1, 2), item 2 = (3, 4) -> 3 items
        assertEquals(3, SpreadLayout.itemCount(5, coverSolo = true))
        // 4 pages with coverSolo: item 0 = (0), item 1 = (1, 2), item 2 = (3) -> 3 items
        assertEquals(3, SpreadLayout.itemCount(4, coverSolo = true))

        assertEquals(0, SpreadLayout.firstPage(0, rtl = false, coverSolo = true))
        assertEquals(SpreadLayout.NO_PAGE, SpreadLayout.secondPage(0, rtl = false, coverSolo = true))

        assertEquals(1, SpreadLayout.firstPage(1, rtl = false, coverSolo = true))
        assertEquals(2, SpreadLayout.secondPage(1, rtl = false, coverSolo = true))
        assertEquals(2, SpreadLayout.firstPage(1, rtl = true, coverSolo = true))
        assertEquals(1, SpreadLayout.secondPage(1, rtl = true, coverSolo = true))

        assertEquals(0, SpreadLayout.itemForPage(0, coverSolo = true))
        assertEquals(1, SpreadLayout.itemForPage(1, coverSolo = true))
        assertEquals(1, SpreadLayout.itemForPage(2, coverSolo = true))
        assertEquals(2, SpreadLayout.itemForPage(3, coverSolo = true))
    }

    @Test fun nextSpreadPageAdvancesFullSpreadInBothModes() {
        // Standard pairs: (0,1) -> (2,3) -> (4,5)
        assertEquals(2, SpreadLayout.nextSpreadPage(0, forward = true, coverSolo = false))
        assertEquals(2, SpreadLayout.nextSpreadPage(1, forward = true, coverSolo = false))
        assertEquals(0, SpreadLayout.nextSpreadPage(2, forward = false, coverSolo = false))

        // Solo cover: (0) -> (1,2) -> (3,4)
        assertEquals(1, SpreadLayout.nextSpreadPage(0, forward = true, coverSolo = true))
        assertEquals(3, SpreadLayout.nextSpreadPage(1, forward = true, coverSolo = true))
        assertEquals(3, SpreadLayout.nextSpreadPage(2, forward = true, coverSolo = true))
        assertEquals(0, SpreadLayout.nextSpreadPage(1, forward = false, coverSolo = true))
    }
}
