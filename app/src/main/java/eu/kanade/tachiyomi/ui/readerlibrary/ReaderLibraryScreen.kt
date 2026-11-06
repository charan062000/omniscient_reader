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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
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
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Download
import mihon.icons.materialsymbols.roundedfilled.Favorite
import omniscient.reader.docs.DocsActivity
import omniscient.reader.docs.PdfLibraryService
import omniscient.reader.localmedia.LocalMediaService
import omniscient.reader.localmedia.LocalMediaKind
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
    var pdfMigrationError by remember { mutableStateOf<String?>(null) }
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
    var imageViewerItems by remember { mutableStateOf<List<ReaderContent>?>(null) }
    var imageViewerIndex by remember { mutableIntStateOf(0) }
    var gifViewerItems by remember { mutableStateOf<List<ReaderContent>?>(null) }
    var gifViewerIndex by remember { mutableIntStateOf(0) }
    var videoViewerItem by remember { mutableStateOf<ReaderContent?>(null) }
    var archiveViewerItem by remember { mutableStateOf<ReaderContent?>(null) }
    var pendingRemoval by remember { mutableStateOf<ReaderContent?>(null) }
    var pendingExport by remember { mutableStateOf<ReaderContent?>(null) }
    var collectionCoverUri by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    var localGridView by remember { mutableStateOf(true) }
    var localTypeFilter by remember { mutableStateOf<ReaderContentType?>(null) }
    val contentInCollection by (
        selectedCollectionId?.let(repository::observeContentIdsInCollection) ?: flowOf(emptySet())
    ).collectAsState(initial = emptySet())

    LaunchedEffect(Unit) {
        runCatching { PdfLibraryService(context, repository).migrateLegacyDocuments(omniscient.reader.docs.DocStore(context)) }
            .onFailure { pdfMigrationError = it.message ?: "Legacy PDFs could not be migrated." }
    }

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
    val imageFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            val result = withContext(Dispatchers.IO) { LocalMediaService(context, repository).scanImageFolder(uri) }
            if (result.errors.isNotEmpty()) scanError = "Imported ${result.imported} of ${result.scanned} scanned files.\n" + result.errors.take(20).joinToString("\n")
            else android.widget.Toast.makeText(context, "Imported ${result.imported} media files", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val collectionId = selectedCollectionId
        if (uri != null && collectionId != null) scope.launch {
            runCatching {
                val cover = withContext(Dispatchers.IO) { LocalMediaService(context, repository).importImage(uri, collectionId) }
                context.getSharedPreferences("local_media", 0).edit().putString("collection_cover_$collectionId", cover.uri).apply()
                collectionCoverUri = cover.uri
            }.onFailure { importError = it.message ?: "The folder cover could not be saved." }
        }
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { destination ->
        val sourceContent = pendingExport
        pendingExport = null
        if (destination != null && sourceContent != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(Uri.parse(sourceContent.uri))?.use { input ->
                        context.contentResolver.openOutputStream(destination, "w")?.use { output -> input.copyTo(output) }
                            ?: error("The selected export location cannot be written")
                    } ?: error("This media file is no longer available")
                }
            }.onFailure { importError = it.message ?: "The media could not be exported." }
        }
    }
    LaunchedEffect(selectedCollectionId) {
        collectionCoverUri = selectedCollectionId?.let { context.getSharedPreferences("local_media", 0).getString("collection_cover_$it", null) }
    }

    val visibleItems = remember(content, contentInCollection, selectedCollectionId, section, query, favoritesOnly, recentFirst, localTypeFilter) {
        content.asSequence()
            .filter { it.matches(section) }
            .filter { section != ReaderSection.LOCAL || localTypeFilter == null || it.type == localTypeFilter }
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

            if (section == ReaderSection.LOCAL) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(null to "All", ReaderContentType.IMAGE to "Images", ReaderContentType.GIF to "GIFs", ReaderContentType.VIDEO to "Videos", ReaderContentType.ARCHIVE to "Archives").forEach { (type, label) ->
                        AssistChip(onClick = { localTypeFilter = type }, label = { Text(if (localTypeFilter == type) "• $label" else label) })
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                collectionCoverUri?.let { cover -> AsyncImage(model = cover, contentDescription = "Category cover", modifier = Modifier.size(36.dp)) }
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
                if (selectedCollectionId != null) TextButton(onClick = { coverPicker.launch(arrayOf("image/*")) }) { Text("Set cover") }
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
                    TextButtonCompat(label = "Open Documents", onClick = { context.startActivity(DocsActivity.newIntent(context)) })
                }
                if (section == ReaderSection.LOCAL) TextButtonCompat(label = if (localGridView) "View: grid" else "View: list", onClick = { localGridView = !localGridView })
            }

            if (section != ReaderSection.MANGA) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { picker.launch(mimeTypes(section)) }) {
                        Text(when (section) {
                            ReaderSection.NOVELS -> "Import books"
                            ReaderSection.PDF -> "Import PDFs"
                            ReaderSection.LOCAL -> "Import media"
                            ReaderSection.MANGA -> "Manga"
                        })
                    }
                    if (section == ReaderSection.LOCAL) Button(onClick = { imageFolderPicker.launch(null) }) { Text("Scan device folder") }
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
                val openItem: (ReaderContent) -> Unit = { item ->
                    when (item.type) {
                        ReaderContentType.PDF -> context.startActivity(DocsActivity.newIntent(context, item.id))
                        ReaderContentType.EPUB, ReaderContentType.TEXT -> navigator.push(EpubReaderScreen(item.id))
                        ReaderContentType.IMAGE -> {
                            val images = visibleItems.filter { it.type == ReaderContentType.IMAGE }
                            imageViewerItems = images
                            imageViewerIndex = images.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        }
                        ReaderContentType.GIF -> {
                            val gifs = visibleItems.filter { it.type == ReaderContentType.GIF }
                            gifViewerItems = gifs
                            gifViewerIndex = gifs.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                        }
                        ReaderContentType.VIDEO -> videoViewerItem = item
                        ReaderContentType.ARCHIVE -> archiveViewerItem = item
                    }
                }
                val favoriteItem: (ReaderContent) -> Unit = { item -> scope.launch { repository.setFavorite(item.id, !item.favorite) } }
                if (section == ReaderSection.LOCAL && localGridView) {
                    LazyVerticalGrid(columns = GridCells.Adaptive(155.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        gridItems(visibleItems, key = ReaderContent::id) { item -> ReaderContentCard(item, onFavorite = { favoriteItem(item) }, onOpen = { openItem(item) }, onRemove = if (section == ReaderSection.LOCAL) ({ pendingRemoval = item }) else null, onExport = if (section == ReaderSection.LOCAL) ({ pendingExport = item; exportPicker.launch(item.exportName()) }) else null, compact = true) }
                    }
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visibleItems, key = ReaderContent::id) { item -> ReaderContentCard(item, onFavorite = { favoriteItem(item) }, onOpen = { openItem(item) }, onRemove = if (section == ReaderSection.LOCAL) ({ pendingRemoval = item }) else null, onExport = if (section == ReaderSection.LOCAL) ({ pendingExport = item; exportPicker.launch(item.exportName()) }) else null, compact = false) }
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
    scanError?.let { message ->
        AlertDialog(onDismissRequest = { scanError = null }, title = { Text("Folder scan finished with errors") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { scanError = null }) { Text("OK") } })
    }

    imageViewerItems?.let { images ->
        if (images.isNotEmpty()) LocalImageViewer(
            items = images,
            initialIndex = imageViewerIndex,
            onClose = { imageViewerItems = null },
            onIndexChanged = { index ->
                imageViewerIndex = index
                val item = images[index]
                scope.launch { repository.updateProgress(item.id, index.toLong(), images.size.toLong(), System.currentTimeMillis()) }
            },
        )
    }
    gifViewerItems?.let { gifs ->
        if (gifs.isNotEmpty()) LocalGifViewer(
            items = gifs,
            initialIndex = gifViewerIndex,
            onClose = { gifViewerItems = null },
            onIndexChanged = { index ->
                gifViewerIndex = index
                val item = gifs[index]
                scope.launch { repository.updateProgress(item.id, index.toLong(), gifs.size.toLong(), System.currentTimeMillis()) }
            },
        )
    }
    videoViewerItem?.let { video ->
        LocalVideoViewer(
            item = video,
            onClose = { videoViewerItem = null },
            onProgress = { position, duration ->
                scope.launch { repository.updateProgress(video.id, position, duration, System.currentTimeMillis()) }
            },
        )
    }
    archiveViewerItem?.let { archive ->
        LocalArchiveBrowser(
            content = archive,
            onDismiss = { archiveViewerItem = null },
            onPreview = { preview ->
                imageViewerItems = listOf(preview)
                imageViewerIndex = 0
            },
        )
    }
    pendingRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text("Remove ${item.title}?") },
            text = { Text("The app's private copy and its library entry will be removed.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemoval = null
                    scope.launch { LocalMediaService(context, repository).remove(item) }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun TextButtonCompat(label: String, onClick: () -> Unit) {
    androidx.compose.material3.TextButton(onClick = onClick) { Text(label) }
}

@Composable
private fun ReaderContentCard(item: ReaderContent, onFavorite: () -> Unit, onOpen: () -> Unit, onRemove: (() -> Unit)?, onExport: (() -> Unit)?, compact: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth().then(
            Modifier.clickable(onClick = onOpen),
        ),
    ) {
        if (compact) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (item.coverUri != null) AsyncImage(model = item.coverUri, contentDescription = null, modifier = Modifier.fillMaxWidth().size(120.dp))
                Text(item.title, style = MaterialTheme.typography.titleMedium, maxLines = 2)
                Text(item.type.name + if (item.progressTotal > 0) " • ${item.progress}/${item.progressTotal}" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.align(Alignment.End)) { ReaderContentActions(item, onFavorite, onExport, onRemove) }
            }
        } else Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (item.coverUri != null) AsyncImage(model = item.coverUri, contentDescription = null, modifier = Modifier.size(48.dp))
            else Icon(imageVector = MaterialSymbols.Rounded.LocalLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
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
            ReaderContentActions(item, onFavorite, onExport, onRemove)
        }
    }
}

