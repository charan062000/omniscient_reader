package eu.kanade.tachiyomi.ui.epub

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

/** EPUB 2/3 package, navigation, and reading-order parser. Entries are read individually. */
class EpubParser {
    fun parse(file: File): EpubBook = ZipFile(file).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size <= MAX_ENTRIES) { "EPUB contains too many archive entries" }
        val byName = entries.associateBy { normalizePath(it.name) }
        val container = byName["META-INF/container.xml"] ?: throw IOException("EPUB container.xml is missing")
        val containerDoc = Jsoup.parse(readText(zip, container, MAX_CONTAINER_BYTES), "", Parser.xmlParser())
        val opfPath = containerDoc.selectFirst("rootfile[full-path]")?.attr("full-path")
            ?: throw IOException("EPUB package document is missing")
        val normalizedOpf = normalizePath(opfPath)
        val opfEntry = byName[normalizedOpf] ?: throw IOException("EPUB package document could not be found")
        val opf = Jsoup.parse(readText(zip, opfEntry, MAX_OPF_BYTES), "", Parser.xmlParser())
        val base = normalizedOpf.substringBeforeLast('/', "")
        val manifest = linkedMapOf<String, ManifestItem>()
        opf.select("manifest > item").forEach { element ->
            val id = element.attr("id")
            val href = resolvePath(base, element.attr("href"))
            if (id.isNotBlank() && href != null) {
                manifest[id] = ManifestItem(id, href, element.attr("media-type"), element.attr("properties").split(' '))
            }
        }
        val spine = opf.select("spine > itemref").mapNotNull { manifest[it.attr("idref")] }
        val ordered = if (spine.isNotEmpty()) spine else manifest.values.filter { it.mediaType in HTML_TYPES }
        if (ordered.isEmpty()) throw IOException("EPUB has no readable chapters")
        val nav = manifest.values.firstOrNull { "nav" in it.properties }
        val navLabels = nav?.let { readNavigation(zip, it.path) }.orEmpty()
        val ncx = manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        val ncxLabels = ncx?.let { readNcx(zip, it.path) }.orEmpty()
        val chapters = ordered.mapIndexed { index, item ->
            val label = navLabels[item.path] ?: ncxLabels[item.path] ?: chapterTitle(zip, byName[item.path], item.path)
            EpubChapter(item.path, label.ifBlank { "Chapter ${index + 1}" }, index)
        }
        val metadata = opf.getAllElements().firstOrNull { it.tagName().substringAfter(':').equals("metadata", true) }
        val title = metadata?.let { childText(it, "title") }?.ifBlank { file.nameWithoutExtension } ?: file.nameWithoutExtension
        val creators = metadata?.getAllElements()?.filter { it.tagName().substringAfter(':').equals("creator", true) }
            ?.map { it.text().trim() }?.filter(String::isNotBlank).orEmpty()
        val coverId = opf.selectFirst("meta[name=cover]")?.attr("content")
        val cover = manifest[coverId]?.path ?: manifest.values.firstOrNull { "cover-image" in it.properties }?.path
            ?: manifest.values.firstOrNull { it.id.contains("cover", true) && it.mediaType.startsWith("image/") }?.path
        val direction = if (opf.selectFirst("spine")?.attr("page-progression-direction") == "rtl") ReadingDirection.RTL else ReadingDirection.LTR
        EpubBook(title, creators, metadata?.let { childText(it, "language") }, cover, direction, chapters)
    }

    fun readChapter(file: File, chapter: EpubChapter): String = ZipFile(file).use { zip ->
        val entry = zip.getEntry(chapter.href) ?: throw IOException("Chapter resource is missing")
        readText(zip, entry, MAX_CHAPTER_BYTES)
    }

    fun search(file: File, book: EpubBook, query: String): List<EpubSearchResult> {
        val needle = query.trim()
        if (needle.length < 2) return emptyList()
        return book.chapters.mapNotNull { chapter ->
            val plain = Jsoup.parse(readChapter(file, chapter)).text()
            val index = plain.indexOf(needle, ignoreCase = true)
            if (index < 0) null else {
                val start = (index - 70).coerceAtLeast(0)
                val end = (index + needle.length + 100).coerceAtMost(plain.length)
                EpubSearchResult(chapter.index, chapter.title, plain.substring(start, end).trim())
            }
        }
    }

    private fun readNavigation(zip: ZipFile, path: String): Map<String, String> {
        val entry = zip.getEntry(path) ?: return emptyMap()
        val doc = Jsoup.parse(readText(zip, entry, MAX_NAV_BYTES), "", Parser.xmlParser())
        val base = path.substringBeforeLast('/', "")
        return doc.select("nav a[href]").mapNotNull { a ->
            val target = resolvePath(base, a.attr("href").substringBefore('#')) ?: return@mapNotNull null
            target to a.text().trim()
        }.toMap()
    }

    private fun readNcx(zip: ZipFile, path: String): Map<String, String> {
        val entry = zip.getEntry(path) ?: return emptyMap()
        val doc = Jsoup.parse(readText(zip, entry, MAX_NAV_BYTES), "", Parser.xmlParser())
        val base = path.substringBeforeLast('/', "")
        return doc.select("navPoint").mapNotNull { point ->
            val src = point.selectFirst("content[src]")?.attr("src") ?: return@mapNotNull null
            val target = resolvePath(base, src.substringBefore('#')) ?: return@mapNotNull null
            target to point.selectFirst("navLabel")?.text()?.trim().orEmpty()
        }.toMap()
    }

    private fun chapterTitle(zip: ZipFile, entry: java.util.zip.ZipEntry?, path: String): String {
        if (entry == null || entry.size > MAX_CHAPTER_BYTES) return path.substringAfterLast('/').substringBeforeLast('.')
        return runCatching {
            Jsoup.parse(readText(zip, entry, MAX_CHAPTER_BYTES)).title().trim()
        }.getOrDefault(path.substringAfterLast('/').substringBeforeLast('.'))
    }

    private fun childText(element: org.jsoup.nodes.Element, localName: String): String? =
        element.getAllElements().firstOrNull { it.tagName().substringAfter(':').equals(localName, true) }
            ?.text()?.trim()?.takeIf(String::isNotBlank)

    private fun resolvePath(base: String, href: String): String? {
        val decoded = runCatching { java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return null
        if (decoded.startsWith('/') || decoded.contains(':')) return null
        return normalizePath(listOf(base, decoded).filter(String::isNotEmpty).joinToString("/"))
            .takeIf { it.isNotBlank() && !it.startsWith("../") }
    }

    private fun normalizePath(path: String): String {
        val stack = ArrayDeque<String>()
        path.substringBefore('#').substringBefore('?').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (stack.isNotEmpty()) stack.removeLast() else stack.addLast("..")
                else -> stack.addLast(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun readText(zip: ZipFile, entry: java.util.zip.ZipEntry, maxBytes: Long): String {
        if (entry.size > maxBytes) throw IOException("EPUB resource exceeds the supported size limit")
        val output = java.io.ByteArrayOutputStream(minOf(entry.size.coerceAtLeast(0L), 64 * 1024L).toInt())
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IOException("EPUB resource exceeds the supported size limit")
                output.write(buffer, 0, count)
            }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private data class ManifestItem(val id: String, val path: String, val mediaType: String, val properties: List<String>)

    private companion object {
        const val MAX_ENTRIES = 10_000
        const val MAX_CHAPTER_BYTES = 16L * 1024 * 1024
        const val MAX_CONTAINER_BYTES = 1024L * 1024
        const val MAX_OPF_BYTES = 4L * 1024 * 1024
        const val MAX_NAV_BYTES = 8L * 1024 * 1024
        val HTML_TYPES = setOf("application/xhtml+xml", "text/html")
    }
}
