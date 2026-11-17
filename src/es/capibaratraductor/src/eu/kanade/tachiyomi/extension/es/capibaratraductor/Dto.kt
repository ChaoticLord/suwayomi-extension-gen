package eu.kanade.tachiyomi.extension.es.capibaratraductor

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

@Serializable
class MangaDto(
    val id: JsonPrimitive? = null,
    val title: String? = null,
    val name: String? = null,
    val cover: String? = null,
    val thumbnail: String? = null,
    val image: String? = null,
    val poster: String? = null,
    val scanName: String? = null,
    val scanSlug: String? = null,
    val mangaSlug: String? = null,
    val mangaUrl: String? = null,
    val url: String? = null,
    val slug: String? = null,
    val description: String? = null,
    val synopsis: String? = null,
    val sinopsis: String? = null,
    val summary: String? = null,
    val status: String? = null,
    val estado: String? = null,
    val type: String? = null,
    val format: String? = null,
    val category: String? = null,
    val contentType: String? = null,
    val author: String? = null,
    val artist: String? = null,
    val genres: List<JsonElement> = emptyList(),
    val tags: List<JsonElement> = emptyList(),
    val categories: List<JsonElement> = emptyList(),
    val chapters: List<ChapterDto> = emptyList(),
)

@Serializable
class ChapterDto(
    val id: JsonPrimitive? = null,
    val number: JsonPrimitive? = null,
    val title: String? = null,
    val name: String? = null,
    val releasedAt: String? = null,
    val publishedAt: String? = null,
    val createdAt: String? = null,
    val date: String? = null,
    val chapterUrl: String? = null,
    val url: String? = null,
    val slug: String? = null,
)
