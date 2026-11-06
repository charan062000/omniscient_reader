package tachiyomi.domain.library.content.model

data class EpubBookmark(
    val id: Long,
    val bookId: String,
    val chapterIndex: Long,
    val chapterTitle: String,
    val selectedText: String?,
    val context: String?,
    val createdAt: Long,
)
