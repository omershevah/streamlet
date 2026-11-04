package dev.omershevach.streamlet

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
 * Keeps the app process (and so the torrent and the local server) alive while
 * VLC is in the foreground playing from it.
 */
class StreamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TorrentEngine.stopStream()
            stopSelf()
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Streaming", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_play)
            .setContentTitle("Streaming")
            .setContentText(intent?.getStringExtra(EXTRA_TITLE))
            .setContentIntent(openApp)
            .addAction(R.drawable.ic_stop, "Stop", stop)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        return START_NOT_STICKY
    }

    // Android 15 limits data-sync services to 6 hours a day.
    override fun onTimeout(startId: Int, fgsType: Int) {
        TorrentEngine.stopStream()
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "streaming"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "stop"
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String) = ContextCompat.startForegroundService(
            context,
            Intent(context, StreamService::class.java).putExtra(EXTRA_TITLE, title),
        )

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamService::class.java))
        }
    }
}
