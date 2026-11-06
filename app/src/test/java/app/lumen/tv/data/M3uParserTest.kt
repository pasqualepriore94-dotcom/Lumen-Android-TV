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

    @Test fun readsTvgIdAndGuideUrl() {
        val list = M3uParser.parse("#EXTINF:-1 tvg-id=\"rai1.it\" group-title=\"TV\",Rai 1 HD\nhttp://h/live/1.ts".reader().buffered())
        assertEquals("rai1.it", list[0].tvgId)
        assertEquals("http://e/g.xml.gz", M3uParser.epgUrl("#EXTM3U url-tvg=\"http://e/g.xml.gz,http://e/b.xml\""))
        assertEquals("http://e/x.xml", M3uParser.epgUrl("#EXTM3U x-tvg-url=\"http://e/x.xml\""))
        assertNull(M3uParser.epgUrl("#EXTINF:-1,Canale"))
    }

    @Test fun parsesXmltvTimes() {
        assertEquals(Epg.parseTime("20261106170000 +0000"), Epg.parseTime("20261106180000 +0100"))
        assertEquals(Epg.parseTime("20261106170000"), Epg.parseTime("20261106180000 +0100"))
        assertEquals(1_793_984_400_000L, Epg.parseTime("20261106170000 +0000"))
        assertEquals(0L, Epg.parseTime("bad"))
    }

    @Test fun normalizesChannelNames() {
        assertEquals(Epg.normalize("Rai 1"), Epg.normalize("RAI 1 HD"))
        assertEquals(Epg.normalize("Rai 1"), Epg.normalize("IT: Rai Uno"))
        assertEquals(Epg.normalize("Rai 1"), Epg.normalize("Rai1.it"))
        assert(Epg.normalize("Rai 1") != Epg.normalize("Rai 1 +1"))
        assert(Epg.normalize("Italia 1") != Epg.normalize("Rai 1"))
    }

    @Test fun cinemaChannelsAreLive() {
        val list = M3uParser.parse("""
            #EXTINF:-1 tvg-id="skycinemauno.it" group-title="Cinema",Sky Cinema Uno HD
            http://h:8080/user/pass/1234
            #EXTINF:-1 group-title="Film",Rai Movie
            http://h/live/user/pass/55.ts
            #EXTINF:-1 group-title="Film Azione",Die Hard (1988)
            http://h:8080/movie/user/pass/77.mkv
            #EXTINF:-1 group-title="Serie TV",Dark S01E02
            http://h:8080/series/user/pass/88.mp4
            #EXTINF:-1 group-title="Canali Cinema",Cine Sony
            http://h/stream/cinesony.m3u8
        """.trimIndent().reader().buffered())
        assertEquals(listOf(MediaKind.LIVE, MediaKind.LIVE, MediaKind.MOVIE, MediaKind.SERIES, MediaKind.LIVE), list.map { it.kind })
    }

    @Test fun matchesCatalogTitlesTolerantly() {
        val list = M3uParser.parse("""
            #EXTINF:-1 group-title="Film",IT | Il Signore degli Anelli - La Compagnia dell'Anello (2001) FHD
            http://h/movie/u/p/1.mkv
            #EXTINF:-1 group-title="Film",The Batman 2022
            http://h/movie/u/p/2.mkv
            #EXTINF:-1 group-title="Film",Dune Parte Due [2024]
            http://h/movie/u/p/3.mkv
            #EXTINF:-1 group-title="Serie",Dark S01E02
            http://h/series/u/p/5.mp4
            #EXTINF:-1 group-title="Serie",Dark S01E01
            http://h/series/u/p/4.mp4
        """.trimIndent().reader().buffered())
        val index = PlaylistIndex(list)
        assertEquals("http://h/movie/u/p/1.mkv", index.find(listOf("Il Signore degli Anelli: La compagnia dell'anello"), "2001", MediaKind.MOVIE)?.url)
        assertEquals("http://h/movie/u/p/2.mkv", index.find(listOf("Batman"), "2022", MediaKind.MOVIE)?.url ?: index.find(listOf("The Batman"), "2022", MediaKind.MOVIE)?.url)
        assertEquals("http://h/movie/u/p/3.mkv", index.find(listOf("Dune - Parte due"), "2024", MediaKind.MOVIE)?.url)
        assertEquals("http://h/series/u/p/4.mp4", index.find(listOf("Dark"), "2017", MediaKind.SERIES)?.url)
        assertNull(index.find(listOf("Dune"), "2021", MediaKind.MOVIE))
    }
}
