package omniscient.reader.docs

import kotlinx.serialization.Serializable

/**
 * One document in the Documents library.
 *
 * @param type "pdf" for now; "epub" and others will be added later.
 */
@Serializable
data class DocItem(
    val id: String,
    val title: String,
    val uri: String,
    val type: String,
    val pageCount: Int,
    val lastPage: Int = 0,
    val addedAt: Long = 0L,
)
