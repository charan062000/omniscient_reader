package eu.kanade.tachiyomi.ui.readerlibrary

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import coil3.compose.AsyncImage
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.epub.EpubLibraryService
import eu.kanade.tachiyomi.ui.epub.EpubReaderScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.flowOf
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.roundedfilled.Favorite
import omniscient.reader.docs.DocStore
import omniscient.reader.docs.DocsActivity
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType

/** Shared entry point for personal reading content; manga continues to use Mihon's original library. */
data object ReaderLibraryScreen : Screen() {
    @Composable
    override fun Content() {
        ReaderLibraryContent()
    }
}

private enum class ReaderSection(val label: String) {
    MANGA("Manga"),
    NOVELS("Novels / EPUBs"),
    PDF("PDFs"),
    LOCAL("Local media"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderLibraryContent() {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val repository = context.appGraph.readerLibraryRepository
    val scope = rememberCoroutineScope()
    val content by repository.observeAll().collectAsState(initial = emptyList())
    val collections by repository.observeCollections().collectAsState(initial = emptyList())
    var section by remember { mutableStateOf(ReaderSection.NOVELS) }
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var recentFirst by remember { mutableStateOf(false) }
    var selectedCollectionId by remember { mutableStateOf<Long?>(null) }
    var collectionMenuOpen by remember { mutableStateOf(false) }
    var createCollectionDialog by remember { mutableStateOf(false) }
    var collectionName by remember { mutableStateOf("") }
    var importError by remember { mutableStateOf<String?>(null) }
    val contentInCollection by (
        selectedCollectionId?.let(repository::observeContentIdsInCollection) ?: flowOf(emptySet())
    ).collectAsState(initial = emptySet())

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            scope.launch {
                val errors = withContext(Dispatchers.IO) {
                    uris.mapNotNull { uri -> runCatching { importContent(context, repository, section, uri, selectedCollectionId) }.exceptionOrNull() }
                }
                if (errors.isNotEmpty()) importError = errors.joinToString("\n") { it.message ?: "The selected file could not be imported." }
            }
        }
    }

