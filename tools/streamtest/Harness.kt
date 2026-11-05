import dev.omershevach.streamlet.engine.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.HttpURLConnection
import java.net.URI
import kotlin.system.measureTimeMillis

/**
 * Runs the app's real engine the way the Tasks flow does: load torrent A and start a task,
 * load torrent B (A keeps downloading in the background) and start a task, play B in VLC,
 * then remove A's task and check its torrent is gone while B carries on.
 */
fun main(args: Array<String>) {
    println("libtorrent " + org.libtorrent4j.LibTorrent.version())
    val (magnetA, magnetB) = args.map { Magnet.normalize(it) ?: error("bad magnet $it") }
    TorrentEngine.init(android.content.Context())
    val t0 = System.currentTimeMillis()
    fun ts() = "%6.1fs".format((System.currentTimeMillis() - t0) / 1000.0)
    fun line(label: String, s: Stream) {
        val st = TorrentEngine.stats(s.id)
        println("${ts()} [$label] ${if (st == null) "GONE" else "ready=${st.ready} done=${(st.downloaded * 100).toInt()}% rate=${formatSize(st.bytesPerSecond.toLong())}/s peers=${st.peers} head=${s.head}"}")
    }

    fun startFirstVideo(magnet: String, label: String): Stream {
        val files = runBlocking { withTimeout(120_000) { TorrentEngine.resolve(magnet) } }
        val ep = Episodes.parse(files).flatMap { it.episodes }.first()
        println("${ts()} [$label] loaded ${TorrentEngine.title}: ${files.size} files, starting #${ep.fileIndex} ${ep.fileName} (${formatSize(ep.size)})")
        return TorrentEngine.startTask(ep.fileIndex) ?: error("startTask returned null")
    }

    val a = startFirstVideo(magnetA, "A")
    repeat(3) { line("A", a); Thread.sleep(2000) }
    val b = startFirstVideo(magnetB, "B") // A is no longer browsed but must keep going
    repeat(4) { line("A", a); line("B", b); Thread.sleep(2000) }
    check(TorrentEngine.stats(a.id) != null) { "task A stopped when B was loaded" }

    // Wait for B to be ready, then play it in VLC while A also downloads.
    val readyBy = System.currentTimeMillis() + 120_000
    while (TorrentEngine.stats(b.id)?.ready != true && System.currentTimeMillis() < readyBy) Thread.sleep(1000)
    check(TorrentEngine.stats(b.id)?.ready == true) { "B never became ready" }
    val url = TorrentEngine.url(b)
    println("${ts()} B ready. Opening in VLC: $url")
    val vlc = ProcessBuilder("cvlc", "--intf", "dummy", "--vout", "dummy", "--aout", "dummy",
        "--play-and-exit", "--run-time=25", "-vv", url)
        .redirectErrorStream(true).redirectOutput(java.io.File("vlc.log")).start()
    while (vlc.isAlive) { line("A", a); line("B", b); Thread.sleep(3000) }
    println("${ts()} VLC exited: ${vlc.exitValue()}")

    // Range reads like a player seeking.
    for ((label, range) in listOf("start" to "bytes=0-", "end" to "bytes=-65536", "middle" to "bytes=${b.size / 2}-")) {
        var got = 0L
        var code = 0
        val ms = measureTimeMillis {
            val c = URI(url).toURL().openConnection() as HttpURLConnection
            c.setRequestProperty("Range", range)
            c.readTimeout = 60_000
            code = c.responseCode
            c.inputStream.use { inp -> val buf = ByteArray(65536); while (got < 1024 * 1024) { val n = inp.read(buf); if (n < 0) break; got += n } }
        }
        println("${ts()} HTTP $label: $code, ${formatSize(got)} in ${ms}ms")
    }

    // Remove A: its stream and torrent go; B is untouched.
    TorrentEngine.removeTask(a.id)
    Thread.sleep(2000)
    check(TorrentEngine.stats(a.id) == null) { "A still has stats after removal" }
    check(TorrentEngine.stats(b.id) != null) { "B stopped when A was removed" }
    val code = (URI(TorrentEngine.url(a)).toURL().openConnection() as HttpURLConnection).responseCode
    println("${ts()} removed A: stats gone, its URL now answers $code; B still running")
    check(code == 404)
    println("RESULT OK")
    TorrentEngine.shutdown()
    kotlin.system.exitProcess(0)
}
