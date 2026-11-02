package com.infinity.comicreader.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagLinkTest {
    @Test fun openPathRoundTripsPlainAndAwkwardNames() {
        for (path in listOf("Comics/One Piece/Ch 001.cbz", "a&b=c+d#e?f%g", "漫画/第1話", "বই/অধ্যায়")) {
            assertEquals(TagLink.Action.OpenPath(path), TagLink.parse(TagLink.openPath(path)))
        }
    }

    @Test fun nonAsciiNamesStayShort() {
        // Written raw (UTF-8), not percent-encoded to 9 bytes per character.
        val link = TagLink.openPath("漫画/第1話")
        assertEquals("comicreader://open?p=漫画/第1話", link)
    }

    @Test fun continueLink() {
        assertEquals(TagLink.Action.Continue, TagLink.parse(TagLink.continueReading()))
        assertEquals(TagLink.Action.Continue, TagLink.parse("COMICREADER://continue/"))
    }

    @Test fun profileRoundTrip() {
        val link = TagLink.profile("longstrip", "rtl", "width", true, false, 35)
        assertEquals(TagLink.Action.Profile("longstrip", "rtl", "width", true, false, 35), TagLink.parse(link))
    }

    @Test fun profileIgnoresUnknownValuesAndClampsBrightness() {
        val parsed = TagLink.parse("comicreader://profile?m=evil&d=rtl&b=500&x=1")
        assertEquals(TagLink.Action.Profile(null, "rtl", null, null, null, 100), parsed)
        assertNull(TagLink.parse("comicreader://profile?m=evil&q=1"))
    }

    @Test fun rejectsForeignOrMalformedLinks() {
        assertNull(TagLink.parse(null))
        assertNull(TagLink.parse("https://example.com/open?p=x"))
        assertNull(TagLink.parse("comicreader://open"))
        assertNull(TagLink.parse("comicreader://open?p="))
        assertNull(TagLink.parse("comicreader://launch-missiles"))
        assertNull(TagLink.parse("comicreader://open?p=%zz"))
    }
}
