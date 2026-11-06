package eu.kanade.tachiyomi.ui.readerlibrary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArchivePathIndexTest {
    @Test
    fun `projects direct files and inferred folders without extracting`() {
        val entries = listOf("cover.jpg", "chapter/001.png", "chapter/info.txt", "nested/deep/page.webp")
        val root = ArchivePathIndex.children(entries, "")
        assertEquals(listOf("chapter", "nested", "cover.jpg"), root.map { it.name })
        assertTrue(root.first().directory)
        assertTrue(root.last().previewable)
        assertTrue(ArchivePathIndex.children(entries, "chapter/").any { it.name == "001.png" && it.previewable })
    }

    @Test
    fun `rejects traversal entries and marks unsupported files non previewable`() {
        val entries = listOf("../secret.png", "safe/document.txt", "safe/photo.gif")
        val rows = ArchivePathIndex.children(entries, "")
        assertFalse(rows.any { it.name == ".." })
        assertEquals(setOf(false, true), ArchivePathIndex.children(entries, "safe/").map { it.previewable }.toSet())
    }
}
