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
 * Flow: [resolve] adds a magnet or .torrent with every file switched off and waits for the file list.
 * [play] switches on one file and returns a [Stream]; [url] is where a player can read it.
 * The torrent stays connected between episodes, so the next one starts without finding peers again.
 *
 * Files marked with [keep] go on downloading after the user leaves their torrent (or the app).
 * A torrent is only removed, with its files, once nothing is streaming from it and none of its
 * files are kept. [Downloads] drives the kept files and calls [unkeep] when each is saved.
 */
object TorrentEngine {
    private val session = SessionManager(false)
    private val server = StreamServer()
    private val lock = ReentrantLock()
    private val pieceDone = lock.newCondition()
    private lateinit var root: File

    private class Loaded(val hash: Sha1Hash, val handle: TorrentHandle, val info: TorrentInfo, val dir: File) {
        /** File indices to finish downloading in the background. */
        val kept = mutableSetOf<Int>()
    }

    /** A file kept for download: enough to find it again and save it. */
    data class Kept(val hash: String, val fileIndex: Int, val file: File, val name: String, val size: Long)

    private val torrents = mutableMapOf<Sha1Hash, Loaded>() // guarded by this

    /** The torrent the user has open. */
    @Volatile private var current: Loaded? = null
    @Volatile var stream: Stream? = null
        private set

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
            .activeDownloads(8) // the open torrent plus any finishing in the background
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
        current?.let { if (it.hash != hash) release() }
        // Already here (still finishing a kept file in the background)? Just reopen it.
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
            current = t
        }
        val files = t.info.files()
        return List(files.numFiles()) { i -> TorrentFile(i, files.filePath(i).replace('\\', '/'), files.fileSize(i)) }
    }

    val title: String? get() = current?.info?.name()

    /** Downloads only [fileIndex] (plus kept files), in playback order, and serves it over local HTTP. */
    fun play(fileIndex: Int): Stream = synchronized(this) {
        val t = current ?: error("No torrent loaded")
        stream?.close()
        val path = File(t.info.files().filePath(fileIndex, t.dir.absolutePath))
        server.start()
        Stream(t.handle, t.info, fileIndex, path, lock, pieceDone).also {
            stream = it
            applyPriorities(t)
        }
    }

    /** Only the file being streamed and the kept files download; everything else is skipped. */
    private fun applyPriorities(t: Loaded) {
        if (!t.handle.isValid) return
        val priorities = Priority.array(Priority.IGNORE, t.info.numFiles())
        stream?.takeIf { it.open && it.handle == t.handle }?.let { priorities[it.fileIndex] = Priority.DEFAULT }
        t.kept.forEach { priorities[it] = Priority.DEFAULT }
        t.handle.prioritizeFiles(priorities)
    }

    fun url(s: Stream): String = "http://127.0.0.1:${server.port}/${s.id}/${Uri.encode(s.name)}"

    internal fun streamFor(id: Int?): Stream? = stream?.takeIf { it.id == id && it.open }

    fun stopStream() = synchronized(this) {
        val s = stream
        s?.close()
        stream = null
        // Stop fetching what was only being streamed; kept files carry on.
        if (s != null) torrents.values.firstOrNull { it.handle == s.handle }?.let(::applyPriorities)
    }

    /** Leaves the open torrent. It's removed with its files unless some of them are kept. */
    fun release() = synchronized(this) {
        stopStream()
        current?.let(::dropIfUnused)
        current = null
    }

    private fun dropIfUnused(t: Loaded) {
        if (t === current && stream != null) return
        if (t.kept.isNotEmpty() && t.handle.isValid) {
            applyPriorities(t)
            return
        }
        if (t.handle.isValid) session.remove(t.handle, SessionHandle.DELETE_FILES)
        torrents.remove(t.hash)
    }

    /** Marks a file of the open torrent to finish downloading even after the user leaves. */
    fun keep(fileIndex: Int): Kept? = synchronized(this) {
        val t = current ?: return null
        t.kept += fileIndex
        applyPriorities(t)
        val files = t.info.files()
        Kept(t.hash.toHex(), fileIndex, File(files.filePath(fileIndex, t.dir.absolutePath)), files.fileName(fileIndex), files.fileSize(fileIndex))
    }

    /** Stops keeping a file (saved or cancelled). Its torrent goes once nothing else needs it. */
    fun unkeep(hash: String, fileIndex: Int) = synchronized(this) {
        val t = torrents.values.firstOrNull { it.hash.toHex() == hash } ?: return
        t.kept -= fileIndex
        if (t === current) applyPriorities(t) else dropIfUnused(t)
    }

    /** Verified bytes of a kept file, or null if its torrent is gone. */
    fun downloaded(hash: String, fileIndex: Int): Long? = synchronized(this) {
        val t = torrents.values.firstOrNull { it.hash.toHex() == hash }?.takeIf { it.handle.isValid } ?: return null
        try {
            t.handle.fileProgress(TorrentHandle.PIECE_GRANULARITY)[fileIndex]
        } catch (_: Exception) {
            null
        }
    }

    /** Download speed of the torrent holding a kept file, for showing progress. */
    fun rate(hash: String): Int = synchronized(this) {
        torrents.values.firstOrNull { it.hash.toHex() == hash }?.takeIf { it.handle.isValid }
            ?.let { runCatching { it.handle.status().downloadPayloadRate() }.getOrNull() } ?: 0
    }

    /** True while something still needs the session: a stream or a kept file. */
    val busy: Boolean get() = synchronized(this) { stream != null || torrents.values.any { it.kept.isNotEmpty() } }

    /** Blocks while the native session shuts down; call off the main thread. */
    fun shutdown() = synchronized(this) {
        stopStream()
        torrents.values.toList().forEach { it.kept.clear() }
        current = null
        torrents.values.toList().forEach(::dropIfUnused)
        server.stop()
        if (session.isRunning) session.stop()
    }

    fun stats(): StreamStats? {
        val s = stream?.takeIf { it.open } ?: return null
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
