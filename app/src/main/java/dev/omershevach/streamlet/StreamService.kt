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
import dev.omershevach.streamlet.engine.formatSize

/**
 * Keeps the app process (and so the torrents, the local server VLC reads from, and any save
 * in progress) alive while there are tasks, whether the app is open or not. One notification
 * sums them up, with a Stop all action.
 */
class StreamService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) Tasks.removeAll(this)
        if (Tasks.isEmpty) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "Tasks", NotificationManager.IMPORTANCE_LOW),
            )
        }
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        running = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    // Android 15+ limits data-sync services to about 6 hours a day. Tasks stay listed and pick
    // up again (the service restarts) the next time the app is opened.
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    companion object {
        const val CHANNEL = "streaming"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP_ALL = "stop_all"

        @Volatile private var running = false

        /** Starts, refreshes or stops the service to match the task list. */
        fun update(context: Context) {
            if (Tasks.isEmpty) {
                if (running) context.stopService(Intent(context, StreamService::class.java))
                return
            }
            if (!running) {
                try {
                    ContextCompat.startForegroundService(context, Intent(context, StreamService::class.java))
                } catch (_: Exception) {
                    // Not allowed from the background (after a system timeout); retried when the app opens.
                }
                return
            }
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(context))
        }

        private fun build(context: Context): Notification {
            val tasks = Tasks.items.value
            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val stopAll = PendingIntent.getService(
                context, 1,
                Intent(context, StreamService::class.java).setAction(ACTION_STOP_ALL),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val saving = tasks.firstNotNullOfOrNull { it.save as? Task.Save.Saving }
            val downloading = tasks.filter { !it.complete }
            val rate = tasks.sumOf { it.stats?.bytesPerSecond ?: 0 }
            val one = tasks.singleOrNull()
            val title = when {
                saving != null -> "Saving to ${Saver.location}"
                one != null -> one.episode.headline
                else -> "${tasks.size} tasks"
            }
            val text = when {
                downloading.isEmpty() -> if (one != null) "Downloaded" else "All downloaded"
                one != null -> "${((one.stats?.downloaded ?: 0f) * 100).toInt()}% · ${formatSize(rate.toLong())}/s"
                else -> "${downloading.size} downloading · ${formatSize(rate.toLong())}/s"
            }
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(if (downloading.isEmpty() && saving == null) R.drawable.ic_play else R.drawable.ic_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openApp)
                .addAction(R.drawable.ic_stop, "Stop all", stopAll)
                .setOngoing(true)
                .setSilent(true)
                .setOnlyAlertOnce(true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            when {
                saving != null -> builder.setProgress(100, (saving.progress * 100).toInt(), false)
                one != null && !one.complete -> builder.setProgress(100, ((one.stats?.downloaded ?: 0f) * 100).toInt(), false)
            }
            return builder.build()
        }
    }
}
