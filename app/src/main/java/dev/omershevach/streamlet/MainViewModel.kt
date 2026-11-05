package dev.omershevach.streamlet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.omershevach.streamlet.engine.Episode
import dev.omershevach.streamlet.engine.Episodes
import dev.omershevach.streamlet.engine.Magnet
import dev.omershevach.streamlet.engine.Season
import dev.omershevach.streamlet.engine.StreamStats
import dev.omershevach.streamlet.engine.TorrentEngine
import dev.omershevach.streamlet.engine.TorrentFile
import dev.omershevach.streamlet.search.Fetched
import dev.omershevach.streamlet.search.Search
import dev.omershevach.streamlet.search.SearchResult
import dev.omershevach.streamlet.search.Source
import dev.omershevach.streamlet.search.SourceStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface Screen {
    data class Home(val error: String? = null) : Screen
    data object Sources : Screen
    data class Resolving(val name: String?) : Screen
    data class Picker(val title: String, val seasons: List<Season>) : Screen
    data class Playing(val title: String, val episode: Episode, val url: String, val next: Episode?) : Screen
}

/** The results of one source for the current query. */
data class SourceResults(
    val source: Source,
    val loading: Boolean = true,
    val results: List<SearchResult> = emptyList(),
    val error: String? = null,
)

