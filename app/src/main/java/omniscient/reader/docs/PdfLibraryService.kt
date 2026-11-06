package omniscient.reader.docs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType
import tachiyomi.domain.library.content.repository.ReaderLibraryRepository
import java.io.File
import java.io.IOException
import java.util.UUID

/** Imports new PDFs and migrates legacy documents into the SQLDelight reader library. */
class PdfLibraryService(
    context: Context,
    private val repository: ReaderLibraryRepository,
) {
    private val appContext = context.applicationContext
    private val files = File(appContext.filesDir, "pdf-library")
    private val covers = File(appContext.filesDir, "pdf-covers")
    private val migrationPreferences = appContext.getSharedPreferences("pdf-library-migration", Context.MODE_PRIVATE)

    suspend fun migrateLegacyDocuments(store: DocStore) {
        if (migrationPreferences.getBoolean(LEGACY_MIGRATED, false)) return
        val legacyItems = withContext(Dispatchers.IO) { store.readLegacyItems() }
        legacyItems.forEach { legacy ->
            val id = "pdf:${legacy.id}"
            if (repository.get(id) == null) {
                val coverUri = store.coverFile(legacy.id).takeIf { it.isFile }?.toURI()?.toString()
                repository.upsert(mapLegacyPdfContent(legacy, coverUri))
            }
        }
        // The JSON file is retained as a backup; the marker prevents deleted records being reimported.
        migrationPreferences.edit().putBoolean(LEGACY_MIGRATED, true).apply()
    }

    suspend fun import(uri: Uri): ReaderContent = withContext(Dispatchers.IO) {
        var source = uri
        val persistentGrant = runCatching {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            true
        }.getOrDefault(false)
        if (!persistentGrant && uri.scheme != "file") source = copyIntoPrivateLibrary(uri)

        val id = "pdf:${UUID.randomUUID()}"
        val document = PdfDoc.open(appContext, source)
        val pages = try {
            if (document.pageCount < 1) throw IOException("This PDF has no pages")
            saveCover(id.removePrefix("pdf:"), document)
            document.pageCount
        } finally {
            document.close()
        }
        val cover = File(covers, "${id.removePrefix("pdf:")}.jpg")
        val title = displayName(uri)
        ReaderContent(
            id = id,
            type = ReaderContentType.PDF,
            title = title,
            uri = source.toString(),
            coverUri = cover.takeIf { it.isFile }?.toURI()?.toString(),
            favorite = false,
            addedAt = System.currentTimeMillis(),
            lastOpenedAt = null,
            progress = 0,
            progressTotal = pages.toLong(),
        ).also { repository.upsert(it) }
    }

    suspend fun remove(content: ReaderContent) {
        val annotations = repository.observePdfAnnotations(content.id).first()
        val annotationDirectory = File(appContext.filesDir, "pdf-annotations").canonicalFile
        withContext(Dispatchers.IO) {
            annotations.mapNotNull { it.imageUri }.forEach { raw ->
                val image = runCatching { File(Uri.parse(raw).path.orEmpty()).canonicalFile }.getOrNull()
                if (image != null && image.path.startsWith(annotationDirectory.path + File.separator)) image.delete()
            }
        }
        repository.remove(content.id)
        val uri = Uri.parse(content.uri)
        if (uri.scheme == "file") {
            val source = File(uri.path.orEmpty()).canonicalFile
            if (source.path.startsWith(files.canonicalPath + File.separator)) source.delete()
        }
        content.coverUri?.let { raw ->
            val cover = runCatching { File(Uri.parse(raw).path.orEmpty()).canonicalFile }.getOrNull() ?: return@let
            if (cover.path.startsWith(covers.canonicalPath + File.separator) ||
                cover.path.contains("${File.separator}omniscient_covers${File.separator}")
            ) {
                cover.delete()
            }
        }
    }

    private fun copyIntoPrivateLibrary(uri: Uri): Uri {
        files.mkdirs()
        val id = UUID.randomUUID().toString()
        val temp = File(files, "$id.tmp")
        val target = File(files, "$id.pdf")
        try {
            val input =
                appContext.contentResolver.openInputStream(uri)
                    ?: throw IOException("Selected PDF is no longer available")
            input.use { source ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        if (copied > MAX_IMPORT_BYTES) throw IOException("PDF exceeds the 1 GB import limit")
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (!temp.renameTo(target)) temp.copyTo(target, overwrite = true)
            return Uri.fromFile(target)
        } catch (error: Throwable) {
            temp.delete()
            target.delete()
            throw error
        } finally {
            temp.delete()
        }
    }

    private suspend fun saveCover(id: String, doc: PdfDoc) {
        val bitmap = doc.render(0, COVER_WIDTH_PX) ?: return
        try {
            covers.mkdirs()
            File(covers, "$id.jpg").outputStream().use { output ->
                if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, COVER_QUALITY, output)) {
                    throw IOException("PDF cover could not be encoded")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun displayName(uri: Uri): String {
        val name = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull() ?: uri.lastPathSegment ?: "Document.pdf"
        return name.substringAfterLast('/').removeSuffix(".pdf").removeSuffix(".PDF").ifBlank { "Document" }
    }

    companion object {
        private const val LEGACY_MIGRATED = "legacy_documents_migrated_v1"
        private const val MAX_IMPORT_BYTES = 1024L * 1024 * 1024
        private const val COVER_WIDTH_PX = 480
        private const val COVER_QUALITY = 90
    }
}

internal fun mapLegacyPdfContent(legacy: DocItem, coverUri: String?): ReaderContent = ReaderContent(
    id = "pdf:${legacy.id}",
    type = ReaderContentType.PDF,
    title = legacy.title,
    uri = legacy.uri,
    coverUri = coverUri,
    favorite = false,
    addedAt = legacy.addedAt,
    lastOpenedAt = null,
    progress = legacy.lastPage.coerceAtLeast(0).toLong(),
    progressTotal = legacy.pageCount.coerceAtLeast(0).toLong(),
)
