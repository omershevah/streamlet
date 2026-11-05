package dev.omershevach.streamlet

import android.content.Context
import dev.omershevach.streamlet.engine.TorrentEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.FileInputStream
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** An episode marked "Save when finished". */
data class Download(
    /** The file's path in the torrent cache; unique per file. */
    val id: String,
    val hash: String,
    val fileIndex: Int,
    val name: String,
    val size: Long,
    val downloaded: Long = 0,
    val bytesPerSecond: Int = 0,
    val status: Status = Status.Downloading,
) {
    sealed interface Status {
        data object Downloading : Status
        data class Saving(val progress: Float) : Status
        data object Saved : Status
        data class Failed(val message: String) : Status
    }

    val progress: Float get() = if (size > 0) downloaded / size.toFloat() else 0f
    val active: Boolean get() = status is Status.Downloading || status is Status.Saving
}

/**
 * Finishes downloading marked episodes in the background (the torrent stays connected after
 * the user leaves it) and copies each to Movies/Streamlet as soon as it's complete. The
 * foreground [StreamService] keeps the process alive while anything here is active.
 */
object Downloads {
    private val _items = MutableStateFlow<List<Download>>(emptyList())
    val items = _items.asStateFlow()

    val active: Boolean get() = _items.value.any { it.active }

    private val ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "downloads").apply { isDaemon = true } }
    private val copier = Executors.newSingleThreadExecutor { r -> Thread(r, "saver").apply { isDaemon = true } }
    @Volatile private var started = false
    private lateinit var app: Context

    /** Marks the episode that's playing to be saved when it finishes downloading. */
    fun add(context: Context, kept: TorrentEngine.Kept) {
        app = context.applicationContext
        val id = kept.file.absolutePath
        _items.update { list ->
            list.filterNot { it.id == id } + Download(id, kept.hash, kept.fileIndex, kept.name, kept.size)
        }
        ensureTicking()
        StreamService.refresh(app)
    }

    /** Cancels a download that hasn't finished, or clears a finished or failed one from the list. */
    fun remove(context: Context, id: String) {
        val item = _items.value.firstOrNull { it.id == id } ?: return
        if (item.status is Download.Status.Saving) return // a copy in progress finishes on its own
        if (item.status == Download.Status.Downloading) TorrentEngine.unkeep(item.hash, item.fileIndex)
        _items.update { list -> list.filterNot { it.id == id } }
        StreamService.refresh(context.applicationContext)
    }

    private fun ensureTicking() {
        if (started) return
        started = true
        ticker.scheduleWithFixedDelay(::tick, 1, 1, TimeUnit.SECONDS)
    }

    private fun tick() {
        var changed = false
        for (item in _items.value) {
            if (item.status != Download.Status.Downloading) continue
            val done = TorrentEngine.downloaded(item.hash, item.fileIndex)
            when {
                done == null -> {
                    set(item.id) { it.copy(status = Download.Status.Failed("The download was stopped")) }
                    changed = true
                }
                done >= item.size -> {
                    set(item.id) { it.copy(downloaded = done, bytesPerSecond = 0, status = Download.Status.Saving(0f)) }
                    copier.execute { save(item) }
                    changed = true
                }
                else -> set(item.id) { it.copy(downloaded = done, bytesPerSecond = TorrentEngine.rate(item.hash)) }
            }
        }
        if (changed || active) StreamService.refresh(app)
    }

    private fun save(item: Download) {
        var lastPercent = -1
        val result = try {
            val uri = FileInputStream(item.id).use { input ->
                Saver.copy(app, input, item.name, item.size) { p ->
                    val percent = (p * 100).toInt()
                    if (percent != lastPercent) {
                        lastPercent = percent
                        set(item.id) { it.copy(status = Download.Status.Saving(p)) }
                    }
                }
            }
            Saver.notifySaved(app, item.name, uri)
            Download.Status.Saved
        } catch (e: Exception) {
            Download.Status.Failed(Saver.describe(e))
        }
        set(item.id) { it.copy(status = result) }
        // Saved (or failed): the torrent's copy is no longer needed.
        TorrentEngine.unkeep(item.hash, item.fileIndex)
        StreamService.refresh(app)
    }

    private fun set(id: String, change: (Download) -> Download) {
        _items.update { list -> list.map { if (it.id == id) change(it) else it } }
    }
}
