package omniscient.reader.docs

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.view.Window
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import kotlin.math.max
import kotlin.math.roundToInt

private const val RENDER_SCALE = 1.25f
private const val PREFETCH_PAGES = 3
private const val PROGRESS_SAVE_DELAY_MS = 600L
private val BarColor = Color.Black.copy(alpha = 0.72f)

/**
 * Continuous PDF reader: pages are stacked with no gaps, fit to the screen width,
 * and the position is remembered between sessions.
 */
@Composable
fun PdfReaderScreen(
    item: DocItem,
    store: DocStore,
    window: Window,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current

    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var ratios by remember { mutableStateOf<FloatArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }

    LaunchedEffect(item.id) {
        try {
            val opened = withContext(Dispatchers.IO) { PdfDoc.open(context, Uri.parse(item.uri)) }
            doc = opened
            ratios = opened.aspectRatios()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = "This PDF could not be opened. It may be password protected, damaged, or moved."
        }
    }

    DisposableEffect(item.id) {
        onDispose { doc?.close() }
    }

    // Light status bar icons on the black reader, and restore everything on exit.
    DisposableEffect(Unit) {
        val controller = WindowCompat.getInsetsController(window, view)
        val wasLight = controller.isAppearanceLightStatusBars
        controller.isAppearanceLightStatusBars = false
        onDispose {
            controller.isAppearanceLightStatusBars = wasLight
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Tapping the page hides the bars for distraction-free reading.
    LaunchedEffect(controlsVisible) {
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (controlsVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val readyDoc = doc
        val readyRatios = ratios
        val message = error
        when {
            message != null -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(text = message, color = Color.White)
                    Button(onClick = onClose, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Back")
                    }
                }
            }
            readyDoc == null || readyRatios == null -> {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
            else -> {
                PdfPages(
                    doc = readyDoc,
                    ratios = readyRatios,
                    title = item.title,
                    startPage = item.lastPage,
                    controlsVisible = controlsVisible,
                    onToggleControls = { controlsVisible = !controlsVisible },
                    onProgress = { page -> store.updateProgress(item.id, page) },
                    onClose = onClose,
                )
            }
        }
    }
}

@Composable
private fun PdfPages(
    doc: PdfDoc,
    ratios: FloatArray,
    title: String,
    startPage: Int,
    controlsVisible: Boolean,
    onToggleControls: () -> Unit,
    onProgress: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val count = ratios.size
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = startPage.coerceIn(0, max(0, count - 1)),
    )
    val scope = rememberCoroutineScope()
    val currentPage = listState.firstVisibleItemIndex
    val latestOnProgress by rememberUpdatedState(onProgress)

    // Save the reading position shortly after scrolling stops, and again when leaving.
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collectLatest { page ->
            delay(PROGRESS_SAVE_DELAY_MS)
            latestOnProgress(page)
        }
    }
    DisposableEffect(listState) {
        onDispose { latestOnProgress(listState.firstVisibleItemIndex) }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // A little sharper than the screen so text stays crisp.
        val widthPx = (constraints.maxWidth * RENDER_SCALE).roundToInt()
        val cache = remember(widthPx) { PageCache(cacheSizeKb()) }

        // Draw the next pages ahead of time so scrolling never waits.
        LaunchedEffect(currentPage, widthPx) {
            for (next in currentPage + 1..currentPage + PREFETCH_PAGES) {
                if (next >= count) break
                if (cache.get(next) == null) {
                    doc.render(next, widthPx)?.let { cache.put(next, it) }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onToggleControls() })
                },
        ) {
            items(count = count, key = { it }) { index ->
                PdfPage(
                    doc = doc,
                    index = index,
                    aspect = ratios[index],
                    widthPx = widthPx,
                    cache = cache,
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BarColor)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    text = title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BarColor)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${currentPage + 1} / $count",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (count > 1) {
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = dragging ?: currentPage.toFloat(),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val target = dragging?.roundToInt()
                            dragging = null
                            if (target != null) {
                                scope.launch { listState.scrollToItem(target) }
                            }
                        },
                        valueRange = 0f..(count - 1).toFloat(),
                    )
                }
            }
        }
    }
}

@Composable
private fun PdfPage(
    doc: PdfDoc,
    index: Int,
    aspect: Float,
    widthPx: Int,
    cache: PageCache,
) {
    val bitmap by produceState<Bitmap?>(initialValue = cache.get(index), doc, index, widthPx) {
        if (value == null) {
            value = doc.render(index, widthPx)?.also { cache.put(index, it) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.05f, 20f))
            .background(Color.White),
    ) {
        bitmap?.let { PageImage(it) }
    }
}

@Composable
private fun PageImage(bitmap: Bitmap) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    Image(
        bitmap = image,
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.High,
    )
}

/** Keeps recently drawn pages in memory (about a sixth of the app's memory budget). */
private class PageCache(maxKb: Int) : LruCache<Int, Bitmap>(maxKb) {
    override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
}

private fun cacheSizeKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt()
