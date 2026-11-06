package omniscient.reader.localmedia

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType
import tachiyomi.domain.library.content.repository.ReaderLibraryRepository
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Owns app-private copies of local media so gallery entries remain available after permissions change. */
class LocalMediaService(
    context: Context,
    private val repository: ReaderLibraryRepository,
) {
    private val appContext = context.applicationContext
    private val mediaRoot = File(appContext.filesDir, "local-media")
    private val root = File(mediaRoot, "images")
    private val gifRoot = File(mediaRoot, "gifs")
    private val videoRoot = File(mediaRoot, "videos")
    private val archiveRoot = File(mediaRoot, "archives")

    suspend fun importImage(source: Uri, collectionId: Long? = null): ReaderContent = withContext(Dispatchers.IO) {
        val titleWithExtension = displayName(source)
        val kind = LocalMediaKind.identify(appContext.contentResolver.getType(source), titleWithExtension)
        if (kind != ReaderContentType.IMAGE) throw IOException("Select a supported still image to import")

        val id = "image:${stableId(source.toString())}"
        val destination = File(root, "$id/${safeName(titleWithExtension)}")
        destination.parentFile?.mkdirs()
        if (!destination.exists()) {
            val input = appContext.contentResolver.openInputStream(source)
                ?: throw IOException("The selected image is no longer available")
            val temp = File(destination.parentFile, "${destination.name}.partial")
            try {
                input.use { stream -> temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var copied = 0L
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_IMAGE_BYTES) throw IOException("Images larger than 1 GB cannot be imported")
                        output.write(buffer, 0, count)
                    }
                } }
                if (!temp.renameTo(destination)) throw IOException("Image could not be saved to app storage")
            } catch (error: Throwable) {
                temp.delete()
                destination.delete()
                throw error
            }
        }
        val detected = runCatching {
            tachiyomi.core.common.util.system.ImageUtil.findImageType { destination.inputStream() }
        }.getOrNull()
        if (detected == null || detected == tachiyomi.core.common.util.system.ImageUtil.ImageType.GIF) {
            destination.delete()
            throw IOException("The selected file is not a readable image")
        }
        val localUri = Uri.fromFile(destination).toString()
        val content = ReaderContent(
            id = id,
            type = ReaderContentType.IMAGE,
            title = titleWithExtension.substringBeforeLast('.', titleWithExtension).ifBlank { "Image" },
            uri = localUri,
            coverUri = localUri,
            favorite = false,
            addedAt = System.currentTimeMillis(),
            lastOpenedAt = null,
            progress = 0,
            progressTotal = 1,
        )
        repository.upsert(content)
        collectionId?.let { repository.addToCollection(content.id, it) }
        content
    }

    suspend fun importGif(source: Uri, collectionId: Long? = null): ReaderContent = withContext(Dispatchers.IO) {
        val sourceName = displayName(source)
        if (LocalMediaKind.identify(appContext.contentResolver.getType(source), sourceName) != ReaderContentType.GIF) {
            throw IOException("The selected file is not an animated GIF")
        }
        val id = "gif:${stableId(source.toString())}"
        val destination = File(gifRoot, "$id/${safeName(sourceName)}")
        destination.parentFile?.mkdirs()
        if (!destination.exists()) copySource(source, destination)
        val imageType = runCatching {
            tachiyomi.core.common.util.system.ImageUtil.findImageType { destination.inputStream() }
        }.getOrNull()
        if (imageType != tachiyomi.core.common.util.system.ImageUtil.ImageType.GIF) {
            destination.delete()
            throw IOException("The selected GIF could not be decoded")
        }
        val localUri = Uri.fromFile(destination).toString()
        val content = ReaderContent(
            id = id,
            type = ReaderContentType.GIF,
            title = sourceName.substringBeforeLast('.', sourceName).ifBlank { "Animation" },
            uri = localUri,
            coverUri = localUri,
            favorite = false,
            addedAt = System.currentTimeMillis(),
            lastOpenedAt = null,
            progress = 0,
            progressTotal = 1,
        )
        repository.upsert(content)
        collectionId?.let { repository.addToCollection(content.id, it) }
        content
    }

    suspend fun importVideo(source: Uri, collectionId: Long? = null): ReaderContent = withContext(Dispatchers.IO) {
        val sourceName = displayName(source)
        if (LocalMediaKind.identify(appContext.contentResolver.getType(source),
             sourceName) != ReaderContentType.VIDEO) {
            throw IOException("The selected file is not a supported video")
        }
        val id = "video:${stableId(source.toString())}"
        val destination = File(videoRoot, "$id/${safeName(sourceName)}")
        destination.parentFile?.mkdirs()
        if (!destination.exists()) copySource(source, destination, MAX_VIDEO_BYTES)
        val content = ReaderContent(
            id = id,
            type = ReaderContentType.VIDEO,
            title = sourceName.substringBeforeLast('.', sourceName).ifBlank { "Video" },
            uri = Uri.fromFile(destination).toString(),
            coverUri = null,
            favorite = false,
            addedAt = System.currentTimeMillis(),
            lastOpenedAt = null,
            progress = 0,
            progressTotal = 0,
        )
        repository.upsert(content)
        collectionId?.let { repository.addToCollection(content.id, it) }
        content
    }

    suspend fun importArchive(source: Uri, collectionId: Long? = null): ReaderContent = withContext(Dispatchers.IO) {
        val sourceName = displayName(source)
        if (LocalMediaKind.identify(appContext.contentResolver.getType(source),
             sourceName) != ReaderContentType.ARCHIVE) {
            throw IOException("The selected file is not a supported archive")
        }
        val id = "archive:${stableId(source.toString())}"
        val destination = File(archiveRoot, "$id/${safeName(sourceName)}")
        destination.parentFile?.mkdirs()
        if (!destination.exists()) copySource(source, destination, MAX_ARCHIVE_BYTES)
        // Verify the file is an archive before adding it to the library.
        try {
            val pfd = android.os.ParcelFileDescriptor.open(destination, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            try {
                mihon.core.archive.ArchiveReader(pfd).use { reader ->
                    reader.useEntries { entries -> entries.take(1).toList() }
                }
            } finally {
                pfd.close()
            }
        } catch (error: Exception) {
            destination.delete()
            destination.parentFile?.delete()
            throw IOException("The selected archive is corrupt or unsupported", error)
        }
        val content = ReaderContent(
            id = id, type = ReaderContentType.ARCHIVE,
            title = sourceName.substringBeforeLast('.', sourceName).ifBlank { "Archive" },
            uri = Uri.fromFile(destination).toString(), coverUri = null,
            favorite = false, addedAt = System.currentTimeMillis(), lastOpenedAt = null,
            progress = 0, progressTotal = 0,
        )
        repository.upsert(content)
        collectionId?.let { repository.addToCollection(content.id, it) }
        content
    }

    suspend fun scanImageFolder(treeUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        runCatching { appContext.contentResolver.takePersistableUriPermission(treeUri,
             Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val directory = UniFile.fromUri(appContext, treeUri)?.takeIf { it.isDirectory }
            ?: throw IOException("The selected folder is no longer available")
        val errors = mutableListOf<String>()
        var imported = 0
        var scanned = 0
        suspend fun visit(folder: UniFile, relative: String, depth: Int) {
            if (depth > MAX_FOLDER_DEPTH || scanned >= MAX_SCANNED_FILES) return
            folder.listFiles().orEmpty().forEach { child ->
                if (scanned >= MAX_SCANNED_FILES) return
                if (child.isDirectory) {
                    val name = child.name?.takeIf(String::isNotBlank) ?: "Folder"
                    visit(child, if (relative.isBlank()) name else "$relative/$name", depth + 1)
                } else {
                    scanned++
                    val type = LocalMediaKind.identify(null, child.name.orEmpty())
                    if (type in setOf(ReaderContentType.IMAGE,
                         ReaderContentType.GIF,
                         ReaderContentType.VIDEO,
                         ReaderContentType.ARCHIVE)) {
                        runCatching {
                            val categoryId = ensureCollection(relative.ifBlank { directory.name ?: "Scanned media" })
                            when (type) {
                                ReaderContentType.IMAGE -> importImage(child.uri, categoryId)
                                ReaderContentType.GIF -> importGif(child.uri, categoryId)
                                ReaderContentType.VIDEO -> importVideo(child.uri, categoryId)
                                ReaderContentType.ARCHIVE -> importArchive(child.uri, categoryId)
                                else -> Unit
                            }
                            imported++
                        }.onFailure { errors += "${child.name}: ${it.message ?: "could not import"}" }
                    }
                }
            }
        }
        visit(directory, "", 0)
        ScanResult(imported = imported, scanned = scanned, errors = errors)
    }

    private suspend fun ensureCollection(name: String): Long {
        val existing = repository.observeCollections().first().firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (existing != null) return existing.id
        repository.createCollection(name)
        return repository.observeCollections().first().first { it.name.equals(name, ignoreCase = true) }.id
    }

    suspend fun removeImage(content: ReaderContent) {
        val uri = Uri.parse(content.uri)
        if (uri.scheme == "file") {
            val file = File(uri.path.orEmpty()).canonicalFile
            if (file.path.startsWith(mediaRoot.canonicalPath + File.separator)) file.delete()
            file.parentFile?.takeIf { it.list().isNullOrEmpty() }?.delete()
        }
        repository.remove(content.id)
    }

    suspend fun remove(content: ReaderContent) = removeImage(content)

    suspend fun exportImage(content: ReaderContent, destination: Uri) = withContext(Dispatchers.IO) {
        val source = appContext.contentResolver.openInputStream(Uri.parse(content.uri))
            ?: throw IOException("This image is no longer available")
        val target = appContext.contentResolver.openOutputStream(destination, "w")
            ?: throw IOException("The selected export destination cannot be written")
        source.use { input -> target.use { output -> input.copyTo(output) } }
    }

    private fun displayName(uri: Uri): String = runCatching {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Image"

    private fun copySource(source: Uri, destination: File, maximumBytes: Long = MAX_IMAGE_BYTES) {
        val input = appContext.contentResolver.openInputStream(source) ?: throw IOException("The selected media is no longer available")
        val temporary = File(destination.parentFile, "${destination.name}.partial")
        try {
            input.use { stream -> temporary.outputStream().buffered().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > maximumBytes) throw IOException("This media file is larger than the import limit")
                    output.write(buffer, 0, count)
                }
            } }
            if (!temporary.renameTo(destination)) throw IOException("Media could not be saved to app storage")
        } catch (error: Throwable) {
            temporary.delete()
            destination.delete()
            throw error
        }
    }

    private fun safeName(name: String): String = name.substringAfterLast('/').substringAfterLast('\\').replace(Regex("[^A-Za-z0-9._ -]"),
         "_").take(180).ifBlank { "image" }

    private fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(12)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_IMAGE_BYTES = 1024L * 1024 * 1024
        private const val MAX_VIDEO_BYTES = 10L * 1024 * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 10L * 1024 * 1024 * 1024
        private const val MAX_FOLDER_DEPTH = 12
        private const val MAX_SCANNED_FILES = 10_000
    }
}

data class ScanResult(val imported: Int, val scanned: Int, val errors: List<String>)

/** Type identification is filename and MIME based because SAF providers often omit MIME metadata. */
object LocalMediaKind {
    fun identify(mimeType: String?, fileName: String): ReaderContentType? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "gif" || mimeType.equals("image/gif", ignoreCase = true) -> ReaderContentType.GIF
            ext in IMAGE_EXTENSIONS || mimeType.orEmpty().startsWith("image/",
                 ignoreCase = true) -> ReaderContentType.IMAGE
            mimeType.orEmpty().startsWith("video/",
                 ignoreCase = true) || ext in VIDEO_EXTENSIONS -> ReaderContentType.VIDEO
            ext in ARCHIVE_EXTENSIONS -> ReaderContentType.ARCHIVE
            else -> null
        }
    }

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif", "avif", "tif", "tiff")
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "avi")
    private val ARCHIVE_EXTENSIONS = setOf("zip", "cbz", "rar", "cbr", "7z", "cb7", "tar", "cbt")
}
