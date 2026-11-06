package omniscient.reader.docs

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.MoreVert
import mihon.icons.materialsymbols.rounded.Favorite
import mihon.icons.materialsymbols.roundedfilled.Favorite
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType

@Composable
fun DocsLibraryScreen(
    onOpen: (ReaderContent) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val repository = context.appGraph.readerLibraryRepository
    val service = remember(context, repository) { PdfLibraryService(context, repository) }
    val scope = rememberCoroutineScope()
    val documents by repository.observeAll(ReaderContentType.PDF).collectAsState(emptyList())
    val categories by repository.observeCollections().collectAsState(emptyList())
    var migrationState by remember { mutableStateOf<Boolean?>(null) }
    var migrationError by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var recentFirst by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf<Long?>(null) }
    var categoryMenu by remember { mutableStateOf(false) }
    var pendingRemove by remember { mutableStateOf<ReaderContent?>(null) }
    var importing by remember { mutableStateOf(false) }
    val categoryContentIds by (selectedCategory?.let(repository::observeContentIdsInCollection) ?: flowOf(emptySet()))
        .collectAsState(emptySet())

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            importing = true
            val failures = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    runCatching {
                        val imported = service.import(uri)
                        selectedCategory?.let { repository.addToCollection(imported.id, it) }
                    }.exceptionOrNull()
                }
            }
            importing = false
            if (failures.isNotEmpty()) Toast.makeText(context, "${failures.size} PDF file(s) could not be imported", Toast.LENGTH_LONG).show()
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { service.migrateLegacyDocuments(DocStore(context)) }
            .onSuccess { migrationState = true }
            .onFailure {
                migrationState = false
                migrationError = it.message ?: "The legacy PDF library could not be migrated."
            }
    }

    val visibleItems = remember(documents, query, favoritesOnly, recentFirst, selectedCategory, categoryContentIds) {
        documents.asSequence()
            .filter { selectedCategory == null || it.id in categoryContentIds }
            .filter { !favoritesOnly || it.favorite }
            .filter { query.isBlank() || it.title.contains(query, ignoreCase = true) }
            .sortedWith(
                compareByDescending<ReaderContent> { it.favorite }
                    .thenByDescending { if (recentFirst) it.lastOpenedAt ?: it.addedAt else 0L }
                    .thenBy { if (recentFirst) "" else it.title.lowercase() },
            ).toList()
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { picker.launch(arrayOf("application/pdf")) }) {
                Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = "Add PDF")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onExit) {
                    Icon(imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack, contentDescription = "Back")
                }
                Text("Documents", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            }
            if (importing || migrationState == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { categoryMenu = true }) {
                    Text(categories.firstOrNull { it.id == selectedCategory }?.name ?: "All categories")
                }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    DropdownMenuItem(text = { Text("All categories") }, onClick = { selectedCategory = null; categoryMenu = false })
                    categories.forEach { category ->
                        DropdownMenuItem(text = { Text(category.name) }, onClick = { selectedCategory = category.id; categoryMenu = false })
                    }
                }
                TextButton(onClick = { favoritesOnly = !favoritesOnly }) { Text(if (favoritesOnly) "Favorites" else "All") }
                TextButton(onClick = { recentFirst = !recentFirst }) { Text(if (recentFirst) "Recent" else "A–Z") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                label = { Text("Search PDFs") },
            )
            when {
                migrationState == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                visibleItems.isEmpty() -> EmptyState()
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(140.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visibleItems, key = ReaderContent::id) { item ->
                        DocCard(
                            item = item,
                            onClick = { onOpen(item) },
                            onFavorite = { scope.launch { repository.setFavorite(item.id, !item.favorite) } },
                            onRemove = { pendingRemove = item },
                        )
                    }
                }
            }
        }
    }

    pendingRemove?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove from library?") },
            text = { Text("\"${item.title}\" will be removed from the library. Original PDFs outside the app are not deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingRemove = null
                    scope.launch { withContext(Dispatchers.IO) { service.remove(item) } }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } },
        )
    }
    migrationError?.let { message ->
        AlertDialog(
            onDismissRequest = { migrationError = null },
            title = { Text("PDF migration failed") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { migrationError = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxSize().padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(MaterialSymbols.Rounded.LocalLibrary, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text("Your documents live here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Tap + to import PDF files. The original document stays unchanged.", style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DocCard(item: ReaderContent, onClick: () -> Unit, onFavorite: () -> Unit, onRemove: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            item.coverUri?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop) }
            if (item.coverUri == null) Icon(MaterialSymbols.Rounded.LocalLibrary, null, Modifier.align(Alignment.Center).size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("PDF", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
            Box(Modifier.align(Alignment.TopEnd)) {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.padding(4.dp).size(32.dp).background(Color.Black.copy(alpha = 0.45f), CircleShape)) {
                    Icon(MaterialSymbols.Rounded.MoreVert, "More", tint = Color.White, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(if (item.favorite) "Remove favorite" else "Add favorite") },
                        onClick = { menuOpen = false; onFavorite() },
                        leadingIcon = { Icon(if (item.favorite) MaterialSymbols.RoundedFilled.Favorite else MaterialSymbols.Rounded.Favorite, null) },
                    )
                    DropdownMenuItem(text = { Text("Remove from library") }, onClick = { menuOpen = false; onRemove() })
                }
            }
            if (item.progress > 0 && item.progressTotal > 0) {
                LinearProgressIndicator(progress = { ((item.progress + 1).toFloat() / item.progressTotal).coerceIn(0f, 1f) }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth())
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            if (item.progress > 0 && item.progressTotal > 0) "Page ${item.progress + 1} of ${item.progressTotal}" else "${item.progressTotal} pages",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
