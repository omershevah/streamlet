package dev.omershevach.streamlet.engine

import java.util.Locale

/** One file in the torrent, as the parser sees it. [path] includes folders, separated by '/'. */
data class TorrentFile(val index: Int, val path: String, val size: Long)

data class Episode(
    val fileIndex: Int,
    val season: Int?,
    val number: Int?,
    val title: String?,
    val fileName: String,
    val size: Long,
) {
    /** "S01E05", "E05" or "" when the file isn't numbered. */
    val code: String
        get() = when {
            number == null -> ""
            season == null -> "E%02d".format(Locale.ROOT, number)
            else -> "S%02dE%02d".format(Locale.ROOT, season, number)
        }

    val headline: String get() = title ?: number?.let { "Episode $it" } ?: fileName
}

data class Season(val number: Int?, val episodes: List<Episode>) {
    val label: String get() = number?.let { "Season $it" } ?: "Other"
}

object Episodes {
    private val VIDEO = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "wmv", "flv", "mpg", "mpeg", "ogv", "3gp",
    )

    private val SXE = Regex("""(?i)(?<![a-z0-9])s(\d{1,2})[ ._-]*e(\d{1,3})(?!\d)""")
    private val NXN = Regex("""(?i)(?<![a-z0-9])(\d{1,2})x(\d{2,3})(?![0-9])""")
    private val WORDS = Regex("""(?i)season[ ._-]*(\d{1,2}).*?episode[ ._-]*(\d{1,3})""")
    private val SEASON_DIR = Regex("""(?i)(?:^|[^a-z0-9])(?:season|series|s)[ ._-]*(\d{1,2})(?![0-9])""")
    private val EP_ONLY = Regex("""(?i)(?:(?<![a-z0-9])(?:e|ep|episode)[ ._-]*|\s-\s|^)(\d{1,3})(?![0-9p])""")
    private val SAMPLE = Regex("""(?i)(?<![a-z])sample(?![a-z])""")
    private val JUNK = Regex(
        """(?i)[ ._\-\[(](?:\d{3,4}p|2160|4k|uhd|web(?:-?dl|rip)?|blu-?ray|bdrip|brrip|hdtv|hdrip|dvdrip|""" +
            """x26[45]|h\.?26[45]|hevc|avc|aac\d?|ac3|dts|ddp?\d|atmos|10bit|hdr\d*|dv|proper|repack|""" +
            """internal|amzn|nf|dsnp|hmax|atvp)(?![a-z]).*$""",
    )
    private val BRACKETS = Regex("""\[[^]]*]|\([^)]*\)""")
    private val SEPARATORS = Regex("""[._]+""")

    fun isVideo(path: String) = path.substringAfterLast('.', "").lowercase() in VIDEO

    /** Groups the video files of a torrent into seasons of numbered episodes. */
    fun parse(files: List<TorrentFile>): List<Season> {
        val videos = files.filter { isVideo(it.path) }
        val wanted = videos.filterNot { SAMPLE.containsMatchIn(it.path.substringAfterLast('/')) }.ifEmpty { videos }
        return wanted.map(::toEpisode)
            .groupBy { it.season }
            .map { (season, eps) ->
                Season(season, eps.sortedWith(compareBy<Episode, Int?>(nullsLast()) { it.number }.thenBy { it.fileName }))
            }
            .sortedWith(compareBy(nullsLast()) { it.number })
    }

    private fun toEpisode(file: TorrentFile): Episode {
        val name = file.path.substringAfterLast('/')
        val base = name.substringBeforeLast('.')
        val dirs = file.path.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }
        val dirSeason = dirs.asReversed().firstNotNullOfOrNull { SEASON_DIR.find(it)?.groupValues?.get(1)?.toInt() }

        val full = SXE.find(base) ?: NXN.find(base) ?: WORDS.find(base)
        if (full != null) {
            return Episode(
                fileIndex = file.index,
                season = full.groupValues[1].toInt(),
                number = full.groupValues[2].toInt(),
                title = clean(base.substring(full.range.last + 1)),
                fileName = name,
                size = file.size,
            )
        }

        val ep = EP_ONLY.find(base)
        return Episode(
            fileIndex = file.index,
            season = dirSeason ?: if (ep != null) 1 else null,
            number = ep?.groupValues?.get(1)?.toInt(),
            title = clean(if (ep != null) base.substring(ep.range.last + 1) else base),
            fileName = name,
            size = file.size,
        )
    }

    private fun clean(text: String): String? = text
        .replace(JUNK, "")
        .replace(BRACKETS, " ")
        .replace(SEPARATORS, " ")
        .replace(Regex("""\s+"""), " ")
        .trim(' ', '-')
        .takeIf { it.length > 1 }
}

fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = "KMGT"
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.ROOT, if (value < 10) "%.1f %sB" else "%.0f %sB", value, units[unit])
}
