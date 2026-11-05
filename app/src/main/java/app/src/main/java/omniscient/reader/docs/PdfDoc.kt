package omniscient.reader.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import kotlin.math.max
import kotlin.math.roundToInt

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
                val height = max(1, (page.height * widthPx.toFloat() / page.width).roundToInt())
                val bitmap = Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888)
                // PDF pages are transparent by default, so paint the paper white first.
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bitmap
            } finally {
                page.close()
            }
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

        /**
         * Opens the PDF at [uri]. Throws if the file is missing, damaged or password protected.
         */
        fun open(context: Context, uri: Uri): PdfDoc {
            val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
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
