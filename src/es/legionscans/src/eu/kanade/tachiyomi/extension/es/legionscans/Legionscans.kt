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
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Legionscans : MangaThemesia() {

    override val mangaUrlDirectory: String = "/wp/manga"

    override val dateFormat: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("MMMM d, yyyy")
        .toFormatter(Locale.forLanguageTag("es"))

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
        if (trimmedQuery.isNotEmpty()) {
            val searchPath = if (page > 1) "$baseUrl/wp/page/$page/" else "$baseUrl/wp/"
            val url = searchPath.toHttpUrl().newBuilder()
                .addQueryParameter("s", trimmedQuery)
                .build()
            return parseMangaList(client.get(url, Fetch.DOCUMENT).asJsoup())
        }

        val urlBuilder = "$baseUrl$mangaUrlDirectory/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())

        filters.forEach { filter ->
            when (filter) {
                is StatusFilter -> urlBuilder.addQueryParameter("status", filter.selectedValue())
                is TypeFilter -> urlBuilder.addQueryParameter("type", filter.selectedValue())
                is OrderFilter -> urlBuilder.addQueryParameter("order", filter.selectedValue())
                else -> {}
            }
        }

        return parseMangaList(client.get(urlBuilder.build(), Fetch.DOCUMENT).asJsoup())
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = try {
            super.getPageList(chapter)
        } catch (e: Exception) {
            val doc = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
            if (isNovelDocument(doc)) {
                throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
            }
            throw e
        }
        if (pages.isEmpty()) {
            val doc = client.get(getChapterUrl(chapter), Fetch.DOCUMENT).asJsoup()
            if (isNovelDocument(doc)) {
                throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
            }
        }
        return pages
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Nota: los filtros no se aplican cuando hay texto de búsqueda."),
        StatusFilter(),
        TypeFilter(),
        OrderFilter(),
    )

    private fun parseMangaList(doc: Document): MangasPage {
        val hasNextPage = doc.selectFirst("div.pagination a.next, a.next.page-numbers, div.hpage a.r") != null
        val elements = doc.select("div.listupd div.bsx, div.bsx")
            .filterNot { isNovelCard(it) }

        val mangas = elements.mapNotNull { element ->
            val link = element.selectFirst("a") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (href.isBlank() || !href.contains("/manga/")) return@mapNotNull null

            val titleNode = element.selectFirst(".bigor .tt, .tt, span.name")?.clone()
            titleNode?.select("span.badge, .tag, small")?.remove()
            val title = titleNode?.text()?.trim()?.takeIf { it.isNotEmpty() }
                ?: link.attr("title").trim().takeIf { it.isNotEmpty() }
                ?: element.selectFirst("img")?.attr("alt")?.trim().orEmpty()
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = extractImageUrl(element.selectFirst("img"))
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, hasNextPage)
    }

    private fun isNovelCard(element: Element): Boolean {
        val typeEl = element.selectFirst("span.type, .type")
        val typeClasses = typeEl?.classNames()?.joinToString(" ").orEmpty().lowercase()
        val typeText = typeEl?.text().orEmpty().lowercase()
        val href = element.selectFirst("a")?.absUrl("href").orEmpty().lowercase()
        return typeClasses.contains("novel") ||
            typeText.contains("novel") ||
            href.contains("/novela/") ||
            href.contains("/novel/")
    }

    private fun isNovelDocument(doc: Document): Boolean {
        val hasTsReaderImages = doc.select("script")
            .any { it.data().contains("ts_reader.run") && it.data().contains("\"images\"") }
        if (hasTsReaderImages) return false

        val readerImages = doc.select("#readerarea img")
            .mapNotNull { extractImageUrl(it) }
            .filterNot { it.contains("readerarea.svg") }
        if (readerImages.isNotEmpty()) return false

        val metaType = doc.selectFirst(".seriestucont, .infox, .tsinfo, .bixbox, .headpost")
            ?.text().orEmpty().lowercase()
        val readerTextLength = doc.selectFirst("#readerarea")?.text()?.trim()?.length ?: 0

        return metaType.contains("novela") ||
            metaType.contains("light novel") ||
            metaType.contains("web novel") ||
            readerTextLength > 200
    }

    private fun extractImageUrl(img: Element?): String? {
        if (img == null) return null
        val candidates = listOf(
            img.absUrl("data-src"),
            img.absUrl("data-lazy-src"),
            img.absUrl("data-original"),
            img.absUrl("data-url"),
            img.absUrl("src"),
        )
        return candidates.firstOrNull { url ->
            url.isNotBlank() &&
                !url.startsWith("data:image/") &&
                !url.endsWith(".svg", ignoreCase = true) &&
                !url.contains("blank.gif", ignoreCase = true)
        }
    }

    private open class SelectOptionFilter(
        name: String,
        private val vals: Array<Pair<String, String>>,
    ) : Filter.Select<String>(name, vals.map { it.first }.toTypedArray()) {
        fun selectedValue(): String = vals[state].second
    }

    private class StatusFilter : SelectOptionFilter(
        "Estado",
        arrayOf(
            "Todos" to "",
            "En emisión" to "ongoing",
            "Finalizado" to "completed",
            "En pausa" to "hiatus",
        ),
    )

    private class TypeFilter : SelectOptionFilter(
        "Tipo",
        arrayOf(
            "Todos" to "",
            "Manga" to "manga",
            "Manhwa" to "manhwa",
            "Manhua" to "manhua",
            "Comic" to "comic",
        ),
    )

    private class OrderFilter : SelectOptionFilter(
        "Ordenar por",
        arrayOf(
            "Por defecto" to "",
            "A-Z" to "title",
            "Z-A" to "titlereverse",
            "Última actualización" to "update",
            "Agregados recientemente" to "latest",
            "Popular" to "popular",
        ),
    )
}
