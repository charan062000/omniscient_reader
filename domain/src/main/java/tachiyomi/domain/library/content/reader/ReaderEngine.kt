package tachiyomi.domain.library.content.reader

import java.io.Closeable
import tachiyomi.domain.library.content.model.ReaderContent
import tachiyomi.domain.library.content.model.ReaderContentType

/** Format-specific reader adapter. Implementations live beside the format's Android rendering code. */
interface ReaderEngine {
    val supportedTypes: Set<ReaderContentType>
    suspend fun open(content: ReaderContent): ReaderSession
}

/** Lifecycle and progress contract shared by native format readers. */
interface ReaderSession : Closeable {
    val contentId: String
    suspend fun saveProgress(position: Long, total: Long)
}
