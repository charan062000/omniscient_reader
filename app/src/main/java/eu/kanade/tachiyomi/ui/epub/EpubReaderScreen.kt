package eu.kanade.tachiyomi.ui.epub

import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import org.jsoup.Jsoup
import tachiyomi.domain.library.content.model.EpubBookmark
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType
import java.io.File

data class EpubReaderScreen(val bookId: String) : Screen() {
    @Composable
    override fun Content() = EpubReaderContent(bookId)
}

private enum class ReaderTheme(val background: String, val foreground: String) {
    LIGHT("#ffffff", "#202124"),
    SEPIA("#f4ecd8", "#433422"),
    DARK("#171717", "#dedede"),
}

private data class ReaderSettings(
    val font: String = "serif",
    val size: Float = 19f,
    val lineSpacing: Float = 1.7f,
    val paragraphSpacing: Float = 1.1f,
    val margin: Int = 8,
    val alignment: String = "justify",
    val direction: String = "book",
    val theme: ReaderTheme = ReaderTheme.LIGHT,
    val paged: Boolean = false,
)

@Composable
private fun EpubReaderContent(bookId: String) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val repository = context.appGraph.readerLibraryRepository
    val scope = rememberCoroutineScope()
    val bookmarks by repository.observeEpubBookmarks(bookId).collectAsState(emptyList())
    var content by remember { mutableStateOf<ReaderContent?>(null) }
    var book by remember { mutableStateOf<EpubBook?>(null) }
    var chapterIndex by remember { mutableStateOf(0) }
    var html by remember { mutableStateOf("") }
    var renderedChapterIndex by remember { mutableStateOf<Int?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var settings by remember { mutableStateOf(loadSettings(context.getSharedPreferences("epub-reader", 0))) }
    var controlsVisible by remember { mutableStateOf(true) }
    var settingsOpen by remember { mutableStateOf(false) }
    var chaptersOpen by remember { mutableStateOf(false) }
    var bookmarkDialog by remember { mutableStateOf(false) }
    var searchDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<EpubSearchResult>>(emptyList()) }
    var isSearching by remember { mutableStateOf(false) }
    var pendingBookmarkText by remember { mutableStateOf<String?>(null) }
    val service = remember(context, repository) { EpubLibraryService(context, repository) }
    val prefs = remember(bookId) { context.getSharedPreferences("epub-position-$bookId", 0) }

    LaunchedEffect(bookId) {
        runCatching {
            val item = repository.get(bookId) ?: error("This EPUB is no longer in the library")
            val file = service.getBookFile(item)
            val parsed = withContext(Dispatchers.IO) {
                if (item.type == ReaderContentType.EPUB) {
                    service.parser().parse(file)
                } else {
                    EpubBook(item.title, listOfNotNull(item.author), null, null, ReadingDirection.LTR, listOf(EpubChapter("", item.title, 0)))
                }
            }
            content = item
            book = parsed
            chapterIndex = item.progress.coerceIn(0, parsed.chapters.lastIndex.toLong()).toInt()
            repository.updateProgress(bookId, item.progress.coerceIn(0, parsed.chapters.lastIndex.toLong()), parsed.chapters.size.toLong(), System.currentTimeMillis())
        }.onFailure { error = it.message ?: "Could not open this EPUB" }
    }

    LaunchedEffect(book, chapterIndex, content) {
        val currentBook = book ?: return@LaunchedEffect
        val item = content ?: return@LaunchedEffect
        runCatching {
            val chapter = currentBook.chapters[chapterIndex]
            val raw = withContext(Dispatchers.IO) {
                if (item.type == ReaderContentType.EPUB) service.parser().readChapter(service.getBookFile(item), chapter)
                else service.getBookFile(item).readText()
            }
            val doc = if (item.type == ReaderContentType.TEXT) Jsoup.parse("").also { it.body().appendElement("pre").text(raw) } else Jsoup.parse(raw)
            doc.select("script, iframe, object, embed, form, input, button").remove()
            doc.getAllElements().forEach { element ->
                element.attributes().asList().filter { it.key.startsWith("on", true) }.forEach { element.removeAttr(it.key) }
                listOf("src", "href", "poster").forEach { key ->
                    val value = element.attr(key)
                    if (value.startsWith("javascript:", true) || value.startsWith("http:", true) || value.startsWith("https:", true)) element.removeAttr(key)
                }
            }
            html = doc.outerHtml()
            renderedChapterIndex = chapterIndex
        }.onFailure { error = it.message ?: "Could not load this chapter" }
    }

    LaunchedEffect(html, webView, content, chapterIndex, settings, book) {
        val item = content ?: return@LaunchedEffect
        val chapter = book?.chapters?.getOrNull(chapterIndex) ?: return@LaunchedEffect
        if (renderedChapterIndex != chapterIndex) return@LaunchedEffect
        if (html.isBlank()) return@LaunchedEffect
        saveSettings(context.getSharedPreferences("epub-reader", 0), settings)
        val view = webView ?: return@LaunchedEffect
        if (view.tag == chapterIndex) {
            prefs.edit().putInt("scroll-x-$chapterIndex", view.scrollX).putInt("scroll-y-$chapterIndex", view.scrollY).apply()
        }
        val direction = if (settings.direction == "book") if (book?.direction == ReadingDirection.RTL) "rtl" else "ltr" else settings.direction
        val doc = Jsoup.parse(html)
        doc.head().append(
            """
                <style id="reader-overrides">
                html,body{background:${settings.theme.background}!important;color:${settings.theme.foreground}!important;
                  font-family:${settings.font},serif!important;font-size:${settings.size}px!important;
                  line-height:${settings.lineSpacing}!important;text-align:${settings.alignment}!important;
                  direction:$direction!important;margin:${settings.margin}px!important;padding:0!important;}
                p{margin-block:0 ${settings.paragraphSpacing}em!important;}
                pre{white-space:pre-wrap!important;}
                img,svg{max-width:100%!important;height:auto!important;}
                ${if (settings.paged) "html,body{height:100%;overflow-y:hidden!important;column-width:96vw;column-gap:4vw;column-fill:auto;}" else ""}
                </style>
            """.trimIndent(),
        )
        val chapterFile = if (item.type == ReaderContentType.EPUB) File(service.getResourceRoot(item), chapter.href) else service.getBookFile(item)
        view.isHorizontalScrollBarEnabled = settings.paged
        view.tag = chapterIndex
        view.loadDataWithBaseURL(chapterFile.toURI().toString(), doc.outerHtml(), "text/html", "UTF-8", null)
    }

    val currentChapter = book?.chapters?.getOrNull(chapterIndex)
    Column(Modifier.fillMaxSize()) {
        if (controlsVisible) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { navigator.pop() }) { Text("Back") }
                Text(currentChapter?.title ?: "Opening EPUB…", Modifier.weight(1f), maxLines = 1)
                TextButton(onClick = { chaptersOpen = true }) { Text("Contents") }
                TextButton(onClick = {
                    currentChapter?.let { chapter -> scope.launch { repository.addEpubBookmark(bookId, chapter.index.toLong(), chapter.title, null, null) } }
                }) { Text("Bookmark") }
                TextButton(onClick = { settingsOpen = true }) { Text("Aa") }
                TextButton(onClick = { searchDialog = true }) { Text("Search") }
                TextButton(onClick = { bookmarkDialog = true }) { Text("Bookmarks") }
            }
        }
        if (error != null) {
            Text(error!!, Modifier.padding(24.dp), color = MaterialTheme.colorScheme.error)
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { viewContext ->
                    EpubProgressWebView(viewContext, prefs, chapterIndex).apply {
                        setBackgroundColor(AndroidColor.TRANSPARENT)
                        this.settings.javaScriptEnabled = false
                        this.settings.domStorageEnabled = false
                        this.settings.allowFileAccess = true
                        this.settings.allowContentAccess = false
                        this.settings.allowFileAccessFromFileURLs = false
                        this.settings.allowUniversalAccessFromFileURLs = false
                        isHorizontalScrollBarEnabled = settings.paged
                        webView = this
                    }
                },
                update = { view ->
                    webView = view
                    view.chapterIndex = chapterIndex
                    view.isHorizontalScrollBarEnabled = settings.paged
                    view.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return true
                            if (uri.scheme == "about") return false
                            val resources = content?.let(service::getResourceRoot)?.canonicalFile ?: return true
                            val requested = runCatching { File(uri.path.orEmpty()).canonicalFile }.getOrNull() ?: return true
                            return !requested.path.startsWith(resources.path + File.separator)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.post {
                                view.scrollTo(prefs.getInt("scroll-x-$chapterIndex", 0), prefs.getInt("scroll-y-$chapterIndex", 0))
                            }
                            val target = pendingBookmarkText ?: return
                            val script = """(()=>{const q=${org.json.JSONObject.quote(target)};const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);let n;while(n=w.nextNode()){const i=n.nodeValue.indexOf(q);if(i>=0){const r=document.createRange();r.setStart(n,i);r.setEnd(n,i+q.length);const s=window.getSelection();s.removeAllRanges();s.addRange(r);r.startContainer.parentElement?.scrollIntoView({block:'center'});return true}}return false})()"""
                            view?.evaluateJavascript(script) { pendingBookmarkText = null }
                        }
                    }
                    view.setOnLongClickListener {
                        view.evaluateJavascript("(()=>{const s=window.getSelection().toString();const t=document.body.innerText;const i=t.indexOf(s);return JSON.stringify({s:s,c:i<0?'':t.substring(Math.max(0,i-100),Math.min(t.length,i+s.length+100))})})()") { value ->
                            val result = runCatching {
                                val jsonText = org.json.JSONTokener(value).nextValue() as? String ?: return@runCatching null
                                org.json.JSONObject(jsonText)
                            }.getOrNull()
                            val selectedText = result?.optString("s")?.takeIf(String::isNotBlank)
                            val chapter = book?.chapters?.getOrNull(chapterIndex)
                            if (selectedText != null && chapter != null) {
                                val surroundingText = result?.optString("c")?.takeIf(String::isNotBlank)
                                scope.launch { repository.addEpubBookmark(bookId, chapterIndex.toLong(), chapter.title, selectedText, surroundingText) }
                            }
                        }
                        true
                    }
                },
            )
        }
        if (controlsVisible && book != null) {
            Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(enabled = chapterIndex > 0, onClick = { changeChapter(chapterIndex - 1, book!!, content!!, repository, scope) { chapterIndex = it } }) { Text("Previous") }
                Text("${chapterIndex + 1} / ${book!!.chapters.size}")
                TextButton(enabled = chapterIndex < book!!.chapters.lastIndex, onClick = { changeChapter(chapterIndex + 1, book!!, content!!, repository, scope) { chapterIndex = it } }) { Text("Next") }
                TextButton(onClick = { controlsVisible = false }) { Text("Hide controls") }
            }
        } else if (book != null) {
            TextButton(onClick = { controlsVisible = true }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Show controls") }
        }
    }

    if (settingsOpen) ReaderSettingsDialog(settings, { settings = it }, { settingsOpen = false })
    if (chaptersOpen) {
        AlertDialog(
            onDismissRequest = { chaptersOpen = false }, title = { Text("Contents") },
            text = { LazyColumn { items(book?.chapters.orEmpty()) { chapter ->
                TextButton(onClick = {
                    val parsed = book
                    val item = content
                    if (parsed != null && item != null) changeChapter(chapter.index, parsed, item, repository, scope) { chapterIndex = it }
                    chaptersOpen = false
                }) { Text(chapter.title) }
            } } },
            confirmButton = { TextButton(onClick = { chaptersOpen = false }) { Text("Close") } },
        )
    }
    if (bookmarkDialog) BookmarkDialog(bookmarks, { bookmark ->
        val parsed = book
        val item = content
        val index = bookmark.chapterIndex.toInt()
        if (parsed != null && item != null) {
            pendingBookmarkText = bookmark.selectedText
            changeChapter(index.coerceIn(0, parsed.chapters.lastIndex), parsed, item, repository, scope) { chapterIndex = it }
        }
        bookmarkDialog = false
    }, { scope.launch { repository.removeEpubBookmark(it) } }, { bookmarkDialog = false })
    if (searchDialog) {
        AlertDialog(
            onDismissRequest = { searchDialog = false }, title = { Text("Search book") },
            text = {
                Column {
                    Row {
                        OutlinedTextField(searchQuery, { searchQuery = it }, Modifier.weight(1f), label = { Text("Search chapters") }, singleLine = true)
                        TextButton(enabled = searchQuery.trim().length >= 2 && !isSearching, onClick = {
                            val parsed = book ?: return@TextButton
                            val item = content ?: return@TextButton
                            isSearching = true
                            scope.launch {
                                searchResults = withContext(Dispatchers.IO) {
                                    if (item.type == ReaderContentType.EPUB) service.parser().search(service.getBookFile(item), parsed, searchQuery)
                                    else {
                                        val text = service.getBookFile(item).readText()
                                        val index = text.indexOf(searchQuery.trim(), ignoreCase = true)
                                        if (index < 0) emptyList() else listOf(EpubSearchResult(0, item.title, text.substring((index - 70).coerceAtLeast(0), (index + searchQuery.length + 100).coerceAtMost(text.length))))
                                    }
                                }
                                isSearching = false
                            }
                        }) { Text(if (isSearching) "…" else "Find") }
                    }
                    LazyColumn { items(searchResults) { result ->
                        TextButton(onClick = {
                            val currentBook = book
                            val currentItem = content
                            pendingBookmarkText = searchQuery.trim()
                            if (currentBook != null && currentItem != null) changeChapter(result.chapterIndex, currentBook, currentItem, repository, scope) { chapterIndex = it }
                            searchDialog = false
                        }) {
                            Column { Text(result.chapterTitle); Text(result.excerpt, maxLines = 3, style = MaterialTheme.typography.bodySmall) }
                        }
                    } }
                }
            },
            confirmButton = { TextButton(onClick = { searchDialog = false }) { Text("Close") } },
        )
    }
}

