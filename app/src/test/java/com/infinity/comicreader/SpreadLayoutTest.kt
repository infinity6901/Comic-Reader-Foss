package com.infinity.comicreader

import com.infinity.comicreader.util.ComicInfo
import com.infinity.comicreader.util.SpreadLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpreadLayoutTest {
    @Test
    fun standardSpreadPairing() {
        assertEquals(3, SpreadLayout.itemCount(5, coverSolo = false))
        assertEquals(0, SpreadLayout.firstPage(0, rtl = false, coverSolo = false))
        assertEquals(1, SpreadLayout.secondPage(0, rtl = false, coverSolo = false))
        assertEquals(4, SpreadLayout.firstPage(2, rtl = false, coverSolo = false))
        assertEquals(5, SpreadLayout.secondPage(2, rtl = false, coverSolo = false))
    }

    @Test
    fun coverSoloSpreadPairing() {
        // Pages: [0], [1, 2], [3, 4] -> 3 spread items for 5 pages
        assertEquals(3, SpreadLayout.itemCount(5, coverSolo = true))
        assertEquals(0, SpreadLayout.firstPage(0, rtl = false, coverSolo = true))
        assertEquals(SpreadLayout.NO_PAGE, SpreadLayout.secondPage(0, rtl = false, coverSolo = true))
        assertEquals(1, SpreadLayout.firstPage(1, rtl = false, coverSolo = true))
        assertEquals(2, SpreadLayout.secondPage(1, rtl = false, coverSolo = true))
        assertEquals(0, SpreadLayout.itemForPage(0, coverSolo = true))
        assertEquals(1, SpreadLayout.itemForPage(1, coverSolo = true))
        assertEquals(1, SpreadLayout.itemForPage(2, coverSolo = true))
        assertEquals(2, SpreadLayout.itemForPage(3, coverSolo = true))
        assertEquals(1, SpreadLayout.nextSpreadPage(0, forward = true, coverSolo = true))
        assertEquals(3, SpreadLayout.nextSpreadPage(1, forward = true, coverSolo = true))
        assertEquals(0, SpreadLayout.nextSpreadPage(2, forward = false, coverSolo = true))
    }

    @Test
    fun comicInfoEntryMatching() {
        assertTrue(ComicInfo.isComicInfoEntry("ComicInfo.xml"))
        assertTrue(ComicInfo.isComicInfoEntry("sub/dir/comicinfo.xml"))
        assertFalse(ComicInfo.isComicInfoEntry("001.jpg"))
    }
}
