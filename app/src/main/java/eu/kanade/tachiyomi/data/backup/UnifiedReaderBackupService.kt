package eu.kanade.tachiyomi.data.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.backup.models.BackupEpubBookmark as EpubBookmarkEntry
import eu.kanade.tachiyomi.data.backup.models.BackupPdfAnnotation as PdfAnnotationEntry
import eu.kanade.tachiyomi.data.backup.models.BackupReaderAssetChunk
import eu.kanade.tachiyomi.data.backup.models.BackupReaderAssetRef
import eu.kanade.tachiyomi.data.backup.models.BackupReaderCategory
import eu.kanade.tachiyomi.data.backup.models.BackupReaderContent
import eu.kanade.tachiyomi.data.backup.models.BackupReaderPreference
import eu.kanade.tachiyomi.data.backup.models.ReaderBackupEntry
import eu.kanade.tachiyomi.data.backup.models.READER_BACKUP_VERSION
import eu.kanade.tachiyomi.data.backup.models.BackupReaderContent as ReaderContentEntry
import eu.kanade.tachiyomi.data.backup.models.BackupReaderAssetRef as AssetRef
import eu.kanade.tachiyomi.data.backup.models.BackupReaderCategory as ReaderCategory
import eu.kanade.tachiyomi.ui.epub.EpubLibraryService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.content.model.EpubBookmark
import tachiyomi.domain.library.content.model.PdfAnnotation
import tachiyomi.domain.library.content.model.PdfAnnotationType
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType
import tachiyomi.domain.library.content.repository.ReaderLibraryRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Stores reader records without serializing device-specific URIs; file contents use bounded protobuf chunks. */
@Inject
class UnifiedReaderBackupService(
    context: Context,
    private val repository: ReaderLibraryRepository,
) {
    private val appContext = context.applicationContext

    suspend fun globalPreferences(): List<BackupReaderPreference> =
        capturePreferences(appContext.getSharedPreferences(EPUB_SETTINGS_PREFS, Context.MODE_PRIVATE))

    suspend fun write(writer: BackupWriter, includeLibrary: Boolean) = withContext(Dispatchers.IO) {
        if (!includeLibrary) return@withContext
        val collections = repository.observeCollections().first()
        val collectionIds = collections.associate { collection ->
            collection.name to repository.observeContentIdsInCollection(collection.id).first()
        }
        val categoryCoverPrefs = appContext.getSharedPreferences(LOCAL_MEDIA_PREFS, Context.MODE_PRIVATE)

        collections.forEach { collection ->
            val coverUri = categoryCoverPrefs.getString(CATEGORY_COVER_PREFIX + collection.id, null)
            val coverRef = coverUri?.let { assetRef("category:${collection.name}", "cover", it) }
            writer.writeReaderEntry(
                ReaderBackupEntry.Category(
                    ReaderCategory(collection.name, collection.order, coverRef?.id),
                ),
            )
            coverRef?.let { streamAsset(writer, it, coverUri!!) }
        }

        repository.observeAll().first().forEach { content ->
            val annotations = repository.observePdfAnnotations(content.id).first()
            val bookmarks = repository.observeEpubBookmarks(content.id).first()
            val refs = linkedMapOf<String, AssetRef>()
            val mainRef = assetRef(content.id, "main", content.uri)
            refs[mainRef.id] = mainRef
            val coverRef = content.coverUri?.takeIf { it != content.uri }?.let {
                assetRef(content.id, "cover", it).also { ref -> refs[ref.id] = ref }
            }
            val annotationRefs = annotations.mapNotNull { annotation ->
                annotation.imageUri?.let { uri ->
                    assetRef(content.id, "annotation:${annotation.id}", uri).also { refs[it.id] = it }
                }
            }.associateBy { it.kind.substringAfter(':') }
            val categoryNames = collectionIds.filterValues { content.id in it }.keys.toList()
            val preferenceName = when (content.type) {
                ReaderContentType.EPUB, ReaderContentType.TEXT -> "epub-position-${content.id}"
                ReaderContentType.PDF -> "pdf-reader-${content.id.hashCode()}"
                else -> null
            }
            val itemPreferences = preferenceName?.let {
                capturePreferences(appContext.getSharedPreferences(it, Context.MODE_PRIVATE))
            }.orEmpty()
            val annotationEntries = annotations.map { annotation ->
                PdfAnnotationEntry(
                    id = annotation.id,
                    pageIndex = annotation.pageIndex,
                    type = annotation.type.name,
                    text = annotation.text,
                    imageAssetId = annotationRefs[annotation.id]?.id,
                    left = annotation.left,
                    top = annotation.top,
                    right = annotation.right,
                    bottom = annotation.bottom,
                    createdAt = annotation.createdAt,
                )
            }
            writer.writeReaderEntry(
                ReaderBackupEntry.Content(
                    ReaderContentEntry(
                        id = content.id,
                        type = content.type.name,
                        title = content.title,
                        favorite = content.favorite,
                        addedAt = content.addedAt,
                        lastOpenedAt = content.lastOpenedAt,
                        progress = content.progress,
                        progressTotal = content.progressTotal,
                        author = content.author,
                        categories = categoryNames,
                        assets = (listOf(mainRef) + listOfNotNull(coverRef) + annotationRefs.values)
                            .distinctBy(AssetRef::id),
                        pdfAnnotations = annotationEntries,
                        epubBookmarks = bookmarks.map {
                            EpubBookmarkEntry(
                                it.chapterIndex,
                                it.chapterTitle,
                                it.selectedText,
                                it.context,
                                it.createdAt,
                            )
                        },
                        readerPreferences = itemPreferences,
                    ),
                ),
            )
            refs.values.forEach { ref ->
                val uri = when (ref.kind) {
                    "main" -> content.uri
                    "cover" -> content.coverUri
                    else -> annotations.firstOrNull { "annotation:${it.id}" == ref.kind }?.imageUri
                }
                if (uri != null) streamAsset(writer, ref, uri)
            }
        }
    }

    suspend fun restore(
        entries: Flow<ReaderBackupEntry>,
        globalPreferences: List<BackupReaderPreference>,
        backupVersion: Int,
    ): List<String> = withContext(Dispatchers.IO) {
        if (backupVersion <= 0) return@withContext emptyList()
        if (backupVersion > READER_BACKUP_VERSION) throw IOException("This backup uses a newer reader-library format")

        val staging = File(appContext.cacheDir, "reader-restore-${UUID.randomUUID()}").apply { mkdirs() }
        val records = mutableListOf<BackupReaderContent>()
        val categories = mutableListOf<ReaderCategory>()
        val assets = mutableMapOf<String, File>()
        val failures = mutableListOf<String>()
        try {
            var activeAssetId: String? = null
            var activeFile: File? = null
            var activeIndex = 0
            var activeSize = 0L
            var activeValid = false
            entries.collect { entry ->
                when (entry) {
                    is ReaderBackupEntry.Content -> records += entry.value
                    is ReaderBackupEntry.Category -> categories += entry.value
                    is ReaderBackupEntry.Asset -> {
                        val chunk = entry.value
                        if (chunk.index == 0) {
                            activeFile?.delete()
                            activeAssetId = chunk.assetId
                            activeFile = File(staging, "${safeHash(chunk.assetId)}.partial")
                            activeIndex = 0
                            activeSize = 0
                            activeValid = true
                        }
                        val target = activeFile
                        if (target == null || chunk.assetId != activeAssetId || chunk.index != activeIndex) {
                            activeValid = false
                            return@collect
                        }
                        activeSize += chunk.data.size
                        if (activeSize > MAX_ASSET_BYTES) activeValid = false
                        if (activeValid) java.io.FileOutputStream(target, true).buffered(64 * 1024).use { output ->
                            output.write(chunk.data)
                        }
                        activeIndex++
                        if (chunk.last) {
                            if (activeValid) {
                                val completed = File(staging, "${safeHash(chunk.assetId)}.asset")
                                if (target.renameTo(completed)) assets[chunk.assetId] = completed
                            }
                            target.delete()
                            activeFile = null
                            activeAssetId = null
                        }
                    }
                }
            }

            globalPreferences.restoreTo(appContext.getSharedPreferences(EPUB_SETTINGS_PREFS, Context.MODE_PRIVATE))
            val collectionIds = restoreCategories(categories, assets, failures)
            records.forEach { record ->
                val type = ReaderContentType.fromStorage(record.type)
                if (type == null) {
                    failures += "${record.title}: unsupported reader format '${record.type}'"
                    return@forEach
                }
                val mainRef = record.assets.firstOrNull { it.kind == "main" }
                val mainAsset = mainRef?.let { assets[it.id] }
                if (mainAsset == null || !mainAsset.isFile) {
                    failures += "${record.title}: source data was missing from the backup"
                    return@forEach
                }
                runCatching {
                    val restored = restoreRecord(record, type, mainAsset, assets)
                    repository.restoreContent(restored)
                    repository.setCollections(restored.id, record.categories.mapNotNull { collectionIds[it] })
                    restoreAnnotations(record, assets)
                    repository.replaceEpubBookmarks(
                        restored.id,
                        record.epubBookmarks.mapIndexed { index, bookmark ->
                            EpubBookmark(
                                index.toLong(),
                                restored.id,
                                bookmark.chapterIndex,
                                bookmark.chapterTitle,
                                bookmark.selectedText,
                                bookmark.context,
                                bookmark.createdAt,
                            )
                        },
                    )
                    record.readerPreferences.restoreTo(preferencesFor(restored))
                }.onFailure { failures += "${record.title}: ${it.message ?: "could not be restored"}" }
            }
            failures
        } finally {
            staging.deleteRecursively()
        }
    }

    private suspend fun restoreCategories(
        records: List<ReaderCategory>,
        assets: Map<String, File>,
        failures: MutableList<String>,
    ): Map<String, Long> {
        records.forEach { record ->
            if (repository.observeCollections().first().none { it.name.equals(record.name, ignoreCase = true) }) {
                repository.createCollection(record.name)
            }
            record.coverAssetId?.let(assets::get)?.let { cover ->
                runCatching {
                    val target = File(appContext.filesDir, "local-media/images/reader-category-covers/${safeHash(record.name)}.${cover.extension()}")
                    copyAtomically(cover, target)
                    val id = repository.observeCollections().first().first { it.name.equals(record.name, ignoreCase = true) }.id
                    appContext.getSharedPreferences(LOCAL_MEDIA_PREFS, Context.MODE_PRIVATE)
                        .edit().putString(CATEGORY_COVER_PREFIX + id, Uri.fromFile(target).toString()).apply()
                }.onFailure { failures += "${record.name}: category cover could not be restored" }
            }
        }
        val current = repository.observeCollections().first()
        return records.mapNotNull { record -> current.firstOrNull { it.name.equals(record.name, true) }?.let { record.name to it.id } }.toMap()
    }

    private suspend fun restoreRecord(
        backup: ReaderContentEntry,
        type: ReaderContentType,
        mainAsset: File,
        assets: Map<String, File>,
    ): ReaderContent {
        val sourceUri = Uri.fromFile(mainAsset)
        val imported = when (type) {
            ReaderContentType.EPUB -> EpubLibraryService(appContext, repository).import(sourceUri)
            ReaderContentType.TEXT -> EpubLibraryService(appContext, repository).importText(sourceUri)
            else -> null
        }
        val mainFile = if (imported != null) File(Uri.parse(imported.uri).path.orEmpty()) else {
            val extension = backup.assets.firstOrNull { it.kind == "main" }?.extension.orEmpty().ifBlank { "bin" }
            val folder = when (type) {
                ReaderContentType.PDF -> "pdf-library"
                ReaderContentType.IMAGE -> "local-media/images"
                ReaderContentType.GIF -> "local-media/gifs"
                ReaderContentType.VIDEO -> "local-media/videos"
                ReaderContentType.ARCHIVE -> "local-media/archives"
                else -> "reader-library"
            }
            val fileName = "${safeHash(backup.id)}.$extension"
            val destination = if (type == ReaderContentType.PDF) {
                File(appContext.filesDir, "$folder/$fileName")
            } else {
                File(appContext.filesDir, "$folder/${safeHash(backup.id)}/$fileName")
            }
            copyAtomically(mainAsset, destination)
            destination
        }
        val coverFile = backup.assets.firstOrNull { it.kind == "cover" }?.let { assets[it.id] }
        val existingCover = imported?.coverUri?.let { File(Uri.parse(it).path.orEmpty()).takeIf(File::isFile) }
        val finalCover = existingCover ?: coverFile?.let { cover ->
            val ext = backup.assets.firstOrNull { it.kind == "cover" }?.extension.orEmpty().ifBlank { "jpg" }
            val dir = if (type == ReaderContentType.PDF) "pdf-covers" else "reader-covers"
            val target = File(appContext.filesDir, "$dir/${safeHash(backup.id)}.$ext")
            copyAtomically(cover, target)
            target
        }
        restorePreferences(backup.readerPreferences, preferencesForName(backup.id, type))
        return ReaderContent(
            id = backup.id,
            type = type,
            title = backup.title,
            uri = Uri.fromFile(mainFile).toString(),
            coverUri = finalCover?.let { Uri.fromFile(it).toString() }
                ?: if (type == ReaderContentType.IMAGE || type == ReaderContentType.GIF) Uri.fromFile(mainFile).toString() else null,
            favorite = backup.favorite,
            addedAt = backup.addedAt,
            lastOpenedAt = backup.lastOpenedAt,
            progress = backup.progress.coerceAtLeast(0),
            progressTotal = backup.progressTotal.coerceAtLeast(0),
            author = backup.author,
        )
    }

    private suspend fun restoreAnnotations(record: ReaderContentEntry, assets: Map<String, File>) {
        record.pdfAnnotations.forEach { value ->
            val type = PdfAnnotationType.entries.firstOrNull { it.name == value.type } ?: return@forEach
            val imageUri = value.imageAssetId?.let(assets::get)?.let { asset ->
                val ext = record.assets.firstOrNull { it.id == value.imageAssetId }?.extension.orEmpty().ifBlank { "png" }
                val target = File(appContext.filesDir, "pdf-annotations/${safeHash(value.id)}.$ext")
                copyAtomically(asset, target)
                Uri.fromFile(target).toString()
            }
            if (type != PdfAnnotationType.IMAGE || imageUri != null) {
                repository.upsertPdfAnnotation(
                    PdfAnnotation(value.id, record.id, value.pageIndex, type, value.text, imageUri, value.left, value.top, value.right, value.bottom, value.createdAt),
                )
            }
        }
    }

    private fun preferencesFor(content: ReaderContent): SharedPreferences = preferencesForName(content.id, content.type)

    private fun preferencesForName(id: String, type: ReaderContentType): SharedPreferences = when (type) {
        ReaderContentType.EPUB, ReaderContentType.TEXT -> appContext.getSharedPreferences("epub-position-$id", Context.MODE_PRIVATE)
        ReaderContentType.PDF -> appContext.getSharedPreferences("pdf-reader-${id.hashCode()}", Context.MODE_PRIVATE)
        else -> appContext.getSharedPreferences("reader-content-$id", Context.MODE_PRIVATE)
    }

    private suspend fun capturePreferences(
        preferences: SharedPreferences,
    ): List<BackupReaderPreference> =
        preferences.all.mapNotNull { (key, value) ->
            when (value) {
                is String -> BackupReaderPreference(key, "string", value)
                is Boolean -> BackupReaderPreference(key, "boolean", value.toString())
                is Int -> BackupReaderPreference(key, "int", value.toString())
                is Long -> BackupReaderPreference(key, "long", value.toString())
                is Float -> BackupReaderPreference(key, "float", value.toString())
                is Set<*> -> BackupReaderPreference(key, "string-set", stringSet = value.filterIsInstance<String>().sorted())
                else -> null
            }
        }

    private fun List<BackupReaderPreference>.restoreTo(preferences: SharedPreferences) = restorePreferences(this, preferences)

    private fun restorePreferences(records: List<BackupReaderPreference>, preferences: SharedPreferences) {
        val editor = preferences.edit()
        records.forEach { record ->
            when (record.type) {
                "string" -> editor.putString(record.key, record.value)
                "boolean" -> record.value?.toBooleanStrictOrNull()?.let { editor.putBoolean(record.key, it) }
                "int" -> record.value?.toIntOrNull()?.let { editor.putInt(record.key, it) }
                "long" -> record.value?.toLongOrNull()?.let { editor.putLong(record.key, it) }
                "float" -> record.value?.toFloatOrNull()?.let { editor.putFloat(record.key, it) }
                "string-set" -> editor.putStringSet(record.key, record.stringSet.toSet())
            }
        }
        editor.apply()
    }

    private suspend fun streamAsset(writer: BackupWriter, ref: BackupReaderAssetRef, uri: String) {
        val input = runCatching { appContext.contentResolver.openInputStream(Uri.parse(uri)) }.getOrNull() ?: return
        input.use { stream ->
            var buffer = ByteArray(ASSET_CHUNK_BYTES)
            var index = 0
            var total = 0L
            var pending = stream.read(buffer)
            while (pending >= 0) {
                total += pending
                if (total > MAX_ASSET_BYTES) return
                val nextBuffer = ByteArray(ASSET_CHUNK_BYTES)
                val next = stream.read(nextBuffer)
                val chunk = buffer.copyOf(pending)
                writer.writeReaderEntry(ReaderBackupEntry.Asset(BackupReaderAssetChunk(ref.id, index++, chunk, next < 0)))
                if (next < 0) break
                buffer = nextBuffer
                pending = next
            }
        }
    }

    private fun assetRef(owner: String, kind: String, uri: String): BackupReaderAssetRef {
        val extension = Uri.parse(uri).lastPathSegment.orEmpty().substringAfterLast('.', "bin")
            .lowercase().filter(Char::isLetterOrDigit).take(10).ifBlank { "bin" }
        return BackupReaderAssetRef(assetId(owner, kind), kind, extension)
    }

    private fun copyAtomically(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        val temporary = File(destination.parentFile, "${destination.name}.partial")
        source.inputStream().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete()
            throw IOException("Restored file could not be installed")
        }
    }

    private fun File.extension(): String = name.substringAfterLast('.', "bin")

    private fun safeHash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(16)
        .joinToString("") { "%02x".format(it) }

    private fun assetId(owner: String, kind: String): String = safeHash("$owner|$kind")

    private companion object {
        const val EPUB_SETTINGS_PREFS = "epub-reader"
        const val LOCAL_MEDIA_PREFS = "local_media"
        const val CATEGORY_COVER_PREFIX = "collection_cover_"
        const val ASSET_CHUNK_BYTES = 1024 * 1024
        const val MAX_ASSET_BYTES = 10L * 1024 * 1024 * 1024
    }
}
