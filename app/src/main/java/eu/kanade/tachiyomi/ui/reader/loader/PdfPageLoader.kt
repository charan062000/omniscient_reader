package eu.kanade.tachiyomi.ui.reader.loader

import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import tachiyomi.source.local.io.PdfReader
import java.io.ByteArrayInputStream

/**
 * Loader used to load a chapter from a .pdf file. Each PDF page becomes one reader page, so the
 * normal webtoon (continuous, gapless) and paged viewers work on PDFs without any changes.
 */
internal class PdfPageLoader(private val reader: PdfReader) : PageLoader() {

    override var isLocal: Boolean = true

    override suspend fun getPages(): List<ReaderPage> = List(reader.pageCount) { index ->
        ReaderPage(index).apply {
            stream = { ByteArrayInputStream(reader.getPageBytes(index)) }
            status = Page.State.Ready
        }
    }

    /**
     * Renders the requested page and the pages around it ahead of time, so scrolling stays smooth.
     */
    override suspend fun loadPage(page: ReaderPage) {
        check(!isRecycled)
        for (offset in PREFETCH_OFFSETS) {
            currentCoroutineContext().ensureActive()
            val index = page.index + offset
            if (isRecycled) return
            if (index < 0 || index >= reader.pageCount) continue
            try {
                reader.getPageBytes(index)
            } catch (_: Exception) {
                // Reader was closed or the page is broken; the page's own load will report it.
                return
            }
        }
    }

    override fun recycle() {
        super.recycle()
        reader.close()
    }

    private companion object {
        val PREFETCH_OFFSETS = intArrayOf(0, 1, 2, -1)
    }
}
