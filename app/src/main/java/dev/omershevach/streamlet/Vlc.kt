package dev.omershevach.streamlet

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/** Hands the local stream URL to the VLC app. */
object Vlc {
    private const val PACKAGE = "org.videolan.vlc"

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }

    /** Returns false (and opens the store page) if VLC isn't installed. */
    fun play(context: Context, url: String, title: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .setPackage(PACKAGE)
            .setDataAndTypeAndNormalize(url.toUri(), "video/*")
            .putExtra("title", title)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            install(context)
            false
        }
    }

    fun install(context: Context) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, "market://details?id=$PACKAGE".toUri()))
        } catch (_: ActivityNotFoundException) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$PACKAGE".toUri()),
            )
        }
    }
}
