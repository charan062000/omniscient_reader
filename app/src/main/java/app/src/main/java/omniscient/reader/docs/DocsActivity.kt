package omniscient.reader.docs

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Window
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent
import mihon.app.di.appGraph

/**
 * The Documents section: a separate screen from the manga/manhwa part of the app,
 * for reading your own PDF files (EPUB, novels and more are added later).
 */
class DocsActivity : BaseActivity() {

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setComposeContent {
            DocsApp(
                window = window,
                initialContentId = intent.getStringExtra(EXTRA_CONTENT_ID),
                onExit = { finish() },
            )
        }
    }

    companion object {
        private const val EXTRA_CONTENT_ID = "pdf_content_id"

        fun newIntent(context: Context, contentId: String? = null): Intent = Intent(context, DocsActivity::class.java).apply {
            contentId?.let { putExtra(EXTRA_CONTENT_ID, it) }
        }
    }
}

@Composable
private fun DocsApp(
    window: Window,
    initialContentId: String?,
    onExit: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = context.appGraph.readerLibraryRepository
    var opened by remember { mutableStateOf<tachiyomi.domain.library.content.model.ReaderContent?>(null) }
    val current = opened

    androidx.compose.runtime.LaunchedEffect(initialContentId) {
        if (!initialContentId.isNullOrBlank()) opened = repository.get(initialContentId)
    }

    BackHandler(enabled = current != null) { opened = null }

    if (current == null) {
        DocsLibraryScreen(
            onOpen = { opened = it },
            onExit = onExit,
        )
    } else {
        PdfReaderScreen(
            item = current,
            repository = repository,
            window = window,
            onClose = { opened = null },
        )
    }
}
