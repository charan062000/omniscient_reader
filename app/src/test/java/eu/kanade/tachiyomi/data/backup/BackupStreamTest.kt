package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupChapter
import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.BackupHistory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupPreference
import eu.kanade.tachiyomi.data.backup.models.BackupReaderAssetChunk
import eu.kanade.tachiyomi.data.backup.models.BackupReaderAssetRef
import eu.kanade.tachiyomi.data.backup.models.BackupReaderCategory
import eu.kanade.tachiyomi.data.backup.models.BackupReaderContent
import eu.kanade.tachiyomi.data.backup.models.BackupReaderPreference
import eu.kanade.tachiyomi.data.backup.models.BackupSource
import eu.kanade.tachiyomi.data.backup.models.BackupSourcePreferences
import eu.kanade.tachiyomi.data.backup.models.BackupTracking
import eu.kanade.tachiyomi.data.backup.models.BooleanPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.ReaderBackupEntry
import eu.kanade.tachiyomi.data.backup.models.StringPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.data.backup.models.mainAssetFile
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okio.Buffer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.File
import java.io.IOException

@Execution(ExecutionMode.CONCURRENT)
class BackupStreamTest {

    private val parser = ProtoBuf

    @Test
    fun `writer output is the plain encoding`() {
        val backup = backup()

        val bytes = write(backup)

        bytes shouldBe encode(backup)
        encode(parser.decodeFromByteArray(Backup.serializer(), bytes)) shouldBe encode(backup)
    }

    @Test
    fun `reader reads the plain encoding`() {
        val backup = backup()

        val (entries, metadata) = read(encode(backup))

        entries.map(::encode) shouldBe backup.backupManga.map(::encode)
        encode(metadata) shouldBe encode(backup.copy(backupManga = emptyList()))
    }

    @Test
    fun `legacy backups default reader format version to zero`() {
        val legacy = parser.decodeFromByteArray(Backup.serializer(), encode(backup()))

        legacy.readerBackupVersion shouldBe 0
    }

    @Test
    fun `missing reader source asset is treated as unavailable`() {
        val record = BackupReaderContent(
            id = "pdf:unavailable",
            type = "PDF",
            title = "Unavailable",
            assets = listOf(BackupReaderAssetRef("main-asset", "main", "pdf")),
        )

        record.mainAssetFile(emptyMap()) shouldBe null
        record.mainAssetFile(mapOf("main-asset" to File("missing-reader-backup.pdf"))) shouldBe null
    }

    @Test
    fun `reader library records and asset chunks coexist with legacy manga entries`() {
        val content = BackupReaderContent(
            id = "epub:stable-id",
            type = "EPUB",
            title = "Novel",
            assets = listOf(BackupReaderAssetRef("asset-1", "main", "epub")),
        )
        val category = BackupReaderCategory("Favorites", 0, "cover-1")
        val chunk = BackupReaderAssetChunk("asset-1", 0, byteArrayOf(1, 2, 3), true)
        val sink = Buffer()
        val writer = BackupWriter(sink, parser)
        val manga = backup().backupManga.first()
        writer.writeEntry(manga)
        writer.writeReaderEntry(ReaderBackupEntry.Content(content))
        writer.writeReaderEntry(ReaderBackupEntry.Category(category))
        writer.writeReaderEntry(ReaderBackupEntry.Asset(chunk))
        writer.writeMetadata(
            Backup(
                emptyList(),
                readerBackupVersion = 1,
                backupReaderPreferences = listOf(BackupReaderPreference("theme", "string", "SEPIA")),
            ),
        )

        val reader = BackupReader(Buffer().write(sink.readByteArray()), parser)
        val restoredManga = mutableListOf<BackupManga>()
        val readerEntries = mutableListOf<ReaderBackupEntry>()
        while (reader.nextEntry()) {
            if (reader.entryType ==
                EntryType.MANGA
            ) {
                restoredManga += reader.readEntry()
            } else {
                readerEntries += reader.readReaderEntry()
            }
        }

        restoredManga.map(::encode) shouldBe listOf(encode(manga))
        (readerEntries[0] as ReaderBackupEntry.Content).value shouldBe content
        (readerEntries[1] as ReaderBackupEntry.Category).value shouldBe category
        val restoredChunk = (readerEntries[2] as ReaderBackupEntry.Asset).value
        restoredChunk.assetId shouldBe chunk.assetId
        restoredChunk.index shouldBe chunk.index
        restoredChunk.last shouldBe chunk.last
        restoredChunk.data.contentEquals(chunk.data) shouldBe true
        val metadata = reader.readMetadata()
        metadata.readerBackupVersion shouldBe 1
        metadata.backupReaderPreferences shouldBe listOf(BackupReaderPreference("theme", "string", "SEPIA"))
    }

