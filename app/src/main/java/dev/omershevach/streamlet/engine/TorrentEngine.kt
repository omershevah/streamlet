package dev.omershevach.streamlet.engine

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
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
 * The single torrent session for the app. Holds at most one torrent and one stream at a time.
 *
 * Flow: [resolve] adds the magnet with every file switched off and waits for the file list.
 * [play] switches on one file and returns a [Stream]; [url] is where a player can read it.
 * The torrent stays connected between episodes, so the next one starts without finding peers again.
 */
object TorrentEngine {
    private val session = SessionManager(false)
    private val server = StreamServer()
    private val lock = ReentrantLock()
    private val pieceDone = lock.newCondition()
    private lateinit var root: File

    private class Loaded(val hash: Sha1Hash, val handle: TorrentHandle, val info: TorrentInfo, val dir: File)

    @Volatile private var torrent: Loaded? = null
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
            .activeDownloads(1)
        session.start(SessionParams(settings))
    }

    /** Connects to the swarm and returns the torrent's file list. Cancellable. */
    suspend fun resolve(magnet: String): List<TorrentFile> = withContext(Dispatchers.IO) {
        ensureStarted()
        val hash = AddTorrentParams.parseMagnetUri(magnet).infoHashes.best
        torrent?.let { if (it.hash != hash) release() }

        val dir = File(root, hash.toHex())
        session.download(magnet, dir, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
        var loaded: Loaded? = null
        while (loaded == null) {
            ensureActive()
            val handle = session.find(hash)
            val info = handle?.torrentFile()
            if (handle != null && info != null) loaded = Loaded(hash, handle, info, dir) else delay(250)
        }
        val t = checkNotNull(loaded)
        torrent = t
        val files = t.info.files()
        List(files.numFiles()) { i -> TorrentFile(i, files.filePath(i).replace('\\', '/'), files.fileSize(i)) }
    }

    val title: String? get() = torrent?.info?.name()

    /** Downloads only [fileIndex], in playback order, and serves it over local HTTP. */
    fun play(fileIndex: Int): Stream = synchronized(this) {
        val t = torrent ?: error("No torrent loaded")
        stream?.close()
        val priorities = Priority.array(Priority.IGNORE, t.info.numFiles())
        priorities[fileIndex] = Priority.DEFAULT
        t.handle.prioritizeFiles(priorities)
        val path = File(t.info.files().filePath(fileIndex, t.dir.absolutePath))
        server.start()
        Stream(t.handle, t.info, fileIndex, path, lock, pieceDone).also { stream = it }
    }

    fun url(s: Stream): String = "http://127.0.0.1:${server.port}/${s.id}/${Uri.encode(s.name)}"

    internal fun streamFor(id: Int?): Stream? = stream?.takeIf { it.id == id && it.open }

    fun stopStream() = synchronized(this) {
        stream?.close()
        stream = null
    }

    /** Stops streaming and removes the torrent and everything it downloaded. */
    fun release() = synchronized(this) {
        stopStream()
        torrent?.let { if (it.handle.isValid) session.remove(it.handle, SessionHandle.DELETE_FILES) }
        torrent = null
    }

    /** Blocks while the native session shuts down; call off the main thread. */
    fun shutdown() = synchronized(this) {
        release()
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
                bufferedAhead = (p - s.head).toLong() * s.pieceLength,
                bytesPerSecond = status.downloadPayloadRate(),
                peers = status.numPeers(),
            )
        } catch (_: Exception) {
            null // the handle went away mid-call
        }
    }
}
