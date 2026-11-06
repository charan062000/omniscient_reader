package tachiyomi.domain.library.content.repository

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.library.content.model.ReaderCollection
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType
import tachiyomi.domain.library.content.model.EpubBookmark
import tachiyomi.domain.library.content.model.PdfAnnotation

/** Persistence boundary for imported books and personal media. It does not own manga records. */
interface ReaderLibraryRepository {
    fun observeAll(type: ReaderContentType? = null): Flow<List<ReaderContent>>
    suspend fun get(id: String): ReaderContent?
    suspend fun upsert(content: ReaderContent)
    suspend fun remove(id: String)
    suspend fun setFavorite(id: String, favorite: Boolean)
    suspend fun updateProgress(id: String, progress: Long, total: Long, openedAt: Long)
    fun observeCollections(): Flow<List<ReaderCollection>>
    fun observeContentIdsInCollection(collectionId: Long): Flow<Set<String>>
    suspend fun createCollection(name: String)
    suspend fun addToCollection(contentId: String, collectionId: Long)
    suspend fun setCollections(contentId: String, collectionIds: List<Long>)
    fun observeEpubBookmarks(bookId: String): Flow<List<EpubBookmark>>
    suspend fun addEpubBookmark(bookId: String, chapterIndex: Long, chapterTitle: String, selectedText: String?, context: String?)
    suspend fun removeEpubBookmark(bookmarkId: Long)
    fun observePdfAnnotations(documentId: String): Flow<List<PdfAnnotation>>
    suspend fun upsertPdfAnnotation(annotation: PdfAnnotation)
    suspend fun removePdfAnnotation(annotationId: String)
}