private class EpubProgressWebView(
    context: android.content.Context,
    private val positionPreferences: android.content.SharedPreferences,
    var chapterIndex: Int,
) : WebView(context) {
    private val handler = Handler(Looper.getMainLooper())
    private val saveScroll = Runnable {
        positionPreferences.edit()
            .putInt("scroll-y-$chapterIndex", scrollY)
            .putInt("scroll-x-$chapterIndex", scrollX)
            .apply()
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        handler.removeCallbacks(saveScroll)
        handler.postDelayed(saveScroll, 350)
    }
}

private fun changeChapter(index: Int, book: EpubBook, content: ReaderContent, repository: tachiyomi.domain.library.content.repository.ReaderLibraryRepository, scope: kotlinx.coroutines.CoroutineScope, update: (Int) -> Unit) {
    update(index)
    scope.launch { repository.updateProgress(content.id, index.toLong(), book.chapters.size.toLong(), System.currentTimeMillis()) }
}

@Composable
private fun ReaderSettingsDialog(settings: ReaderSettings, update: (ReaderSettings) -> Unit, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Reading settings") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    TextButton(onClick = { update(settings.copy(font = "serif")) }) { Text("Serif") }
                    TextButton(onClick = { update(settings.copy(font = "sans-serif")) }) { Text("Sans") }
                    TextButton(onClick = { update(settings.copy(font = "monospace")) }) { Text("Mono") }
                }
                Text("Font size ${settings.size.toInt()} sp")
                Slider(settings.size, { update(settings.copy(size = it)) }, valueRange = 14f..34f)
                Text("Line spacing ${settings.lineSpacing}")
                Slider(settings.lineSpacing, { update(settings.copy(lineSpacing = it)) }, valueRange = 1.2f..2.4f)
                Text("Paragraph spacing ${settings.paragraphSpacing} em")
                Slider(settings.paragraphSpacing, { update(settings.copy(paragraphSpacing = it)) }, valueRange = 0.3f..2.5f)
                Text("Margins ${settings.margin} dp")
                Slider(settings.margin.toFloat(), { update(settings.copy(margin = it.toInt())) }, valueRange = 0f..32f)
                Row {
                    ReaderTheme.entries.forEach { theme -> TextButton(onClick = { update(settings.copy(theme = theme)) }) { Text(theme.name) } }
                }
                Row {
                    TextButton(onClick = { update(settings.copy(alignment = "left")) }) { Text("Left") }
                    TextButton(onClick = { update(settings.copy(alignment = "justify")) }) { Text("Justified") }
                    TextButton(onClick = { update(settings.copy(paged = !settings.paged)) }) { Text(if (settings.paged) "Paged" else "Scroll") }
                }
                Row {
                    TextButton(onClick = { update(settings.copy(direction = "book")) }) { Text("Book direction") }
                    TextButton(onClick = { update(settings.copy(direction = "ltr")) }) { Text("LTR") }
                    TextButton(onClick = { update(settings.copy(direction = "rtl")) }) { Text("RTL") }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done") } },
    )
}

