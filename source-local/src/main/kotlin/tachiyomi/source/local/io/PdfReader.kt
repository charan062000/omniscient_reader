package tachiyomi.source.local.io

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import com.hippo.unifile.UniFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Renders the pages of a PDF file to JPEG images using Android's built-in [PdfRenderer].
 *
 * Every page is rendered at the same width (fit to the screen), so the reader can stack the pages
 * without gaps. Rendered pages are kept in a small memory cache so scrolling back is instant.
 */
class PdfReader(context: Context, file: UniFile) : Closeable {

    private val descriptor: ParcelFileDescriptor = context.contentResolver.openFileDescriptor(file.uri, "r")
        ?: throw IOException("Failed to open PDF: ${file.uri}")

    private val renderer: PdfRenderer = try {
        PdfRenderer(descriptor)
    } catch (e: Throwable) {
        descriptor.close()
        throw e
    }

    private val lock = Any()
    private var closed = false

    // Slightly larger than the screen so zooming in stays sharp.
    private val targetWidthPx: Int = (context.resources.displayMetrics.widthPixels * 1.5f)
        .roundToInt()
        .coerceIn(MIN_WIDTH_PX, MAX_WIDTH_PX)

    private val cache = object : LruCache<Int, ByteArray>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: ByteArray): Int = value.size
    }

    val pageCount: Int = renderer.pageCount

    /**
     * Returns the JPEG bytes of the page at [index], rendering it if it is not cached yet.
     * Safe to call from several threads; PdfRenderer only allows one open page at a time,
     * so renders are done one after another.
     */
    fun getPageBytes(index: Int): ByteArray = synchronized(lock) {
        check(!closed) { "PDF reader is closed" }
        cache.get(index) ?: renderPage(index).also { cache.put(index, it) }
    }

    private fun renderPage(index: Int): ByteArray {
        val page = renderer.openPage(index)
        try {
            val scale = targetWidthPx.toFloat() / page.width
            var width = targetWidthPx
            var height = max(1, (page.height * scale).roundToInt())
            if (height > MAX_HEIGHT_PX) {
                val shrink = MAX_HEIGHT_PX.toFloat() / height
                height = MAX_HEIGHT_PX
                width = max(1, (width * shrink).roundToInt())
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // PDF pages are transparent by default, so paint the paper white first.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

                val output = ByteArrayOutputStream(INITIAL_BUFFER_BYTES)
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                return output.toByteArray()
            } finally {
                bitmap.recycle()
            }
        } finally {
            page.close()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (!closed) {
                closed = true
                cache.evictAll()
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

    private companion object {
        const val MIN_WIDTH_PX = 1080
        const val MAX_WIDTH_PX = 1800
        const val MAX_HEIGHT_PX = 6000
        const val JPEG_QUALITY = 95
        const val INITIAL_BUFFER_BYTES = 256 * 1024
        const val CACHE_BYTES = 64 * 1024 * 1024
    }
}
