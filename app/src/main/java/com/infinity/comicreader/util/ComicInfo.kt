package com.infinity.comicreader.util

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader

/**
 * Lightweight parser for standard `ComicInfo.xml` metadata embedded in CBZ, CBR,
 * and CB7 archives. Uses Android/JVM's built-in [XmlPullParser] with external
 * entities disabled to prevent XXE vulnerabilities.
 */
data class ComicInfo(
    val title: String = "",
    val series: String = "",
    val number: String = "",
    val volume: String = "",
    val summary: String = "",
    val writer: String = "",
    val penciller: String = "",
    val genre: String = "",
    val languageISO: String = "",
    val pageCount: Int = -1,
    val year: Int = -1,
    val month: Int = -1,
    /** True when `<Manga>` is `Yes` or `YesAndRightToLeft`. */
    val isMangaRtl: Boolean = false
) {
    val isEmpty: Boolean
        get() = title.isBlank() && series.isBlank() && number.isBlank() &&
            summary.isBlank() && writer.isBlank() && penciller.isBlank() &&
            genre.isBlank() && pageCount <= 0

    companion object {
        private const val MAX_XML_BYTES = 256 * 1024

        fun isComicInfoEntry(entryName: String): Boolean {
            val normalized = entryName.replace('\\', '/').substringAfterLast('/')
            return normalized.equals("ComicInfo.xml", ignoreCase = true)
        }

        fun parse(input: InputStream): ComicInfo? = runCatching {
            val bytes = input.readNBytesCapped(MAX_XML_BYTES)
            parseXml(bytes.toString(Charsets.UTF_8))
        }.getOrNull()

        fun parseXml(xml: String): ComicInfo? = runCatching {
            val factory = XmlPullParserFactory.newInstance().apply {
                isNamespaceAware = false
            }
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var title = ""
            var series = ""
            var number = ""
            var volume = ""
            var summary = ""
            var writer = ""
            var penciller = ""
            var genre = ""
            var languageISO = ""
            var pageCount = -1
            var year = -1
            var month = -1
            var isMangaRtl = false

            var currentTag: String? = null
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> currentTag = parser.name
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim().orEmpty()
                        if (text.isNotEmpty() && currentTag != null) {
                            when (currentTag.lowercase()) {
                                "title" -> title = text
                                "series" -> series = text
                                "number" -> number = text
                                "volume" -> volume = text
                                "summary" -> summary = text
                                "writer" -> writer = text
                                "penciller" -> penciller = text
                                "genre" -> genre = text
                                "languageiso" -> languageISO = text
                                "pagecount" -> pageCount = text.toIntOrNull() ?: -1
                                "year" -> year = text.toIntOrNull() ?: -1
                                "month" -> month = text.toIntOrNull() ?: -1
                                "manga" -> isMangaRtl = text.equals("Yes", ignoreCase = true) ||
                                    text.equals("YesAndRightToLeft", ignoreCase = true)
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> currentTag = null
                }
                event = parser.next()
            }
            ComicInfo(
                title = title,
                series = series,
                number = number,
                volume = volume,
                summary = summary,
                writer = writer,
                penciller = penciller,
                genre = genre,
                languageISO = languageISO,
                pageCount = pageCount,
                year = year,
                month = month,
                isMangaRtl = isMangaRtl
            ).takeUnless { it.isEmpty && !isMangaRtl }
        }.getOrNull()

        private fun InputStream.readNBytesCapped(maxBytes: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = read(buf)
                if (n < 0) break
                if (n == 0) {
                    val b = read()
                    if (b < 0) break
                    if (++total > maxBytes) break
                    out.write(b)
                    continue
                }
                if (total + n > maxBytes) {
                    out.write(buf, 0, maxBytes - total)
                    break
                }
                total += n
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        }
    }
}
