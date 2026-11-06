package eu.kanade.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import eu.kanade.domain.ui.model.ReaderSurfacePreset
import mihon.app.di.appGraph

data class ReaderSurfaceColors(val background: Color, val foreground: Color)

@Composable
fun rememberReaderSurfaceColors(): ReaderSurfaceColors {
    val context = LocalContext.current
    val appearance = remember { context.appGraph.uiPreferences.readerAppearance }
    val preset by appearance.surfacePreset.changes().collectAsState(initial = appearance.surfacePreset.get())
    val customBackground by appearance.customBackground.changes().collectAsState(initial = appearance.customBackground.get())
    val customForeground by appearance.customForeground.changes().collectAsState(initial = appearance.customForeground.get())
    return readerSurfaceColors(preset, MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.onBackground, customBackground, customForeground)
}

fun readerSurfaceColors(
    preset: ReaderSurfacePreset,
    appBackground: Color,
    appForeground: Color,
    customBackground: String,
    customForeground: String,
): ReaderSurfaceColors = when (preset) {
    ReaderSurfacePreset.APP -> ReaderSurfaceColors(appBackground, appForeground)
    ReaderSurfacePreset.LIGHT -> ReaderSurfaceColors(Color(0xFFFFFBFF), Color(0xFF1C1B1F))
    ReaderSurfacePreset.SEPIA -> ReaderSurfaceColors(Color(0xFFF4ECD8), Color(0xFF433422))
    ReaderSurfacePreset.DARK -> ReaderSurfaceColors(Color(0xFF121212), Color(0xFFE6E1E5))
    ReaderSurfacePreset.CUSTOM -> ReaderSurfaceColors(
        customBackground.toReaderColorOr(Color(0xFFF4ECD8)),
        customForeground.toReaderColorOr(Color(0xFF433422)),
    )
}

fun String.toReaderColorOr(fallback: Color): Color {
    val value = removePrefix("#")
    if (value.length != 6 && value.length != 8) return fallback
    val parsed = value.toLongOrNull(16) ?: return fallback
    return Color(if (value.length == 6) (0xFF000000L or parsed).toInt() else parsed.toInt())
}

fun Color.toCssColor(): String = "#%08X".format(toArgbCompat())

private fun Color.toArgbCompat(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(),
    (red * 255).toInt(),
    (green * 255).toInt(),
    (blue * 255).toInt(),
)
