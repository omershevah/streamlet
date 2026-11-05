package dev.omershevach.streamlet.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionHandle
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The single torrent session for the app.
 *
 * Flow: [resolve] adds a magnet or .torrent with every file switched off and waits for the file
 * list; that torrent is the one being browsed. [startTask] switches one of its files on and
 * returns a [Stream], which downloads in playback order and is served at [url]. Any number of
 * tasks can run at once, from any number of torrents. A torrent is removed, with its files,
 * once it has no tasks and isn't being browsed.
 */
object TorrentEngine {
    private val session = SessionManager(false)
    private val server = StreamServer()
    private val lock = ReentrantLock()
    private val pieceDone = lock.newCondition()
    private lateinit var root: File

    private class Loaded(val hash: Sha1Hash, val handle: TorrentHandle, val info: TorrentInfo, val dir: File)

    private val torrents = mutableMapOf<Sha1Hash, Loaded>() // guarded by this
    private val streams = mutableMapOf<Int, Stream>() // guarded by this; one per task

    /** The torrent whose files are being looked at (picker), if any. */
    @Volatile private var browsing: Loaded? = null

    init {
        // Wake up readers waiting in Stream.awaitReadable whenever a piece lands.
        session.addListener(object : AlertListener {
            override fun types() = intArrayOf(AlertType.PIECE_FINISHED.swig())
            override fun alert(alert: Alert<*>) = lock.withLock { pieceDone.signalAll() }
        })
    }

    fun init(context: Context) {
        root = File(context.cacheDir, "torrents")
    }

    private fun ensureStarted() = synchronized(this) {
        if (session.isRunning) return@synchronized
        root.deleteRecursively() // leftovers from a previous run
        root.mkdirs()
        val settings = SettingsPack()
            .connectionsLimit(200)
            .activeDownloads(16) // every torrent with a task downloads at once
        session.start(SessionParams(settings))
    }

    /** Connects to the swarm and returns the torrent's file list. Cancellable. */
    suspend fun resolve(magnet: String): List<TorrentFile> = withContext(Dispatchers.IO) {
        ensureStarted()
        val hash = AddTorrentParams.parseMagnetUri(magnet).infoHashes.best
        load(hash) { dir -> session.download(magnet, dir, TorrentFlags.DEFAULT_DONT_DOWNLOAD) }
    }

    /** Same as [resolve] for the contents of a .torrent file. The file list is already in it, so this is quick. */
    suspend fun resolve(torrentFile: ByteArray): List<TorrentFile> = withContext(Dispatchers.IO) {
        ensureStarted()
        val info = TorrentInfo(torrentFile)
        val skipAll = Priority.array(Priority.IGNORE, info.numFiles())
        load(info.infoHash()) { dir -> session.download(info, dir, null, skipAll, null, TorrentFlags.DEFAULT_DONT_DOWNLOAD) }
    }

    private suspend fun load(hash: Sha1Hash, add: (File) -> Unit): List<TorrentFile> {
        browsing?.let { if (it.hash != hash) stopBrowsing() }
        // Already here because one of its files is a task? Just look at it again.
        val existing = synchronized(this) { torrents[hash]?.takeIf { it.handle.isValid } }
        val t = existing ?: run {
            val dir = File(root, hash.toHex())
            add(dir)
            var loaded: Loaded? = null
            while (loaded == null) {
                currentCoroutineContext().ensureActive()
                val handle = session.find(hash)
                val info = handle?.torrentFile()
                if (handle != null && info != null) loaded = Loaded(hash, handle, info, dir) else delay(250)
            }
            checkNotNull(loaded)
        }
        synchronized(this) {
            torrents[hash] = t
            browsing = t
        }
        val files = t.info.files()
        return List(files.numFiles()) { i -> TorrentFile(i, files.filePath(i).replace('\\', '/'), files.fileSize(i)) }
    }

    /** Name of the torrent being browsed. */
    val title: String? get() = browsing?.info?.name()

