package eu.kanade.tachiyomi.extension.es.legionscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Legionscans : MangaThemesia() {

    override val mangaUrlDirectory: String = "/wp/manga"

    override val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale("es"))

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl$mangaUrlDirectory/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "popular")
            .build()
        return parseMangaList(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl$mangaUrlDirectory/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "update")
            .build()
        return parseMangaList(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()
        val hasActiveFilters = filters.any {
            when (it) {
                is StatusFilter -> it.state != 0
                is TypeFilter -> it.state != 0
                is OrderByFilter -> it.state != 0
                else -> false
            }
        }

        val url = if (trimmedQuery.isNotEmpty() && !hasActiveFilters) {
            "$baseUrl/wp/page/$page/".toHttpUrl().newBuilder()
                .addQueryParameter("s", trimmedQuery)
                .build()
        } else {
            "$baseUrl$mangaUrlDirectory/".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                if (trimmedQuery.isNotEmpty()) {
                    addQueryParameter("title", trimmedQuery)
                }
                filters.forEach { filter ->
                    when (filter) {
                        is StatusFilter -> addQueryParameter("status", filter.selectedValue())
                        is TypeFilter -> addQueryParameter("type", filter.selectedValue())
                        is OrderByFilter -> addQueryParameter("order", filter.selectedValue())
                        else -> {}
                    }
                }
            }.build()
        }

        return parseMangaList(client.get(url, Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (chapter.url.contains("/novela/", ignoreCase = true) ||
            chapter.url.contains("/novel/", ignoreCase = true)
        ) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }
        val pages = super.getPageList(chapter)
        if (pages.isEmpty()) {
            throw Exception("Este capítulo no contiene imágenes válidas (posible novela de texto).")
        }
        return pages
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        StatusFilter(),
        TypeFilter(),
        OrderByFilter(),
    )

    private fun parseMangaList(document: Document): MangasPage {
        val hasNextPage = document.selectFirst(".hpage a.r, .pagination a.next, a.next.page-numbers") != null
        val mangas = document.select(".listupd .bs .bsx").mapNotNull { card ->
            if (isNovelCard(card)) return@mapNotNull null

            val link = card.selectFirst("a") ?: return@mapNotNull null
            val href = link.absUrl("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (href.contains("/blog/", ignoreCase = true)) return@mapNotNull null

            val title = card.selectFirst(".tt")?.ownText()?.trim()?.takeIf { it.isNotEmpty() }
                ?: card.selectFirst(".tt, .name")?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: card.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                this.title = title
                this.url = toRelativeUrl(href)
                this.thumbnail_url = extractImageUrl(card.selectFirst("img"))
            }
        }
        return MangasPage(mangas, hasNextPage)
    }

    private fun isNovelCard(card: Element): Boolean {
        val typeElement = card.selectFirst(".limit .type, .type")
        val typeText = typeElement?.text()?.trim().orEmpty()
        val typeClass = typeElement?.className().orEmpty()
        val href = card.selectFirst("a")?.absUrl("href").orEmpty()
        return NOVEL_REGEX.containsMatchIn(typeText) ||
            NOVEL_REGEX.containsMatchIn(typeClass) ||
            href.contains("/novela/", ignoreCase = true) ||
            href.contains("/novel/", ignoreCase = true)
    }

    private fun extractImageUrl(img: Element?): String? {
        img ?: return null
        val attrs = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-url",
            "data-cfsrc",
            "data-echo",
            "srcset",
            "src",
        )
        for (attr in attrs) {
            val candidate = if (attr == "srcset") {
                img.attr("srcset").split(",").firstOrNull()?.trim()?.substringBefore(" ").orEmpty()
            } else {
                img.absUrl(attr).ifBlank { img.attr(attr).trim() }
            }
            if (candidate.isBlank()) continue
            if (candidate.startsWith("data:image/", ignoreCase = true)) continue
            if (IGNORED_IMAGE_REGEX.containsMatchIn(candidate)) continue
            return candidate
        }
        return null
    }

    private fun toRelativeUrl(fullUrl: String): String =
        fullUrl.toHttpUrlOrNull()?.let { parsed ->
            parsed.encodedPath + (parsed.encodedQuery?.let { "?$it" } ?: "")
        } ?: fullUrl.removePrefix(baseUrl)

    private open class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        fun selectedValue(): String = options[state].second
    }

    private class StatusFilter : SelectFilter(
        "Estado",
        arrayOf(
            "Todos" to "",
            "En emisión" to "ongoing",
            "Finalizado" to "completed",
            "En pausa" to "hiatus",
        ),
    )

    private class TypeFilter : SelectFilter(
        "Tipo",
        arrayOf(
            "Todos" to "",
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
            "Comic" to "comic",
        ),
    )

    private class OrderByFilter : SelectFilter(
        "Ordenar por",
        arrayOf(
            "Por defecto" to "",
            "A-Z" to "title",
            "Z-A" to "titlereverse",
            "Última actualización" to "update",
            "Recién añadidos" to "latest",
            "Popular" to "popular",
        ),
    )

    companion object {
        private val NOVEL_REGEX = Regex("""\b(?:novela|novel|light\s*novel|web\s*novel)\b""", RegexOption.IGNORE_CASE)
        private val IGNORED_IMAGE_REGEX = Regex("""(?:blank\.gif|placeholder|loading)\b|\.svg(?:\?|$)""", RegexOption.IGNORE_CASE)
    }
}