data class SearchState(val query: String = "", val groups: List<SourceResults> = emptyList())

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _screen = MutableStateFlow<Screen>(Screen.Home())
    val screen = _screen.asStateFlow()

    private val _stats = MutableStateFlow<StreamStats?>(null)
    val stats = _stats.asStateFlow()

    private val store = SourceStore(app)
    private val _sources = MutableStateFlow(store.load())
    val sources = _sources.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search = _search.asStateFlow()

    private var title = ""
    private var seasons: List<Season> = emptyList()
    private val episodes get() = seasons.flatMap { it.episodes }
    private var job: Job? = null
    private var searchJob: Job? = null
    private var statsJob: Job? = null

    // Search -----------------------------------------------------------------------------------

    /** Searches every enabled source at once; each one fills in its own group as it answers. */
    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        searchJob?.cancel()
        val enabled = _sources.value.filter { it.enabled }
        _search.value = SearchState(q, enabled.map { SourceResults(it) })
        searchJob = viewModelScope.launch {
            enabled.forEach { source ->
                launch(Dispatchers.IO) {
                    val done = try {
                        SourceResults(source, loading = false, results = Search.run(source, q))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SourceResults(source, loading = false, error = describe(e))
                    }
                    _search.update { s -> s.copy(groups = s.groups.map { if (it.source.id == source.id) done else it }) }
                }
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchState()
    }

    fun openResult(result: SearchResult) {
        if (result.link.startsWith("magnet:", ignoreCase = true)) {
            open(result.link)
            return
        }
        load(result.title) {
            when (val fetched = withContext(Dispatchers.IO) { Search.fetch(result.link) }) {
                is Fetched.Magnet -> {
                    val magnet = Magnet.normalize(fetched.uri) ?: error("The source returned a broken magnet link")
                    TorrentEngine.resolve(magnet)
                }
                is Fetched.Torrent -> TorrentEngine.resolve(fetched.bytes)
            }
        }
    }

    // Sources ----------------------------------------------------------------------------------

    fun showSources() {
        _screen.value = Screen.Sources
    }

    fun saveSource(source: Source) = updateSources { list ->
        if (list.any { it.id == source.id }) list.map { if (it.id == source.id) source else it } else list + source
    }

    /** The built-in Internet Archive can only be switched off, not deleted. */
    fun deleteSource(source: Source) = updateSources { list -> list.filterNot { it.id == source.id && !it.builtIn } }

    fun setEnabled(source: Source, enabled: Boolean) = saveSource(source.copy(enabled = enabled))

    private fun updateSources(change: (List<Source>) -> List<Source>) {
        val next = change(_sources.value)
        _sources.value = next
        store.save(next)
    }

    // Opening and playing ----------------------------------------------------------------------

    fun open(raw: String) {
        val magnet = Magnet.normalize(raw)
        if (magnet == null) {
            _screen.value = Screen.Home("That isn't a magnet link")
            return
        }
        load(Magnet.displayName(magnet)) { TorrentEngine.resolve(magnet) }
    }

    private fun load(name: String?, resolve: suspend () -> List<TorrentFile>) {
        job?.cancel()
        stopPlayback()
        _screen.value = Screen.Resolving(name)
        job = viewModelScope.launch {
            try {
                val files = withTimeout(RESOLVE_TIMEOUT_MS) { resolve() }
                seasons = Episodes.parse(files)
                title = TorrentEngine.title ?: name ?: "Torrent"
                when (episodes.size) {
                    0 -> fail("There are no video files in this torrent")
                    1 -> startStream(episodes.single())
                    else -> _screen.value = Screen.Picker(title, seasons)
                }
            } catch (_: TimeoutCancellationException) {
                fail("No peers found for this link. It may have no seeders right now.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(describe(e))
            }
        }
    }

    fun play(episode: Episode) {
        job?.cancel()
        job = viewModelScope.launch {
            try {
                startStream(episode)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Couldn't start the stream")
            }
        }
    }

    private suspend fun startStream(episode: Episode) {
        val stream = withContext(Dispatchers.IO) { TorrentEngine.play(episode.fileIndex) }
        val all = episodes
        val next = all.getOrNull(all.indexOf(episode) + 1)
        StreamService.start(getApplication(), episode.headline)
        _screen.value = Screen.Playing(title, episode, TorrentEngine.url(stream), next)
        watchStats()
    }

    /** Back navigation: playing → picker → home, and sources → home. */
    fun back() {
        when (_screen.value) {
            is Screen.Playing -> {
                stopPlayback()
                if (episodes.size > 1) _screen.value = Screen.Picker(title, seasons) else reset()
            }
            is Screen.Resolving, is Screen.Picker -> reset()
            is Screen.Sources -> _screen.value = Screen.Home()
            is Screen.Home -> Unit
        }
    }

    fun clearError() {
        if ((_screen.value as? Screen.Home)?.error != null) _screen.value = Screen.Home()
    }

    private fun watchStats() {
        statsJob?.cancel()
        statsJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                val s = TorrentEngine.stats()
                // Stopped from the notification: leave the playing screen.
                if (s == null && TorrentEngine.stream == null) {
                    withContext(Dispatchers.Main) { if (_screen.value is Screen.Playing) back() }
                    break
                }
                _stats.value = s
                delay(1000)
            }
        }
    }

    private fun stopPlayback() {
        statsJob?.cancel()
        _stats.value = null
        TorrentEngine.stopStream()
        StreamService.stop(getApplication())
    }

    private fun reset() {
        job?.cancel()
        stopPlayback()
        seasons = emptyList()
        _screen.value = Screen.Home()
        viewModelScope.launch(Dispatchers.IO) { TorrentEngine.release() }
    }

    private suspend fun fail(message: String) {
        withContext(Dispatchers.IO) { TorrentEngine.release() }
        _screen.value = Screen.Home(message)
    }

    private fun describe(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Couldn't find the server. Check the address and your connection."
        is java.net.SocketTimeoutException -> "The server didn't answer in time"
        is java.net.ConnectException -> "Couldn't connect to the server"
        is javax.net.ssl.SSLException -> "Secure connection failed"
        is org.json.JSONException, is org.xml.sax.SAXException -> "The server's answer wasn't in the expected format"
        else -> e.message ?: "Something went wrong"
    }

    override fun onCleared() {
        // Keep going if something is still streaming to the player; the notification can stop it.
        if (TorrentEngine.stream == null) Thread { TorrentEngine.shutdown() }.start()
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 120_000L
    }
}
