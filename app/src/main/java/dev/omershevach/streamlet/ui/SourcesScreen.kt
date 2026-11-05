@file:OptIn(ExperimentalMaterial3Api::class)

package dev.omershevach.streamlet.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.omershevach.streamlet.R
import dev.omershevach.streamlet.search.Source
import dev.omershevach.streamlet.search.SourceType

@Composable
internal fun SourcesScreen(
    sources: List<Source>,
    onToggle: (Source, Boolean) -> Unit,
    onSave: (Source) -> Unit,
    onDelete: (Source) -> Unit,
) {
    // null = closed, Source = editing it, NEW = adding
    var editing by remember { mutableStateOf<Source?>(null) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(title = { Text("Sources") })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editing = NEW },
                icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                text = { Text("Add source") },
            )
        },
    ) { padding ->
        LazyColumn(contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = 96.dp)) {
            items(sources, key = { it.id }) { s ->
                ListItem(
                    modifier = if (s.builtIn) Modifier else Modifier.clickable { editing = s },
                    headlineContent = { Text(s.name) },
                    supportingContent = { Text(s.summary) },
                    trailingContent = { Switch(checked = s.enabled, onCheckedChange = { onToggle(s, it) }) },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                )
            }
            item {
                Text(
                    "Searches go to every source that's on. Streamlet only shows what they return.\n\n" +
                        "Torznab is the search API of indexer managers like Jackett and Prowlarr. Paste its API URL and key.\n\n" +
                        "JSON works with any URL that returns a list of titles with magnet links, torrent links or " +
                        "info hashes. Put {query} where the search words go.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    editing?.let { current ->
        SourceDialog(
            initial = current.takeIf { it !== NEW },
            onDismiss = { editing = null },
            onSave = { onSave(it); editing = null },
            onDelete = { onDelete(it); editing = null },
        )
    }
}

private val NEW = Source(id = "", name = "", type = SourceType.TORZNAB)

@Composable
private fun SourceDialog(
    initial: Source?,
    onDismiss: () -> Unit,
    onSave: (Source) -> Unit,
    onDelete: (Source) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var type by rememberSaveable { mutableStateOf(initial?.type ?: SourceType.TORZNAB) }
    var url by rememberSaveable { mutableStateOf(initial?.url.orEmpty()) }
    var key by rememberSaveable { mutableStateOf(initial?.apiKey.orEmpty()) }

    val urlOk = url.trim().let { (it.startsWith("http://") || it.startsWith("https://")) && Source.host(it) != null }
    val valid = name.isNotBlank() && urlOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add source" else "Edit source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(SourceType.TORZNAB, SourceType.JSON)
                    options.forEachIndexed { i, t ->
                        SegmentedButton(
                            selected = type == t,
                            onClick = { type = t },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(t.label) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(if (type == SourceType.TORZNAB) "API URL" else "Search URL") },
                    supportingText = {
                        Text(
                            when {
                                url.isNotBlank() && !urlOk -> "Start with http:// or https://"
                                type == SourceType.TORZNAB -> "e.g. http://192.168.1.10:9117/api/v2.0/indexers/all/results/torznab"
                                else -> "e.g. https://example.com/search?q={query}"
                            },
                        )
                    },
                    isError = url.isNotBlank() && !urlOk,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (type == SourceType.TORZNAB) {
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (initial != null) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { onDelete(initial) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete source") }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val apiKey = if (type == SourceType.TORZNAB) key else ""
                    onSave(
                        initial?.copy(name = name.trim(), type = type, url = url.trim(), apiKey = apiKey.trim())
                            ?: Source.new(name, type, url, apiKey),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
