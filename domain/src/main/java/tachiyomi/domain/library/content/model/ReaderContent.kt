package tachiyomi.domain.library.content.model

/** Content tracked by the cross-format reader library. Manga remains in its existing model/tables. */
enum class ReaderContentType {
    EPUB,
    PDF,
    IMAGE,
    GIF,
    VIDEO,
    ARCHIVE,
    TEXT,
    ;

    companion object {
        fun fromStorage(value: String): ReaderContentType? = entries.firstOrNull { it.name == value }
    }
}

data class ReaderContent(
    val id: String,
    val type: ReaderContentType,
    val title: String,
    val uri: String,
    val coverUri: String?,
    val favorite: Boolean,
    val addedAt: Long,
    val lastOpenedAt: Long?,
    val progress: Long,
    val progressTotal: Long,
    val author: String? = null,
)

data class ReaderCollection(
    val id: Long,
    val name: String,
    val order: Long,
)
