package dev.omershevach.streamlet.search

import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import javax.xml.parsers.DocumentBuilderFactory

data class SearchResult(
    val title: String,
    /** A magnet link, or an http(s) link to a .torrent file (which may redirect to a magnet). */
    val link: String,
    val size: Long? = null,
    val seeders: Int? = null,
    val year: String? = null,
)

/** What a result link turned out to be. */
sealed interface Fetched {
    data class Magnet(val uri: String) : Fetched
    class Torrent(val bytes: ByteArray) : Fetched
}

object Search {
    /** Runs [query] on one source. Blocking; call from a background thread. */
    fun run(source: Source, query: String): List<SearchResult> = when (source.type) {
        SourceType.ARCHIVE -> parseArchive(Http.get(archiveUrl(query)).decodeToString())
        SourceType.TORZNAB -> parseTorznab(Http.get(torznabUrl(source, query)))
        SourceType.JSON -> parseJson(Http.get(jsonUrl(source, query)).decodeToString())
    }

    /** Resolves a result link to a magnet or to the bytes of a .torrent file. Blocking. */
    fun fetch(link: String): Fetched =
        if (link.startsWith("magnet:", ignoreCase = true)) Fetched.Magnet(link) else Http.torrent(link)

    // Internet Archive ------------------------------------------------------------------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun archiveUrl(query: String): String {
        // Video items only, and not ones that are restricted (those have no public torrent).
        val q = "($query) AND mediatype:(movies) AND NOT access-restricted-item:(true)"
        val fields = listOf("identifier", "title", "year", "item_size", "downloads")
        return "https://archive.org/advancedsearch.php?q=${enc(q)}" +
            fields.joinToString("") { "&fl%5B%5D=$it" } +
            "&sort%5B%5D=downloads+desc&rows=40&page=1&output=json"
    }

    fun archiveTorrent(identifier: String) =
        "https://archive.org/download/${enc(identifier)}/${enc(identifier)}_archive.torrent"

    fun parseArchive(json: String): List<SearchResult> {
        val docs = JSONObject(json).optJSONObject("response")?.optJSONArray("docs") ?: return emptyList()
        return objects(docs).mapNotNull { d ->
            val id = d.text("identifier") ?: return@mapNotNull null
            SearchResult(
                title = d.text("title") ?: id,
                link = archiveTorrent(id),
                size = d.number("item_size"),
                year = d.text("year")?.take(4),
            )
        }
    }

    // Torznab ---------------------------------------------------------------------------------

    fun torznabUrl(source: Source, query: String): String {
        val base = source.url.trim()
        val sep = if ('?' in base) '&' else '?'
        // 2000 = Movies, 5000 = TV in the Torznab category list.
        val key = if (source.apiKey.isNotBlank()) "&apikey=${enc(source.apiKey)}" else ""
        return "$base${sep}t=search&cat=2000,5000&q=${enc(query)}$key"
    }

    fun parseTorznab(xml: ByteArray): List<SearchResult> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            // No external entities or DTDs from a server response.
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(xml.inputStream())
        val root = doc.documentElement
        if (root.tagName == "error") {
            throw IOException(root.getAttribute("description").ifBlank { "Error ${root.getAttribute("code")}" })
        }
        val items = doc.getElementsByTagName("item")
        return (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as Element
            val attrs = buildMap {
                val nodes = item.getElementsByTagName("torznab:attr")
                for (j in 0 until nodes.length) {
                    val a = nodes.item(j) as Element
                    put(a.getAttribute("name"), a.getAttribute("value"))
                }
            }
            val title = item.child("title") ?: return@mapNotNull null
            val enclosure = (item.getElementsByTagName("enclosure").item(0) as Element?)?.getAttribute("url")
            val link = attrs["magneturl"]?.takeIf { it.isNotBlank() }
                ?: item.child("link")?.takeIf { it.isNotBlank() }
                ?: enclosure?.takeIf { it.isNotBlank() }
                ?: attrs["infohash"]?.let { magnetFor(it, title) }
                ?: return@mapNotNull null
            SearchResult(
                title = title,
                link = link,
                size = (item.child("size") ?: attrs["size"])?.toLongOrNull(),
                seeders = attrs["seeders"]?.toIntOrNull(),
                year = attrs["year"],
            )
        }
    }

    private fun Element.child(tag: String): String? {
        val nodes = getElementsByTagName(tag)
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.parentNode == this) return n.textContent?.trim()
        }
        return null
    }

    // JSON ------------------------------------------------------------------------------------

    fun jsonUrl(source: Source, query: String): String {
        val url = source.url.trim()
        return if ("{query}" in url) {
            url.replace("{query}", enc(query))
        } else {
            url + (if ('?' in url) '&' else '?') + "q=" + enc(query)
        }
    }

    /**
     * Reads a list of results from any reasonably shaped JSON: an array, or an object holding one
     * under "results", "items", "data" or "torrents". Field names are matched loosely.
     */
    fun parseJson(text: String): List<SearchResult> {
        val trimmed = text.trim()
        val array = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val o = JSONObject(trimmed)
            listOf("results", "items", "data", "torrents").firstNotNullOfOrNull { o.optJSONArray(it) }
                ?: return emptyList()
        }
        return objects(array).mapNotNull { o ->
            val title = o.text("title", "name") ?: return@mapNotNull null
            val link = o.text("magnet", "magnetUrl", "magnet_url", "magnetUri", "link", "url", "torrent", "download")
                ?.takeIf { it.startsWith("magnet:", true) || it.startsWith("http", true) }
                ?: o.text("infoHash", "info_hash", "infohash", "hash")?.let { magnetFor(it, title) }
                ?: return@mapNotNull null
            SearchResult(
                title = title,
                link = link,
                size = o.number("size", "bytes", "size_bytes"),
                seeders = o.number("seeders", "seeds", "seed")?.toInt(),
                year = o.text("year")?.take(4),
            )
        }
    }

    // Helpers ---------------------------------------------------------------------------------

    private fun magnetFor(hash: String, title: String): String? =
        hash.trim().takeIf { it.matches(Regex("[0-9a-fA-F]{40}|[A-Za-z2-7]{32}")) }
            ?.let { "magnet:?xt=urn:btih:$it&dn=${enc(title)}" }

    private fun objects(array: JSONArray) = (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    /** First present field as text. Arrays (the Archive sometimes returns those) give their first element. */
    private fun JSONObject.text(vararg names: String): String? = names.firstNotNullOfOrNull { n ->
        when (val v = opt(n)) {
            null, JSONObject.NULL -> null
            is JSONArray -> v.optString(0).takeIf { it.isNotBlank() }
            else -> v.toString().trim().takeIf { it.isNotEmpty() }
        }
    }

    private fun JSONObject.number(vararg names: String): Long? = names.firstNotNullOfOrNull { n ->
        when (val v = opt(n)) {
            is Number -> v.toLong()
            is String -> v.trim().toLongOrNull() ?: v.toDoubleOrNull()?.toLong()
            else -> null
        }
    }
}

