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
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Legionscans : MangaThemesia() {

    override val mangaUrlDirectory: String = "/wp/manga"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = "$baseUrl$mangaUrlDirectory/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "popular")
            .build()
        val document = client.get(url.toString(), Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl$mangaUrlDirectory/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "update")
            .build()
        val document = client.get(url.toString(), Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val filterList = if (filters.isEmpty()) getFilterList(null) else filters
        var status = ""
        var type = ""
        var order = ""
        val genres = mutableListOf<String>()

        filterList.forEach { filter ->
            when (filter) {
                is StatusFilter -> status = filter.selectedValue()
                is TypeFilter -> type = filter.selectedValue()
                is OrderFilter -> order = filter.selectedValue()
                is GenreFilter -> filter.state.filter { it.state }.forEach { genres.add(it.id) }
                else -> {}
            }
        }

        val hasActiveFilters = status.isNotEmpty() || type.isNotEmpty() || order.isNotEmpty() || genres.isNotEmpty()

        val requestUrl = if (query.isNotBlank() && !hasActiveFilters) {
            val baseSearch = if (page > 1) "$baseUrl/wp/page/$page/" else "$baseUrl/wp/"
            baseSearch.toHttpUrl().newBuilder()
                .addQueryParameter("s", query.trim())
                .build()
        } else {
            "$baseUrl$mangaUrlDirectory/".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                if (query.isNotBlank()) addQueryParameter("title", query.trim())
                if (status.isNotEmpty()) addQueryParameter("status", status)
                if (type.isNotEmpty()) addQueryParameter("type", type)
                if (order.isNotEmpty()) addQueryParameter("order", order)
                genres.forEach { addQueryParameter("genre[]", it) }
            }.build()
        }

        val document = client.get(requestUrl.toString(), Fetch.DOCUMENT).asJsoup()
        val parsed = parseMangaList(document)

        if (query.isNotBlank() && parsed.mangas.isNotEmpty()) {
            val normalizedQuery = normalizeText(query)
            val filtered = parsed.mangas.filter { normalizeText(it.title).contains(normalizedQuery) }
            if (filtered.isNotEmpty()) {
                return MangasPage(filtered, parsed.hasNextPage)
            }
        }

        return parsed
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (!fetchDetails && !fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val fullUrl = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        val document = client.get(fullUrl, Fetch.DOCUMENT).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(manga, document) else manga
        val updatedChapters = if (fetchChapters) parseChapters(document) else chapters

        return SMangaUpdate(updatedManga ?: manga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = runCatching { super.getPageList(chapter) }.getOrNull().orEmpty()
        val validPages = pages.filterNot { isIgnoredImageUrl(it.imageUrl) }
        if (validPages.isNotEmpty()) {
            return validPages
        }

        val chapterUrl = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        val document = client.get(chapterUrl, Fetch.DOCUMENT).asJsoup()
        val isNovel = document.selectFirst("span.type.Novel, span.type.Novela, a[href*='/novela/'], a[href*='/novel/']") != null ||
            (document.selectFirst("script:containsData(ts_reader.run)") == null &&
                document.select("div#readerarea p").sumOf { it.text().trim().length } > 150)

        if (isNovel) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }

        return super.getPageList(chapter)
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        if (!url.encodedPath.startsWith(mangaUrlDirectory)) return null
        val stub = SManga.create().apply {
            this.url = url.encodedPath
        }
        return fetchMangaUpdate(stub, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        StatusFilter(),
        TypeFilter(),
        OrderFilter(),
        GenreFilter(),
    )

    private fun parseMangaList(document: Document): MangasPage {
        val hasNextPage = document.selectFirst("div.hpage a.r, div.pagination a.next, a.next.page-numbers") != null
        val cards = document.select("div.listupd div.bsx, div.bsx")

        val mangas = cards.mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val absUrl = link.absUrl("href")
            if (absUrl.isBlank() || !absUrl.contains(mangaUrlDirectory) || isNovelCard(card, absUrl)) {
                return@mapNotNull null
            }

            val title = extractCardTitle(card, link)
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(absUrl)
                this.title = title
                thumbnail_url = card.selectFirst("img")?.let { extractImageUrl(it) }
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, hasNextPage)
    }

    private fun isNovelCard(card: Element, href: String): Boolean {
        val lowerHref = href.lowercase(Locale.ROOT)
        if (lowerHref.contains("/novela/") || lowerHref.contains("/novel/")) return true

        val typeEl = card.selectFirst("span.type")
        val typeText = typeEl?.text()?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val typeClasses = typeEl?.classNames().orEmpty().map { it.lowercase(Locale.ROOT) }
        if (typeText in listOf("novel", "novela", "light novel", "web novel") ||
            typeClasses.any { it == "novel" || it == "novela" }
        ) {
            return true
        }

        val badgeText = card.select(".type, .badge, .egg, .status").text().lowercase(Locale.ROOT)
        return badgeText.contains("novela") || Regex("""\bnovel\b""").containsMatchIn(badgeText)
    }

    private fun extractCardTitle(card: Element, link: Element): String {
        val titleNode = card.selectFirst(".tt, .name, h2, h3, h4")
        val rawTitle = if (titleNode != null) {
            titleNode.clone().apply {
                select("span.badge, .tag, small, .type").remove()
            }.text().trim()
        } else {
            ""
        }
        if (rawTitle.isNotBlank()) return rawTitle

        val attrTitle = link.attr("title").trim()
        if (attrTitle.isNotBlank()) return attrTitle

        return card.selectFirst("img")?.attr("alt")?.trim().orEmpty()
    }

    private fun parseMangaDetails(baseManga: SManga, document: Document): SManga {
        val infoMap = mutableMapOf<String, String>()
        document.select(".infotable tr").forEach { row ->
            val cells = row.select("td")
            if (cells.size >= 2) {
                infoMap[normalizeText(cells[0].text())] = cells[1].text().trim()
            }
        }
        document.select(".tsinfo .imptdt, .fmed").forEach { item ->
            val label = item.selectFirst("b, span")?.text()?.let(::normalizeText).orEmpty()
            val value = item.selectFirst("i, a, span:last-child")?.text()?.trim()
                ?: item.ownText().trim()
            if (label.isNotBlank() && value.isNotBlank()) {
                infoMap.putIfAbsent(label, value)
            }
        }

        val rawDescription = document.selectFirst("div.entry-content[itemprop=description], div.entry-content")
            ?.clone()
            ?.apply {
                select("#wpd-post-rating, .wpd-post-rating, .code-block, script, style, [itemtype*=Product]").remove()
            }
            ?.let { content ->
                val paragraphs = content.select("p").map { it.text().trim() }.filter { it.isNotBlank() }
                if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else content.text().trim()
            }
            .orEmpty()

        val rawStatus = infoMap["estado"] ?: infoMap["status"]
            ?: document.selectFirst(".status, [class*=status], [class*=estado]")?.text()
            .orEmpty()

        val genres = document.select(".seriestugenre a, .mgen a, .gnr a")
            .map { it.text().trim() }
            .filter { genre ->
                val norm = normalizeText(genre)
                genre.isNotBlank() &&
                    !Regex("""^\d+(?:[.,]\d+)?/\d+$""").matches(genre) &&
                    !Regex("""^\d+$""").matches(genre) &&
                    !Regex("""^cap\.?\s*\d+$""", RegexOption.IGNORE_CASE).matches(genre) &&
                    norm !in listOf("en emision", "emision", "finalizado", "completado", "pausado", "cancelado", "ongoing", "completed", "novela", "novel")
            }
            .distinct()

        return SManga.create().apply {
            url = baseManga.url
            title = document.selectFirst("h1.entry-title")?.text()?.trim().takeUnless { it.isNullOrBlank() } ?: baseManga.title
            thumbnail_url = document.selectFirst(".seriestucont .thumb img, .thumb img, meta[property=og:image]")
                ?.let { el ->
                    if (el.tagName() == "meta") el.absUrl("content").takeIf { it.isNotBlank() }
                    else extractImageUrl(el)
                } ?: baseManga.thumbnail_url
            author = infoMap["autor"] ?: infoMap["author"]
            artist = infoMap["artista"] ?: infoMap["artist"]
            description = rawDescription.ifBlank { null }
            genre = genres.joinToString(", ").ifBlank { null }
            status = parseStatus(rawStatus)
        }
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val items = document.select("#chapterlist li")
        return items.mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val absUrl = link.absUrl("href")
            if (absUrl.isBlank() || absUrl.endsWith("#/")) return@mapNotNull null

            val numAttr = item.attr("data-num").toFloatOrNull()
            val rawName = item.selectFirst(".chapternum")?.text()?.trim()
                ?: link.clone().apply { select(".chapterdate").remove() }.text().trim()

            val cleanedName = rawName
                .replace(Regex("""\s+hace\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s+(?:Nuevo|New)(?:\s+\d+)?\s*$""", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank {
                    if (numAttr != null) "Capítulo ${formatChapterNumber(numAttr)}" else "Capítulo"
                }

            val dateText = item.selectFirst(".chapterdate")?.text()?.trim().orEmpty()

            SChapter.create().apply {
                setUrlWithoutDomain(absUrl)
                name = cleanedName
                date_upload = parseDate(dateText)
                chapter_number = numAttr ?: extractChapterNumber(cleanedName) ?: -1f
            }
        }.distinctBy { it.url }
    }

    private fun extractImageUrl(img: Element): String? {
        val candidates = mutableListOf<String>()
        listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-cfsrc", "data-echo").forEach { attr ->
            img.absUrl(attr).takeIf { it.isNotBlank() }?.let(candidates::add)
        }
        img.attr("srcset").split(",").firstOrNull()?.trim()?.split(Regex("""\s+"""))?.firstOrNull()
            ?.takeIf { it.startsWith("http") }?.let(candidates::add)
        img.absUrl("src").takeIf { it.isNotBlank() }?.let(candidates::add)

        return candidates.firstOrNull { !isIgnoredImageUrl(it) }
    }

    private fun isIgnoredImageUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val lower = url.lowercase(Locale.ROOT)
        return lower.startsWith("data:image/") ||
            lower.endsWith(".svg") ||
            lower.contains("readerarea.svg") ||
            lower.contains("wpdiscuz") ||
            lower.contains("icon_info") ||
            lower.contains("blank.gif") ||
            lower.contains("placeholder") ||
            lower.contains("loading")
    }

    private fun parseStatus(raw: String): Int {
        val norm = normalizeText(raw)
        return when {
            norm.contains("emision") || norm.contains("publicandose") || norm.contains("en curso") ||
                norm.contains("activo") || norm.contains("ongoing") || norm.contains("publishing") -> SManga.ONGOING
            norm.contains("finalizado") || norm.contains("completado") || norm.contains("terminado") ||
                norm.contains("concluido") || norm.contains("completed") || norm.contains("finished") -> SManga.COMPLETED
            norm.contains("pausa") || norm.contains("espera") || norm.contains("hiatus") -> SManga.ON_HIATUS
            norm.contains("cancelado") || norm.contains("dropeado") || norm.contains("abandonado") ||
                norm.contains("cancelled") || norm.contains("dropped") -> SManga.CANCELLED
            norm.contains("licenciado") || norm.contains("licensed") -> SManga.LICENSED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val normalized = normalizeText(raw)
        val now = ZonedDateTime.now(ZoneOffset.UTC)

        if (normalized == "hoy" || normalized == "today" || normalized == "justo ahora") {
            return now.toInstant().toEpochMilli()
        }
        if (normalized == "ayer" || normalized == "yesterday") {
            return now.minusDays(1).toInstant().toEpochMilli()
        }

        val relativeMatch = Regex("""(?:hace\s+)?(\d+)\s+(minuto|minutos|min|hora|horas|hr|dia|dias|day|days|semana|semanas|week|weeks|mes|meses|month|months|ano|anos|year|years)(?:\s+ago)?""")
            .find(normalized)
        if (relativeMatch != null) {
            val amount = relativeMatch.groupValues[1].toLongOrNull() ?: 0L
            val unit = relativeMatch.groupValues[2]
            val instant = when {
                unit.startsWith("min") -> now.minusMinutes(amount)
                unit.startsWith("h") -> now.minusHours(amount)
                unit.startsWith("d") -> now.minusDays(amount)
                unit.startsWith("s") || unit.startsWith("w") -> now.minusWeeks(amount)
                unit.startsWith("m") -> now.minusMonths(amount)
                unit.startsWith("a") || unit.startsWith("y") -> now.minusYears(amount)
                else -> now
            }
            return instant.toInstant().toEpochMilli()
        }

        val cleaned = raw.trim().replace(Regex("""\s+"""), " ")
        val patterns = listOf(
            "MMMM d, yyyy",
            "MMMM dd, yyyy",
            "d 'de' MMMM 'de' yyyy",
            "d MMMM, yyyy",
            "d MMMM yyyy",
            "MMM d, yyyy",
            "dd/MM/yyyy",
            "d/M/yyyy",
            "yyyy-MM-dd",
        )
        val locales = listOf(Locale("es", "ES"), Locale.ENGLISH)

        for (locale in locales) {
            for (pattern in patterns) {
                val parsed = runCatching {
                    val formatter = DateTimeFormatterBuilder()
                        .parseCaseInsensitive()
                        .appendPattern(pattern)
                        .toFormatter(locale)
                    LocalDate.parse(cleaned, formatter).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                }.getOrNull()
                if (parsed != null) return parsed
            }
        }

        return 0L
    }

    private fun extractChapterNumber(name: String): Float? {
        return Regex("""(\d+(?:\.\d+)?)""").find(name)?.groupValues?.getOrNull(1)?.toFloatOrNull()
    }

    private fun formatChapterNumber(num: Float): String {
        return if (num % 1f == 0f) num.toInt().toString() else num.toString()
    }

    private fun normalizeText(input: String): String {
        return Normalizer.normalize(input, Normalizer.Form.NFD)
            .replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
            .lowercase(Locale.ROOT)
            .trim()
    }
}
