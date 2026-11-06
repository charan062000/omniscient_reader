package eu.kanade.tachiyomi.ui.epub

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubParserTest {
    private val parser = EpubParser()

    @Test
    fun `parses epub 2 metadata ncx navigation and spine order`() {
        val epub = fixture(
            """
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Legacy Novel</dc:title><dc:creator>A. Writer</dc:creator><dc:language>en</dc:language><meta name="cover" content="cover"/></metadata>
              <manifest><item id="toc" href="toc.ncx" media-type="application/x-dtbncx+xml"/><item id="c2" href="Text/chapter2.xhtml" media-type="application/xhtml+xml"/><item id="c1" href="Text/chapter1.xhtml" media-type="application/xhtml+xml"/><item id="cover" href="Images/cover.jpg" media-type="image/jpeg"/></manifest>
              <spine toc="toc"><itemref idref="c1"/><itemref idref="c2"/></spine>
            </package>
            """.trimIndent(),
            mapOf(
                "toc.ncx" to
                    "<ncx><navMap><navPoint><navLabel><text>Opening</text></navLabel><content src=\"Text/chapter1.xhtml\"/></navPoint><navPoint><navLabel><text>Afterword</text></navLabel><content src=\"Text/chapter2.xhtml\"/></navPoint></navMap></ncx>",
                "Text/chapter1.xhtml" to
                    "<html><head><title>First</title></head><body><p>First chapter body.</p></body></html>",
                "Text/chapter2.xhtml" to "<html><body><p>Last chapter searchable phrase.</p></body></html>",
                "Images/cover.jpg" to "cover-data",
            ),
        )
        val book = parser.parse(epub)
        assertEquals("Legacy Novel", book.title)
        assertEquals(listOf("A. Writer"), book.authors)
        assertEquals("en", book.language)
        assertEquals("OEBPS/Images/cover.jpg", book.coverPath)
        assertEquals(listOf("Opening", "Afterword"), book.chapters.map(EpubChapter::title))
        assertTrue(parser.readChapter(epub, book.chapters[0]).contains("First chapter body"))
        assertEquals(1, parser.search(epub, book, "searchable phrase").single().chapterIndex)
        epub.delete()
    }

    @Test
    fun `parses epub 3 nav order direction and linked cover`() {
        val epub = fixture(
            """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Right to Left</dc:title></metadata>
              <manifest><item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/><item id="cover" href="cover.png" media-type="image/png" properties="cover-image"/><item id="one" href="chapter.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine page-progression-direction="rtl"><itemref idref="one"/></spine>
            </package>
            """.trimIndent(),
            mapOf(
                "nav.xhtml" to
                    "<html xmlns:epub=\"http://www.idpf.org/2007/ops\"><body><nav epub:type=\"toc\"><ol><li><a href=\"chapter.xhtml\">Chapter One</a></li></ol></nav></body></html>",
                "chapter.xhtml" to "<html><body><p>Book text.</p></body></html>",
                "cover.png" to "image-data",
            ),
        )
        val book = parser.parse(epub)
        assertEquals("Right to Left", book.title)
        assertEquals(ReadingDirection.RTL, book.direction)
        assertEquals("OEBPS/cover.png", book.coverPath)
        assertEquals("Chapter One", book.chapters.single().title)
        epub.delete()
    }

    @Test
    fun `reports malformed archive and missing reading order`() {
        val malformed = File.createTempFile("bad-", ".epub").apply { writeText("not a zip") }
        assertThrows(Exception::class.java) { parser.parse(malformed) }
        malformed.delete()
        val empty =
            fixture(
                "<package><metadata><title>Empty</title></metadata><manifest></manifest><spine/></package>",
                emptyMap(),
            )
        assertThrows(Exception::class.java) { parser.parse(empty) }
        empty.delete()
    }

    private fun fixture(opf: String, extraEntries: Map<String, String>): File {
        val file = File.createTempFile("reader-test-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun add(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            add("mimetype", "application/epub+zip")
            add(
                "META-INF/container.xml",
                "<container><rootfiles><rootfile full-path=\"OEBPS/content.opf\"/></rootfiles></container>",
            )
            add("OEBPS/content.opf", opf)
            extraEntries.forEach { (path, text) -> add("OEBPS/$path", text) }
        }
        return file
    }
}
