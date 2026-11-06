package eu.kanade.tachiyomi.ui.epub

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType
import tachiyomi.domain.library.content.repository.ReaderLibraryRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipFile

class EpubLibraryService(
    context: Context,
    private val repository: ReaderLibraryRepository,
    private val parser: EpubParser = EpubParser(),
) {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "epub-library")

    suspend fun import(uri: Uri): ReaderContent {
        root.mkdirs()
        val stage = File(root, "import-${System.nanoTime()}.epub")
        try {
            val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open selected EPUB")
            input.use { source -> stage.outputStream().buffered().use { target ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_ARCHIVE_BYTES) throw IOException("EPUB exceeds the 500 MB import limit")
                    target.write(buffer, 0, count)
                }
            } }
            val id = sha256(stage)
            val directory = File(root, id)
            directory.mkdirs()
            val epub = File(directory, "book.epub")
            if (!epub.exists()) {
                if (!stage.renameTo(epub)) stage.copyTo(epub, overwrite = true)
            }
            extractResources(epub, File(directory, "resources"))
            val metadata = parser.parse(epub)
            val cover = metadata.coverPath?.let { File(File(directory, "resources"), it).takeIf(File::isFile)?.toURI()?.toString() }
            val existing = repository.get("epub:$id")
            val displayName = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                ?.substringBeforeLast('.')
            return ReaderContent(
                id = "epub:$id",
                type = ReaderContentType.EPUB,
                title = metadata.title.ifBlank { displayName ?: "Untitled EPUB" },
                uri = epub.toURI().toString(),
                coverUri = cover,
                favorite = existing?.favorite ?: false,
                addedAt = existing?.addedAt ?: System.currentTimeMillis(),
                lastOpenedAt = existing?.lastOpenedAt,
                progress = existing?.progress ?: 0,
                progressTotal = metadata.chapters.size.toLong(),
                author = metadata.authors.joinToString(", ").ifBlank { null },
            ).also { repository.upsert(it) }
        } finally {
            stage.delete()
        }
    }

    suspend fun importText(uri: Uri): ReaderContent {
        root.mkdirs()
        val temp = File(root, "text-${System.nanoTime()}.txt")
        try {
            val source = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open selected text book")
            source.use { input -> temp.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_TEXT_BYTES) throw IOException("Text book exceeds the 50 MB import limit")
                    output.write(buffer, 0, count)
                }
            } }
            val id = sha256(temp)
            val directory = File(root, "text-$id").apply { mkdirs() }
            val target = File(directory, "book.txt")
            if (!target.exists() && !temp.renameTo(target)) temp.copyTo(target, overwrite = true)
            val old = repository.get("text:$id")
            val displayName = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0)?.substringBeforeLast('.') else null }
            return ReaderContent(
                id = "text:$id", type = ReaderContentType.TEXT,
                title = displayName ?: "Untitled text", uri = target.toURI().toString(),
                coverUri = null, favorite = old?.favorite ?: false,
                addedAt = old?.addedAt ?: System.currentTimeMillis(), lastOpenedAt = old?.lastOpenedAt,
                progress = old?.progress ?: 0, progressTotal = 1, author = old?.author,
            ).also { repository.upsert(it) }
        } finally {
            temp.delete()
        }
    }

    fun getBookFile(content: ReaderContent): File = File(Uri.parse(content.uri).path ?: content.uri)

    fun getResourceRoot(content: ReaderContent): File = File(getBookFile(content).parentFile, "resources")

    fun parser(): EpubParser = parser

    private fun extractResources(epub: File, destination: File) {
        if (File(destination, ".complete").exists()) return
        destination.deleteRecursively()
        destination.mkdirs()
        var total = 0L
        ZipFile(epub).use { zip ->
            val entries = zip.entries()
            var count = 0
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++count > MAX_ENTRIES) throw IOException("EPUB contains too many archive entries")
                if (entry.isDirectory) continue
                val relative = entry.name.replace('\\', '/')
                if (relative.startsWith('/') || relative.split('/').any { it == ".." }) continue
                val output = File(destination, relative)
                if (!output.canonicalPath.startsWith(destination.canonicalPath + File.separator)) continue
                output.parentFile?.mkdirs()
                zip.getInputStream(entry).use { input -> output.outputStream().buffered().use { out ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var entryTotal = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        entryTotal += read
                        total += read
                        if (entryTotal > MAX_ENTRY_BYTES || total > MAX_EXTRACTED_BYTES) throw IOException("EPUB expands beyond the safe extraction limit")
                        out.write(buffer, 0, read)
                    }
                } }
            }
        }
        File(destination, ".complete").writeText("ok")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_ARCHIVE_BYTES = 500L * 1024 * 1024
        const val MAX_EXTRACTED_BYTES = 750L * 1024 * 1024
        const val MAX_ENTRY_BYTES = 128L * 1024 * 1024
        const val MAX_ENTRIES = 10_000
        const val MAX_TEXT_BYTES = 50L * 1024 * 1024
    }
}
