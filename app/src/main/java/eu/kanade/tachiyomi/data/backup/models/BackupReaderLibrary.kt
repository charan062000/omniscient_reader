package eu.kanade.tachiyomi.data.backup.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import java.io.File

@Serializable
data class BackupReaderContent(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val type: String,
    @ProtoNumber(3) val title: String,
    @ProtoNumber(4) val favorite: Boolean = false,
    @ProtoNumber(5) val addedAt: Long = 0,
    @ProtoNumber(6) val lastOpenedAt: Long? = null,
    @ProtoNumber(7) val progress: Long = 0,
    @ProtoNumber(8) val progressTotal: Long = 0,
    @ProtoNumber(9) val author: String? = null,
    @ProtoNumber(10) val categories: List<String> = emptyList(),
    @ProtoNumber(11) val assets: List<BackupReaderAssetRef> = emptyList(),
    @ProtoNumber(12) val pdfAnnotations: List<BackupPdfAnnotation> = emptyList(),
    @ProtoNumber(13) val epubBookmarks: List<BackupEpubBookmark> = emptyList(),
    @ProtoNumber(14) val readerPreferences: List<BackupReaderPreference> = emptyList(),
)

@Serializable
data class BackupReaderCategory(
    @ProtoNumber(1) val name: String,
    @ProtoNumber(2) val order: Long,
    @ProtoNumber(3) val coverAssetId: String? = null,
)

@Serializable
data class BackupReaderAssetRef(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val kind: String,
    @ProtoNumber(3) val extension: String,
)

@Serializable
data class BackupReaderAssetChunk(
    @ProtoNumber(1) val assetId: String,
    @ProtoNumber(2) val index: Int,
    @ProtoNumber(3) val data: ByteArray,
    @ProtoNumber(4) val last: Boolean,
)

@Serializable
data class BackupPdfAnnotation(
    @ProtoNumber(1) val id: String,
    @ProtoNumber(2) val pageIndex: Long,
    @ProtoNumber(3) val type: String,
    @ProtoNumber(4) val text: String? = null,
    @ProtoNumber(5) val imageAssetId: String? = null,
    @ProtoNumber(6) val left: Double = 0.0,
    @ProtoNumber(7) val top: Double = 0.0,
    @ProtoNumber(8) val right: Double = 0.0,
    @ProtoNumber(9) val bottom: Double = 0.0,
    @ProtoNumber(10) val createdAt: Long = 0,
)

@Serializable
data class BackupEpubBookmark(
    @ProtoNumber(1) val chapterIndex: Long,
    @ProtoNumber(2) val chapterTitle: String,
    @ProtoNumber(3) val selectedText: String? = null,
    @ProtoNumber(4) val context: String? = null,
    @ProtoNumber(5) val createdAt: Long = 0,
)

@Serializable
data class BackupReaderPreference(
    @ProtoNumber(1) val key: String,
    @ProtoNumber(2) val type: String,
    @ProtoNumber(3) val value: String? = null,
    @ProtoNumber(4) val stringSet: List<String> = emptyList(),
)

sealed interface ReaderBackupEntry {
    data class Content(val value: BackupReaderContent) : ReaderBackupEntry
    data class Category(val value: BackupReaderCategory) : ReaderBackupEntry
    data class Asset(val value: BackupReaderAssetChunk) : ReaderBackupEntry
}

const val READER_BACKUP_VERSION = 1

internal fun BackupReaderContent.mainAssetFile(assets: Map<String, File>): File? =
    this.assets.firstOrNull { it.kind == "main" }?.let { assets[it.id] }?.takeIf(File::isFile)
