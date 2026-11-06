package eu.kanade.tachiyomi.ui.readerlibrary

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.view.Window
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import eu.kanade.presentation.theme.rememberReaderSurfaceColors
import mihon.app.di.appGraph
import com.davemorrissey.labs.subscaleview.ImageSource
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import tachiyomi.domain.library.content.model.ReaderContent

@Composable
fun LocalImageViewer(
    items: List<ReaderContent>,
    initialIndex: Int,
    onClose: () -> Unit,
    onIndexChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    val localView = LocalView.current
    val scope = rememberCoroutineScope()
    val surfaceColors = rememberReaderSurfaceColors()
    val imageFitPreference = remember { context.appGraph.uiPreferences.readerAppearance.imageFit }
    val brightnessPreference = remember { context.appGraph.uiPreferences.readerAppearance.readerBrightness }
    val brightness by brightnessPreference.changes().collectAsState(initial = brightnessPreference.get())
    var index by remember(items) { mutableIntStateOf(initialIndex.coerceIn(items.indices)) }
    var rotation by remember(items) { mutableIntStateOf(0) }
    var fitMode by remember(items) { mutableIntStateOf(when (imageFitPreference.get()) {
        "FILL" -> SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP
        "ORIGINAL" -> SubsamplingScaleImageView.SCALE_TYPE_CUSTOM
        else -> SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE
    }) }
    var isSlideshow by remember(items) { mutableStateOf(false) }
    var isFullscreen by remember(items) { mutableStateOf(true) }
    var imageView by remember { mutableStateOf<SubsamplingScaleImageView?>(null) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    val item = items[index]
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/*")) { target ->
        if (target != null) scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(Uri.parse(item.uri))?.use { input ->
                        context.contentResolver.openOutputStream(target, "w")?.use { output -> input.copyTo(output) }
                            ?: error("Export destination cannot be written")
                    } ?: error("This image is no longer available")
                }
            }.onFailure { android.widget.Toast.makeText(context,
                 it.message ?: "Image export failed",
                 android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    LaunchedEffect(isSlideshow, index) {
        if (isSlideshow && items.size > 1) {
            delay(3_000)
            index = (index + 1) % items.size
            onIndexChanged(index)
        }
    }
    LaunchedEffect(index) {
        rotation = 0
        loading = true
        loadError = false
        imageView?.setMinimumScaleType(fitMode)
        imageView?.setImage(ImageSource.uri(context, Uri.parse(item.uri)))
    }
    DisposableEffect(Unit) { onDispose { imageView?.recycle() } }

    val window = context.findActivityWindow()
    DisposableEffect(window, isFullscreen) {
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, localView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (isFullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { if (window != null) WindowCompat.getInsetsController(window,
             localView).show(WindowInsetsCompat.Type.systemBars()) }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !isFullscreen),
    ) {
        Column(Modifier.fillMaxSize().background(surfaceColors.background)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(surfaceColors.background.copy(alpha = .9f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),

                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) { Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack,
                     "Close image viewer",
                     tint = surfaceColors.foreground) }
                Text(item.title,
                     Modifier.weight(1f),
                     color = surfaceColors.foreground,
                     maxLines = 1,
                     overflow = TextOverflow.Ellipsis,
                     style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { isFullscreen = !isFullscreen }) { Text(if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                     color = surfaceColors.foreground) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        SubsamplingScaleImageView(context).apply {
                            setMinimumDpi(1)
                            setMinimumTileDpi(160)
                            setPanLimit(SubsamplingScaleImageView.PAN_LIMIT_INSIDE)
                            setMinimumScaleType(fitMode)
                            setDoubleTapZoomStyle(SubsamplingScaleImageView.ZOOM_FOCUS_CENTER)
                            setDoubleTapZoomScale(2f)
                            setImage(ImageSource.uri(context, Uri.parse(item.uri)))
                            setOnImageEventListener(object : SubsamplingScaleImageView.DefaultOnImageEventListener() {
                                override fun onReady() {
                                    loading = false
                                    loadError = false
                                    if (imageFitPreference.get() == "ORIGINAL") setScaleAndCenter(1f, center)
                                }
                                override fun onImageLoadError(e: Exception) { loading = false; loadError = true }
                            })
                            imageView = this
                        }
                    },
                    update = { view ->
                        imageView = view
                        view.rotation = rotation.toFloat()
                        view.setMinimumScaleType(fitMode)
                    },
                )
                if (brightness > 0) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.Black.copy(alpha = brightness.coerceIn(0, 50) / 100f))
                }
                if (loading && !loadError) androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center),
                     color = surfaceColors.foreground)
                if (loadError) Text("This image is unavailable or cannot be decoded.",
                     Modifier.align(Alignment.Center).padding(24.dp),
                     color = surfaceColors.foreground)
                Row(Modifier.align(Alignment.CenterStart)) {
                    IconButton(onClick = {
                        index = (index - 1).coerceAtLeast(0)
                        onIndexChanged(index)
                    },
                         enabled = index > 0) { Text("‹",
                         color = surfaceColors.foreground,
                         style = MaterialTheme.typography.headlineMedium) }
                }
                Row(Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = {
                        index = (index + 1).coerceAtMost(items.lastIndex)
                        onIndexChanged(index)
                    },
                         enabled = index < items.lastIndex) { Text("›",
                         color = surfaceColors.foreground,
                         style = MaterialTheme.typography.headlineMedium) }
                }
            }
            Row(
                Modifier.fillMaxWidth().background(surfaceColors.background.copy(alpha = .9f)).padding(horizontal = 8.dp,
                     vertical = 4.dp),

                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { fitMode = SubsamplingScaleImageView.SCALE_TYPE_CENTER_INSIDE; imageFitPreference.set("FIT"); imageView?.setMinimumScaleType(fitMode) }) { Text("Fit",
                     color = surfaceColors.foreground) }
                TextButton(onClick = { fitMode = SubsamplingScaleImageView.SCALE_TYPE_CENTER_CROP; imageFitPreference.set("FILL"); imageView?.setMinimumScaleType(fitMode) }) { Text("Fill",
                     color = surfaceColors.foreground) }
                IconButton(onClick = {
                    imageFitPreference.set("ORIGINAL")
                    imageView?.let { if (it.isReady) it.setScaleAndCenter(1f, it.center) }
                }) {
                    Text("1:1", color = surfaceColors.foreground)
                }
                IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                    Text("↻", color = surfaceColors.foreground, style = MaterialTheme.typography.titleLarge)
                }
                IconButton(onClick = { isSlideshow = !isSlideshow }) {
                    Text(if (isSlideshow) "Pause" else "Play", color = surfaceColors.foreground)
                }
                TextButton(onClick = {
                    val extension = Uri.parse(item.uri).lastPathSegment?.substringAfterLast('.', "jpg") ?: "jpg"
                    exportPicker.launch("${item.title}.$extension")
                }) { Text("Export", color = surfaceColors.foreground) }
                Text("${index + 1} / ${items.size}",
                     color = surfaceColors.foreground,
                     style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private tailrec fun Context.findActivityWindow(): Window? = when (this) {
    is Activity -> window
    is ContextWrapper -> baseContext.findActivityWindow()
    else -> null
}
