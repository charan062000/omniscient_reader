package eu.kanade.tachiyomi.ui.readerlibrary

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import omniscient.reader.localmedia.LocalMediaKind
import tachiyomi.domain.library.content.model.ReaderContentType

class LocalMediaKindTest {
    @Test
    fun `media identification falls back to filename when provider mime is generic`() {
        assertEquals(ReaderContentType.GIF, LocalMediaKind.identify("application/octet-stream", "loop.GIF"))
        assertEquals(ReaderContentType.VIDEO, LocalMediaKind.identify(null, "clip.MKV"))
        assertEquals(ReaderContentType.ARCHIVE, LocalMediaKind.identify(null, "comic.cb7"))
        assertEquals(ReaderContentType.IMAGE, LocalMediaKind.identify("image/webp", "cover.bin"))
        assertNull(LocalMediaKind.identify("application/octet-stream", "notes.txt"))
    }
}
