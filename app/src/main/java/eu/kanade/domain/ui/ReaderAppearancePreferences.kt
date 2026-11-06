package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.ReaderSurfacePreset
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/** Appearance preferences shared by the non-manga readers. */
class ReaderAppearancePreferences(preferenceStore: PreferenceStore) {
    val surfacePreset: Preference<ReaderSurfacePreset> = preferenceStore.getEnum(
        "reader_surface_preset",
        ReaderSurfacePreset.APP,
    )
    val customBackground: Preference<String> = preferenceStore.getString("reader_custom_background", "#F4ECD8")
    val customForeground: Preference<String> = preferenceStore.getString("reader_custom_foreground", "#433422")
    val imageFit: Preference<String> = preferenceStore.getString("reader_image_fit", "FIT")
    val gifAutoplay: Preference<Boolean> = preferenceStore.getBoolean("reader_gif_autoplay", true)
    val videoSpeed: Preference<Float> = preferenceStore.getFloat("reader_video_speed", 1f)
    val readerBrightness: Preference<Int> = preferenceStore.getInt("reader_brightness", 0)
}
