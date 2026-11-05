@file:OptIn(ExperimentalMaterial3Api::class)

package dev.omershevach.streamlet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.Checkbox
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.omershevach.streamlet.MainViewModel
import dev.omershevach.streamlet.R
import dev.omershevach.streamlet.Background
import dev.omershevach.streamlet.Download
import dev.omershevach.streamlet.Downloads
import dev.omershevach.streamlet.Saver
import dev.omershevach.streamlet.Screen
import dev.omershevach.streamlet.Vlc
import dev.omershevach.streamlet.engine.Episode
import dev.omershevach.streamlet.engine.Season
import dev.omershevach.streamlet.engine.StreamStats
import dev.omershevach.streamlet.engine.formatSize
import dev.omershevach.streamlet.search.Source
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun StreamletApp(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // The notification is how you stop a stream while VLC is in front, so ask for it on first use.
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun askNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Android 17 asks before an app can reach the local network (a Jackett or Prowlarr at home).
    // Ask when a source on the local network is about to be used, then carry on with the search.
    var pendingQuery by remember { mutableStateOf<String?>(null) }
    val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingQuery?.let(vm::search)
        pendingQuery = null
    }
    fun needsLocalNetwork(urls: List<String>) = Build.VERSION.SDK_INT >= 37 &&
        urls.any(Source::isLocal) &&
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED

    val downloads by Downloads.items.collectAsStateWithLifecycle()

    // Ticking "Save when finished": on Android 8 and 9 saving to Movies needs the storage
    // permission, and the first time, ask Android to let the app run in the background
    // without battery limits so long downloads aren't cut off.
    var pendingSaveId by remember { mutableStateOf<String?>(null) }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        pendingSaveId?.let { if (granted) vm.setSaveWhenFinished(true, it) }
        pendingSaveId = null
    }
    fun saveWhenFinished(enabled: Boolean, id: String) {
        if (!enabled) {
            vm.setSaveWhenFinished(false, id)
            return
        }
        askNotifications()
        Background.askOnce(context)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingSaveId = id
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            vm.setSaveWhenFinished(true, id)
        }
    }

    BackHandler(enabled = screen !is Screen.Home) { vm.back() }

    AnimatedContent(
        targetState = screen,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
        label = "screen",
    ) { s ->
        when (s) {
            is Screen.Home -> HomeScreen(
                error = s.error,
                search = search,
                downloads = downloads,
                onRemoveDownload = vm::removeDownload,
                onOpen = { askNotifications(); vm.open(it) },
                onSearch = { query ->
                    if (needsLocalNetwork(sources.filter { it.enabled }.map { it.url })) {
                        pendingQuery = query
                        localNetwork.launch(LOCAL_NETWORK)
                    } else {
                        vm.search(query)
                    }
                },
                onClear = vm::clearSearch,
                onPick = { askNotifications(); vm.openResult(it) },
                onSources = vm::showSources,
                onEdit = vm::clearError,
            )
            is Screen.Sources -> SourcesScreen(
                sources = sources,
                onBack = vm::back,
                onToggle = vm::setEnabled,
                onSave = { source ->
                    vm.saveSource(source)
                    if (needsLocalNetwork(listOf(source.url))) localNetwork.launch(LOCAL_NETWORK)
                },
                onDelete = vm::deleteSource,
            )
            is Screen.Resolving -> ResolvingScreen(s.name, onCancel = vm::back)
            is Screen.Picker -> PickerScreen(s.title, s.seasons, onBack = vm::back, onPick = vm::play)
            is Screen.Playing -> PlayingScreen(
                s, stats,
                download = downloads.firstOrNull { it.id == s.file },
                onBack = vm::back,
                onNext = vm::play,
                onSaveWhenFinished = { saveWhenFinished(it, s.file) },
            )
        }
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

@Composable
private fun ResolvingScreen(name: String?, onCancel: () -> Unit) {
    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(24.dp))
            Text(
                name ?: "Opening link",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Finding peers and fetching the file list…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun PickerScreen(title: String, seasons: List<Season>, onBack: () -> Unit, onPick: (Episode) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val season = seasons[tab.coerceIn(seasons.indices)]

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            if (seasons.size > 1) {
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
                    seasons.forEachIndexed { i, s ->
                        Tab(selected = i == tab, onClick = { tab = i }, text = { Text(s.label) })
                    }
                }
            }
            LazyColumn(
                contentPadding = PaddingValues(
                    top = 8.dp,
                    bottom = 8.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
                ),
            ) {
                items(season.episodes, key = { it.fileIndex }) { ep -> EpisodeRow(ep, onClick = { onPick(ep) }) }
            }
        }
    }
}

