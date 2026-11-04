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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

sealed interface Screen {
    data class Home(val error: String? = null) : Screen
    data class Resolving(val name: String?) : Screen
    data class Picker(val title: String, val seasons: List<Season>) : Screen
    data class Playing(val title: String, val episode: Episode, val url: String, val next: Episode?) : Screen
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val _screen = MutableStateFlow<Screen>(Screen.Home())
    val screen = _screen.asStateFlow()

    private val _stats = MutableStateFlow<StreamStats?>(null)
    val stats = _stats.asStateFlow()

    private var title = ""
    private var seasons: List<Season> = emptyList()
    private val episodes get() = seasons.flatMap { it.episodes }
    private var job: Job? = null
    private var statsJob: Job? = null

    fun open(raw: String) {
        val magnet = Magnet.normalize(raw)
        if (magnet == null) {
            _screen.value = Screen.Home("That isn't a magnet link")
            return
        }
        job?.cancel()
        stopPlayback()
        _screen.value = Screen.Resolving(Magnet.displayName(magnet))
        job = viewModelScope.launch {
            try {
                val files = withTimeout(RESOLVE_TIMEOUT_MS) { TorrentEngine.resolve(magnet) }
                seasons = Episodes.parse(files)
                title = TorrentEngine.title ?: Magnet.displayName(magnet) ?: "Torrent"
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
                fail(e.message ?: "Couldn't open this link")
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

    /** Back navigation: playing → picker → home. */
    fun back() {
        when (_screen.value) {
            is Screen.Playing -> {
                stopPlayback()
                if (episodes.size > 1) _screen.value = Screen.Picker(title, seasons) else reset()
            }
            is Screen.Resolving, is Screen.Picker -> reset()
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

    override fun onCleared() {
        // Keep going if something is still streaming to the player; the notification can stop it.
        if (TorrentEngine.stream == null) Thread { TorrentEngine.shutdown() }.start()
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 120_000L
    }
}
