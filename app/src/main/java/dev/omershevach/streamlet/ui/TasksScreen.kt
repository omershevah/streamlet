@file:OptIn(ExperimentalMaterial3Api::class)

package dev.omershevach.streamlet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.omershevach.streamlet.R
import dev.omershevach.streamlet.Saver
import dev.omershevach.streamlet.Task
import dev.omershevach.streamlet.Vlc
import dev.omershevach.streamlet.engine.formatSize

/** Every task, downloading or done, with a play button for each. */
@Composable
internal fun TasksScreen(tasks: List<Task>, onOpen: (Int) -> Unit, onRemove: (Int) -> Unit) {
    val context = LocalContext.current
    var removing by remember { mutableStateOf<Task?>(null) }

    Scaffold(topBar = { CenterAlignedTopAppBar(title = { Text("Tasks") }) }) { padding ->
        if (tasks.isEmpty()) {
            Column(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("No tasks", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Search or paste a link, pick an episode and press Start.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = padding.calculateTopPadding() + 4.dp, bottom = 16.dp, start = 16.dp, end = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                items(tasks, key = { it.id }) { task ->
                    TaskCard(
                        task,
                        onClick = { onOpen(task.id) },
                        onPlay = { Vlc.play(context, task.url, task.episode.headline) },
                        onRemove = { removing = task },
                    )
                }
            }
        }
    }

    removing?.let { task ->
        RemoveDialog(task, onConfirm = { onRemove(task.id); removing = null }, onDismiss = { removing = null })
    }
}

@Composable
private fun TaskCard(task: Task, onClick: () -> Unit, onPlay: () -> Unit, onRemove: () -> Unit) {
    val stats = task.stats
    Card(
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        shape = MaterialTheme.shapes.large,
    ) {
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 4.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    listOf(task.torrentTitle, task.episode.code).filter { it.isNotEmpty() }.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(task.episode.headline, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                if (task.complete) {
                    LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                } else if (stats != null && stats.downloaded > 0f) {
                    LinearProgressIndicator(progress = { stats.downloaded }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(6.dp))
                Text(statusLine(task), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                saveLine(task)?.let { (text, isError) ->
                    Text(
                        text,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FilledTonalIconButton(onClick = onPlay, enabled = stats?.ready == true) {
                    Icon(painterResource(R.drawable.ic_play), contentDescription = "Watch in VLC")
                }
                IconButton(onClick = onRemove, enabled = task.save !is Task.Save.Saving) {
                    Icon(painterResource(R.drawable.ic_close), contentDescription = "Remove")
                }
            }
        }
    }
}

private fun statusLine(task: Task): String {
    val s = task.stats ?: return "Starting…"
    val rate = formatSize(s.bytesPerSecond.toLong()) + "/s"
    val peers = if (s.peers == 1) "1 peer" else "${s.peers} peers"
    return when {
        task.complete -> "Downloaded · ${formatSize(task.episode.size)}"
        !s.ready && s.bytesPerSecond == 0 -> "Connecting · $peers"
        !s.ready -> "Buffering · $rate · $peers"
        else -> "${(s.downloaded * 100).toInt()}% · $rate · $peers"
    }
}

/** The "Save when finished" state in one line, or null if it's off. Second value: is it an error. */
private fun saveLine(task: Task): Pair<String, Boolean>? = when (val save = task.save) {
    is Task.Save.Saving -> "Saving to ${Saver.location} · ${(save.progress * 100).toInt()}%" to false
    Task.Save.Saved -> "Saved to ${Saver.location}" to false
    is Task.Save.Failed -> "Couldn't save: ${save.message}" to true
    Task.Save.None -> if (task.saveWhenFinished) "Saves to ${Saver.location} when finished" to false else null
}

@Composable
private fun RemoveDialog(task: Task, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove task?") },
        text = {
            Text(
                "\"${task.episode.headline}\" stops downloading and its download is deleted." +
                    if (task.save == Task.Save.Saved) " The saved copy in ${Saver.location} stays." else "",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("Remove")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** One task: progress, Watch in VLC, the next episode, Save when finished, and Remove. */
@Composable
internal fun TaskDetailScreen(
    task: Task?,
    onBack: () -> Unit,
    onNext: (Task) -> Unit,
    onRemove: (Int) -> Unit,
    onSaveWhenFinished: (Int, Boolean) -> Unit,
) {
    val context = LocalContext.current
    var vlcInstalled by remember { mutableStateOf(Vlc.isInstalled(context)) }
    LifecycleResumeEffect(Unit) {
        vlcInstalled = Vlc.isInstalled(context)
        onPauseOrDispose { }
    }
    var removing by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(task?.torrentTitle.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { BackButton(onBack) },
            )
        },
    ) { padding ->
        if (task == null) return@Scaffold // just removed; navigation follows
        val stats = task.stats
        val ready = stats?.ready == true
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
                    if (task.episode.code.isNotEmpty()) {
                        Text(task.episode.code, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(4.dp))
                    }
                    Text(task.episode.headline, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(20.dp))
                    when {
                        task.complete -> LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                        stats != null && !stats.ready && stats.startup > 0f ->
                            LinearProgressIndicator(progress = { stats.startup }, modifier = Modifier.fillMaxWidth())
                        stats != null && stats.ready ->
                            LinearProgressIndicator(progress = { stats.downloaded }, modifier = Modifier.fillMaxWidth())
                        else -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(statusLine(task), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (ready && !task.complete) {
                            Text(
                                "${formatSize(stats?.bufferedAhead ?: 0)} ahead",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(4.dp))
            Button(
                onClick = { if (vlcInstalled) Vlc.play(context, task.url, task.episode.headline) else Vlc.install(context) },
                enabled = !vlcInstalled || ready,
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.fillMaxWidth().height(56.dp),
            ) {
                Icon(painterResource(R.drawable.ic_play), contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text(
                    when {
                        !vlcInstalled -> "Install VLC"
                        ready -> "Watch in VLC"
                        else -> "Watch in VLC (buffering…)"
                    },
                )
            }
            task.next?.let { next ->
                FilledTonalButton(onClick = { onNext(task) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(
                        "Start next: " + listOf(next.code, next.headline).filter { it.isNotEmpty() }.distinct().joinToString(" · "),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            SaveWhenFinished(task, onChange = { onSaveWhenFinished(task.id, it) })
            OutlinedButton(
                onClick = { removing = true },
                enabled = task.save !is Task.Save.Saving,
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                Text("Remove task")
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Only this episode is downloaded, in playback order. It keeps going in the background until you remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
        }
        if (removing) {
            RemoveDialog(task, onConfirm = { removing = false; onRemove(task.id) }, onDismiss = { removing = false })
        }
    }
}

/** "Save when finished": copies the episode to Movies/Streamlet as soon as it's complete. */
@Composable
private fun SaveWhenFinished(task: Task, onChange: (Boolean) -> Unit) {
    val checked = task.saveWhenFinished || task.save is Task.Save.Saving || task.save == Task.Save.Saved
    // Once the copy has started there's nothing left to change.
    val locked = task.save is Task.Save.Saving || task.save == Task.Save.Saved
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
        val save = task.save
        if (save is Task.Save.Saving) {
            LinearProgressIndicator(progress = { save.progress }, modifier = Modifier.fillMaxWidth().padding(start = 48.dp, top = 4.dp, bottom = 4.dp))
        }
        val (text, isError) = saveLine(task) ?: ("Copies it to ${Saver.location} at 100%" to false)
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = when {
                isError -> MaterialTheme.colorScheme.error
                save == Task.Save.Saved -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(start = 48.dp),
        )
    }
}
