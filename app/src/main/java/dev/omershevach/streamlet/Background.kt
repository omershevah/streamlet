package dev.omershevach.streamlet

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.edit
import androidx.core.net.toUri

/**
 * Background downloads already run in a foreground service, but battery optimization (and
 * some phone makers' extra task killers) can still pause or stop them. This asks Android, once,
 * to let the app run in the background without battery limits.
 */
object Background {
    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    /** Shows the system "Allow app to always run in background?" dialog, the first time only. */
    @SuppressLint("BatteryLife") // A download app the user is sideloading; asked once, on a clear action.
    fun askOnce(context: Context) {
        if (isUnrestricted(context)) return
        val prefs = context.getSharedPreferences("background", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked", false)) return
        prefs.edit { putBoolean("asked", true) }
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        try {
            context.startActivity(request)
        } catch (_: ActivityNotFoundException) {
            // Some builds hide the dialog; the list in Settings works everywhere.
            runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        }
    }
}
