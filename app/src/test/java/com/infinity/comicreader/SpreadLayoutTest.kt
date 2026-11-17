package com.infinity.comicreader

import com.infinity.comicreader.util.SpreadLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpreadLayoutTest {
    @Test
    fun standardSpreadPairing() {
        assertEquals(3, SpreadLayout.itemCount(5, spread = true, coverSolo = false))
        assertEquals(0, SpreadLayout.firstPage(0, 5, spread = true, coverSolo = false))
        assertEquals(1, SpreadLayout.secondPage(0, 5, spread = true, coverSolo = false))
        assertEquals(4, SpreadLayout.firstPage(2, 5, spread = true, coverSolo = false))
        assertNull(SpreadLayout.secondPage(2, 5, spread = true, coverSolo = false))
    }

    @Test
    fun coverSoloSpreadPairing() {
        // Pages: [0], [1, 2], [3, 4] -> 3 spread items for 5 pages
        assertEquals(3, SpreadLayout.itemCount(5, spread = true, coverSolo = true))
        assertEquals(0, SpreadLayout.firstPage(0, 5, spread = true, coverSolo = true))
        assertNull(SpreadLayout.secondPage(0, 5, spread = true, coverSolo = true))
        assertEquals(1, SpreadLayout.firstPage(1, 5, spread = true, coverSolo = true))
        assertEquals(2, SpreadLayout.secondPage(1, 5, spread = true, coverSolo = true))
        assertEquals(0, SpreadLayout.itemForPage(0, spread = true, coverSolo = true))
        assertEquals(1, SpreadLayout.itemForPage(1, spread = true, coverSolo = true))
        assertEquals(1, SpreadLayout.itemForPage(2, spread = true, coverSolo = true))
        assertEquals(2, SpreadLayout.itemForPage(3, spread = true, coverSolo = true))
        assertEquals(1, SpreadLayout.nextSpreadPage(0, 1, 5, spread = true, coverSolo = true))
        assertEquals(3, SpreadLayout.nextSpreadPage(1, 1, 5, spread = true, coverSolo = true))
        assertEquals(0, SpreadLayout.nextSpreadPage(2, -1, 5, spread = true, coverSolo = true))
    }
}
