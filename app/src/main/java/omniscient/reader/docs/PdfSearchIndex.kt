package omniscient.reader.docs

/** Small pure-Kotlin helpers for snippets and page grouping; PDF decoding remains in PdfRenderer. */
object PdfSearchIndex {
    fun snippet(text: String, query: String, contextChars: Int = 64): String {
        if (text.isBlank()) return ""
        val index = text.indexOf(query, ignoreCase = true)
        if (index < 0) return text.take(contextChars * 2 + query.length).trim()
        val rawStart = (index - contextChars).coerceAtLeast(0)
        val rawEnd = (index + query.length + contextChars).coerceAtMost(text.length)
        val start = if (rawStart == 0) 0 else text.indexOf(' ', rawStart).let { if (it < 0) rawStart else it + 1 }
        val end = if (rawEnd == text.length) text.length else text.lastIndexOf(' ', rawEnd).let { if (it <= index + query.length) rawEnd else it }
        return buildString {
            if (start > 0) append('…')
            append(text.substring(start, end).trim())
            if (end < text.length) append('…')
        }
    }

    fun groupPages(pageCount: Int, dualPage: Boolean): List<List<Int>> {
        if (pageCount <= 0) return emptyList()
        if (!dualPage || pageCount == 1) return (0 until pageCount).map { listOf(it) }
        val groups = mutableListOf(listOf(0)) // Keep a cover page by itself.
        var page = 1
        while (page < pageCount) {
            groups += (page until minOf(page + 2, pageCount)).toList()
            page += 2
        }
        return groups
    }
}