/** Minimal HTTP for search requests and .torrent downloads. */
internal object Http {
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val AGENT = "Streamlet (Android)"

    fun get(url: String): ByteArray {
        val c = open(url)
        try {
            val code = c.responseCode
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.use { read(it) }
        } finally {
            c.disconnect()
        }
    }

    /** Downloads a .torrent, following redirects by hand because some lead to a magnet: link. */
    fun torrent(url: String): Fetched {
        var current = url
        repeat(6) {
            val c = open(current, followRedirects = false)
            try {
                val code = c.responseCode
                if (code in 300..399) {
                    val location = c.getHeaderField("Location") ?: throw IOException("Redirect without a location")
                    if (location.startsWith("magnet:", ignoreCase = true)) return Fetched.Magnet(location)
                    current = URI(current).resolve(location).toString()
                    return@repeat
                }
                if (code !in 200..299) throw IOException("HTTP $code")
                val bytes = c.inputStream.use { read(it) }
                // Some servers answer with the magnet link as plain text.
                val text = bytes.take(8).toByteArray().decodeToString()
                if (text.startsWith("magnet:", ignoreCase = true)) return Fetched.Magnet(bytes.decodeToString().trim())
                if (bytes.isEmpty() || bytes[0] != 'd'.code.toByte()) throw IOException("That link isn't a torrent file")
                return Fetched.Torrent(bytes)
            } finally {
                c.disconnect()
            }
        }
        throw IOException("Too many redirects")
    }

    private fun open(url: String, followRedirects: Boolean = true): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = followRedirects
            setRequestProperty("User-Agent", AGENT)
        }

    private fun read(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            if (out.size() > MAX_BYTES) throw IOException("Response too large")
        }
        return out.toByteArray()
    }
}
