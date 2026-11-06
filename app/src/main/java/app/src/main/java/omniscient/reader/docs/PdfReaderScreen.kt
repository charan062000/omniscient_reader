package omniscient.reader.docs

import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.view.Window
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import coil3.compose.AsyncImage
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
import mihon.icons.materialsymbols.rounded.Bookmark
import mihon.icons.materialsymbols.rounded.EditNote
import mihon.icons.materialsymbols.rounded.Image
import mihon.icons.materialsymbols.rounded.Search
import mihon.icons.materialsymbols.rounded.Settings
import mihon.app.di.appGraph
import eu.kanade.domain.ui.model.ReaderSurfacePreset
import eu.kanade.presentation.theme.readerSurfaceColors
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.UUID
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.PdfAnnotation
import tachiyomi.domain.library.content.model.PdfAnnotationType
import tachiyomi.domain.library.content.repository.ReaderLibraryRepository

private const val RENDER_SCALE = 1.25f
private const val PREFETCH_PAGES = 3
private const val PROGRESS_SAVE_DELAY_MS = 600L

/**
 * Continuous PDF reader: pages are stacked with no gaps, fit to the screen width,
 * and the position is remembered between sessions.
 */
@Composable
fun PdfReaderScreen(
    item: ReaderContent,
    repository: ReaderLibraryRepository,
    window: Window,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val appearance = remember { context.appGraph.uiPreferences.readerAppearance }
    val surfacePreset by appearance.surfacePreset.changes().collectAsState(initial = appearance.surfacePreset.get())
    val customBackground by appearance.customBackground.changes().collectAsState(initial = appearance.customBackground.get())
    val customForeground by appearance.customForeground.changes().collectAsState(initial = appearance.customForeground.get())
    val brightness by appearance.readerBrightness.changes().collectAsState(initial = appearance.readerBrightness.get())
    val pageColors = readerSurfaceColors(surfacePreset, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onBackground, customBackground, customForeground)

    var doc by remember { mutableStateOf<PdfDoc?>(null) }
    var ratios by remember { mutableStateOf<FloatArray?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var activePage by remember(item.id) { mutableIntStateOf(item.progress.toInt()) }
    var searchOpen by remember { mutableStateOf(false) }
    var bookmarksOpen by remember { mutableStateOf(false) }
    var noteOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var noteText by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<PdfTextSearchResult>>(emptyList()) }
    var requestedPage by remember { mutableStateOf<Int?>(null) }
    var searchMessage by remember { mutableStateOf<String?>(null) }
    var margin by remember { mutableStateOf(8f) }
    val preferences = remember(item.id) { context.getSharedPreferences("pdf-reader-${item.id.hashCode()}", android.content.Context.MODE_PRIVATE) }
    var pageMode by remember(item.id) { mutableStateOf(preferences.getBoolean("page_mode", false)) }
    var rtl by remember(item.id) { mutableStateOf(preferences.getBoolean("rtl", false)) }
    LaunchedEffect(item.id) { margin = preferences.getFloat("margin", 8f) }
    val annotations by repository.observePdfAnnotations(item.id).collectAsState(emptyList())
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val annotationDir = java.io.File(context.filesDir, "pdf-annotations").apply { mkdirs() }
                val mime = context.contentResolver.getType(uri).orEmpty()
                val extension = when (mime) { "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/gif" -> "gif"; else -> "png" }
                val image = java.io.File(annotationDir, "${UUID.randomUUID()}.$extension")
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input -> image.outputStream().use { output -> input.copyTo(output) } }
                        ?: error("Selected image is no longer available")
                }
                repository.upsertPdfAnnotation(PdfAnnotation(UUID.randomUUID().toString(), item.id, activePage.toLong(), PdfAnnotationType.IMAGE, null, Uri.fromFile(image).toString(), .68, .05, .95, .26, System.currentTimeMillis()))
            }.onFailure { error -> android.widget.Toast.makeText(context, error.message ?: "Image annotation could not be saved", android.widget.Toast.LENGTH_LONG).show() }
        }
    }

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
            .background(pageColors.background),
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
                    Text(text = message, color = pageColors.foreground)
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
                    startPage = item.progress.toInt(),
                    controlsVisible = controlsVisible,
                    onToggleControls = { controlsVisible = !controlsVisible },
                    onProgress = { page ->
                        activePage = page
                        scope.launch { repository.updateProgress(item.id, page.toLong(), item.progressTotal, System.currentTimeMillis()) }
                    },
                    margin = margin,
                    barColor = pageColors.background.copy(alpha = 0.9f),
                    barContentColor = pageColors.foreground,
                    brightness = brightness,
                    annotations = annotations,
                    searchResults = searchResults,
                    requestedPage = requestedPage,
                    pageMode = pageMode,
                    rtl = rtl,
                    onSearch = { searchOpen = true },
                    onBookmark = { page -> scope.launch { repository.upsertPdfAnnotation(PdfAnnotation(UUID.randomUUID().toString(), item.id, page.toLong(), PdfAnnotationType.BOOKMARK, null, null, 0.0, 0.0, 0.0, 0.0, System.currentTimeMillis())) } },
                    onNote = { noteOpen = true },
                    onBookmarks = { bookmarksOpen = true },
                    onSettings = { settingsOpen = true },
                    onAddImage = { imagePicker.launch(arrayOf("image/*")) },
                    onClose = onClose,
                )
            }
        }
    }

    if (searchOpen) AlertDialog(
        onDismissRequest = { searchOpen = false },
        title = { Text("Search this PDF") },
        text = {
            Column {
                OutlinedTextField(searchQuery, { searchQuery = it }, label = { Text("Search text") }, singleLine = true)
                searchMessage?.let { Text(it, modifier = Modifier.padding(top = 8.dp)) }
                searchResults.take(30).forEach { result ->
                    TextButton(onClick = {
                        requestedPage = result.pageIndex
                        scope.launch {
                            result.bounds.forEach { bounds ->
                                repository.upsertPdfAnnotation(
                                    PdfAnnotation(UUID.randomUUID().toString(), item.id, result.pageIndex.toLong(), PdfAnnotationType.HIGHLIGHT,
                                        result.matchedText, null, bounds.left.toDouble(), bounds.top.toDouble(), bounds.right.toDouble(), bounds.bottom.toDouble(), System.currentTimeMillis()),
                                )
                            }
                        }
                        searchOpen = false
                    }) { Text("Page ${result.pageIndex + 1}: ${result.excerpt}", maxLines = 2) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    searchMessage = if (Build.VERSION.SDK_INT < 35) "Searchable PDF text is available on Android 15 and newer." else "Searching…"
                    searchResults = runCatching { doc?.searchText(searchQuery).orEmpty() }.getOrDefault(emptyList())
                    searchMessage = when {
                        searchResults.isNotEmpty() -> "${searchResults.size} matching page(s)"
                        Build.VERSION.SDK_INT < 35 -> "Searchable PDF text is available on Android 15 and newer."
                        else -> "No text matches. Scanned PDFs may not contain a searchable text layer."
                    }
                }
            }) { Text("Search") }
        },
        dismissButton = { TextButton(onClick = { searchOpen = false }) { Text("Close") } },
    )
    if (bookmarksOpen) AlertDialog(onDismissRequest = { bookmarksOpen = false }, title = { Text("Page bookmarks") }, text = {
        Column { annotations.filter { it.type == PdfAnnotationType.BOOKMARK }.forEach { mark ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { requestedPage = mark.pageIndex.toInt(); bookmarksOpen = false }, modifier = Modifier.weight(1f)) { Text("Page ${mark.pageIndex + 1}") }
                TextButton(onClick = { scope.launch { repository.removePdfAnnotation(mark.id) } }) { Text("Remove") }
            }
        } }
    }, confirmButton = { TextButton(onClick = { bookmarksOpen = false }) { Text("Done") } })
    if (noteOpen) AlertDialog(
        onDismissRequest = { noteOpen = false }, title = { Text("Add note") },
        text = { OutlinedTextField(noteText, { noteText = it }, label = { Text("Note for current page") }) },
        confirmButton = { TextButton(onClick = {
            scope.launch { repository.upsertPdfAnnotation(PdfAnnotation(UUID.randomUUID().toString(), item.id, activePage.toLong(), PdfAnnotationType.NOTE, noteText, null, 0.0, 0.0, 0.0, 0.0, System.currentTimeMillis())) }
            noteText = ""
            noteOpen = false
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { noteOpen = false }) { Text("Cancel") } },
    )
    if (settingsOpen) AlertDialog(
        onDismissRequest = { settingsOpen = false },
        containerColor = pageColors.background,
        titleContentColor = pageColors.foreground,
        textContentColor = pageColors.foreground,
        title = { Text("PDF reader settings") },
        text = {
        Column {
            Text("Page margin: ${margin.roundToInt()} dp")
            Slider(margin, { margin = it }, valueRange = 0f..32f)
            Row {
                TextButton(onClick = { pageMode = !pageMode }) { Text(if (pageMode) "Page-by-page" else "Continuous scroll") }
                TextButton(onClick = { rtl = !rtl }) { Text(if (rtl) "Right to left" else "Left to right") }
            }
            Row {
                ReaderSurfacePreset.entries.forEach { preset ->
                    TextButton(onClick = { appearance.surfacePreset.set(preset) }) { Text(preset.name) }
                }
            }
            Text("Dim level: $brightness%")
            Slider(brightness.toFloat(), { appearance.readerBrightness.set(it.toInt()) }, valueRange = 0f..50f)
        }
        },
        confirmButton = { TextButton(onClick = {
            preferences.edit().putFloat("margin", margin).putBoolean("page_mode", pageMode).putBoolean("rtl", rtl).apply()
            settingsOpen = false
        }) { Text("Done") } },
    )
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
    margin: Float,
    barColor: Color,
    barContentColor: Color,
    brightness: Int,
    annotations: List<PdfAnnotation>,
    searchResults: List<PdfTextSearchResult>,
    requestedPage: Int?,
    pageMode: Boolean,
    rtl: Boolean,
    onSearch: () -> Unit,
    onBookmark: (Int) -> Unit,
    onNote: () -> Unit,
    onBookmarks: () -> Unit,
    onSettings: () -> Unit,
    onAddImage: () -> Unit,
    onClose: () -> Unit,
) {
    val count = ratios.size
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = startPage.coerceIn(0, max(0, count - 1)),
    )
    LaunchedEffect(requestedPage, pageMode) { if (!pageMode) requestedPage?.let { listState.animateScrollToItem(it.coerceIn(0, max(0, count - 1))) } }
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
        val dualPage = maxWidth > maxHeight
        val pageGroups = remember(count, dualPage) { PdfSearchIndex.groupPages(count, dualPage) }
        val initialPage = pageGroups.indexOfFirst { startPage.coerceIn(0, max(0, count - 1)) in it }.coerceAtLeast(0)
        val pagerState = rememberPagerState(initialPage = initialPage, pageCount = { pageGroups.size })
        val currentPagedPage = pageGroups.getOrNull(pagerState.currentPage)?.firstOrNull() ?: 0
        LaunchedEffect(requestedPage, pageMode, pageGroups) {
            if (pageMode && requestedPage != null) pagerState.animateScrollToPage(pageGroups.indexOfFirst { requestedPage in it }.coerceAtLeast(0))
        }
        LaunchedEffect(pagerState.currentPage, pageMode) {
            if (pageMode) { delay(PROGRESS_SAVE_DELAY_MS); latestOnProgress(currentPagedPage) }
        }

        // Draw the next pages ahead of time so scrolling never waits.
        LaunchedEffect(currentPage, widthPx) {
            for (next in currentPage + 1..currentPage + PREFETCH_PAGES) {
                if (next >= count) break
                if (cache.get(next) == null) {
                    doc.render(next, widthPx)?.let { cache.put(next, it) }
                }
            }
        }

        if (pageMode) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = rtl,
                modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { onToggleControls() }) },
            ) { groupIndex ->
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    val group = pageGroups[groupIndex]
                    group.forEach { index ->
                        PdfPage(doc, index, ratios[index], (widthPx / group.size).coerceAtLeast(1), cache, margin,
                            annotations.filter { it.pageIndex == index.toLong() }, searchResults.filter { it.pageIndex == index }, Modifier.weight(1f))
                    }
                }
            }
        } else LazyColumn(
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
                    margin = margin,
                    annotations = annotations.filter { it.pageIndex == index.toLong() },
                    searchResults = searchResults.filter { it.pageIndex == index },
                )
            }
        }

        if (brightness > 0) Canvas(Modifier.fillMaxSize()) {
            drawRect(Color.Black.copy(alpha = brightness.coerceIn(0, 50) / 100f))
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
                    .background(barColor)
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = MaterialSymbols.AutoMirroredRounded.ArrowBack,
                        contentDescription = "Back",
                        tint = barContentColor,
                    )
                }
                Text(
                    text = title,
                    color = barContentColor,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onSearch) { Icon(MaterialSymbols.Rounded.Search, "Search PDF", tint = barContentColor) }
                IconButton(onClick = { onBookmark(if (pageMode) currentPagedPage else currentPage) }) { Icon(MaterialSymbols.Rounded.Bookmark, "Bookmark page", tint = barContentColor) }
                IconButton(onClick = onBookmarks) { Icon(MaterialSymbols.Rounded.Bookmark, "Show bookmarks", tint = barContentColor) }
                IconButton(onClick = onNote) { Icon(MaterialSymbols.Rounded.EditNote, "Add note", tint = barContentColor) }
                IconButton(onClick = onSettings) { Icon(MaterialSymbols.Rounded.Settings, "Reader settings", tint = barContentColor) }
                IconButton(onClick = onAddImage) { Icon(MaterialSymbols.Rounded.Image, "Add image annotation", tint = barContentColor) }
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
                    .background(barColor)
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "${(if (pageMode) currentPagedPage else currentPage) + 1} / $count",
                    color = barContentColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (count > 1) {
                    var dragging by remember { mutableStateOf<Float?>(null) }
                    Slider(
                        value = dragging ?: (if (pageMode) currentPagedPage else currentPage).toFloat(),
                        onValueChange = { dragging = it },
                        onValueChangeFinished = {
                            val target = dragging?.roundToInt()
                            dragging = null
                            if (target != null) {
                                scope.launch { if (pageMode) pagerState.animateScrollToPage(pageGroups.indexOfFirst { target in it }.coerceAtLeast(0)) else listState.scrollToItem(target) }
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
    margin: Float,
    annotations: List<PdfAnnotation>,
    searchResults: List<PdfTextSearchResult>,
    modifier: Modifier = Modifier,
) {
    var zoomFactor by remember(index) { mutableStateOf(1f) }
    var translateX by remember(index) { mutableFloatStateOf(0f) }
    var translateY by remember(index) { mutableFloatStateOf(0f) }
    val bitmap by produceState<Bitmap?>(initialValue = cache.get(index), doc, index, widthPx) {
        if (value == null) {
            value = doc.render(index, widthPx)?.also { cache.put(index, it) }
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspect.coerceIn(0.05f, 20f))
            .padding(horizontal = margin.dp, vertical = margin.dp)
            .background(Color.White)
            .pointerInput(index) { detectTransformGestures { _, pan, zoom, _ ->
                zoomFactor = (zoomFactor * zoom).coerceIn(1f, 5f)
                if (zoomFactor > 1f) {
                    translateX += pan.x
                    translateY += pan.y
                } else {
                    translateX = 0f
                    translateY = 0f
                }
            } }
            .pointerInput(index) { detectTapGestures(onDoubleTap = {
                zoomFactor = if (zoomFactor > 1f) 1f else 2f
                translateX = 0f
                translateY = 0f
            }) }
            .graphicsLayer(scaleX = zoomFactor, scaleY = zoomFactor, translationX = translateX, translationY = translateY),
    ) {
        bitmap?.let { PageImage(it) }
        annotations.filter { it.type == PdfAnnotationType.NOTE }.forEach { note ->
            Text(note.text.orEmpty(), modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).background(Color.Yellow.copy(alpha = 0.85f)).padding(4.dp), color = Color.Black)
        }
        annotations.filter { it.type == PdfAnnotationType.IMAGE && it.imageUri != null }.forEach { annotation ->
            AsyncImage(annotation.imageUri, "PDF page image annotation", Modifier.align(Alignment.TopEnd).padding(8.dp).size(88.dp).clip(RoundedCornerShape(8.dp)))
        }
        val bounds = annotations.filter { it.type == PdfAnnotationType.HIGHLIGHT }.map {
            PdfMatchBounds(it.left.toFloat(), it.top.toFloat(), it.right.toFloat(), it.bottom.toFloat())
        } + searchResults.flatMap { it.bounds }
        if (bounds.isNotEmpty()) Canvas(Modifier.fillMaxSize()) {
            bounds.forEach { rect ->
                drawRect(
                    color = Color.Yellow.copy(alpha = 0.3f),
                    topLeft = androidx.compose.ui.geometry.Offset(rect.left * size.width, rect.top * size.height),
                    size = androidx.compose.ui.geometry.Size((rect.right - rect.left) * size.width, (rect.bottom - rect.top) * size.height),
                )
            }
        }
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

private fun cacheSizeKb(): Int = (Runtime.getRuntime().maxMemory() / 1024 / 6).toInt().coerceIn(8 * 1024, 64 * 1024)
