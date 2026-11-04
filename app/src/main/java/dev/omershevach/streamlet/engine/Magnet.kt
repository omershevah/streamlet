package dev.omershevach.streamlet.engine

import java.net.URLDecoder
import java.net.URLEncoder

object Magnet {
    private val LINK = Regex("""magnet:\?\S+""", RegexOption.IGNORE_CASE)
    private val BARE_HASH = Regex("""^(?:[0-9a-fA-F]{40}|[A-Za-z2-7]{32})$""")

    // A few long-lived public trackers. They find peers much faster than DHT alone.
    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce",
        "udp://open.stealth.si:80/announce",
        "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce",
    )

    /** Accepts a magnet link (also inside surrounding text) or a bare info hash. Returns null if neither. */
    fun normalize(raw: String): String? {
        val text = raw.trim()
        val link = LINK.find(text)?.value?.takeIf { it.contains("xt=urn:bt", ignoreCase = true) }
            ?: if (BARE_HASH.matches(text)) "magnet:?xt=urn:btih:$text" else return null
        val missing = TRACKERS.filterNot { link.contains(URLEncoder.encode(it, "UTF-8")) || link.contains(it) }
        return link + missing.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }
    }

    /** The "dn" (display name) parameter, if the link has one. */
    fun displayName(link: String): String? = link.substringAfter('?', "")
        .split('&')
        .firstOrNull { it.startsWith("dn=", ignoreCase = true) }
        ?.let { runCatching { URLDecoder.decode(it.substring(3), "UTF-8") }.getOrNull() }
        ?.takeIf { it.isNotBlank() }
}
