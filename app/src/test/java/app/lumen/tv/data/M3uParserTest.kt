package app.lumen.tv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {
    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-logo="http://x/a.png" group-title="Film, azione",IT | Inception (2010) [FHD]
        http://host/movie/u/p/1.mkv
        #EXTINF:-1 group-title="Serie TV",Dark S01E02 HD
        http://host/series/u/p/2.mp4
        #EXTINF:-1 group-title="News",Canale 1
        http://host/live/u/p/3.ts
        #EXTINF:-1,Senza gruppo
        https://host/stream.m3u8
        non-un-indirizzo
    """.trimIndent()

    private val items = M3uParser.parse(sample.reader().buffered())

    @Test fun readsEntriesAndIgnoresInvalidLines() = assertEquals(4, items.size)

    @Test fun commaInsideQuotesIsNotTheTitleSeparator() {
        assertEquals("IT | Inception (2010) [FHD]", items[0].name)
        assertEquals("Film, azione", items[0].group)
        assertEquals("http://x/a.png", items[0].logo)
    }

    @Test fun classifiesKinds() {
        assertEquals(listOf(MediaKind.MOVIE, MediaKind.SERIES, MediaKind.LIVE, MediaKind.LIVE), items.map { it.kind })
    }

    @Test fun missingGroupFallsBack() {
        assertEquals("Altro", items[3].group)
        assertNull(items[3].logo)
    }

    @Test fun respectsLimit() = assertEquals(2, M3uParser.parse(sample.reader().buffered(), limit = 2).size)

    @Test fun cleansTitles() {
        assertEquals("Inception" to "2010", TitleCleaner.clean("IT | Inception (2010) [FHD]"))
        assertEquals("Dark" to null, TitleCleaner.clean("IT - Dark S01E02 HD"))
    }

    @Test fun findsCatalogTitleInPlaylist() {
        val found = TitleCleaner.findInPlaylist(listOf("Inception"), "2010", MediaKind.MOVIE, items)
        assertEquals("http://host/movie/u/p/1.mkv", found?.url)
        assertNull(TitleCleaner.findInPlaylist(listOf("Inception"), "1999", MediaKind.MOVIE, items))
        assertNull(TitleCleaner.findInPlaylist(listOf("Inception"), null, MediaKind.SERIES, items))
    }
}
