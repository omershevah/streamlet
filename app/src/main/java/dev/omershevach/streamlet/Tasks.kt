package dev.omershevach.streamlet

import android.content.Context
import dev.omershevach.streamlet.engine.Episode
import dev.omershevach.streamlet.engine.StreamStats
import dev.omershevach.streamlet.engine.TorrentEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One episode being downloaded (in playback order) and served to VLC. */
data class Task(
    /** The engine's stream id. */
    val id: Int,
    val hash: String,
    val torrentTitle: String,
    val episode: Episode,
    /** Where VLC reads it. */
    val url: String,
    /** The file in the torrent cache. */
    val file: String,
    /** The episode after this one in the same torrent, if any. */
    val next: Episode?,
    val stats: StreamStats? = null,
    val saveWhenFinished: Boolean = false,
    val save: Save = Save.None,
) {
    sealed interface Save {
        data object None : Save
        data class Saving(val progress: Float) : Save
        data object Saved : Save
        data class Failed(val message: String) : Save
    }

    val complete: Boolean get() = stats?.complete == true
}

/**
 * Every running task. A task keeps downloading until it's removed, whether or not the app is
 * open; [StreamService] keeps the process alive while there are any. Tasks marked "Save when
 * finished" are copied to Movies/Streamlet as soon as they're complete.
 */
object Tasks {
    private val _items = MutableStateFlow<List<Task>>(emptyList())
    val items = _items.asStateFlow()

    val isEmpty: Boolean get() = _items.value.isEmpty()
    val saving: Boolean get() = _items.value.any { it.save is Task.Save.Saving }

    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "tasks").apply { isDaemon = true } }
    private val copier = Executors.newSingleThreadExecutor { r -> Thread(r, "saver").apply { isDaemon = true } }
    @Volatile private var started = false
    private lateinit var app: Context

    fun add(context: Context, task: Task) {
        app = context.applicationContext
        _items.update { list -> list.filterNot { it.id == task.id } + task }
        if (!started) {
            started = true
            ticker.scheduleWithFixedDelay(::tick, 0, 1, TimeUnit.SECONDS)
        }
        StreamService.update(app)
    }

    fun get(id: Int): Task? = _items.value.firstOrNull { it.id == id }

    /** Stops a task and deletes what it downloaded (a saved copy stays). Not while it's being saved. */
    fun remove(context: Context, id: Int) {
        if (get(id)?.save is Task.Save.Saving) return
        TorrentEngine.removeTask(id)
        _items.update { list -> list.filterNot { it.id == id } }
        StreamService.update(context.applicationContext)
    }

    fun removeAll(context: Context) {
        _items.value.filter { it.save !is Task.Save.Saving }.forEach { remove(context, it.id) }
    }

    fun setSaveWhenFinished(id: Int, enabled: Boolean) {
        set(id) { t ->
            // Once saved or saving there's nothing to change; after a failure, ticking again retries.
            if (t.save is Task.Save.Saving || t.save == Task.Save.Saved) t
            else t.copy(saveWhenFinished = enabled, save = Task.Save.None)
        }
    }

    private fun tick() {
        for (task in _items.value) {
            val stats = TorrentEngine.stats(task.id) ?: continue
            set(task.id) { it.copy(stats = stats) }
            if (task.saveWhenFinished && task.save == Task.Save.None && stats.complete) {
                set(task.id) { it.copy(save = Task.Save.Saving(0f)) }
                copier.execute { save(task) }
            }
        }
        if (_items.value.isNotEmpty()) StreamService.update(app)
    }

    private fun save(task: Task) {
        var lastPercent = -1
        val name = task.episode.fileName
        val result = try {
            val uri = FileInputStream(task.file).use { input ->
                Saver.copy(app, input, name, task.episode.size) { p ->
                    val percent = (p * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        set(task.id) { it.copy(save = Task.Save.Saving(p)) }
                    }
                }
            }
            Saver.notifySaved(app, name, uri)
            Task.Save.Saved
        } catch (e: Exception) {
            Task.Save.Failed(Saver.describe(e))
        }
        set(task.id) { it.copy(save = result, saveWhenFinished = result == Task.Save.Saved) }
        StreamService.update(app)
    }

    private fun set(id: Int, change: (Task) -> Task) {
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
    }
}
