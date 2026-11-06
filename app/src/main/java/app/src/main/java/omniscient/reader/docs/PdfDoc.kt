package omniscient.reader.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.os.Build
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class PdfMatchBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class PdfTextSearchResult(
    val pageIndex: Int,
    val excerpt: String,
    val matchedText: String,
    val bounds: List<PdfMatchBounds>,
)

/**
 * A PDF file opened with Android's built-in [PdfRenderer].
 * The renderer only allows one page to be open at a time, so every call is guarded by a mutex.
 */
class PdfDoc private constructor(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
) : Closeable {

    private val mutex = Mutex()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var closed = false

    val pageCount: Int = renderer.pageCount

    /**
     * Returns width / height of every page, used to reserve the right space before a page is drawn
     * so the list does not jump around while scrolling.
     */
    suspend fun aspectRatios(): FloatArray = withContext(Dispatchers.Default) {
        val ratios = FloatArray(pageCount) { DEFAULT_ASPECT }
        for (index in 0 until pageCount) {
            mutex.withLock {
                if (closed) return@withContext ratios
                val page = renderer.openPage(index)
                try {
                    if (page.height > 0) ratios[index] = page.width.toFloat() / page.height
                } finally {
                    page.close()
                }
            }
        }
        ratios
    }

    /**
     * Draws page [index] to a bitmap that is [widthPx] wide. Returns null if the document is closed.
     */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (closed) return@withContext null
            val page = renderer.openPage(index)
            try {
                var renderWidth = widthPx.coerceIn(1, MAX_RENDER_WIDTH)
                var height = max(1, (page.height * renderWidth.toFloat() / page.width).roundToInt())
                val pixels = renderWidth.toLong() * height
                if (pixels > MAX_RENDER_PIXELS) {
                    val factor = sqrt(MAX_RENDER_PIXELS.toDouble() / pixels).toFloat()
                    renderWidth = max(1, (renderWidth * factor).roundToInt())
                    height = max(1, (height * factor).roundToInt())
                }
                val bitmap = Bitmap.createBitmap(renderWidth, height, Bitmap.Config.ARGB_8888)
                // PDF pages are transparent by default, so paint the paper white first.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
        }
    }

    /** Uses PdfRenderer's native text index on Android 15+; scanned/image-only PDFs yield no hits. */
    suspend fun searchText(query: String): List<PdfTextSearchResult> = withContext(Dispatchers.IO) {
        val needle = query.trim()
        if (needle.length < 2 || Build.VERSION.SDK_INT < 35) return@withContext emptyList()
        val results = mutableListOf<PdfTextSearchResult>()
        for (index in 0 until pageCount) {
            mutex.withLock {
                if (closed) return@withContext results
                val page = renderer.openPage(index)
                try {
                    results += searchPage(page, index, needle)
                } finally {
                    page.close()
                }
            }
        }
        results
    }

    @RequiresApi(35)
    private fun searchPage(page: PdfRenderer.Page, pageIndex: Int, query: String): List<PdfTextSearchResult> {
        val contents = page.textContents
        val fullText = contents.joinToString(" ") { it.text }
        return page.searchText(query).mapNotNull { match ->
            val bounds = match.bounds.map { rect -> rect.normalized(page.width, page.height) }
            if (bounds.isEmpty()) return@mapNotNull null
            PdfTextSearchResult(pageIndex = pageIndex, excerpt = PdfSearchIndex.snippet(fullText, query), matchedText = query, bounds = bounds)
        }
    }

    override fun close() {
        closed = true
        // Wait (off the main thread) for any page still being drawn, then release the file.
        cleanupScope.launch {
            mutex.withLock {
                try {
                    renderer.close()
                } catch (_: Throwable) {
                    // Already closed
                }
                try {
                    descriptor.close()
                } catch (_: Throwable) {
                    // Already closed
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_ASPECT = 0.707f
        private const val MAX_RENDER_WIDTH = 4096
        private const val MAX_RENDER_PIXELS = 16L * 1024 * 1024

        /**
         * Opens the PDF at [uri]. Throws if the file is missing, damaged or password protected.
         */
        fun open(context: Context, uri: Uri): PdfDoc {
            val descriptor = if (uri.scheme == "file") {
                ParcelFileDescriptor.open(File(uri.path ?: throw IOException("Missing file path")), ParcelFileDescriptor.MODE_READ_ONLY)
            } else {
                context.contentResolver.openFileDescriptor(uri, "r")
            }
                ?: throw IOException("Could not open the file")
            return try {
                PdfDoc(descriptor, PdfRenderer(descriptor))
            } catch (e: Throwable) {
                descriptor.close()
                throw e
            }
        }
    }
}

private fun RectF.normalized(pageWidth: Int, pageHeight: Int) = PdfMatchBounds(
    left = (left / pageWidth).coerceIn(0f, 1f),
    top = (top / pageHeight).coerceIn(0f, 1f),
    right = (right / pageWidth).coerceIn(0f, 1f),
    bottom = (bottom / pageHeight).coerceIn(0f, 1f),
)
