package omniscient.reader.docs

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Keeps the Documents library on the phone: a small JSON file plus one cover image per document.
 * Nothing is sent anywhere.
 */
class DocStore(context: Context) {

    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(DocItem.serializer())
    private val libraryFile = File(appContext.filesDir, "omniscient_documents.json")
    private val coverDir = File(appContext.filesDir, "omniscient_covers")
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val lock = Any()
    private var items: MutableList<DocItem>? = null

    /** Returns all documents, newest first. */
    fun all(): List<DocItem> = synchronized(lock) {
        loadLocked().sortedByDescending { it.addedAt }
    }

    fun coverFile(id: String): File = File(coverDir, "$id.jpg")

    /**
     * Adds the PDF at [uri] to the library and makes its cover from the first page.
     * Throws if the file cannot be read as a PDF.
     */
    fun importPdf(uri: Uri): DocItem {
        try {
            appContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) {
            // Not every provider offers a permanent permission; the file still opens this session.
        }

        val existing = synchronized(lock) { loadLocked().firstOrNull { it.uri == uri.toString() } }
        if (existing != null) return existing

        val id = UUID.randomUUID().toString()
        val pageCount = PdfDoc.open(appContext, uri).use { doc ->
            if (doc.pageCount > 0) saveCover(id, doc)
            doc.pageCount
        }

        val item = DocItem(
            id = id,
            title = displayName(uri),
            uri = uri.toString(),
            type = TYPE_PDF,
            pageCount = pageCount,
            addedAt = System.currentTimeMillis(),
        )
        synchronized(lock) {
            loadLocked().add(item)
            persistLocked()
        }
        return item
    }

    fun remove(id: String) {
        synchronized(lock) {
            loadLocked().removeAll { it.id == id }
            persistLocked()
        }
        coverFile(id).delete()
    }

    /** Remembers where the reader stopped. Safe to call often. */
    fun updateProgress(id: String, page: Int) {
        synchronized(lock) {
            val list = loadLocked()
            val index = list.indexOfFirst { it.id == id }
            if (index < 0 || list[index].lastPage == page) return
            list[index] = list[index].copy(lastPage = page)
        }
        ioScope.launch {
            synchronized(lock) { persistLocked() }
        }
    }

    private fun loadLocked(): MutableList<DocItem> {
        items?.let { return it }
        val loaded = try {
            if (libraryFile.exists()) {
                json.decodeFromString(serializer, libraryFile.readText()).toMutableList()
            } else {
                mutableListOf()
            }
        } catch (_: Exception) {
            mutableListOf()
        }
        items = loaded
        return loaded
    }

    private fun persistLocked() {
        val list = items ?: return
        try {
            val temp = File(libraryFile.parentFile, "${libraryFile.name}.tmp")
            temp.writeText(json.encodeToString(serializer, list))
            if (!temp.renameTo(libraryFile)) {
                libraryFile.writeText(json.encodeToString(serializer, list))
                temp.delete()
            }
        } catch (_: Exception) {
            // Keep the in-memory list; the next change will try to save again.
        }
    }

    private fun saveCover(id: String, doc: PdfDoc) {
        try {
            val bitmap = runBlocking { doc.render(0, COVER_WIDTH_PX) } ?: return
            coverDir.mkdirs()
            FileOutputStream(coverFile(id)).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, COVER_QUALITY, output)
            }
            bitmap.recycle()
        } catch (_: Exception) {
            // A missing cover only means a placeholder is shown.
        }
    }

    private fun displayName(uri: Uri): String {
        var name: String? = null
        try {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) name = it.getString(0)
            }
        } catch (_: Exception) {
            // Fall back to the last part of the address
        }
        val raw = name ?: uri.lastPathSegment ?: "Document"
        return raw.substringAfterLast('/').removeSuffix(".pdf").removeSuffix(".PDF")
    }

    companion object {
        const val TYPE_PDF = "pdf"
        private const val COVER_WIDTH_PX = 480
        private const val COVER_QUALITY = 90
    }
}
