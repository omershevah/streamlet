package dev.omershevach.streamlet

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.omershevach.streamlet.engine.Episode
import dev.omershevach.streamlet.engine.Episodes
import dev.omershevach.streamlet.engine.Magnet
import dev.omershevach.streamlet.engine.Season
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class Tab { Search, Tasks, Sources }

sealed interface Screen {
    /** The three tabs of the bottom bar. [error] shows on the search tab. */
    data class Main(val tab: Tab, val error: String? = null) : Screen
    data class Resolving(val name: String?) : Screen

    /** A loaded torrent's episodes. [confirm] is the one waiting for Start, if any. */
    data class Picker(val title: String, val seasons: List<Season>, val confirm: Episode? = null) : Screen
    data class TaskDetail(val id: Int) : Screen
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
    private val _screen = MutableStateFlow<Screen>(Screen.Main(Tab.Search))
    val screen = _screen.asStateFlow()

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

    /** Each loaded torrent's episodes in order, to offer "next episode" on its tasks. */
    private val episodesByHash = mutableMapOf<String, List<Episode>>()

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

    fun selectTab(tab: Tab) {
        _screen.value = Screen.Main(tab)
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
            _screen.value = Screen.Main(Tab.Search, "That isn't a magnet link")
            return
        }
        load(Magnet.displayName(magnet)) { TorrentEngine.resolve(magnet) }
    }

    private fun load(name: String?, resolve: suspend () -> List<TorrentFile>) {
        job?.cancel()
        _screen.value = Screen.Resolving(name)
        job = viewModelScope.launch {
            try {
                val files = withTimeout(RESOLVE_TIMEOUT_MS) { resolve() }
                seasons = Episodes.parse(files)
                title = TorrentEngine.title ?: name ?: "Torrent"
                TorrentEngine.browsingHash?.let { episodesByHash[it] = episodes }
                when (episodes.size) {
                    0 -> fail("There are no video files in this torrent")
                    // Nothing to pick: go straight to asking whether to start it.
                    1 -> _screen.value = Screen.Picker(title, seasons, confirm = episodes.single())
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

    // Starting and managing tasks --------------------------------------------------------------

    /** Asks before starting: shows the Start sheet for [episode]. */
    fun askStart(episode: Episode) {
        (_screen.value as? Screen.Picker)?.let { _screen.value = it.copy(confirm = episode) }
    }

    fun dismissStart() {
        (_screen.value as? Screen.Picker)?.let { _screen.value = it.copy(confirm = null) }
    }

    /** Start pressed: the episode becomes a task and starts downloading in playback order. */
    fun start(episode: Episode, saveWhenFinished: Boolean) {
        val id = TorrentEngine.browsingHash?.let { addTask(episode, it, title, saveWhenFinished) }
        _screen.value = if (id == null) Screen.Main(Tab.Search, "Couldn't start this download") else Screen.Main(Tab.Tasks)
    }

    /** "Next episode" on a task: starts it straight away (already chosen) and opens it. */
    fun startNext(task: Task) {
        val next = task.next ?: return
        val id = addTask(next, task.hash, task.torrentTitle, task.saveWhenFinished) ?: return
        _screen.value = Screen.TaskDetail(id)
    }

    private fun addTask(episode: Episode, hash: String, torrentTitle: String, saveWhenFinished: Boolean): Int? {
        val stream = TorrentEngine.startTask(episode.fileIndex, hash) ?: return null
        val all = episodesByHash[hash].orEmpty()
        val next = all.getOrNull(all.indexOfFirst { it.fileIndex == episode.fileIndex } + 1)
        if (Tasks.get(stream.id) == null) {
            Tasks.add(
                getApplication(),
                Task(stream.id, hash, torrentTitle, episode, TorrentEngine.url(stream), stream.file.absolutePath, next),
            )
        }
        if (saveWhenFinished) Tasks.setSaveWhenFinished(stream.id, true)
        return stream.id
    }

    fun openTask(id: Int) {
        _screen.value = Screen.TaskDetail(id)
    }

    fun removeTask(id: Int) {
        Tasks.remove(getApplication(), id)
        if ((_screen.value as? Screen.TaskDetail)?.id == id) _screen.value = Screen.Main(Tab.Tasks)
    }

    fun setSaveWhenFinished(id: Int, enabled: Boolean) = Tasks.setSaveWhenFinished(id, enabled)

    /** Back: task → tasks, picker or loading → search, other tabs → search. */
    fun back() {
        when (val s = _screen.value) {
            is Screen.TaskDetail -> _screen.value = Screen.Main(Tab.Tasks)
            is Screen.Picker -> if (s.confirm != null && episodes.size > 1) dismissStart() else leaveTorrent()
            is Screen.Resolving -> leaveTorrent()
            is Screen.Main -> if (s.tab != Tab.Search) _screen.value = Screen.Main(Tab.Search)
        }
    }

    fun clearError() {
        (_screen.value as? Screen.Main)?.takeIf { it.error != null }?.let { _screen.value = it.copy(error = null) }
    }

    /** Leaves the torrent being looked at. Its tasks, if any, carry on. */
    private fun leaveTorrent() {
        job?.cancel()
        seasons = emptyList()
        _screen.value = Screen.Main(Tab.Search)
        viewModelScope.launch(Dispatchers.IO) { TorrentEngine.stopBrowsing() }
    }

    private suspend fun fail(message: String) {
        withContext(Dispatchers.IO) { TorrentEngine.stopBrowsing() }
        _screen.value = Screen.Main(Tab.Search, message)
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
        // Tasks keep going with the app closed; the notification shows them and can stop them.
        if (!TorrentEngine.busy) Thread { TorrentEngine.shutdown() }.start()
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 120_000L
    }
}