@Composable
private fun ReaderContentActions(item: ReaderContent, onFavorite: () -> Unit, onExport: (() -> Unit)?, onRemove: (() -> Unit)?) {
    IconButton(onClick = onFavorite) {
        Icon(imageVector = if (item.favorite) MaterialSymbols.RoundedFilled.Favorite else MaterialSymbols.Rounded.Favorite, contentDescription = if (item.favorite) "Remove from favorites" else "Add to favorites", tint = MaterialTheme.colorScheme.primary)
    }
    if (onExport != null) IconButton(onClick = onExport) { Icon(imageVector = MaterialSymbols.Rounded.Download, contentDescription = "Export ${item.title}") }
    if (onRemove != null) IconButton(onClick = onRemove) { Icon(imageVector = MaterialSymbols.Rounded.Delete, contentDescription = "Remove ${item.title}", tint = MaterialTheme.colorScheme.error) }
}

private fun ReaderContent.exportName(): String {
    val extension = Uri.parse(uri).lastPathSegment?.substringAfterLast('.', "")?.takeIf { it.isNotBlank() }
    return title + extension?.let { ".$it" }.orEmpty()
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
    ReaderSection.LOCAL -> arrayOf("image/*", "video/*", "application/zip", "application/x-rar-compressed", "application/vnd.rar", "application/x-7z-compressed", "application/x-tar", "application/octet-stream")
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
            else -> LocalMediaKind.identify(mime, name) ?: ReaderContentType.ARCHIVE
        }
        ReaderSection.MANGA -> return
    }

    if (type == ReaderContentType.EPUB || type == ReaderContentType.TEXT) {
        val service = EpubLibraryService(context, repository)
        val imported = if (type == ReaderContentType.EPUB) service.import(uri) else service.importText(uri)
        collectionId?.let { repository.addToCollection(imported.id, it) }
        return
    }

    if (type == ReaderContentType.PDF) {
        val imported = PdfLibraryService(context, repository).import(uri)
        collectionId?.let { repository.addToCollection(imported.id, it) }
        return
    }

    if (type == ReaderContentType.IMAGE) {
        LocalMediaService(context, repository).importImage(uri, collectionId)
        return
    }

    if (type == ReaderContentType.GIF) {
        LocalMediaService(context, repository).importGif(uri, collectionId)
        return
    }

    if (type == ReaderContentType.VIDEO) {
        LocalMediaService(context, repository).importVideo(uri, collectionId)
        return
    }

    if (type == ReaderContentType.ARCHIVE) {
        LocalMediaService(context, repository).importArchive(uri, collectionId)
        return
    }

    val contentId = run {
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
