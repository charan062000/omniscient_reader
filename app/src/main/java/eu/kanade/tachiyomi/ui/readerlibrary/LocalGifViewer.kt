package eu.kanade.tachiyomi.ui.readerlibrary

import android.graphics.drawable.Animatable
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.asDrawable
import coil3.compose.AsyncImage
import eu.kanade.presentation.theme.rememberReaderSurfaceColors
import kotlinx.coroutines.delay
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import tachiyomi.domain.library.content.model.ReaderContent

@Composable
fun LocalGifViewer(
    items: List<ReaderContent>,
    initialIndex: Int,
    onClose: () -> Unit,
    onIndexChanged: (Int) -> Unit,
) {
    val context = LocalContext.current
    val surfaceColors = rememberReaderSurfaceColors()
    val autoplayPreference = remember { context.appGraph.uiPreferences.readerAppearance.gifAutoplay }
    val brightnessPreference = remember { context.appGraph.uiPreferences.readerAppearance.readerBrightness }
    val brightness by brightnessPreference.changes().collectAsState(initial = brightnessPreference.get())
    var index by remember(items) { mutableIntStateOf(initialIndex.coerceIn(items.indices)) }
    var isPlaying by remember(items) { mutableStateOf(autoplayPreference.get()) }
    var isFullscreen by remember(items) { mutableStateOf(true) }
    var loading by remember(items) { mutableStateOf(true) }
    var error by remember(items) { mutableStateOf(false) }
    var animation by remember(items) { mutableStateOf<Animatable?>(null) }
    val item = items[index]

    LaunchedEffect(isPlaying, animation) {
        if (isPlaying) animation?.start() else animation?.stop()
    }
    LaunchedEffect(index) {
        loading = true
        error = false
        animation?.stop()
        animation = null
    }
    DisposableEffect(animation) { onDispose { animation?.stop() } }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !isFullscreen),
    ) {
        Column(Modifier.fillMaxSize().background(surfaceColors.background)) {
            Row(
                Modifier.fillMaxWidth().background(
                    surfaceColors.background.copy(alpha = .9f),
                ).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        MaterialSymbols.AutoMirroredRounded.ArrowBack,
                        "Close GIF viewer",
                        tint = surfaceColors.foreground,
                    )
                }
                Text(
                    item.title,
                    Modifier.weight(1f),
                    color = surfaceColors.foreground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                TextButton(onClick = {
                    isFullscreen = !isFullscreen
                }) { Text(if (isFullscreen) "Exit fullscreen" else "Fullscreen", color = surfaceColors.foreground) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                    onLoading = {
                        loading = true
                        error = false
                    },
                    onSuccess = { state ->
                        loading = false
                        error = false
                        animation = state.result.image.asDrawable(context.resources) as? Animatable
                        if (isPlaying) animation?.start()
                    },
                    onError = {
                        loading = false
                        error = true
                    },
                )
                if (brightness > 0) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color.Black.copy(alpha = brightness.coerceIn(0, 50) / 100f))
                    }
                }
                if (loading) androidx.compose.material3.CircularProgressIndicator(color = surfaceColors.foreground)
                if (error) {
                    Text(
                        "This GIF is unavailable or could not be decoded.",
                        color = surfaceColors.foreground,
                        modifier = Modifier.padding(24.dp),
                    )
                }
                IconButton(
                    onClick = {
                        index = (index - 1).coerceAtLeast(0)
                        onIndexChanged(index)
                    },
                    modifier = Modifier.align(Alignment.CenterStart),
                    enabled = index > 0,
                ) { Text("‹", color = surfaceColors.foreground, style = MaterialTheme.typography.headlineMedium) }
                IconButton(
                    onClick = {
                        index = (index + 1).coerceAtMost(items.lastIndex)
                        onIndexChanged(index)
                    },
                    modifier = Modifier.align(Alignment.CenterEnd),
                    enabled = index < items.lastIndex,
                ) { Text("›", color = surfaceColors.foreground, style = MaterialTheme.typography.headlineMedium) }
            }
            Row(
                Modifier.fillMaxWidth().background(
                    surfaceColors.background.copy(alpha = .9f),
                ).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = {
                    isPlaying = !isPlaying
                    autoplayPreference.set(isPlaying)
                }) { Text(if (isPlaying) "Pause" else "Play", color = surfaceColors.foreground) }
                Text(
                    "${index + 1} / ${items.size}",
                    color = surfaceColors.foreground,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}
