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

/**
 * The Documents section: a separate screen from the manga/manhwa part of the app,
 * for reading your own PDF files (EPUB, novels and more are added later).
 */
class DocsActivity : BaseActivity() {

    private lateinit var store: DocStore

    init {
        registerSecureActivity(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        store = DocStore(applicationContext)

        setComposeContent {
            DocsApp(
                store = store,
                window = window,
                onExit = { finish() },
            )
        }
    }

    companion object {
        fun newIntent(context: Context): Intent = Intent(context, DocsActivity::class.java)
    }
}

@Composable
private fun DocsApp(
    store: DocStore,
    window: Window,
    onExit: () -> Unit,
) {
    var opened by remember { mutableStateOf<DocItem?>(null) }
    val current = opened

    BackHandler(enabled = current != null) { opened = null }

    if (current == null) {
        DocsLibraryScreen(
            store = store,
            onOpen = { opened = it },
            onExit = onExit,
        )
    } else {
        PdfReaderScreen(
            item = current,
            store = store,
            window = window,
            onClose = { opened = null },
        )
    }
}
