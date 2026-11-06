package eu.kanade.tachiyomi.ui.epub

data class EpubBook(
    val title: String,
    val authors: List<String>,
    val language: String?,
    val coverPath: String?,
    val direction: ReadingDirection,
    val chapters: List<EpubChapter>,
)

data class EpubChapter(val href: String, val title: String, val index: Int)

enum class ReadingDirection { LTR, RTL }

data class EpubSearchResult(val chapterIndex: Int, val chapterTitle: String, val excerpt: String)