@Composable
private fun BookmarkDialog(bookmarks: List<EpubBookmark>, open: (EpubBookmark) -> Unit, remove: (Long) -> Unit, close: () -> Unit) {
    AlertDialog(
        onDismissRequest = close, title = { Text("Bookmarks") },
        text = { LazyColumn { items(bookmarks, key = EpubBookmark::id) { bookmark ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { open(bookmark) }, modifier = Modifier.weight(1f)) {
                    Column { Text(bookmark.chapterTitle); bookmark.selectedText?.let { Text(it, maxLines = 2, style = MaterialTheme.typography.bodySmall) } }
                }
                TextButton(onClick = { remove(bookmark.id) }) { Text("Remove") }
            }
        } } },
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
    )
}

private fun loadSettings(prefs: android.content.SharedPreferences) = ReaderSettings(
    font = prefs.getString("font", "serif") ?: "serif",
    size = prefs.getFloat("size", 19f),
    lineSpacing = prefs.getFloat("line", 1.7f),
    paragraphSpacing = prefs.getFloat("paragraph", 1.1f),
    margin = prefs.getInt("margin", 8),
    alignment = prefs.getString("alignment", "justify") ?: "justify",
    direction = prefs.getString("direction", "book") ?: "book",
    theme = ReaderTheme.entries.firstOrNull { it.name == prefs.getString("theme", "LIGHT") } ?: ReaderTheme.LIGHT,
    paged = prefs.getBoolean("paged", false),
)

private fun saveSettings(prefs: android.content.SharedPreferences, settings: ReaderSettings) {
    prefs.edit().putString("font", settings.font).putFloat("size", settings.size).putFloat("line", settings.lineSpacing)
        .putFloat("paragraph", settings.paragraphSpacing).putInt("margin", settings.margin)
        .putString("alignment", settings.alignment).putString("direction", settings.direction)
        .putString("theme", settings.theme.name).putBoolean("paged", settings.paged).apply()
}
