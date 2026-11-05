@file:OptIn(ExperimentalMaterial3Api::class)

package dev.omershevach.streamlet.ui

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
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.omershevach.streamlet.R
import dev.omershevach.streamlet.SearchState
import dev.omershevach.streamlet.SourceResults
import dev.omershevach.streamlet.engine.Magnet
import dev.omershevach.streamlet.engine.formatSize
import dev.omershevach.streamlet.search.SearchResult
import kotlinx.coroutines.launch

/**
 * One field for both jobs: a magnet link opens straight away, anything else is a search
 * across the enabled sources. Results are grouped by source, each filling in as it answers.
 */
@Composable
internal fun HomeScreen(
    error: String?,
    search: SearchState,
    onOpen: (String) -> Unit,
    onSearch: (String) -> Unit,
    onClear: () -> Unit,
    onPick: (SearchResult) -> Unit,
    onEdit: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(search.query) }
    val clipboard = LocalClipboard.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    fun submit(value: String) {
        if (value.isBlank()) return
        keyboard?.hide()
        if (Magnet.normalize(value) != null) onOpen(value) else onSearch(value)
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Streamlet") })
        },
    ) { padding ->
        Column(
            Modifier
                .padding(top = padding.calculateTopPadding())
                .imePadding()
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; onEdit() },
                placeholder = { Text("Search or paste a magnet link", maxLines = 1) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                trailingIcon = {
                    if (text.isEmpty()) {
                        IconButton(onClick = {
                            scope.launch {
                                val clip = clipboard.getClipEntry()?.clipData?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) {
                                    text = clip.trim()
                                    submit(text)
                                }
                            }
                        }) { Icon(painterResource(R.drawable.ic_paste), contentDescription = "Paste") }
                    } else {
                        IconButton(onClick = { text = ""; onEdit(); onClear() }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = "Clear")
                        }
                    }
                },
                isError = error != null,
                supportingText = if (error != null) {
                    { Text(error) }
                } else {
                    null
                },
                singleLine = true,
                shape = CircleShape,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit(text) }),
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )

            when {
                search.groups.isNotEmpty() -> Results(search.groups, onPick, Modifier.widthIn(max = 640.dp))
                search.query.isNotEmpty() -> NoSources() // searched, but every source is off
                else -> Welcome()
            }
        }
    }
}

@Composable
private fun Results(groups: List<SourceResults>, onPick: (SearchResult) -> Unit, modifier: Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        groups.forEach { group ->
            item(key = "h-" + group.source.id) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(group.source.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    if (group.loading) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else if (group.error == null) {
                        Text(
                            if (group.results.size == 1) "1 result" else "${group.results.size} results",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            when {
                group.error != null -> item(key = "e-" + group.source.id) {
                    Message(group.error, MaterialTheme.colorScheme.error)
                }
                !group.loading && group.results.isEmpty() -> item(key = "n-" + group.source.id) {
                    Message("Nothing found", MaterialTheme.colorScheme.onSurfaceVariant)
                }
                else -> items(group.results, key = { group.source.id + "|" + it.link }) { r ->
                    ResultRow(r, onClick = { onPick(r) })
                }
            }
        }
    }
}

@Composable
private fun ResultRow(r: SearchResult, onClick: () -> Unit) {
    val details = listOfNotNull(
        r.year,
        r.size?.takeIf { it > 0 }?.let(::formatSize),
        r.seeders?.let { if (it == 1) "1 seeder" else "$it seeders" },
    )
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        headlineContent = { Text(r.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = if (details.isNotEmpty()) {
            { Text(details.joinToString(" · ")) }
        } else {
            null
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
    )
}

@Composable
private fun Message(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = color,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun Welcome() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(
                    painterResource(R.drawable.ic_play),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.padding(20.dp).size(36.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            Text("Find something to watch", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(8.dp))
            Text(
                "Search the Internet Archive and your own sources, or paste a magnet link. Pick an episode, press Start, and watch it in VLC.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(72.dp)) // sits a little above center, clear of the keyboard
        }
    }
}

@Composable
private fun NoSources() {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("All sources are switched off", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Turn one on in the Sources tab. Magnet links still work.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