    val visibleItems = remember(content, contentInCollection, selectedCollectionId, section, query, favoritesOnly, recentFirst) {
        content.asSequence()
            .filter { it.matches(section) }
            .filter { selectedCollectionId == null || it.id in contentInCollection }
            .filter { !favoritesOnly || it.favorite }
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .sortedWith(
                compareByDescending<ReaderContent> { it.favorite }
                    .thenByDescending { if (recentFirst) it.lastOpenedAt ?: it.addedAt else 0L }
                    .thenBy { if (recentFirst) "" else it.title.lowercase() },
            )
            .toList()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Omniscient Reader") })
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ReaderSection.entries.forEach { target ->
                    AssistChip(
                        onClick = {
                            if (target == ReaderSection.MANGA) {
                                scope.launch {
                                    navigator.pop()
                                    HomeScreen.openTab(HomeScreen.Tab.Library())
                                }
                            } else {
                                section = target
                            }
                        },
                        label = { Text(target.label) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { collectionMenuOpen = true }) {
                    Text(collections.firstOrNull { it.id == selectedCollectionId }?.name ?: "All categories")
                }
                DropdownMenu(expanded = collectionMenuOpen, onDismissRequest = { collectionMenuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("All categories") },
                        onClick = {
                            selectedCollectionId = null
                            collectionMenuOpen = false
                        },
                    )
                    collections.forEach { collection ->
                        DropdownMenuItem(
                            text = { Text(collection.name) },
                            onClick = {
                                selectedCollectionId = collection.id
                                collectionMenuOpen = false
                            },
                        )
                    }
                }
                TextButton(onClick = { createCollectionDialog = true }) { Text("New category") }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Search this library") },
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButtonCompat(
                    label = if (favoritesOnly) "Showing favorites" else "Show favorites",
                    onClick = { favoritesOnly = !favoritesOnly },
                )
                TextButtonCompat(
                    label = if (recentFirst) "Sort: recent" else "Sort: A-Z",
                    onClick = { recentFirst = !recentFirst },
                )
                if (section == ReaderSection.PDF) {
                    Button(onClick = { context.startActivity(DocsActivity.newIntent(context)) }) {
                        Text("Open PDF reader")
                    }
                }
            }

            if (section != ReaderSection.PDF) {
                Button(onClick = { picker.launch(mimeTypes(section)) }) {
                    Text(if (section == ReaderSection.LOCAL) "Import media" else "Import books")
                }
            }

            if (visibleItems.isEmpty()) {
                Text(
                    text = when (section) {
                        ReaderSection.NOVELS -> "Imported EPUB and text books will appear here."
                        ReaderSection.PDF -> "PDFs imported from the PDF reader appear here."
                        ReaderSection.LOCAL -> "Imported images, GIFs, videos and archives will appear here."
                        ReaderSection.MANGA -> "Manga remains in Mihon's Library."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleItems, key = ReaderContent::id) { item ->
                        ReaderContentCard(
                            item = item,
                            onFavorite = {
                                scope.launch { repository.setFavorite(item.id, !item.favorite) }
                            },
                            onOpen = {
                                when (item.type) {
                                    ReaderContentType.PDF -> context.startActivity(DocsActivity.newIntent(context))
                                    ReaderContentType.EPUB, ReaderContentType.TEXT -> navigator.push(EpubReaderScreen(item.id))
                                    else -> Unit
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (createCollectionDialog) {
        AlertDialog(
            onDismissRequest = { createCollectionDialog = false },
            title = { Text("New category") },
            text = {
                OutlinedTextField(
                    value = collectionName,
                    onValueChange = { collectionName = it },
                    singleLine = true,
                    label = { Text("Category name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = collectionName.isNotBlank(),
                    onClick = {
                        val name = collectionName.trim()
                        scope.launch {
                            repository.createCollection(name)
                            collectionName = ""
                            createCollectionDialog = false
                        }
                    },
                ) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { createCollectionDialog = false }) { Text("Cancel") } },
        )
    }

    importError?.let { message ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Import failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun TextButtonCompat(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun ReaderContentCard(item: ReaderContent, onFavorite: () -> Unit, onOpen: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().then(
            if (item.type == ReaderContentType.PDF || item.type == ReaderContentType.EPUB || item.type == ReaderContentType.TEXT) Modifier.clickable(onClick = onOpen) else Modifier,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.coverUri != null) {
                AsyncImage(model = item.coverUri, contentDescription = null, modifier = Modifier.size(48.dp))
            } else {
                Icon(
                    imageVector = MaterialSymbols.Rounded.LocalLibrary,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.titleMedium)
                item.author?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text(
                    if (item.progressTotal > 0) {
                        "${item.type.name} • ${item.progress}/${item.progressTotal}"
                    } else {
                        item.type.name
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(
                    imageVector = if (item.favorite) MaterialSymbols.RoundedFilled.Favorite else MaterialSymbols.Rounded.Favorite,
                    contentDescription = if (item.favorite) "Remove from favorites" else "Add to favorites",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun ReaderContent.matches(section: ReaderSection): Boolean = when (section) {
    ReaderSection.MANGA -> false
    ReaderSection.NOVELS -> type == ReaderContentType.EPUB || type == ReaderContentType.TEXT
    ReaderSection.PDF -> type == ReaderContentType.PDF
    ReaderSection.LOCAL -> type in setOf(
        ReaderContentType.IMAGE,
        ReaderContentType.GIF,
        ReaderContentType.VIDEO,
        ReaderContentType.ARCHIVE,
    )
}

private fun mimeTypes(section: ReaderSection): Array<String> = when (section) {
    ReaderSection.NOVELS -> arrayOf("application/epub+zip", "text/plain", "application/octet-stream")
    ReaderSection.PDF -> arrayOf("application/pdf")
    ReaderSection.LOCAL -> arrayOf("image/*", "video/*", "application/zip", "application/x-rar-compressed", "application/x-7z-compressed")
    ReaderSection.MANGA -> emptyArray()
}

private suspend fun importContent(
    context: android.content.Context,
    repository: tachiyomi.domain.library.content.repository.ReaderLibraryRepository,
    section: ReaderSection,
    uri: Uri,
    collectionId: Long?,
) {
    runCatching {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val mime = context.contentResolver.getType(uri).orEmpty()
    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        ?: uri.lastPathSegment?.substringAfterLast('/')
        ?: "Untitled"
    val type = when (section) {
        ReaderSection.NOVELS -> if (name.endsWith(".txt", true) || mime.startsWith("text/")) ReaderContentType.TEXT else ReaderContentType.EPUB
        ReaderSection.PDF -> ReaderContentType.PDF
        ReaderSection.LOCAL -> when {
            mime == "image/gif" || name.endsWith(".gif", true) -> ReaderContentType.GIF
            mime.startsWith("image/") -> ReaderContentType.IMAGE
            mime.startsWith("video/") -> ReaderContentType.VIDEO
            else -> ReaderContentType.ARCHIVE
        }
        ReaderSection.MANGA -> return
    }

    if (type == ReaderContentType.EPUB || type == ReaderContentType.TEXT) {
        val service = EpubLibraryService(context, repository)
        val imported = if (type == ReaderContentType.EPUB) service.import(uri) else service.importText(uri)
        collectionId?.let { repository.addToCollection(imported.id, it) }
        return
    }

    val contentId = if (type == ReaderContentType.PDF) {
        val imported = DocStore(context).importPdf(uri)
        repository.upsert(
            ReaderContent(
                id = "pdf:${imported.id}",
                type = type,
                title = imported.title,
                uri = imported.uri,
                coverUri = DocStore(context).coverFile(imported.id).takeIf { it.exists() }?.toURI()?.toString(),
                favorite = false,
                addedAt = imported.addedAt,
                lastOpenedAt = null,
                progress = imported.lastPage.toLong(),
                progressTotal = imported.pageCount.toLong(),
            ),
        )
        "pdf:${imported.id}"
    } else {
        val id = "${type.name.lowercase()}:${uri}"
        repository.upsert(
            ReaderContent(
                id = id,
                type = type,
                title = name.substringBeforeLast('.', name),
                uri = uri.toString(),
                coverUri = uri.toString().takeIf { type == ReaderContentType.IMAGE || type == ReaderContentType.GIF },
                favorite = false,
                addedAt = System.currentTimeMillis(),
                lastOpenedAt = null,
                progress = 0,
                progressTotal = 0,
            ),
        )
        id
    }
    collectionId?.let { repository.addToCollection(contentId, it) }
}