    /** Hash of the torrent being browsed, to start more tasks from it later. */
    val browsingHash: String? get() = browsing?.hash?.toHex()

    /** Stops looking at the browsed torrent. It's removed unless it has tasks. */
    fun stopBrowsing() {
        synchronized(this) {
            val t = browsing ?: return
            browsing = null
            dropIfUnused(t)
        }
    }

    /**
     * Starts downloading one file in playback order, as a task. [hash] picks the torrent
     * (default: the one being browsed). Returns null if that torrent is gone.
     */
    fun startTask(fileIndex: Int, hash: String? = null): Stream? = synchronized(this) {
        val t = (if (hash == null) browsing else torrents.values.firstOrNull { it.hash.toHex() == hash })
            ?.takeIf { it.handle.isValid } ?: return null
        streams.values.firstOrNull { it.handle == t.handle && it.fileIndex == fileIndex && it.open }?.let { return it }
        val path = File(t.info.files().filePath(fileIndex, t.dir.absolutePath))
        server.start()
        val s = Stream(t.handle, t.info, fileIndex, path, lock, pieceDone)
        streams[s.id] = s
        applyPriorities(t)
        s
    }

    /** Stops a task. Its torrent goes, with its files, once nothing else needs it. */
    fun removeTask(id: Int) {
        synchronized(this) {
            val s = streams.remove(id) ?: return
            s.close()
            torrents.values.firstOrNull { it.handle == s.handle }?.let(::dropIfUnused)
        }
    }

    /** Only files with a task download; everything else is skipped. */
    private fun applyPriorities(t: Loaded) {
        if (!t.handle.isValid) return
        val priorities = Priority.array(Priority.IGNORE, t.info.numFiles())
        streams.values.filter { it.handle == t.handle }.forEach { priorities[it.fileIndex] = Priority.DEFAULT }
        t.handle.prioritizeFiles(priorities)
    }

    private fun dropIfUnused(t: Loaded) {
        if (t === browsing) return applyPriorities(t)
        if (streams.values.any { it.handle == t.handle }) return applyPriorities(t)
        if (t.handle.isValid) session.remove(t.handle, SessionHandle.DELETE_FILES)
        torrents.remove(t.hash)
    }

    fun url(s: Stream): String = "http://127.0.0.1:${server.port}/${s.id}/${Uri.encode(s.name)}"

    internal fun streamFor(id: Int?): Stream? = synchronized(this) { streams[id]?.takeIf { it.open } }

    /** True while any task exists, so the session (and the process) must stay up. */
    val busy: Boolean get() = synchronized(this) { streams.isNotEmpty() }

    /** Blocks while the native session shuts down; call off the main thread. */
    fun shutdown(): Unit = synchronized(this) {
        streams.values.forEach { it.close() }
        streams.clear()
        browsing = null
        torrents.values.toList().forEach(::dropIfUnused)
        server.stop()
        if (session.isRunning) session.stop()
    }

    fun stats(id: Int): StreamStats? {
        val s = synchronized(this) { streams[id] }?.takeIf { it.open } ?: return null
        return try {
            val h = s.handle
            val status = h.status()
            val have = s.startupPieces.count { h.havePiece(it) }
            var p = s.head
            val limit = minOf(s.lastPiece, s.head + 512)
            while (p <= limit && h.havePiece(p)) p++
            val done = h.fileProgress(TorrentHandle.PIECE_GRANULARITY)[s.fileIndex]
            StreamStats(
                startup = have / s.startupPieces.size.toFloat(),
                ready = have == s.startupPieces.size,
                downloaded = if (s.size > 0) done / s.size.toFloat() else 0f,
                complete = done >= s.size,
                bufferedAhead = (p - s.head).toLong() * s.pieceLength,
                bytesPerSecond = status.downloadPayloadRate(),
                peers = status.numPeers(),
            )
        } catch (_: Exception) {
            null // the handle went away mid-call
        }
    }
}
