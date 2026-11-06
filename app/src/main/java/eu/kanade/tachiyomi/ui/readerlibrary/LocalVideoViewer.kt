package eu.kanade.tachiyomi.ui.readerlibrary

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.presentation.theme.rememberReaderSurfaceColors
import mihon.app.di.appGraph
import kotlinx.coroutines.delay
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowBack
import tachiyomi.domain.library.content.model.ReaderContent

private enum class TrackType(val id: Int, val label: String) {
    AUDIO(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_AUDIO, "Audio"),
    SUBTITLE(MediaPlayer.TrackInfo.MEDIA_TRACK_TYPE_SUBTITLE, "Subtitles"),
}

@Composable
fun LocalVideoViewer(
    item: ReaderContent,
    onClose: () -> Unit,
    onProgress: (positionMs: Long, durationMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val surfaceColors = rememberReaderSurfaceColors()
    val speedPreference = remember { context.appGraph.uiPreferences.readerAppearance.videoSpeed }
    val brightnessPreference = remember { context.appGraph.uiPreferences.readerAppearance.readerBrightness }
    val brightness by brightnessPreference.changes().collectAsState(initial = brightnessPreference.get())
    var surfaceHolder by remember(item.id) { mutableStateOf<SurfaceHolder?>(null) }
    var player by remember(item.id) { mutableStateOf<MediaPlayer?>(null) }
    var isPrepared by remember(item.id) { mutableStateOf(false) }
    var isPlaying by remember(item.id) { mutableStateOf(false) }
    var isFullscreen by remember(item.id) { mutableStateOf(true) }
    var duration by remember(item.id) { mutableLongStateOf(item.progressTotal) }
    var position by remember(item.id) { mutableLongStateOf(item.progress.coerceAtLeast(0)) }
    var draggingPosition by remember { mutableFloatStateOf(Float.NaN) }
    var speed by remember(item.id) { mutableFloatStateOf(speedPreference.get()) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    var speedMenu by remember { mutableStateOf(false) }
    var trackDialog by remember { mutableStateOf<TrackType?>(null) }
    var resumeAfterLifecyclePause by remember { mutableStateOf(false) }
    val trackInfo = remember(player, isPrepared) { if (isPrepared) runCatching { player?.trackInfo?.toList().orEmpty() }.getOrDefault(emptyList()) else emptyList() }

    DisposableEffect(item.id, surfaceHolder) {
        val holder = surfaceHolder ?: return@DisposableEffect onDispose { }
        val media = MediaPlayer()
        player = media
        try {
            media.setDisplay(holder)
            media.setOnPreparedListener { ready ->
                isPrepared = true
                duration = ready.duration.coerceAtLeast(0).toLong()
                if (item.progress in 1 until duration) ready.seekTo(item.progress.toInt())
                ready.start()
                isPlaying = true
                error = null
            }
            media.setOnCompletionListener {
                isPlaying = false
                position = duration
                onProgress(duration, duration)
            }
            media.setOnErrorListener { _, what, extra ->
                error = "This video format or codec is not supported (player error $what/$extra)."
                isPlaying = false
                true
            }
            media.setDataSource(context, Uri.parse(item.uri))
            media.prepareAsync()
        } catch (exception: Exception) {
            error = exception.localizedMessage ?: "This video could not be opened."
        }
        onDispose {
            if (media.isPlaying) {
                runCatching { onProgress(media.currentPosition.toLong(), duration) }
                runCatching { media.pause() }
            }
            media.setOnPreparedListener(null)
            media.setOnCompletionListener(null)
            media.setOnErrorListener(null)
            media.reset()
            media.release()
            if (player === media) player = null
            isPrepared = false
            isPlaying = false
        }
    }

    LaunchedEffect(isPrepared, isPlaying, player) {
        while (isPrepared) {
            val current = player
            if (current != null && isPlaying && current.isPlaying) {
                position = current.currentPosition.toLong()
                onProgress(position, duration)
            }
            delay(1_000)
        }
    }

    DisposableEffect(lifecycleOwner, player, isPrepared) {
        val observer = LifecycleEventObserver { _, event ->
            val activePlayer = player ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    resumeAfterLifecyclePause = isPrepared && runCatching { activePlayer.isPlaying }.getOrDefault(false)
                    if (resumeAfterLifecyclePause) {
                        runCatching { onProgress(activePlayer.currentPosition.toLong(), duration) }
                        runCatching { activePlayer.pause() }
                        isPlaying = false
                    }
                }
                Lifecycle.Event.ON_RESUME -> if (resumeAfterLifecyclePause) {
                    runCatching { activePlayer.start(); isPlaying = true }
                    resumeAfterLifecyclePause = false
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = !isFullscreen)) {
        Column(Modifier.fillMaxSize().background(surfaceColors.background)) {
            Row(Modifier.fillMaxWidth().background(surfaceColors.background.copy(alpha = .9f)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(MaterialSymbols.AutoMirroredRounded.ArrowBack, "Close video", tint = surfaceColors.foreground) }
                Text(item.title, Modifier.weight(1f), color = surfaceColors.foreground, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = { isFullscreen = !isFullscreen }) { Text(if (isFullscreen) "Exit fullscreen" else "Fullscreen", color = surfaceColors.foreground) }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        SurfaceView(viewContext).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) { surfaceHolder = holder }
                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                                override fun surfaceDestroyed(holder: SurfaceHolder) { if (surfaceHolder === holder) surfaceHolder = null }
                            })
                        }
                    },
                )
                if (brightness > 0) androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawRect(Color.Black.copy(alpha = brightness.coerceIn(0, 50) / 100f))
                }
                if (!isPrepared && error == null) androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                error?.let { Text(it, Modifier.padding(24.dp), color = surfaceColors.foreground) }
            }
            Column(Modifier.fillMaxWidth().background(surfaceColors.background.copy(alpha = .9f)).padding(horizontal = 12.dp, vertical = 4.dp)) {
                Slider(
                    value = if (draggingPosition.isNaN()) position.toFloat() else draggingPosition,
                    onValueChange = { draggingPosition = it },
                    onValueChangeFinished = {
                        val seek = draggingPosition.toLong().coerceIn(0, duration)
                        player?.let { runCatching { it.seekTo(seek.toInt()) } }
                        position = seek
                        draggingPosition = Float.NaN
                        onProgress(seek, duration)
                    },
                    valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                    enabled = isPrepared && duration > 0,
                )
                Row(horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("${formatDuration(position)} / ${formatDuration(duration)}", color = surfaceColors.foreground, style = MaterialTheme.typography.labelMedium)
                    TextButton(enabled = isPrepared, onClick = {
                        player?.let {
                            if (isPlaying) it.pause() else it.start()
                            isPlaying = !isPlaying
                        }
                    }) { Text(if (isPlaying) "Pause" else "Play", color = surfaceColors.foreground) }
                    Box {
                        TextButton(enabled = isPrepared, onClick = { speedMenu = true }) { Text("${speed}×", color = surfaceColors.foreground) }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { rate ->
                                DropdownMenuItem(text = { Text("${rate}×") }, onClick = {
                                    speed = rate
                                    speedPreference.set(rate)
                                    if (android.os.Build.VERSION.SDK_INT >= 23) runCatching { player?.playbackParams = (player?.playbackParams ?: android.media.PlaybackParams()).setSpeed(rate) }
                                    speedMenu = false
                                })
                            }
                        }
                    }
                    TextButton(enabled = isPrepared && trackInfo.any { it.trackType == TrackType.AUDIO.id }, onClick = { trackDialog = TrackType.AUDIO }) { Text("Audio", color = surfaceColors.foreground) }
                    TextButton(enabled = isPrepared && trackInfo.any { it.trackType == TrackType.SUBTITLE.id }, onClick = { trackDialog = TrackType.SUBTITLE }) { Text("Subtitles", color = surfaceColors.foreground) }
                }
            }
        }
    }

    trackDialog?.let { type ->
        val trackIndices = trackInfo.indices.filter { trackInfo[it].trackType == type.id }
        AlertDialog(
            onDismissRequest = { trackDialog = null },
            title = { Text("${type.label} tracks") },
            text = {
                Column {
                    if (type == TrackType.SUBTITLE) TextButton(onClick = { player?.let { runCatching { it.trackInfo.indices.filter { i -> it.trackInfo[i].trackType == type.id }.forEach(it::deselectTrack) } }; trackDialog = null }) { Text("Off") }
                    trackIndices.forEach { index ->
                        val info = trackInfo[index]
                        val language = info.language?.takeIf(String::isNotBlank) ?: "Track ${index + 1}"
                        TextButton(onClick = { player?.let { runCatching { it.selectTrack(index) } }; trackDialog = null }) { Text(language) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { trackDialog = null }) { Text("Done") } },
        )
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
