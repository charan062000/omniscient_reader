package omniscient.reader.docs

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Read-only access to the pre-Phase-3 JSON library, kept solely for one-time migration. */
class DocStore(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(DocItem.serializer())
    private val libraryFile = File(appContext.filesDir, "omniscient_documents.json")
    private val coverDir = File(appContext.filesDir, "omniscient_covers")

    fun readLegacyItems(): List<DocItem> = synchronized(lock) {
        if (!libraryFile.exists()) return@synchronized emptyList()
        try {
            json.decodeFromString(serializer, libraryFile.readText())
        } catch (error: Exception) {
            throw IllegalStateException("The legacy PDF library could not be read; it was left untouched.", error)
        }
    }

    fun coverFile(id: String): File = File(coverDir, "$id.jpg")

    private companion object {
        val lock = Any()
    }
}
