@file:OptIn(ExperimentalMaterial3Api::class)

package dev.omershevach.streamlet.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.omershevach.streamlet.Background
import dev.omershevach.streamlet.MainViewModel
import dev.omershevach.streamlet.R
import dev.omershevach.streamlet.Saver
import dev.omershevach.streamlet.Screen
import dev.omershevach.streamlet.Tab as AppTab
import dev.omershevach.streamlet.Tasks
import dev.omershevach.streamlet.engine.Episode
import dev.omershevach.streamlet.engine.Season
import dev.omershevach.streamlet.engine.formatSize
import dev.omershevach.streamlet.search.Source
import java.util.Locale

@Composable
fun StreamletApp(vm: MainViewModel) {
    val screen by vm.screen.collectAsStateWithLifecycle()
    val search by vm.search.collectAsStateWithLifecycle()
    val sources by vm.sources.collectAsStateWithLifecycle()
    val tasks by Tasks.items.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Tasks run in the background; their notification is how you see and stop them.
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

    // Saving to Movies on Android 8 and 9 needs the storage permission; ask, then carry on.
    var afterStorage by remember { mutableStateOf<(() -> Unit)?>(null) }
    val storage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) afterStorage?.invoke()
        afterStorage = null
    }
    fun withSavePermission(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            afterStorage = then
            storage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            then()
        }
    }

    // Back closes the app only from the Search tab.
    BackHandler(enabled = (screen as? Screen.Main)?.tab != AppTab.Search) { vm.back() }

    AnimatedContent(
        targetState = screen,
        contentKey = { it::class },
        transitionSpec = { fadeIn(tween(220, delayMillis = 60)) togetherWith fadeOut(tween(90)) },
        label = "screen",
    ) { s ->
        when (s) {
            is Screen.Main -> MainTabs(s.tab, taskCount = tasks.size, onSelect = vm::selectTab) { tab ->
                when (tab) {
                    AppTab.Search -> HomeScreen(
                        error = s.error,
                        search = search,
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
                        onEdit = vm::clearError,
                    )
                    AppTab.Tasks -> TasksScreen(tasks, onOpen = vm::openTask, onRemove = vm::removeTask)
                    AppTab.Sources -> SourcesScreen(
                        sources = sources,
                        onToggle = vm::setEnabled,
                        onSave = { source ->
                            vm.saveSource(source)
                            if (needsLocalNetwork(listOf(source.url))) localNetwork.launch(LOCAL_NETWORK)
                        },
                        onDelete = vm::deleteSource,
                    )
                }
            }
            is Screen.Resolving -> ResolvingScreen(s.name, onCancel = vm::back)
            is Screen.Picker -> PickerScreen(
                s,
                onBack = vm::back,
                onPick = vm::askStart,
                onDismissStart = vm::dismissStart,
                onStart = { episode, save ->
                    Background.askOnce(context) // tasks keep running with the app closed
                    if (save) withSavePermission { vm.start(episode, true) } else vm.start(episode, false)
                },
            )
            is Screen.TaskDetail -> TaskDetailScreen(
                task = tasks.firstOrNull { it.id == s.id },
                onBack = vm::back,
                onNext = vm::startNext,
                onRemove = vm::removeTask,
                onSaveWhenFinished = { id, enabled ->
                    if (enabled) withSavePermission { vm.setSaveWhenFinished(id, true) } else vm.setSaveWhenFinished(id, false)
                },
            )
        }
    }
}

private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

/** The bottom bar and the selected tab's screen above it. */
@Composable
private fun MainTabs(tab: AppTab, taskCount: Int, onSelect: (AppTab) -> Unit, content: @Composable (AppTab) -> Unit) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == AppTab.Search,
                    onClick = { onSelect(AppTab.Search) },
                    icon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                    label = { Text("Search") },
                )
                NavigationBarItem(
                    selected = tab == AppTab.Tasks,
                    onClick = { onSelect(AppTab.Tasks) },
                    icon = {
                        BadgedBox(badge = { if (taskCount > 0) Badge { Text("$taskCount") } }) {
                            Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                        }
                    },
                    label = { Text("Tasks") },
                )
                NavigationBarItem(
                    selected = tab == AppTab.Sources,
                    onClick = { onSelect(AppTab.Sources) },
                    icon = { Icon(painterResource(R.drawable.ic_tune), contentDescription = null) },
                    label = { Text("Sources") },
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding)) {
            Crossfade(targetState = tab, animationSpec = tween(150), label = "tab") { content(it) }
        }
    }
}

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
private fun PickerScreen(
    s: Screen.Picker,
    onBack: () -> Unit,
    onPick: (Episode) -> Unit,
    onDismissStart: () -> Unit,
    onStart: (Episode, Boolean) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    val season = s.seasons[tab.coerceIn(s.seasons.indices)]

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            TopAppBar(
                title = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
                scrollBehavior = scroll,
            )
        },
    ) { padding ->
        Column(Modifier.padding(top = padding.calculateTopPadding())) {
            if (s.seasons.size > 1) {
                PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 16.dp) {
                    s.seasons.forEachIndexed { i, season ->
                        Tab(selected = i == tab, onClick = { tab = i }, text = { Text(season.label) })
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

    s.confirm?.let { StartSheet(s.title, it, onDismiss = onDismissStart, onStart = onStart) }
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
        supportingContent = { Text(details(ep)) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

internal fun details(ep: Episode): String {
    val type = ep.fileName.substringAfterLast('.', "").uppercase()
    return listOf(ep.code, type, formatSize(ep.size)).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Asks before downloading anything: what it is, whether to save it when done, and Start. */
@Composable
private fun StartSheet(torrentTitle: String, episode: Episode, onDismiss: () -> Unit, onStart: (Episode, Boolean) -> Unit) {
    var save by rememberSaveable(episode.fileIndex) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                torrentTitle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(episode.headline, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(4.dp))
            Text(details(episode), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .toggleable(value = save, role = Role.Checkbox, onValueChange = { save = it })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = save, onCheckedChange = null)
                Spacer(Modifier.size(8.dp))
                Column {
                    Text("Save when finished", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Copies it to ${Saver.location} at 100%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "It downloads in playback order, so you can watch in VLC while it does, and keeps going in the background until you remove it from Tasks.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Button(onClick = { onStart(episode, save) }, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null, Modifier.size(18.dp))
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                    Text("Start")
                }
            }
        }
    }
}

@Composable
internal fun BackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = "Back")
    }
}
