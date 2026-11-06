package tachiyomi.data.library.content

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.data.Database
import tachiyomi.data.Reader_contentQueries

class ReaderLibraryRepositoryImplTest {
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
}
