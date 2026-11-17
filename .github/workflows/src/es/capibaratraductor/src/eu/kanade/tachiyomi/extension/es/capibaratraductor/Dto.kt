package eu.kanade.tachiyomi.extension.es.capibaratraductor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
class CapibaraMangaDto(
    val id: JsonElement? = null,
    val title: String? = null,
    val name: String? = null,
    val cover: String? = null,
    val coverUrl: String? = null,
    val image: String? = null,
    val thumbnail: String? = null,
    val scanName: String? = null,
    val scanSlug: String? = null,
    val mangaSlug: String? = null,
    val slug: String? = null,
    val mangaUrl: String? = null,
    val url: String? = null,
    val synopsis: String? = null,
    val description: String? = null,
    val summary: String? = null,
    val status: JsonElement? = null,
    val state: JsonElement? = null,
    val type: String? = null,
    val format: String? = null,
    val category: String? = null,
    val language: String? = null,
    val author: JsonElement? = null,
    val artist: JsonElement? = null,
    val genres: JsonElement? = null,
    val tags: JsonElement? = null,
    val categories: JsonElement? = null,
    val chapters: List<CapibaraChapterDto> = emptyList(),
)

@Serializable
class CapibaraChapterDto(
    val id: JsonElement? = null,
    val number: Double? = null,
    val chapterNumber: Double? = null,
    val title: String? = null,
    val name: String? = null,
    val releasedAt: String? = null,
    val createdAt: String? = null,
    val publishedAt: String? = null,
    val chapterUrl: String? = null,
    val url: String? = null,
    val slug: String? = null,
)

@Serializable
class CapibaraPageDto(
    val url: String? = null,
    val imageUrl: String? = null,
    val src: String? = null,
    val image: String? = null,
    val path: String? = null,
    val file: String? = null,
    val page: Int? = null,
    val order: Int? = null,
    val number: Int? = null,
)
