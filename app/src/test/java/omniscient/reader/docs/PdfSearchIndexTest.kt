package omniscient.reader.docs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PdfSearchIndexTest {
    @Test
    fun `snippet returns matching context independent of case`() {
        val text = "A searchable report has the phrase Quarterly Revenue near its center and more text follows."
        assertEquals(
            "A searchable report has the phrase Quarterly Revenue near its center and more text…",
            PdfSearchIndex.snippet(text, "quarterly revenue", 36),
        )
    }

    @Test
    fun `page grouping supports single and cover-aware dual layouts`() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2), listOf(3)), PdfSearchIndex.groupPages(4, false))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3)), PdfSearchIndex.groupPages(4, true))
        assertEquals(emptyList<List<Int>>(), PdfSearchIndex.groupPages(0, true))
    }
}
