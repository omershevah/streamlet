package dev.omershevach.streamlet

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.omershevach.streamlet.engine.TorrentEngine

/**
 * Keeps the app process (and so the torrent, the local server and any background download)
 * alive while VLC is in front or the app is closed. Runs while a stream is open or an episode
 * marked "Save when finished" is still downloading or saving, with one notification for both.
 */
class StreamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TorrentEngine.stopStream()
            streamTitle = null
            if (!Downloads.active) {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW),
            )
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        return START_NOT_STICKY
    }

    // Android 15 limits data-sync services to 6 hours a day.
    override fun onTimeout(startId: Int, fgsType: Int) {
        TorrentEngine.stopStream()
        streamTitle = null
        stopSelf()
    }

    companion object {
        const val CHANNEL = "streaming"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"

        /** What's streaming, or null. Set from the main thread, read when building the notification. */
        @Volatile private var streamTitle: String? = null

        fun start(context: Context, title: String) {
            streamTitle = title
            ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
        }

        /** The stream ended. The service keeps running if a save is still going. */
        fun stop(context: Context) {
            streamTitle = null
            refresh(context)
        }

        /** Updates the notification, or stops the service when there's nothing left to do. */
        fun refresh(context: Context) {
            if (streamTitle == null && !Downloads.active) {
                context.stopService(Intent(context, StreamService::class.java))
            } else {
                context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(context))
            }
        }

        private fun build(context: Context): Notification {
            val stream = streamTitle
            val active = Downloads.items.value.filter { it.active }
            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(if (stream != null) R.drawable.ic_play else R.drawable.ic_download)
                .setContentIntent(openApp)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            if (stream != null) {
                val stop = PendingIntent.getService(
                    context, 1,
                    Intent(context, StreamService::class.java).setAction(ACTION_STOP),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                builder.setContentTitle("Streaming").setContentText(stream).addAction(R.drawable.ic_stop, "Stop", stop)
                if (active.isNotEmpty()) builder.setSubText("${active.size} to save")
            } else if (active.isNotEmpty()) {
                val one = active.singleOrNull()
                val saving = one?.status as? Download.Status.Saving
                val percent = ((saving?.progress ?: (active.sumOf { it.downloaded }.toFloat() / active.sumOf { it.size }.coerceAtLeast(1))) * 100).toInt()
                builder.setContentTitle(
                    when {
                        saving != null -> "Saving to ${Saver.location}"
                        one != null -> "Downloading to save"
                        else -> "Downloading ${active.size} videos to save"
                    },
                )
                    .setContentText(one?.name ?: active.joinToString { it.name })
                    .setProgress(100, percent, false)
            }
            return builder.build()
        }
    }
}
