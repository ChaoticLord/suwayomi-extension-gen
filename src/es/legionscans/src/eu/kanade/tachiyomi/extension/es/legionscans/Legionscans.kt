package eu.kanade.tachiyomi.extension.es.legionscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.Filter
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Legionscans : MangaThemesia() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        super.configureClient().rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = buildDirectoryUrl(page, order = "popular")
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = buildDirectoryUrl(page, order = "update")
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            val basePath = if (page > 1) "$baseUrl/wp/page/$page/" else "$baseUrl/wp/"
            val url = basePath.toHttpUrl().newBuilder()
                .addQueryParameter("s", trimmedQuery)
                .build()
                .toString()
            val document = client.get(url, Fetch.DOCUMENT).asJsoup()
            return parseMangaList(document)
        }

        var status = ""
        var type = ""
        var order = ""
        filters.filterIsInstance<SelectFilter>().forEach { filter ->
            when (filter.queryParam) {
                "status" -> status = filter.selectedValue()
                "type" -> type = filter.selectedValue()
                "order" -> order = filter.selectedValue()
            }
        }
        val url = buildDirectoryUrl(page, status = status, type = type, order = order)
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val document = client.get(toAbsoluteUrl(manga.url), Fetch.DOCUMENT).asJsoup()

        val updatedManga = if (fetchDetails) {
            SManga.create().apply {
                url = manga.url
                title = document.selectFirst("h1.entry-title, .infox h1, h1")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: manga.title
                thumbnail_url = extractImageUrl(
                    document.selectFirst(".thumb img, .thumbook img, .ime img"),
                ) ?: manga.thumbnail_url
                description = document.select(
                    ".entry-content[itemprop=description] p, .wd-full .entry-content p, " +
                        ".synops p, .entry-content p",
                )
                    .eachText()
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                    .ifBlank {
                        document.selectFirst(".entry-content[itemprop=description], .synops, .entry-content")
                            ?.text()
                            ?.trim()
                    }

                val infoRows = document.select(".tsinfo .imptdt, .fmed, .spe span")
                author = extractInfoField(infoRows, listOf("autor", "author"))
                artist = extractInfoField(infoRows, listOf("artista", "artist"))

                val rawStatus = extractInfoField(infoRows, listOf("estado", "status"))
                    ?: document.selectFirst(".status, [class*=status], [class*=estado]")?.text()
                    ?: ""
                status = parseStatus(rawStatus)

                genre = document.select(".mgen a, .seriestugenre a, .genres-content a")
                    .map { it.text().trim() }
                    .filter { isValidGenre(it) }
                    .distinct()
                    .joinToString(", ")
                    .ifBlank { null }
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            parseChapters(document)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga ?: manga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val document = client.get(toAbsoluteUrl(chapter.url), Fetch.DOCUMENT).asJsoup()

        if (isNovelDocument(document, chapter.url)) {
            throw Exception(
                "Este capítulo es una novela de texto y no es compatible con lectores de imágenes.",
            )
        }

        val scriptUrls = extractTsReaderImages(document)
        val imageUrls = if (scriptUrls.isNotEmpty()) {
            scriptUrls
        } else {
            document.select("#readerarea img, .reading-content img, .rdminimal img")
                .filterNot { img ->
                    val classes = img.className().lowercase(Locale.ROOT)
                    classes.contains("avatar") ||
                        classes.contains("ts-post-image") ||
                        classes.contains("wp-post-image") ||
                        classes.contains("cover") ||
                        classes.contains("portada") ||
                        img.closest(".thumb, .thumbook, .lastend, .bixbox.hothome") != null
                }
                .mapNotNull { extractImageUrl(it) }
                .distinct()
        }

        if (imageUrls.isEmpty()) {
            val proseLength = document.selectFirst("#readerarea, .reading-content, .entry-content")
                ?.text()
                ?.trim()
                ?.length
                ?: 0
            if (proseLength > 300) {
                throw Exception(
                    "Este capítulo es una novela de texto y no es compatible con lectores de imágenes.",
                )
            }
            throw Exception("No se encontraron páginas de imagen en el capítulo.")
        }

        return imageUrls.mapIndexed { index, imgUrl ->
            Page(index, imageUrl = imgUrl)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.startsWith("/wp/manga/") || path == "/wp/manga/") {
            return null
        }
        val manga = SManga.create().apply {
            this.url = path
        }
        return fetchMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SelectFilter(
            "Ordenar por",
            "order",
            arrayOf(
                "Predeterminado" to "",
                "Popular" to "popular",
                "Más recientes" to "update",
                "Agregados recientemente" to "latest",
                "A-Z" to "title",
                "Z-A" to "titlereverse",
            ),
        ),
        SelectFilter(
            "Estado",
            "status",
            arrayOf(
                "Todos" to "",
                "En emisión" to "ongoing",
                "Finalizado" to "completed",
                "Pausado" to "hiatus",
            ),
        ),
        SelectFilter(
            "Tipo",
            "type",
            arrayOf(
                "Todos" to "",
                "Manga" to "manga",
                "Manhwa" to "manhwa",
                "Manhua" to "manhua",
                "Cómic" to "comic",
            ),
        ),
    )

    private fun buildDirectoryUrl(
        page: Int,
        status: String = "",
        type: String = "",
        order: String = "",
    ): String {
        val basePath = if (page > 1) "$baseUrl/wp/manga/page/$page/" else "$baseUrl/wp/manga/"
        return basePath.toHttpUrl().newBuilder()
            .addQueryParameter("status", status)
            .addQueryParameter("type", type)
            .addQueryParameter("order", order)
            .build()
            .toString()
    }

    private fun parseMangaList(document: Document): MangasPage {
        val hasNextPage = document.selectFirst(".hpage a.r, .pagination a.next, a.next.page-numbers") != null
        val cards = document.select(".listupd .bs .bsx, .utao .uta")

        val mangas = cards.mapNotNull { card ->
            if (isNovelCard(card)) return@mapNotNull null

            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (href.isBlank() || !href.contains("/manga/")) return@mapNotNull null

            val rawTitle = card.selectFirst(".bigor .tt, .tt, .luf h4, h2, h3")
                ?.clone()
                ?.apply { select("span.badge, .tag, small, .type").remove() }
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.attr("title").trim().takeIf { it.isNotBlank() }
                ?: card.selectFirst("img")?.attr("alt")?.trim().orEmpty()

            if (rawTitle.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(href)
                title = rawTitle
                thumbnail_url = extractImageUrl(card.selectFirst("img"))
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, hasNextPage)
    }

    private fun isNovelCard(card: Element): Boolean {
        val typeText = card.select("span.type, .type, .badge, .tag")
            .joinToString(" ") { "${it.text()} ${it.className()}" }
            .lowercase(Locale.ROOT)
        val href = card.selectFirst("a[href]")?.absUrl("href")?.lowercase(Locale.ROOT).orEmpty()
        return NOVEL_KEYWORDS.any { keyword -> typeText.contains(keyword) } ||
            href.contains("/novela/") ||
            href.contains("/novel/")
    }

    private fun isNovelDocument(document: Document, chapterUrl: String): Boolean {
        val urlLower = chapterUrl.lowercase(Locale.ROOT)
        if (urlLower.contains("/novela/") || urlLower.contains("/novel/")) return true

        val typeText = document.select(".tsinfo .imptdt, .spe span, .seriestype, span.type")
            .text()
            .lowercase(Locale.ROOT)
        return NOVEL_KEYWORDS.any { keyword -> typeText.contains(keyword) }
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val elements = document.select("#chapterlist li, .eplister ul li")
        return elements.mapNotNull { li ->
            val link = li.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (href.isBlank()) return@mapNotNull null

            val linkTextLower = link.text().trim().lowercase(Locale.ROOT)
            if (CTA_PREFIXES.any { linkTextLower.startsWith(it) }) return@mapNotNull null

            val rawName = li.selectFirst(".chapternum")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.text().trim()
            val cleanedName = cleanChapterName(rawName)

            val dateText = li.selectFirst(".chapterdate")?.text()?.trim().orEmpty()
            val numAttr = li.attr("data-num").toFloatOrNull()

            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = cleanedName
                date_upload = parseDate(dateText)
                if (numAttr != null) {
                    chapter_number = numAttr
                }
            }
        }.distinctBy { it.url }
    }

    private fun cleanChapterName(raw: String): String {
        var cleaned = raw.trim()
        cleaned = RELATIVE_DATE_SUFFIX_REGEX.replace(cleaned, "")
        cleaned = NEW_BADGE_SUFFIX_REGEX.replace(cleaned, "")
        cleaned = MULTI_SPACE_REGEX.replace(cleaned, " ").trim()
        return cleaned.ifBlank { "Capítulo" }
    }

    private fun extractTsReaderImages(document: Document): List<String> {
        val scripts = document.select("script")
        for (script in scripts) {
            val data = script.data()
            val match = TS_READER_REGEX.find(data) ?: continue
            val jsonText = match.groupValues.getOrNull(1) ?: continue
            val images = runCatching {
                val root = Json.parseToJsonElement(jsonText).jsonObject
                val sources = root["sources"]?.jsonArray ?: return@runCatching emptyList()
                val firstSource = sources.firstOrNull()?.jsonObject ?: return@runCatching emptyList()
                firstSource["images"]?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.filter { isValidImageUrl(it) }
                    .orEmpty()
            }.getOrDefault(emptyList())
            if (images.isNotEmpty()) return images
        }
        return emptyList()
    }

    private fun extractImageUrl(element: Element?): String? {
        if (element == null) return null
        val srcsetFirst = element.attr("srcset")
            .substringBefore(",")
            .trim()
            .substringBefore(" ")
            .let { if (it.startsWith("http")) it else "" }
        val candidates = listOf(
            element.absUrl("data-src"),
            element.absUrl("data-lazy-src"),
            element.absUrl("data-original"),
            element.absUrl("data-url"),
            element.absUrl("data-cfsrc"),
            element.absUrl("data-echo"),
            srcsetFirst,
            element.absUrl("src"),
        )
        return candidates.firstOrNull { isValidImageUrl(it) }
    }

    private fun isValidImageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase(Locale.ROOT)
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) return false
        if (lower.startsWith("data:image/")) return false
        return BLOCKED_IMAGE_TOKENS.none { lower.contains(it) }
    }

    private fun extractInfoField(elements: List<Element>, labels: List<String>): String? {
        for (el in elements) {
            val fullText = el.text().trim()
            val normalized = normalizeText(fullText)
            if (labels.any { normalized.startsWith(it) || normalized.contains("$it:") }) {
                val value = el.selectFirst("i, a, span:last-child")?.text()?.trim()
                    ?.takeIf { it.isNotBlank() && !labels.contains(normalizeText(it)) }
                    ?: fullText.substringAfter(":", "").trim()
                if (value.isNotBlank() && value != "-" && value != "?") {
                    return value
                }
            }
        }
        return null
    }

    private fun parseStatus(raw: String): Int {
        val norm = normalizeText(raw)
        return when {
            norm.contains("emision") || norm.contains("en curso") || norm.contains("publicandose") ||
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

    private fun isValidGenre(genre: String): Boolean {
        if (genre.isBlank()) return false
        if (RATING_REGEX.matches(genre) || PURE_NUMBER_REGEX.matches(genre) || CHAPTER_BADGE_REGEX.matches(genre)) {
            return false
        }
        val norm = normalizeText(genre)
        return norm !in STATUS_WORDS
    }

    private fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "").trim()
    }

    private fun parseDate(raw: String): Long {
        val text = raw.trim()
        if (text.isBlank()) return 0L
        val norm = normalizeText(text)

        val now = ZonedDateTime.now(ZoneOffset.UTC)
        if (norm == "hoy" || norm == "today" || norm == "ahora" || norm == "just now") {
            return now.toInstant().toEpochMilli()
        }
        if (norm == "ayer" || norm == "yesterday") {
            return now.minusDays(1).toInstant().toEpochMilli()
        }

        val relMatch = RELATIVE_TIME_REGEX.find(norm)
        if (relMatch != null) {
            val amount = (relMatch.groupValues[1].ifBlank { relMatch.groupValues[3] }).toLongOrNull()
            val unit = relMatch.groupValues[2].ifBlank { relMatch.groupValues[4] }
            if (amount != null) {
                val adjusted = when {
                    unit.startsWith("seg") || unit.startsWith("sec") -> now.minus(amount, ChronoUnit.SECONDS)
                    unit.startsWith("min") -> now.minus(amount, ChronoUnit.MINUTES)
                    unit.startsWith("hor") || unit.startsWith("hour") -> now.minus(amount, ChronoUnit.HOURS)
                    unit.startsWith("dia") || unit.startsWith("day") -> now.minus(amount, ChronoUnit.DAYS)
                    unit.startsWith("sem") || unit.startsWith("week") -> now.minusWeeks(amount)
                    unit.startsWith("mes") || unit.startsWith("month") -> now.minusMonths(amount)
                    unit.startsWith("ano") || unit.startsWith("year") -> now.minusYears(amount)
                    else -> null
                }
                if (adjusted != null) return adjusted.toInstant().toEpochMilli()
            }
        }

        for (formatter in DATE_FORMATTERS) {
            val parsed = runCatching {
                LocalDate.parse(text, formatter).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()
            if (parsed != null) return parsed
        }

        return runCatching { Instant.parse(text).toEpochMilli() }.getOrDefault(0L)
    }

    private fun toAbsoluteUrl(pathOrUrl: String): String {
        return if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            pathOrUrl
        } else {
            "$baseUrl${if (pathOrUrl.startsWith("/")) pathOrUrl else "/$pathOrUrl"}"
        }
    }

    private class SelectFilter(
        name: String,
        val queryParam: String,
        private val options: Array<Pair<String, String>>,
    ) : Filter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        fun selectedValue(): String = options.getOrNull(state)?.second.orEmpty()
    }

    companion object {
        private val NOVEL_KEYWORDS = listOf("novela", "novel", "light novel", "web novel")
        private val CTA_PREFIXES = listOf(
            "leer ",
            "empezar",
            "continuar",
            "primer ",
            "último ",
            "ultimo ",
            "first ",
            "new chapter",
        )
        private val BLOCKED_IMAGE_TOKENS = listOf(
            ".svg",
            "gravatar.com/avatar",
            "/avatar",
            "logo",
            "banner",
            "icon",
            "loading",
            "placeholder",
            "blank.gif",
        )
        private val STATUS_WORDS = setOf(
            "en emision",
            "emision",
            "finalizado",
            "completado",
            "pausado",
            "cancelado",
            "ongoing",
            "completed",
            "hiatus",
            "cancelled",
        )

        private val RELATIVE_DATE_SUFFIX_REGEX = Regex("""\s+hace\s+\d+.*$""", RegexOption.IGNORE_CASE)
        private val NEW_BADGE_SUFFIX_REGEX = Regex(
            """\s+(?:Nuevo|New)(?:\s+\d+)?\s*$""",
            RegexOption.IGNORE_CASE,
        )
        private val MULTI_SPACE_REGEX = Regex("""\s+""")
        private val TS_READER_REGEX = Regex("""ts_reader\.run\((\{.*?\})\)\s*;?""", RegexOption.DOT_MATCHES_ALL)
        private val RATING_REGEX = Regex("""^\d+(?:[.,]\d+)?/\d+$""")
        private val PURE_NUMBER_REGEX = Regex("""^\d+$""")
        private val CHAPTER_BADGE_REGEX = Regex("""^cap\.?\s*\d+$""", RegexOption.IGNORE_CASE)
        private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
        private val RELATIVE_TIME_REGEX = Regex(
            """hace\s+(\d+)\s+([a-z]+)|(\d+)\s+([a-z]+)\s+ago""",
            RegexOption.IGNORE_CASE,
        )

        private val SPANISH_LOCALE = Locale.forLanguageTag("es")
        private val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("MMMM d, yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("d 'de' MMMM, yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("d/M/yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT),
        )
    }
}
