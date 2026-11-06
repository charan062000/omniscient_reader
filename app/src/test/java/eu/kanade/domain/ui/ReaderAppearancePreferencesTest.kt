package eu.kanade.domain.ui

import eu.kanade.domain.ui.model.ReaderSurfacePreset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ReaderAppearancePreferencesTest {
    @Test
    fun `reader appearance options persist through Mihon preference entries`() {
        val preferences = ReaderAppearancePreferences(InMemoryPreferenceStore())
        assertEquals(ReaderSurfacePreset.APP, preferences.surfacePreset.get())
        assertEquals(true, preferences.gifAutoplay.get())
        assertEquals("FIT", preferences.imageFit.get())

        preferences.surfacePreset.set(ReaderSurfacePreset.SEPIA)
        preferences.customBackground.set("#EAD9B5")
        preferences.customForeground.set("#392D20")
        preferences.imageFit.set("FILL")
        preferences.gifAutoplay.set(false)
        preferences.videoSpeed.set(1.25f)

        assertEquals(ReaderSurfacePreset.SEPIA, preferences.surfacePreset.get())
        assertEquals("#EAD9B5", preferences.customBackground.get())
        assertEquals("#392D20", preferences.customForeground.get())
        assertEquals("FILL", preferences.imageFit.get())
        assertEquals(false, preferences.gifAutoplay.get())
        assertEquals(1.25f, preferences.videoSpeed.get())
    }
}
