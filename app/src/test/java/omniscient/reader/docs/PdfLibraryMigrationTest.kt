package omniscient.reader.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.content.model.ReaderContentType

class PdfLibraryMigrationTest {
    @Test
    fun `legacy document metadata cover and progress map to unified PDF content`() {
        val legacy = DocItem(
            id = "legacy-42",
            title = "The preserved title",
            uri = "content://documents/book-42",
            type = "pdf",
            pageCount = 240,
            lastPage = 37,
            addedAt = 123456,
        )

        val migrated = mapLegacyPdfContent(legacy, "file:///covers/legacy-42.jpg")

        assertEquals("pdf:legacy-42", migrated.id)
        assertEquals(ReaderContentType.PDF, migrated.type)
        assertEquals(legacy.title, migrated.title)
        assertEquals(legacy.uri, migrated.uri)
        assertEquals("file:///covers/legacy-42.jpg", migrated.coverUri)
        assertEquals(37L, migrated.progress)
        assertEquals(240L, migrated.progressTotal)
        assertEquals(legacy.addedAt, migrated.addedAt)
        assertFalse(migrated.favorite)
        assertNull(migrated.lastOpenedAt)
    }

    @Test
    fun `legacy progress is clamped to valid nonnegative values`() {
        val legacy = DocItem("legacy-negative", "PDF", "content://pdf", "pdf", -1, -4, 10)

        val migrated = mapLegacyPdfContent(legacy, null)

        assertEquals(0L, migrated.progress)
        assertEquals(0L, migrated.progressTotal)
        assertNull(migrated.coverUri)
    }
}