    @Test
    fun `reader counts entries without decoding them`() {
        val backup = backup()
        val reader = BackupReader(Buffer().write(encode(backup)), parser)

        var count = 0
        while (reader.nextEntry()) {
            reader.skipEntry()
            count++
        }

        count shouldBe backup.backupManga.size
        encode(reader.readMetadata()) shouldBe encode(backup.copy(backupManga = emptyList()))
    }

    @Test
    fun `reader decodes only the fields an entry class declares`() {
        val backup = backup()
        val reader = BackupReader(Buffer().write(encode(backup)), parser)

        val entries = buildList {
            while (reader.nextEntry()) add(reader.readEntry(PartialManga.serializer()))
        }

        entries.map { Triple(it.source, it.url, it.tracking.map(::encode)) } shouldBe backup.backupManga.map {
            Triple(it.source, it.url, it.tracking.map(::encode))
        }
        encode(reader.readMetadata()) shouldBe encode(backup.copy(backupManga = emptyList()))
    }

    @Test
    fun `reader takes fields in any order`() {
        val backup = backup()
        val (first, second, third) = backup.backupManga
        val input = Buffer()
            .write(encode(Backup(emptyList(), backupSources = backup.backupSources)))
            .write(encode(Backup(listOf(first))))
            // Fields Backup doesn't know, one of each remaining wire type
            .writeByte(3 shl 3 or 0).writeByte(0x96).writeByte(0x01)
            .writeByte(4 shl 3 or 1).writeLongLe(42)
            .write(encode(Backup(listOf(second), backupCategories = backup.backupCategories)))
            .writeByte(5 shl 3 or 5).writeIntLe(42)
            .write(encode(Backup(emptyList(), backupPreferences = backup.backupPreferences)))
            .write(encode(Backup(listOf(third), backupExtensionStores = backup.backupExtensionStores)))
            .write(encode(Backup(emptyList(), backupSourcePreferences = backup.backupSourcePreferences)))
            .readByteArray()

        val (entries, metadata) = read(input)

        entries.map(::encode) shouldBe backup.backupManga.map(::encode)
        encode(metadata) shouldBe encode(backup.copy(backupManga = emptyList()))
    }

    @Test
    fun `empty backup`() {
        val sink = Buffer()
        val writer = BackupWriter(sink, parser)
        writer.writeMetadata(Backup(emptyList()))

        writer.isEmpty shouldBe true
        sink.size shouldBe 0L

        val (entries, metadata) = read(ByteArray(0))
        entries shouldBe emptyList()
        encode(metadata) shouldBe ByteArray(0)
    }

    @Test
    fun `truncated input throws`() {
        val bytes = encode(backup())

        // Cut inside the last entry, inside the metadata, and right after a tag
        val entryEnd = encode(Backup(backup().backupManga)).size
        listOf(entryEnd - 1, bytes.size - 1, entryEnd + 1).forEach { size ->
            assertThrows<IOException> { read(bytes.copyOf(size)) }
        }
    }

