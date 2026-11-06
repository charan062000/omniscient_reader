package tachiyomi.domain.library.content.model

import tachiyomi.domain.library.model.LibraryManga

/** A presentation-neutral projection over Mihon manga and newly supported personal content. */
sealed interface UnifiedLibraryItem {
    val id: String
    val kind: Kind
    val title: String
    val coverUri: String?
    val favorite: Boolean
    val progress: Long
    val progressTotal: Long

    enum class Kind {
        MANGA,
        EPUB,
        PDF,
        TEXT,
        IMAGE,
        GIF,
        VIDEO,
        ARCHIVE,
    }

    data class MangaEntry(val libraryManga: LibraryManga) : UnifiedLibraryItem {
        override val id: String get() = "manga:${libraryManga.manga.id}"
        override val kind: Kind get() = Kind.MANGA
        override val title: String get() = libraryManga.manga.title
        override val coverUri: String? get() = libraryManga.manga.thumbnailUrl
        override val favorite: Boolean get() = libraryManga.manga.favorite
        override val progress: Long get() = libraryManga.readCount
        override val progressTotal: Long get() = libraryManga.totalChapters
    }

    data class ReaderEntry(val content: ReaderContent) : UnifiedLibraryItem {
        override val id: String get() = content.id
        override val kind: Kind get() = Kind.valueOf(content.type.name)
        override val title: String get() = content.title
        override val coverUri: String? get() = content.coverUri
        override val favorite: Boolean get() = content.favorite
        override val progress: Long get() = content.progress
        override val progressTotal: Long get() = content.progressTotal
    }
}
