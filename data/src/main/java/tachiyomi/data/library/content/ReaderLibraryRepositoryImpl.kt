package tachiyomi.data.library.content

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.library.content.model.EpubBookmark
import tachiyomi.domain.library.content.model.PdfAnnotation
import tachiyomi.domain.library.content.model.PdfAnnotationType
import tachiyomi.domain.library.content.model.ReaderCollection
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType
import tachiyomi.domain.library.content.repository.ReaderLibraryRepository

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class ReaderLibraryRepositoryImpl(
    private val database: Database,
) : ReaderLibraryRepository {

    override fun observeAll(type: ReaderContentType?): Flow<List<ReaderContent>> =
        database.reader_contentQueries.observeAll(type?.name, ::mapContent).subscribeToList()

    override suspend fun get(id: String): ReaderContent? =
        database.reader_contentQueries.getById(id, ::mapContent).awaitAsOneOrNull()

    override suspend fun upsert(content: ReaderContent) {
        database.reader_contentQueries.upsert(
            id = content.id,
            type = content.type.name,
            title = content.title,
            contentUri = content.uri,
            coverUri = content.coverUri,
            favorite = content.favorite,
            addedAt = content.addedAt,
            lastOpenedAt = content.lastOpenedAt,
            progress = content.progress,
            progressTotal = content.progressTotal,
            author = content.author,
        )
    }

    override suspend fun remove(id: String) {
        database.transaction {
            database.reader_contentQueries.removePdfAnnotationsForDocument(id)
            database.reader_contentQueries.remove(id)
        }
    }

    override suspend fun setFavorite(id: String, favorite: Boolean) {
        database.reader_contentQueries.setFavorite(favorite = favorite, id = id)
    }

    override suspend fun updateProgress(id: String, progress: Long, total: Long, openedAt: Long) {
        database.reader_contentQueries.updateProgress(
            progress = progress.coerceAtLeast(0),
            total = total.coerceAtLeast(0),
            openedAt = openedAt,
            id = id,
        )
    }

    override fun observeCollections(): Flow<List<ReaderCollection>> =
        database.reader_contentQueries.observeCollections(::mapCollection).subscribeToList()

    override fun observeContentIdsInCollection(collectionId: Long): Flow<Set<String>> =
        database.reader_contentQueries.observeContentIdsInCollection(collectionId).subscribeToList().map { it.toSet() }

    override suspend fun createCollection(name: String) {
        val normalized = name.trim()
        require(normalized.isNotEmpty()) { "Collection name cannot be empty" }
        database.reader_contentQueries.insertCollection(normalized)
    }

    override suspend fun addToCollection(contentId: String, collectionId: Long) {
        database.reader_contentQueries.addCollectionToContent(contentId, collectionId)
    }

    override suspend fun setCollections(contentId: String, collectionIds: List<Long>) {
        database.transaction {
            database.reader_contentQueries.clearCollectionsForContent(contentId)
            collectionIds.distinct().forEach { id ->
                database.reader_contentQueries.addCollectionToContent(contentId, id)
            }
        }
    }

    override fun observeEpubBookmarks(bookId: String): Flow<List<EpubBookmark>> =
        database.reader_contentQueries.observeBookmarks(bookId, ::mapBookmark).subscribeToList()

    override suspend fun addEpubBookmark(
        bookId: String,
        chapterIndex: Long,
        chapterTitle: String,
        selectedText: String?,
        context: String?,
    ) {
        database.reader_contentQueries.insertBookmark(
            bookId,
            chapterIndex,
            chapterTitle,
            selectedText,
            context,
            System.currentTimeMillis(),
        )
    }

    override suspend fun removeEpubBookmark(bookmarkId: Long) {
        database.reader_contentQueries.removeBookmark(bookmarkId)
    }

    override fun observePdfAnnotations(documentId: String): Flow<List<PdfAnnotation>> =
        database.reader_contentQueries.observePdfAnnotations(documentId, ::mapPdfAnnotation).subscribeToList()

    override suspend fun upsertPdfAnnotation(annotation: PdfAnnotation) {
        database.reader_contentQueries.upsertPdfAnnotation(
            id = annotation.id,
            documentId = annotation.documentId,
            pageIndex = annotation.pageIndex,
            type = annotation.type.name,
            text = annotation.text,
            imageUri = annotation.imageUri,
            left = annotation.left,
            top = annotation.top,
            right = annotation.right,
            bottom = annotation.bottom,
            createdAt = annotation.createdAt,
        )
    }

    override suspend fun removePdfAnnotation(annotationId: String) {
        database.reader_contentQueries.removePdfAnnotation(annotationId)
    }

    private fun mapContent(
        id: String,
        type: String,
        title: String,
        content_uri: String,
        cover_uri: String?,
        favorite: Boolean,
        added_at: Long,
        last_opened_at: Long?,
        progress: Long,
        progress_total: Long,
        author: String?,
    ): ReaderContent {
        val parsedType = requireNotNull(ReaderContentType.fromStorage(type)) {
            "Unknown reader content type: $type"
        }
        return ReaderContent(
            id = id,
            type = parsedType,
            title = title,
            uri = content_uri,
            coverUri = cover_uri,
            favorite = favorite,
            addedAt = added_at,
            lastOpenedAt = last_opened_at,
            progress = progress,
            progressTotal = progress_total,
            author = author,
        )
    }

    private fun mapCollection(id: Long, name: String, order: Long) =
        ReaderCollection(id = id, name = name, order = order)

    private fun mapBookmark(
        id: Long,
        book_id: String,
        chapter_index: Long,
        chapter_title: String,
        selected_text: String?,
        context: String?,
        created_at: Long,
    ) =
        EpubBookmark(id, book_id, chapter_index, chapter_title, selected_text, context, created_at)

    private fun mapPdfAnnotation(
        id: String,
        document_id: String,
        page_index: Long,
        type: String,
        text: String?,
        image_uri: String?,
        rect_left: Double,
        rect_top: Double,
        rect_right: Double,
        rect_bottom: Double,
        created_at: Long,
    ) = PdfAnnotation(
        id, document_id, page_index, requireNotNull(PdfAnnotationType.entries.firstOrNull { it.name == type }),
        text, image_uri, rect_left, rect_top, rect_right, rect_bottom, created_at,
    )
}
