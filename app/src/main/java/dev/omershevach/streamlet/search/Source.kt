package dev.omershevach.streamlet.search

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Where search results come from. */
enum class SourceType(val label: String) {
    /** The Internet Archive's own search API. Built in. */
    ARCHIVE("Built in"),

    /** The Torznab API (RSS with extra attributes), served by indexer managers such as Jackett or Prowlarr. */
    TORZNAB("Torznab"),

    /** Any URL that returns a JSON list of titles with magnet links, torrent links or info hashes. */
    JSON("JSON"),
}

data class Source(
    val id: String,
    val name: String,
    val type: SourceType,
    val url: String = "",
    val apiKey: String = "",
    val enabled: Boolean = true,
) {
    val builtIn: Boolean get() = type == SourceType.ARCHIVE

    /** Short description shown under the name, like "Torznab · 192.168.1.5:9117". */
    val summary: String
        get() = if (builtIn) "Built in · archive.org" else "${type.label} · ${host(url) ?: url}"

    companion object {
        val INTERNET_ARCHIVE = Source(id = "archive", name = "Internet Archive", type = SourceType.ARCHIVE)

        fun new(name: String, type: SourceType, url: String, apiKey: String) =
            Source(UUID.randomUUID().toString(), name.trim(), type, url.trim(), apiKey.trim())

        fun host(url: String): String? = runCatching { java.net.URI(url.trim()).authority }.getOrNull()

        /**
         * True for addresses on the local network (a NAS or a PC at home). Android 17 asks the user
         * before an app can reach those. Loopback (this device) doesn't count.
         */
        fun isLocal(url: String): Boolean {
            val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.trim('[', ']')?.lowercase()
                ?: return false
            if (host == "localhost" || host.startsWith("127.") || host == "::1") return false
            if (host.endsWith(".local") || host.endsWith(".lan") || host.endsWith(".home.arpa") || '.' !in host && ':' !in host) {
                return true
            }
            val v4 = host.split('.').mapNotNull { it.toIntOrNull() }.takeIf { it.size == 4 }
            if (v4 != null) {
                val (a, b) = v4
                return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
            }
            return ':' in host && (host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80"))
        }
    }
}

/** Saves the source list in shared preferences. The Internet Archive is always present (it can be switched off). */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("sources", Context.MODE_PRIVATE)

    fun load(): List<Source> {
        val saved = prefs.getString(KEY, null)?.let { runCatching { decode(it) }.getOrNull() }.orEmpty()
        return if (saved.none { it.builtIn }) listOf(Source.INTERNET_ARCHIVE) + saved else saved
    }

    fun save(sources: List<Source>) {
        prefs.edit().putString(KEY, encode(sources)).apply()
    }

    private companion object {
        const val KEY = "list"

        fun encode(sources: List<Source>): String = JSONArray().apply {
            sources.forEach { s ->
                put(
                    JSONObject()
                        .put("id", s.id).put("name", s.name).put("type", s.type.name)
                        .put("url", s.url).put("apiKey", s.apiKey).put("enabled", s.enabled),
                )
            }
        }.toString()

        fun decode(text: String): List<Source> {
            val array = JSONArray(text)
            return List(array.length()) { i ->
                val o = array.getJSONObject(i)
                Source(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    type = SourceType.valueOf(o.getString("type")),
                    url = o.optString("url"),
                    apiKey = o.optString("apiKey"),
                    enabled = o.optBoolean("enabled", true),
                )
            }
        }
    }
}
