package eu.kanade.tachiyomi.extension.es.legionscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import keiyoushi.annotation.Source
import keiyoushi.network.Fetch
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.asJsoup
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Legionscans : MangaThemesia() {

    private val tsReaderJson = Json { ignoreUnknownKeys = true }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "popular")
            .build()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseListingPage(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "update")
            .build()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseListingPage(document)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()
        val url = if (trimmedQuery.isNotEmpty()) {
            val baseSearchUrl = if (page > 1) "$baseUrl/page/$page/" else "$baseUrl/"
            baseSearchUrl.toHttpUrl().newBuilder()
                .addQueryParameter("s", trimmedQuery)
                .build()
        } else {
            val builder = "$baseUrl/manga/".toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())

            filters.forEach { filter ->
                when (filter) {
                    is LegionscansStatusFilter -> builder.addQueryParameter("status", filter.selectedValue())
                    is LegionscansTypeFilter -> builder.addQueryParameter("type", filter.selectedValue())
                    is LegionscansOrderByFilter -> builder.addQueryParameter("order", filter.selectedValue())
                    is LegionscansGenreListFilter -> {
                        filter.state.filter { it.state }.forEach { genre ->
                            builder.addQueryParameter("genre[]", genre.id)
                        }
                    }
                    else -> Unit
                }
            }
            builder.build()
        }

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseListingPage(document)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val update = super.fetchMangaUpdate(manga, chapters, fetchDetails, fetchChapters)
        val cleanedManga = if (fetchDetails) {
            val rawGenres = update.manga.genre.orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { genre ->
                    genre.isNotEmpty() &&
                        !GENRE_NOISE_REGEX.matches(genre) &&
                        !genre.equals("En emisión", ignoreCase = true) &&
                        !genre.equals("Finalizado", ignoreCase = true)
                }
            update.manga.apply {
                genre = rawGenres.joinToString(", ").ifBlank { null }
            }
        } else {
            update.manga
        }

        val cleanedChapters = if (fetchChapters) {
            update.chapters
                .filterNot { ch ->
                    val lower = ch.name.lowercase()
                    lower.startsWith("leer ") ||
                        lower.startsWith("empezar") ||
                        lower.startsWith("primer ") ||
                        lower.startsWith("último ") ||
                        lower.startsWith("continuar ")
                }
                .distinctBy { it.url }
        } else {
            update.chapters
        }

        return SMangaUpdate(cleanedManga, cleanedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val chapterUrl = when {
            chapter.url.startsWith("http") -> chapter.url
            chapter.url.startsWith("/wp/") -> "${baseUrl.removeSuffix("/wp")}${chapter.url}"
            else -> "$baseUrl${chapter.url}"
        }
        val document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()

        if (isNovelDocument(document, chapter.url)) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val readerAreaImages = extractReaderAreaImages(document)
        if (readerAreaImages.isNotEmpty()) {
            return readerAreaImages.mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
        }

        val scriptImages = extractTsReaderImages(document)
        if (scriptImages.isNotEmpty()) {
            return scriptImages.mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
        }

        val fallbackImages = document
            .select(".rdminimal img, .reading-content img, .chapter-content img")
            .filterNot { img ->
                img.closest(".chapter-header, .headpost, .wpdiscuz-comment-pagination, #comments, .related") != null
            }
            .mapNotNull { extractImageUrl(it) }
            .distinct()

        if (fallbackImages.isEmpty()) {
            if (document.select("#readerarea p, .novel-content p, .text-chapter p").text().length > 300) {
                throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
            }
            throw Exception("No se encontraron páginas de imágenes en este capítulo.")
        }

        return fallbackImages.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = getLegionscansFilterList()

    private fun extractReaderAreaImages(document: Document): List<String> {
        val directImages = document
            .select("#readerarea img")
            .filterNot { img ->
                img.closest(".chapter-header, .headpost, .wpdiscuz-comment-pagination, #comments, .related") != null
            }
            .mapNotNull { extractImageUrl(it) }
            .distinct()

        if (directImages.isNotEmpty()) return directImages

        return document.select("#readerarea noscript")
            .flatMap { noscript ->
                val rawHtml = noscript.data().ifBlank { noscript.html() }
                Jsoup.parseBodyFragment(rawHtml, document.baseUri()).select("img")
            }
            .mapNotNull { extractImageUrl(it) }
            .distinct()
    }

    private fun parseListingPage(document: Document): MangasPage {
        val hasNextPage = document.selectFirst(
            "div.hpage a.r, div.pagination a.next, a.next.page-numbers, .nav-links a.next",
        ) != null

        val cards = document.select(".listupd .bsx, .listupd .utao .uta, .bs .bsx")
        val mangas = cards.mapNotNull { card ->
            if (isNovelCard(card)) return@mapNotNull null

            val link = card.selectFirst("a[href*='/manga/']") ?: card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (href.isBlank() || !href.contains("/manga/")) return@mapNotNull null

            val rawTitle = card.selectFirst(".bigor .tt, .tt, h2, h3, .name")?.let { titleEl ->
                val cloned = titleEl.clone()
                cloned.select("span.badge, .tag, small, .type").remove()
                cloned.text().trim()
            }?.takeIf { it.isNotBlank() }
                ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                ?: card.selectFirst("img")?.attr("alt")?.trim().orEmpty()

            if (rawTitle.isBlank()) return@mapNotNull null

            val thumb = card.selectFirst("img")?.let { extractImageUrl(it) }
            val relativeUrl = href.removePrefix(baseUrl).let {
                if (it.startsWith("http")) "/${href.toHttpUrl().encodedPath.removePrefix("/wp")}" else it
            }

            SManga.create().apply {
                url = if (relativeUrl.startsWith("/")) relativeUrl else "/$relativeUrl"
                title = rawTitle
                thumbnail_url = thumb
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, hasNextPage)
    }

    private fun isNovelCard(card: Element): Boolean {
        val typeSpan = card.selectFirst("span.type, .type")
        val typeClasses = typeSpan?.classNames().orEmpty().joinToString(" ").lowercase()
        val typeText = typeSpan?.text().orEmpty().lowercase()
        val href = card.selectFirst("a[href]")?.absUrl("href").orEmpty().lowercase()

        return typeClasses.contains("novel") ||
            typeText.contains("novela") ||
            typeText.contains("novel") ||
            href.contains("/novela/") ||
            href.contains("/novel/")
    }

    private fun isNovelDocument(document: Document, chapterUrl: String): Boolean {
        val lowerUrl = chapterUrl.lowercase()
        if (lowerUrl.contains("/novela/") || lowerUrl.contains("/novel/")) return true

        val typeText = document.select(".tsinfo .imptdt, .spe span, span.type, .seriestucon .infotable tr")
            .text()
            .lowercase()
        if (typeText.contains("novela") || typeText.contains("light novel") || typeText.contains("web novel")) {
            return true
        }

        return document.selectFirst(".novel-content, .text-chapter") != null &&
            document.selectFirst("#readerarea img, .rdminimal img") == null
    }

    private fun extractTsReaderImages(document: Document): List<String> {
        val scripts = document.select("script")
        for (script in scripts) {
            val data = script.data()
            if (!data.contains("ts_reader.run")) continue
            val match = TS_READER_REGEX.find(data) ?: continue
            val jsonPayload = match.groupValues.getOrNull(1) ?: continue
            val parsed = runCatching {
                tsReaderJson.decodeFromString<TsReaderDto>(jsonPayload)
            }.getOrNull() ?: continue

            val images = parsed.sources
                ?.firstOrNull { !it?.images.isNullOrEmpty() }
                ?.images
                ?.filterNotNull()
                ?.filter { it.startsWith("http") && !isIgnoredImageUrl(it) }
                .orEmpty()

            if (images.isNotEmpty()) return images
        }
        return emptyList()
    }

    private fun extractImageUrl(img: Element): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-lazy", "data-original", "data-url", "data-cfsrc", "data-echo")
        for (attr in attrs) {
            val candidate = img.absUrl(attr).ifBlank { img.attr(attr).trim() }
            if (candidate.startsWith("http") && !isIgnoredImageUrl(candidate)) {
                return candidate
            }
        }
        for (srcsetAttr in listOf("data-srcset", "srcset")) {
            val srcsetUrl = img.attr(srcsetAttr).split(",").firstOrNull()
                ?.trim()
                ?.substringBefore(" ")
                ?.trim()
                .orEmpty()
            if (srcsetUrl.startsWith("http") && !isIgnoredImageUrl(srcsetUrl)) {
                return srcsetUrl
            }
        }
        val src = img.absUrl("src").ifBlank { img.attr("src").trim() }
        return src.takeIf { it.startsWith("http") && !isIgnoredImageUrl(it) }
    }

    private fun isIgnoredImageUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("data:image/") ||
            lower.endsWith(".svg") ||
            lower.contains("gravatar.com/avatar") ||
            lower.contains("/avatar") ||
            lower.contains("logo") ||
            lower.contains("banner") ||
            lower.contains("placeholder") ||
            lower.contains("loading") ||
            lower.contains("blank.gif")
    }

    companion object {
        private val TS_READER_REGEX = Regex("""ts_reader\.run\((\{.*?\})\);?""", RegexOption.DOT_MATCHES_ALL)
        private val GENRE_NOISE_REGEX = Regex("""^\d+(?:[.,]\d+)?(?:/\d+)?$|^Cap\.\s*\d+$""", RegexOption.IGNORE_CASE)
    }
}
