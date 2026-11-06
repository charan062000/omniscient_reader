package eu.kanade.tachiyomi.ui.readerlibrary

import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.archive.ArchiveReader
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType
import java.io.File
import java.io.IOException

/** Browses archive paths without extracting the archive; only a selected image is copied to cache. */
@Composable
fun LocalArchiveBrowser(content: ReaderContent, onDismiss: () -> Unit, onPreview: (ReaderContent) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val archiveFile = remember(content.uri) { File(Uri.parse(content.uri).path.orEmpty()) }
    var entries by remember(content.uri) { mutableStateOf<List<String>>(emptyList()) }
    var currentPath by remember(content.uri) { mutableStateOf("") }
    var error by remember(content.uri) { mutableStateOf<String?>(null) }
    var loading by remember(content.uri) { mutableStateOf(true) }

    LaunchedEffect(content.uri) {
        runCatching { withContext(Dispatchers.IO) { ArchivePathIndex.read(archiveFile) } }
            .onSuccess { entries = it }
            .onFailure { error = it.message ?: "This archive cannot be opened." }
        loading = false
    }
    val rows = remember(entries, currentPath) { ArchivePathIndex.children(entries, currentPath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (currentPath.isBlank()) content.title else currentPath.trimEnd('/').substringAfterLast('/')) },
        text = {
            Column {
                if (currentPath.isNotBlank()) Text("..", Modifier.fillMaxWidth().clickable { currentPath = currentPath.substringBeforeLast('/', "") }.padding(12.dp))
                when {
                    loading -> Text("Reading archive…")
                    error != null -> Text(error!!)
                    rows.isEmpty() -> Text("This folder is empty or contains no supported entries.")
                    else -> LazyColumn {
                        items(rows, key = { it.path }) { row ->
                            Text(
                                text = (if (row.directory) "📁 " else if (row.previewable) "🖼 " else "📄 ") + row.name,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    if (row.directory) currentPath = row.path
                                    else if (row.previewable) scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { ArchivePathIndex.extractPreview(archiveFile, row.path, context.cacheDir) } }
                                            .onSuccess { file ->
                                                onPreview(
                                                    ReaderContent(
                                                        id = "archive-preview:${content.id}:${row.path}",
                                                        type = ReaderContentType.IMAGE,
                                                        title = row.name.substringBeforeLast('.', row.name),
                                                        uri = Uri.fromFile(file).toString(), coverUri = Uri.fromFile(file).toString(),
                                                        favorite = false, addedAt = System.currentTimeMillis(), lastOpenedAt = null,
                                                        progress = 0, progressTotal = 1,
                                                    ),
                                                )
                                            }
                                            .onFailure { error = it.message ?: "The archive entry could not be read." }
                                    }
                                }.padding(horizontal = 8.dp, vertical = 10.dp),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

internal data class ArchiveRow(val name: String, val path: String, val directory: Boolean, val previewable: Boolean)

/** Pure path projection used for safe folder navigation inside archives. */
internal object ArchivePathIndex {
    private const val MAX_PREVIEW_BYTES = 128L * 1024 * 1024
    private const val MAX_ARCHIVE_ENTRIES = 100_000
    private const val MAX_ENTRY_NAME_CHARS = 4_096
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "avif")

    fun read(file: File): List<String> {
        if (!file.isFile) throw IOException("The imported archive is missing")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        return try {
            ArchiveReader(pfd).use { reader ->
                reader.useEntries { seq ->
                    val names = seq.filter { it.isFile }.take(MAX_ARCHIVE_ENTRIES + 1).map { it.name.take(MAX_ENTRY_NAME_CHARS) }.toList()
                    if (names.size > MAX_ARCHIVE_ENTRIES) throw IOException("Archives with more than $MAX_ARCHIVE_ENTRIES entries are not supported")
                    names
                }
            }
        } finally {
            pfd.close()
        }
    }

    fun children(paths: List<String>, parent: String): List<ArchiveRow> {
        val prefix = parent.trim('/').takeIf(String::isNotBlank)?.plus('/') ?: ""
        val rows = linkedMapOf<String, ArchiveRow>()
        paths.forEach { raw ->
            val safe = raw.replace('\\', '/').trimStart('/')
            if (safe.split('/').any { it == ".." } || !safe.startsWith(prefix)) return@forEach
            val rest = safe.removePrefix(prefix)
            val segment = rest.substringBefore('/')
            if (segment.isBlank()) return@forEach
            val directory = '/' in rest
            val path = prefix + segment + if (directory) "/" else ""
            rows.putIfAbsent(path, ArchiveRow(segment, path, directory, !directory && segment.substringAfterLast('.', "").lowercase() in imageExtensions))
        }
        return rows.values.sortedWith(compareByDescending<ArchiveRow> { it.directory }.thenBy { it.name.lowercase() })
    }

    fun extractPreview(file: File, entryName: String, cacheDirectory: File): File {
        if (entryName.replace('\\', '/').split('/').any { it == ".." }) throw IOException("Invalid archive entry path")
        val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        val output = File(cacheDirectory, "archive-preview-${entryName.hashCode().toUInt().toString(16)}.${entryName.substringAfterLast('.', "img").take(8)}")
        try {
            ArchiveReader(pfd).use { reader ->
                val input = reader.getInputStream(entryName) ?: throw IOException("Archive entry not found")
                input.use { source -> output.outputStream().buffered().use { target ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var countTotal = 0L
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        countTotal += count
                        if (countTotal > MAX_PREVIEW_BYTES) throw IOException("Archive previews larger than 128 MB are not supported")
                        target.write(buffer, 0, count)
                    }
                } }
            }
        } catch (error: Throwable) {
            output.delete()
            throw error
        } finally {
            pfd.close()
        }
        return output
    }
}
