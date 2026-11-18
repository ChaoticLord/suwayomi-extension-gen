package eu.kanade.tachiyomi.extension.es.legionscans

import kotlinx.serialization.Serializable

@Serializable
class TsReaderDto(
    val sources: List<TsReaderSourceDto?>? = null,
)

@Serializable
class TsReaderSourceDto(
    val source: String? = null,
    val images: List<String?>? = null,
)
