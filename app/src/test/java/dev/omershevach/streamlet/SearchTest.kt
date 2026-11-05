package dev.omershevach.streamlet

import dev.omershevach.streamlet.search.Search
import dev.omershevach.streamlet.search.Source
import dev.omershevach.streamlet.search.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class SearchTest {

    @Test
    fun archiveResults() {
        val json = """
            {"responseHeader":{"status":0},"response":{"numFound":2,"start":0,"docs":[
              {"identifier":"night_of_the_living_dead","title":"Night of the Living Dead","year":"1968","item_size":1834567890,"downloads":123456},
              {"identifier":"some_cartoon","title":["A Cartoon"],"year":1941},
              {"title":"no identifier, skipped"}
            ]}}
        """.trimIndent()
        val r = Search.parseArchive(json)
        assertEquals(2, r.size)
        assertEquals("Night of the Living Dead", r[0].title)
        assertEquals(
            "https://archive.org/download/night_of_the_living_dead/night_of_the_living_dead_archive.torrent",
            r[0].link,
        )
        assertEquals(1834567890L, r[0].size)
        assertEquals("1968", r[0].year)
        assertEquals("A Cartoon", r[1].title)
        assertEquals("1941", r[1].year)
        assertNull(r[1].size)
    }

    @Test
    fun archiveUrlLimitsToPublicVideo() {
        val url = Search.archiveUrl("charlie chaplin")
        val q = URLDecoder.decode(url.substringAfter("q=").substringBefore('&'), "UTF-8")
        assertEquals("(charlie chaplin) AND mediatype:(movies) AND NOT access-restricted-item:(true)", q)
        assertTrue(url.startsWith("https://archive.org/advancedsearch.php?"))
        assertTrue("output=json" in url)
    }

    @Test
    fun torznabResults() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0" xmlns:torznab="http://torznab.com/schemas/2015/feed">
              <channel>
                <title>Indexer</title>
                <item>
                  <title>Open Movie 2008 1080p</title>
                  <link>http://192.168.1.10:9117/dl/abc.torrent</link>
                  <size>734003200</size>
                  <enclosure url="http://192.168.1.10:9117/dl/abc.torrent" length="734003200" type="application/x-bittorrent" />
                  <torznab:attr name="seeders" value="42" />
                  <torznab:attr name="magneturl" value="magnet:?xt=urn:btih:dd8255ecdc7ca55fb0bbf81323d87062db1f6d1c" />
                </item>
                <item>
                  <title>Only an enclosure</title>
                  <enclosure url="https://example.org/b.torrent" type="application/x-bittorrent" />
                  <torznab:attr name="size" value="1000" />
                </item>
                <item><title>No link at all</title></item>
              </channel>
            </rss>
        """.trimIndent()
        val r = Search.parseTorznab(xml.toByteArray())
        assertEquals(2, r.size)
        assertEquals("Open Movie 2008 1080p", r[0].title)
        assertTrue(r[0].link.startsWith("magnet:?xt=urn:btih:dd8255"))
        assertEquals(734003200L, r[0].size)
        assertEquals(42, r[0].seeders)
        assertEquals("https://example.org/b.torrent", r[1].link)
        assertEquals(1000L, r[1].size)
    }

    @Test(expected = java.io.IOException::class)
    fun torznabErrorIsReported() {
        Search.parseTorznab("""<?xml version="1.0"?><error code="100" description="Invalid API Key"/>""".toByteArray())
    }

    @Test
    fun torznabUrl() {
        val s = Source.new("Home", SourceType.TORZNAB, "http://nas:9117/api/v2.0/indexers/all/results/torznab", "k e y")
        assertEquals(
            "http://nas:9117/api/v2.0/indexers/all/results/torznab?t=search&cat=2000,5000&q=big+buck&apikey=k+e+y",
            Search.torznabUrl(s, "big buck"),
        )
    }

    @Test
    fun jsonResultsInManyShapes() {
        val arr = """[{"name":"A","magnet":"magnet:?xt=urn:btih:aaaa","size":"12345","seeders":3},
                      {"title":"B","info_hash":"0123456789abcdef0123456789abcdef01234567","bytes":99},
                      {"title":"C","url":"https://x.org/c.torrent","seeds":"7"},
                      {"title":"D","link":"not a link"}]"""
        val r = Search.parseJson(arr)
        assertEquals(listOf("A", "B", "C"), r.map { it.title })
        assertEquals(12345L, r[0].size)
        assertEquals(3, r[0].seeders)
        assertTrue(r[1].link.startsWith("magnet:?xt=urn:btih:0123456789abcdef"))
        assertEquals(99L, r[1].size)
        assertEquals(7, r[2].seeders)

        val wrapped = Search.parseJson("""{"results":[{"title":"E","magnetUrl":"magnet:?xt=urn:btih:eeee"}]}""")
        assertEquals("E", wrapped.single().title)
        assertTrue(Search.parseJson("""{"nothing":true}""").isEmpty())
    }

    @Test
    fun jsonUrl() {
        val withSlot = Source.new("J", SourceType.JSON, "https://api.example.com/find?term={query}&limit=20", "")
        assertEquals("https://api.example.com/find?term=night+of&limit=20", Search.jsonUrl(withSlot, "night of"))
        val plain = Source.new("J", SourceType.JSON, "https://api.example.com/search", "")
        assertEquals("https://api.example.com/search?q=x", Search.jsonUrl(plain, "x"))
    }

    @Test
    fun localAddresses() {
        listOf(
            "http://192.168.1.10:9117/api", "http://10.0.0.5/x", "http://172.20.1.1", "http://nas:9696/1/api",
            "http://jackett.local:9117", "http://[fd00::1]:9117/", "http://169.254.3.3",
        ).forEach { assertTrue(it, Source.isLocal(it)) }
        listOf(
            "https://archive.org", "http://127.0.0.1:9117", "http://localhost:9117", "https://172.32.0.1",
            "https://fcbarcelona.com", "https://8.8.8.8",
        ).forEach { assertFalse(it, Source.isLocal(it)) }
    }
}
