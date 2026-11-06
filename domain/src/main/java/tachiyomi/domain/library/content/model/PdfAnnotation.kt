package tachiyomi.domain.library.content.model

enum class PdfAnnotationType {
    BOOKMARK,
    HIGHLIGHT,
    NOTE,
    IMAGE,
}

/** Coordinates are normalized to the page bounds, so annotations survive zoom and rotation. */
data class PdfAnnotation(
    val id: String,
    val documentId: String,
    val pageIndex: Long,
    val type: PdfAnnotationType,
    val text: String?,
    val imageUri: String?,
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
    val createdAt: Long,
)