    @Test
    fun `garbage input throws`() {
        listOf(
            // Wire type 7 doesn't exist
            byteArrayOf(0x0F),
            // Field number 0
            byteArrayOf(0x02, 0x00),
            // A varint that never ends
            ByteArray(11) { 0xFF.toByte() },
            // An entry longer than the input
            byteArrayOf(0x0A, 0x10, 0x01),
            // An entry that isn't a BackupManga
            byteArrayOf(0x0A, 0x02, 0x0F, 0x00),
        ).forEach { input ->
            assertThrows<IOException> { read(input) }
        }
    }

    private fun write(backup: Backup): ByteArray {
        val sink = Buffer()
        val writer = BackupWriter(sink, parser)
        backup.backupManga.forEach(writer::writeEntry)
        writer.writeMetadata(backup.copy(backupManga = emptyList()))
        writer.isEmpty shouldBe false
        return sink.readByteArray()
    }

    private fun read(bytes: ByteArray): Pair<List<BackupManga>, Backup> {
        val reader = BackupReader(Buffer().write(bytes), parser)
        val entries = buildList {
            while (reader.nextEntry()) add(reader.readEntry())
        }
        return entries to reader.readMetadata()
    }

    private fun encode(backup: Backup) = parser.encodeToByteArray(Backup.serializer(), backup)

    private fun encode(manga: BackupManga) = parser.encodeToByteArray(BackupManga.serializer(), manga)

    private fun encode(tracking: BackupTracking) = parser.encodeToByteArray(BackupTracking.serializer(), tracking)

    @Serializable
    private class PartialManga(
        @ProtoNumber(1) val source: Long,
        @ProtoNumber(2) val url: String,
        @ProtoNumber(18) val tracking: List<BackupTracking> = emptyList(),
    )

    private fun backup() = Backup(
        backupManga = listOf(
            BackupManga(
                source = 1L,
                url = "/manga/1",
                title = "First",
                genre = listOf("Action", "Drama"),
                chapters = listOf(
                    BackupChapter(url = "/chapter/1", name = "Chapter 1", read = true, chapterNumber = 1f),
                    BackupChapter(url = "/chapter/2", name = "Chapter 2", bookmark = true, lastPageRead = 4),
                ),
                categories = listOf(0L, 1L),
                tracking = listOf(BackupTracking(syncId = 2, libraryId = 3L, title = "First", mediaId = 7L)),
                history = listOf(BackupHistory(url = "/chapter/1", lastRead = 1_700_000_000_000L, readDuration = 60)),
                excludedScanlators = listOf("Group"),
            ),
            BackupManga(source = 2L, url = "/manga/2", title = "Second", favorite = false),
            BackupManga(
                source = 1L,
                url = "/manga/3",
                title = "Third",
                description = "x".repeat(300),
                chapters = List(200) { BackupChapter(url = "/chapter/3/$it", name = "Chapter $it") },
            ),
        ),
        backupCategories = listOf(
            BackupCategory(name = "Reading", order = 0),
            BackupCategory(name = "Done", order = 1),
        ),
        backupSources = listOf(BackupSource(name = "One", sourceId = 1L), BackupSource(name = "Two", sourceId = 2L)),
        backupPreferences = listOf(
            BackupPreference("flag", BooleanPreferenceValue(true)),
            BackupPreference("tags", StringSetPreferenceValue(setOf("a", "b"))),
        ),
        backupSourcePreferences = listOf(
            BackupSourcePreferences("source_1", listOf(BackupPreference("lang", StringPreferenceValue("en")))),
        ),
        backupExtensionStores = listOf(
            BackupExtensionStore(
                indexUrl = "https://example.org/index.min.json",
                name = "Store",
                badgeLabel = null,
                signingKey = "key",
                contactWebsite = "https://example.org",
                contactDiscord = null,
                isLegacy = false,
                extensionListUrl = null,
            ),
        ),
    )
}
