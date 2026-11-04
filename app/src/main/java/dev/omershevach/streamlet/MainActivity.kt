package dev.omershevach.streamlet

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.omershevach.streamlet.engine.TorrentEngine
import dev.omershevach.streamlet.ui.StreamletApp
import dev.omershevach.streamlet.ui.theme.StreamletTheme

class StreamletApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        TorrentEngine.init(this)
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handleLink(intent)
        setContent {
            StreamletTheme { StreamletApp(viewModel) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** Magnet links tapped in a browser, or text shared to the app. */
    private fun handleLink(intent: Intent?) {
        val link = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        viewModel.open(link)
    }
}