@Composable
private fun EpisodeRow(ep: Episode, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    Text(
                        ep.number?.let { String.format(Locale.ROOT, "%02d", it) } ?: "•",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        headlineContent = { Text(ep.headline, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            val type = ep.fileName.substringAfterLast('.', "").uppercase()
            Text(listOf(ep.code, type, formatSize(ep.size)).filter { it.isNotEmpty() }.joinToString(" · "))
        },
        trailingContent = {
            Icon(painterResource(R.drawable.ic_play), contentDescription = "Play", Modifier.size(20.dp))
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun PlayingScreen(
    s: Screen.Playing,
    stats: StreamStats?,
    download: Download?,
    onBack: () -> Unit,
    onNext: (Episode) -> Unit,
    onSaveWhenFinished: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var vlcInstalled by remember { mutableStateOf(Vlc.isInstalled(context)) }
    LifecycleResumeEffect(Unit) {
        vlcInstalled = Vlc.isInstalled(context)
        onPauseOrDispose { }
    }

    // Hand off to VLC by itself as soon as the start of the file is in.
    val ready = stats?.ready == true
    var handedOff by rememberSaveable(s.url) { mutableStateOf(false) }
    LaunchedEffect(ready, vlcInstalled, s.url) {
        if (ready && vlcInstalled && !handedOff) {
            handedOff = true
            Vlc.play(context, s.url, s.episode.headline)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                shape = MaterialTheme.shapes.extraLarge,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(24.dp)) {
                    if (s.episode.code.isNotEmpty()) {
                        Text(
                            s.episode.code,
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(s.episode.headline, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(20.dp))
                    StreamProgress(stats)
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { if (vlcInstalled) Vlc.play(context, s.url, s.episode.headline) else Vlc.install(context) },
                enabled = !vlcInstalled || ready,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(if (vlcInstalled) "Watch in VLC" else "Install VLC")
            }
            s.next?.let { next ->
                FilledTonalButton(onClick = { onNext(next) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(
                        "Next: " + listOf(next.code, next.headline).filter { it.isNotEmpty() }.distinct()
                            .joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SaveWhenFinished(download, onSaveWhenFinished)
            OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Stop")
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Only this episode is downloaded, in playback order. Downloads are cleared when you leave this torrent, unless they're set to save.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
    }
}

@Composable
private fun StreamProgress(stats: StreamStats?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    when {
        stats == null || (!stats.ready && stats.startup == 0f && stats.bytesPerSecond == 0) -> {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                if (stats == null) "Connecting…" else "Connecting · ${peers(stats.peers)}",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }
        !stats.ready -> {
            LinearProgressIndicator(progress = { stats.startup }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(
                "Buffering · ${rate(stats.bytesPerSecond)} · ${peers(stats.peers)}",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
            )
        }
        else -> {
            LinearProgressIndicator(progress = { stats.downloaded }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${(stats.downloaded * 100).toInt()}% · ${formatSize(stats.bufferedAhead)} ahead",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
                Text(
                    "${rate(stats.bytesPerSecond)} · ${peers(stats.peers)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = muted,
                )
            }
        }
    }
}

/**
 * "Save when finished": once ticked, the episode keeps downloading even after you leave it or
 * close the app, and is copied to Movies/Streamlet at 100%. Shows how that's going underneath.
 */
@Composable
private fun SaveWhenFinished(download: Download?, onChange: (Boolean) -> Unit) {
    val status = download?.status
    val checked = download != null && status !is Download.Status.Failed
    // Once the copy has started there's nothing left to cancel.
    val locked = status is Download.Status.Saving || status == Download.Status.Saved
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .toggleable(value = checked, enabled = !locked, role = Role.Checkbox, onValueChange = onChange)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = checked, onCheckedChange = null, enabled = !locked)
            Spacer(Modifier.size(8.dp))
            Text("Save when finished", style = MaterialTheme.typography.bodyLarge)
        }
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        when (status) {
            null -> Text(
                "Copies it to ${Saver.location} at 100%. It keeps downloading if you leave or close the app.",
                style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(start = 48.dp),
            )
            Download.Status.Downloading -> Text(
                "Will save to ${Saver.location} at 100%. Keeps downloading in the background.",
                style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(start = 48.dp),
            )
            is Download.Status.Saving -> Column(Modifier.padding(start = 48.dp)) {
                LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                Text("Saving to ${Saver.location} · ${(status.progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall, color = muted)
            }
            Download.Status.Saved -> Text(
                "Saved to ${Saver.location}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 48.dp),
            )
            is Download.Status.Failed -> Text(
                "Couldn't save: ${status.message}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 48.dp),
            )
        }
    }
}

@Composable
internal fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
    }
}

private fun rate(bytesPerSecond: Int) = formatSize(bytesPerSecond.toLong()) + "/s"

private fun peers(n: Int) = if (n == 1) "1 peer" else "$n peers"
