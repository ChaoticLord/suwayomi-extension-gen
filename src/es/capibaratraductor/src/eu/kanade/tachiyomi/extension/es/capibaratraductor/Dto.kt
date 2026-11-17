package eu.kanade.tachiyomi.extension.es.capibaratraductor

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class MangaListResponseDto(
    val data: List<MangaItemDto>? = null,
    val items: List<MangaItemDto>? = null,
    val mangas: List<MangaItemDto>? = null,
    val results: List<MangaItemDto>? = null,
    val pagination: PaginationDto? = null,
    val hasMore: Boolean? = null,
    val hasNextPage: Boolean? = null,
    val totalPages: Int? = null,
    val page: Int? = null,
)

@Serializable
class PaginationDto(
    val page: Int? = null,
    val currentPage: Int? = null,
    val totalPages: Int? = null,
    val hasNextPage: Boolean? = null,
    val hasMore: Boolean? = null,
)

@Serializable
class ScanInfoDto(
    val name: String? = null,
    val slug: String? = null,
)

@Serializable
class MangaItemDto(
    val id: JsonElement? = null,
    val title: String? = null,
    val name: String? = null,
    val alternativeTitle: String? = null,
    val slug: String? = null,
    val mangaSlug: String? = null,
    val mangaUrl: String? = null,
    val url: String? = null,
    val cover: String? = null,
    val coverUrl: String? = null,
    val imageUrl: String? = null,
    val thumbnail: String? = null,
    val scanName: String? = null,
    val scanSlug: String? = null,
    val organization: ScanInfoDto? = null,
    val isJoint: Boolean? = null,
    val isNovel: Boolean? = null,
    val type: String? = null,
    val format: String? = null,
    val mangaType: String? = null,
    val demography: String? = null,
    val status: String? = null,
    val badge: String? = null,
    val badges: List<String>? = null,
    val genres: List<JsonElement>? = null,
    val categories: List<JsonElement>? = null,
    val description: String? = null,
    val synopsis: String? = null,
    val author: String? = null,
    val artist: String? = null,
)

@Serializable
class PageDto(
    val imageUrl: String? = null,
    val url: String? = null,
    val src: String? = null,
    val image: String? = null,
    val pageNumber: Int? = null,
    val number: Int? = null,
)

@Serializable
class PagesResponseDto(
    val data: JsonElement? = null,
    val pages: List<PageDto>? = null,
    val images: List<PageDto>? = null,
)
