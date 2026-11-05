package dev.omershevach.streamlet

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Copies a fully downloaded video into the shared Movies/Streamlet folder, where gallery
 * and player apps can see it. Called by [Downloads], one copy at a time.
 */
object Saver {
    private const val FOLDER = "Streamlet"
    private const val SAVED_NOTIFICATION_ID = 3

    /** Where saved videos end up, for showing to the user. */
    val location: String get() = "${Environment.DIRECTORY_MOVIES}/$FOLDER"

    /** Returns a content Uri the saved file can be opened with, if the platform gives one. */
    fun copy(context: Context, input: InputStream, name: String, total: Long, onProgress: (Float) -> Unit): Uri? {
        val mime = mimeType(name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$FOLDER")
                put(MediaStore.Video.Media.IS_PENDING, 1) // hidden from other apps until complete
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create the file")
            try {
                val out = resolver.openOutputStream(uri) ?: throw IOException("Couldn't open the file")
                out.use { pipe(input, it, total, onProgress) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } else {
            // Android 8 and 9: write to the public folder directly (needs the storage permission).
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Couldn't create $location")
            val target = unique(dir, name)
            try {
                FileOutputStream(target).use { pipe(input, it, total, onProgress) }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            MediaScannerConnection.scanFile(context, arrayOf(target.path), arrayOf(mime), null)
            null
        }
    }

    private fun pipe(input: InputStream, out: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buffer = ByteArray(256 * 1024)
        var done = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            done += n
            if (total > 0) onProgress(done / total.toFloat())
        }
        if (done != total) throw IOException("The file was cut short")
    }

    private fun unique(dir: File, name: String): File {
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "$base (${n++})$ext")
        return file
    }

    private fun mimeType(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.takeIf { it.startsWith("video/") }
            ?: when (ext) {
                "mkv" -> "video/x-matroska"
                "ts", "m2ts" -> "video/mp2t"
                else -> "video/mp4"
            }
    }

    fun describe(e: Exception): String {
        val text = e.message.orEmpty()
        return when {
            "ENOSPC" in text || "No space" in text -> "Not enough storage space"
            e is SecurityException || "EACCES" in text -> "Storage permission is needed to save"
            text.isNotBlank() -> text
            else -> "Couldn't save the video"
        }
    }

    /** A separate notification that stays after saving; tapping it opens the video. */
    fun notifySaved(context: Context, name: String, uri: Uri?) {
        val builder = NotificationCompat.Builder(context, StreamService.CHANNEL)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Saved to $location")
            .setContentText(name)
            .setAutoCancel(true)
        if (uri != null) {
            val open = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType(name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(
                PendingIntent.getActivity(context, name.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }
        // One notification per video, so several saved in a row all show.
        context.getSystemService(NotificationManager::class.java).notify(SAVED_NOTIFICATION_ID + (name.hashCode() and 0xFFFF), builder.build())
    }
}
