package tachiyomi.data.library.content

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.Reader_contentQueries
import tachiyomi.domain.library.content.model.PdfAnnotation
import tachiyomi.domain.library.content.model.PdfAnnotationType
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType

class ReaderLibraryRepositoryImplTest {
    @Test
    fun `local media kinds are persisted through the unified content row`() = runTest {
        val queries = mockk<Reader_contentQueries>()
        val database = mockk<Database>()
        every { database.reader_contentQueries } returns queries
        coEvery { queries.upsert(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1L
        val types = listOf(ReaderContentType.IMAGE, ReaderContentType.GIF, ReaderContentType.VIDEO, ReaderContentType.ARCHIVE)

        types.forEach { type ->
            ReaderLibraryRepositoryImpl(database).upsert(
                ReaderContent("${type.name.lowercase()}:fixture", type, "fixture", "file:///fixture", null, false, 1, null, 0, 0),
            )
        }

        types.forEach { type ->
            coVerify(exactly = 1) {
                queries.upsert("${type.name.lowercase()}:fixture", type.name, "fixture", "file:///fixture", null, false, 1, null, 0, 0, null)
            }
        }
    }

    @Test
    fun `reading progress is persisted in the generic content row`() = runTest {
        val queries = mockk<Reader_contentQueries>()
        val database = mockk<Database>()
        every { database.reader_contentQueries } returns queries
        coEvery { queries.updateProgress(any(), any(), any(), any()) } returns 1L

        ReaderLibraryRepositoryImpl(database).updateProgress("epub:book", 4, 18, 1234)

        coVerify(exactly = 1) { queries.updateProgress(4, 18, 1234, "epub:book") }
    }

    @Test
    fun `progress values are clamped before persistence`() = runTest {
        val queries = mockk<Reader_contentQueries>()
        val database = mockk<Database>()
        every { database.reader_contentQueries } returns queries
        coEvery { queries.updateProgress(any(), any(), any(), any()) } returns 1L

        ReaderLibraryRepositoryImpl(database).updateProgress("epub:book", -2, -5, 456)

        coVerify(exactly = 1) { queries.updateProgress(0, 0, 456, "epub:book") }
    }

    @Test
    fun `pdf annotation geometry and metadata are persisted`() = runTest {
        val queries = mockk<Reader_contentQueries>()
        val database = mockk<Database>()
        every { database.reader_contentQueries } returns queries
        coEvery { queries.upsertPdfAnnotation(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns 1L
        val annotation = PdfAnnotation(
            id = "mark-1", documentId = "pdf:book-1", pageIndex = 7, type = PdfAnnotationType.HIGHLIGHT,
            text = "important", imageUri = null, left = .1, top = .2, right = .6, bottom = .25, createdAt = 1234,
        )

        ReaderLibraryRepositoryImpl(database).upsertPdfAnnotation(annotation)

        coVerify(exactly = 1) {
            queries.upsertPdfAnnotation("mark-1", "pdf:book-1", 7, "HIGHLIGHT", "important", null, .1, .2, .6, .25, 1234)
        }
    }
}
