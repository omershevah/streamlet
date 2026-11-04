package dev.omershevach.streamlet.engine

import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One file of a torrent being streamed.
 *
 * Instead of letting libtorrent download in its usual rarest-first order, every read
 * sets deadlines on the pieces just ahead of the reader. libtorrent fetches pieces with
 * deadlines first, so playback (and seeking) only waits for the data it needs right now.
 */
class Stream internal constructor(
    val handle: TorrentHandle,
    info: TorrentInfo,
    val fileIndex: Int,
    val file: File,
    private val lock: ReentrantLock,
    private val pieceDone: Condition,
) {
    val id = ids.incrementAndGet()
    val name: String = info.files().fileName(fileIndex)
    val size: Long = info.files().fileSize(fileIndex)
    val pieceLength: Int = info.pieceLength()

    private val offset = info.files().fileOffset(fileIndex)
    val firstPiece = (offset / pieceLength).toInt()
    val lastPiece = ((offset + size - 1) / pieceLength).toInt()

    /** How many pieces ahead of the reader get a deadline (about 24 MB). */
    private val window = (WINDOW_BYTES / pieceLength).toInt().coerceIn(4, 64)

    /** The pieces needed before a player can start: the start of the file and its index at the end. */
    val startupPieces: List<Int> =
        ((firstPiece until firstPiece + (STARTUP_BYTES / pieceLength + 1).toInt()) + lastPiece)
            .map { it.coerceAtMost(lastPiece) }
            .distinct()

    @Volatile var open = true
        private set

    /** The piece the player is currently reading. */
    @Volatile var head = firstPiece
        private set

    private var focused = -1

    init {
        for (p in startupPieces) handle.setPieceDeadline(p, 0)
        focus(firstPiece)
    }

    /**
     * Blocks until the byte at [pos] has been downloaded, then returns how many bytes
     * can be read from there without waiting again. Returns -1 if the stream was closed.
     */
    fun awaitReadable(pos: Long): Long {
        val piece = ((offset + pos) / pieceLength).toInt()
        // Players read the end of the file once for its index. Don't move the window there.
        if (piece >= lastPiece - 1 && focused in firstPiece until lastPiece - window) {
            handle.setPieceDeadline(piece, 0)
        } else {
            focus(piece)
        }
        lock.withLock {
            var waits = 0
            while (open && !handle.havePiece(piece)) {
                // Re-assert the deadline now and then, in case it landed before the file priority did.
                if (++waits % 6 == 0) handle.setPieceDeadline(piece, 0)
                pieceDone.await(500, TimeUnit.MILLISECONDS)
            }
        }
        if (!open) return -1
        val pieceEnd = (piece + 1L) * pieceLength - offset
        return minOf(pieceEnd, size) - pos
    }

    @Synchronized
    private fun focus(piece: Int) {
        if (piece == focused || !open) return
        val next = piece until minOf(piece + window, lastPiece + 1)
        if (focused >= 0) {
            for (p in focused until minOf(focused + window, lastPiece + 1)) {
                if (p !in next && !handle.havePiece(p)) handle.resetPieceDeadline(p)
            }
        }
        focused = piece
        head = piece
        for ((i, p) in next.withIndex()) {
            if (!handle.havePiece(p)) handle.setPieceDeadline(p, 200 + i * 150)
        }
    }

    internal fun close() {
        open = false
        lock.withLock { pieceDone.signalAll() }
    }

    private companion object {
        val ids = AtomicInteger()
        const val WINDOW_BYTES = 24L * 1024 * 1024
        const val STARTUP_BYTES = 4L * 1024 * 1024
    }
}

data class StreamStats(
    /** Progress toward [ready], 0..1. */
    val startup: Float,
    val ready: Boolean,
    /** Share of the whole file that's on disk, 0..1. */
    val downloaded: Float,
    val bufferedAhead: Long,
    val bytesPerSecond: Int,
    val peers: Int,
)
