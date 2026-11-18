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
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
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
        val url = "$baseUrl/wp/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "popular")
            .build()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = "$baseUrl/wp/manga/".toHttpUrl().newBuilder()
            .addQueryParameter("page", page.toString())
            .addQueryParameter("order", "update")
            .build()
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()
        val statusFilter = filters.filterIsInstance<StatusFilter>().firstOrNull()?.selected.orEmpty()
        val typeFilter = filters.filterIsInstance<TypeFilter>().firstOrNull()?.selected.orEmpty()
        val orderFilter = filters.filterIsInstance<OrderByFilter>().firstOrNull()?.selected.orEmpty()
        val selectedGenres = filters.filterIsInstance<GenreFilter>().firstOrNull()
            ?.state
            ?.filter { it.state }
            ?.map { it.value }
            .orEmpty()

        val hasDirectoryFilters = statusFilter.isNotEmpty() ||
            typeFilter.isNotEmpty() ||
            orderFilter.isNotEmpty() ||
            selectedGenres.isNotEmpty()

        val url = if (trimmedQuery.isNotEmpty() && !hasDirectoryFilters) {
            val basePath = if (page > 1) "$baseUrl/wp/page/$page/" else "$baseUrl/wp/"
            basePath.toHttpUrl().newBuilder()
                .addQueryParameter("s", trimmedQuery)
                .build()
        } else {
            "$baseUrl/wp/manga/".toHttpUrl().newBuilder().apply {
                addQueryParameter("page", page.toString())
                addQueryParameter("status", statusFilter)
                addQueryParameter("type", typeFilter)
                addQueryParameter("order", orderFilter)
                selectedGenres.forEach { genre ->
                    addQueryParameter("genre[]", genre)
                }
                if (trimmedQuery.isNotEmpty()) {
                    addQueryParameter("title", trimmedQuery)
                }
            }.build()
        }

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val pageResult = parseMangaList(document)
        if (trimmedQuery.isNotEmpty() && hasDirectoryFilters && pageResult.mangas.isNotEmpty()) {
            val normalizedQuery = normalizeText(trimmedQuery)
            val filtered = pageResult.mangas.filter { normalizeText(it.title).contains(normalizedQuery) }
            if (filtered.isNotEmpty()) {
                return MangasPage(filtered, pageResult.hasNextPage)
            }
        }
        return pageResult
    }

    private fun parseMangaList(document: Document): MangasPage {
        val hasNextPage = document.selectFirst(".hpage a.r, .pagination a.next, a.next.page-numbers") != null
        val cards = document.select(".listupd .bsx, .listupd article .bsx, div.bsx")
            .distinctBy { it.selectFirst("a[href]")?.absUrl("href") ?: it.html() }

        val mangas = cards.mapNotNull { card ->
            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (href.isBlank() || !href.contains("/manga/")) return@mapNotNull null
            if (isNovelCard(card, href)) return@mapNotNull null

            val title = extractCardTitle(card, link)
            if (title.isBlank()) return@mapNotNull null

            SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = card.selectFirst("img")?.let(::extractImageUrl)
            }
        }

        return MangasPage(mangas, hasNextPage)
    }

    private fun extractCardTitle(card: Element, link: Element): String {
        val titleNode = card.selectFirst(".tt, .bigor .tt, h2, h3, h4, .title")
        val cleanedText = titleNode?.clone()?.apply {
            select("span.badge, span.type, .tag, small, .epxs, .adds").remove()
        }?.text()?.trim().orEmpty()

        val candidate = when {
            cleanedText.isNotBlank() && !cleanedText.endsWith("...") -> cleanedText
            link.attr("title").isNotBlank() -> link.attr("title").trim()
            cleanedText.isNotBlank() -> cleanedText
            else -> card.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        }
        return candidate.replace(WHITESPACE_REGEX, " ").trim()
    }

    private fun isNovelCard(card: Element, url: String): Boolean {
        val normalizedUrl = url.lowercase(Locale.ROOT)
        if (normalizedUrl.contains("/novela/") || normalizedUrl.contains("/novel/")) return true

        if (card.selectFirst(".novelabel, .type.Novel, .type.Novela") != null) return true

        val typeElement = card.selectFirst("span.type, .type")
        if (typeElement != null) {
            val classes = typeElement.classNames().joinToString(" ") { it.lowercase(Locale.ROOT) }
            val text = normalizeText(typeElement.text())
            if (NOVEL_REGEX.containsMatchIn(classes) || NOVEL_REGEX.containsMatchIn(text)) {
                return true
            }
        }
        return false
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = "$baseUrl${manga.url}"
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()

        val updatedManga = if (fetchDetails) parseMangaDetails(manga, document) else null
        val updatedChapters = if (fetchChapters) parseChapters(document) else null

        return SMangaUpdate(updatedManga ?: manga, updatedChapters ?: chapters)
    }

    private fun parseMangaDetails(baseManga: SManga, document: Document): SManga {
        val titleText = document.selectFirst("h1.entry-title, .infox h1, .seriestucon h1")
            ?.text()
            ?.replace(WHITESPACE_REGEX, " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: baseManga.title

        val coverUrl = document.selectFirst(".thumb img, .seriestucon .thumb img, article img[itemprop=image]")
            ?.let(::extractImageUrl)
            ?: baseManga.thumbnail_url

        val descriptionText = document.selectFirst(".entry-content[itemprop=description], .seriestucon .entry-content, div[itemprop=description], .entry-content")
            ?.clone()
            ?.apply {
                select("script, style, .code-block, .sharedaddy, .ai-viewports, noscript").remove()
            }
            ?.let { content ->
                val paragraphs = content.select("p").mapNotNull { p ->
                    p.text().trim().takeIf { it.isNotBlank() }
                }
                if (paragraphs.isNotEmpty()) paragraphs.joinToString("\n\n") else content.text().trim()
            }
            ?.takeIf { it.isNotBlank() }

        var parsedStatus = SManga.UNKNOWN
        var parsedAuthor: String? = null
        var parsedArtist: String? = null

        val infoRows = document.select(".tsinfo .imptdt, .spe span, .fmed, .infotable tr")
        for (row in infoRows) {
            val rowText = row.text().trim()
            if (rowText.isBlank()) continue
            val normalizedRow = normalizeText(rowText)
            val valueText = extractInfoValue(row)

            when {
                normalizedRow.contains("estado") || normalizedRow.contains("status") -> {
                    val mapped = mapStatus(valueText.ifBlank { rowText })
                    if (mapped != SManga.UNKNOWN) parsedStatus = mapped
                }
                normalizedRow.contains("autor") || normalizedRow.contains("author") -> {
                    if (isValidMetaValue(valueText)) parsedAuthor = valueText
                }
                normalizedRow.contains("artista") || normalizedRow.contains("artist") -> {
                    if (isValidMetaValue(valueText)) parsedArtist = valueText
                }
            }
        }

        if (parsedStatus == SManga.UNKNOWN) {
            document.selectFirst(".status, [class*=status], [class*=estado]")?.text()?.let {
                val mapped = mapStatus(it)
                if (mapped != SManga.UNKNOWN) parsedStatus = mapped
            }
        }

        val genres = document.select(".mgen a, .seriestugenre a, span.mgen a")
            .mapNotNull { el ->
                val genre = el.text().trim()
                if (isValidGenre(genre)) genre else null
            }
            .distinct()

        return SManga.create().apply {
            url = baseManga.url
            title = titleText
            thumbnail_url = coverUrl
            description = descriptionText
            author = parsedAuthor
            artist = parsedArtist
            status = parsedStatus
            genre = genres.joinToString(", ").takeIf { it.isNotBlank() }
            initialized = true
        }
    }

    private fun extractInfoValue(row: Element): String {
        val specific = row.selectFirst("td:last-child, span, i, a")?.text()?.trim()
        if (!specific.isNullOrBlank()) return specific
        val raw = row.text().trim()
        return raw.substringAfter(':', raw).trim()
    }

    private fun isValidMetaValue(value: String): Boolean {
        val normalized = normalizeText(value)
        return normalized.isNotBlank() && normalized != "-" && normalized != "n/a" && normalized != "desconocido"
    }

    private fun isValidGenre(genre: String): Boolean {
        if (genre.isBlank()) return false
        if (RATING_REGEX.matches(genre) || PURE_NUMBER_REGEX.matches(genre)) return false
        if (CHAPTER_BADGE_REGEX.containsMatchIn(genre)) return false
        if (mapStatus(genre) != SManga.UNKNOWN) return false
        return true
    }

    private fun mapStatus(raw: String): Int {
        val normalized = normalizeText(raw)
        return when {
            listOf("emision", "publicandose", "en curso", "activo", "ongoing", "publishing").any { it in normalized } -> SManga.ONGOING
            listOf("finalizado", "completado", "terminado", "concluido", "completed", "finished").any { it in normalized } -> SManga.COMPLETED
            listOf("pausa", "pausado", "espera", "hiatus").any { it in normalized } -> SManga.ON_HIATUS
            listOf("cancelado", "dropeado", "abandonado", "cancelled", "dropped").any { it in normalized } -> SManga.CANCELLED
            listOf("licenciado", "licensed").any { it in normalized } -> SManga.LICENSED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseChapters(document: Document): List<SChapter> {
        val chapterElements = document.select("#chapterlist ul li, .eplister ul li, div.bxcl ul li")
        return chapterElements.mapNotNull { item ->
            val link = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (href.isBlank()) return@mapNotNull null

            val rawLinkText = link.text().replace(WHITESPACE_REGEX, " ").trim()
            if (CTA_CHAPTER_REGEX.containsMatchIn(rawLinkText)) return@mapNotNull null
            if (link.closest(".inepcx, .lastend") != null) return@mapNotNull null

            val rawName = item.selectFirst(".chapternum")?.text()
                ?: link.selectFirst(".chapternum")?.text()
                ?: rawLinkText

            val cleanedName = cleanChapterName(rawName)
            val chapterNumber = extractChapterNumber(cleanedName, item.attr("data-num"))
            val finalName = if (cleanedName.isNotBlank()) {
                cleanedName
            } else if (chapterNumber >= 0f) {
                val numStr = if (chapterNumber % 1f == 0f) chapterNumber.toInt().toString() else chapterNumber.toString()
                "Capítulo $numStr"
            } else {
                "Capítulo"
            }

            val dateText = item.selectFirst(".chapterdate")?.text()?.trim()
                ?: link.selectFirst(".chapterdate")?.text()?.trim()
                ?: ""

            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = finalName
                date_upload = parseDate(dateText)
                if (chapterNumber >= 0f) {
                    chapter_number = chapterNumber
                }
            }
        }.distinctBy { it.url }
    }

    private fun cleanChapterName(raw: String): String {
        return raw
            .replace(WHITESPACE_REGEX, " ")
            .replace(RELATIVE_TIME_SUFFIX_REGEX, "")
            .replace(NEW_BADGE_SUFFIX_REGEX, "")
            .trim()
    }

    private fun extractChapterNumber(name: String, dataNum: String): Float {
        dataNum.trim().toFloatOrNull()?.let { return it }
        val match = CHAPTER_NUMBER_REGEX.find(name)
        return match?.groupValues?.getOrNull(1)?.replace(',', '.')?.toFloatOrNull() ?: -1f
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = "$baseUrl${chapter.url}"
        if (url.lowercase(Locale.ROOT).let { it.contains("/novela/") || it.contains("/novel/") }) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()

        if (isNovelDocument(document)) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val tsReaderPages = extractPagesFromTsReader(document)
        if (tsReaderPages.isNotEmpty()) {
            return tsReaderPages.mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
        }

        val readerContainer = document.selectFirst("#readerarea, .rdminimal, .chapter-content, div.reader-area")
        val imageUrls = if (readerContainer != null) {
            val directUrls = readerContainer.select("img")
                .filterNot(::isExcludedReaderImage)
                .mapNotNull(::extractImageUrl)
            if (directUrls.isNotEmpty()) {
                directUrls
            } else {
                readerContainer.select("noscript").flatMap { noscript ->
                    org.jsoup.Jsoup.parseBodyFragment(noscript.html()).select("img")
                        .filterNot(::isExcludedReaderImage)
                        .mapNotNull(::extractImageUrl)
                }
            }
        } else {
            emptyList()
        }.distinct()

        if (imageUrls.isEmpty()) {
            val hasNovelProse = document.select("#readerarea p, .entry-content p").count { it.text().trim().length > 80 } >= 3
            if (hasNovelProse) {
                throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
            }
            throw Exception("No se encontraron páginas de cómic en este capítulo.")
        }

        return imageUrls.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    private fun isNovelDocument(document: Document): Boolean {
        val typeText = document.select(".tsinfo .imptdt, .spe span, .breadcrumb, .bixbox .type")
            .joinToString(" ") { normalizeText(it.text() + " " + it.classNames().joinToString(" ")) }
        return NOVEL_REGEX.containsMatchIn(typeText) && document.selectFirst("#readerarea img") == null && !document.html().contains("ts_reader.run")
    }

    private fun extractPagesFromTsReader(document: Document): List<String> {
        for (script in document.select("script")) {
            val data = script.data()
            if (!data.contains("ts_reader.run")) continue
            val match = TS_READER_REGEX.find(data) ?: continue
            val jsonPayload = match.groupValues.getOrNull(1) ?: continue
            val dto = runCatching { tsJson.decodeFromString<TsReaderDto>(jsonPayload) }.getOrNull() ?: continue
            val images = dto.sources
                ?.firstOrNull { source -> !source?.images.isNullOrEmpty() }
                ?.images
                ?.mapNotNull { it?.trim()?.takeIf(::isValidImageUrl) }
                .orEmpty()
            if (images.isNotEmpty()) return images
        }
        return emptyList()
    }

    private fun isExcludedReaderImage(img: Element): Boolean {
        if (img.closest("header, footer, .wpdiscuz, #comments, .related, .bixbox:has(.bsx), .thumb") != null) {
            return true
        }
        val classAndAlt = (img.classNames().joinToString(" ") + " " + img.attr("alt")).lowercase(Locale.ROOT)
        return EXCLUDED_IMAGE_TERMS.any { it in classAndAlt }
    }

    private fun extractImageUrl(img: Element): String? {
        val candidateAttrs = listOf(
            "data-src",
            "data-lazy-src",
            "data-original",
            "data-url",
            "data-cfsrc",
            "data-echo",
            "srcset",
            "src",
        )
        for (attr in candidateAttrs) {
            if (!img.hasAttr(attr)) continue
            val resolved = if (attr == "srcset") {
                val firstEntry = img.attr("srcset").split(",").firstOrNull()?.trim()?.split(WHITESPACE_REGEX)?.firstOrNull().orEmpty()
                if (firstEntry.startsWith("http")) firstEntry else img.absUrl(attr).split(",").firstOrNull()?.trim()?.split(WHITESPACE_REGEX)?.firstOrNull().orEmpty()
            } else {
                img.absUrl(attr).ifBlank { img.attr(attr).trim() }
            }
            if (isValidImageUrl(resolved)) {
                return resolved
            }
        }
        return null
    }

    private fun isValidImageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("data:image/") || lower.endsWith(".svg")) return false
        return !EXCLUDED_IMAGE_TERMS.any { it in lower }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (!path.contains("/wp/manga/") || path.trimEnd('/') == "/wp/manga") return null
        val tempManga = SManga.create().apply {
            this.url = path
        }
        val update = fetchMangaUpdate(tempManga, emptyList(), fetchDetails = true, fetchChapters = false)
        return update.manga.takeIf { it.title.isNotBlank() }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Nota: Si escribes texto sin filtros se usa el buscador de WordPress."),
        StatusFilter(),
        TypeFilter(),
        OrderByFilter(),
        GenreFilter(),
    )

    private fun parseDate(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 0L

        val normalized = normalizeText(trimmed)
        val now = ZonedDateTime.now(ZoneOffset.UTC)

        if (normalized == "hoy" || normalized == "today" || normalized == "ahora" || normalized == "just now") {
            return now.toInstant().toEpochMilli()
        }
        if (normalized == "ayer" || normalized == "yesterday") {
            return now.minusDays(1).toInstant().toEpochMilli()
        }

        RELATIVE_ES_REGEX.find(normalized)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return@let
            val unit = match.groupValues[2]
            val target = when {
                unit.startsWith("seg") -> now.minus(amount, ChronoUnit.SECONDS)
                unit.startsWith("min") -> now.minus(amount, ChronoUnit.MINUTES)
                unit.startsWith("hor") -> now.minus(amount, ChronoUnit.HOURS)
                unit.startsWith("dia") -> now.minus(amount, ChronoUnit.DAYS)
                unit.startsWith("sem") -> now.minus(amount * 7, ChronoUnit.DAYS)
                unit.startsWith("mes") -> now.minus(amount, ChronoUnit.MONTHS)
                unit.startsWith("ano") -> now.minus(amount, ChronoUnit.YEARS)
                else -> null
            }
            if (target != null) return target.toInstant().toEpochMilli()
        }

        RELATIVE_EN_REGEX.find(normalized)?.let { match ->
            val amount = match.groupValues[1].toLongOrNull() ?: return@let
            val unit = match.groupValues[2]
            val target = when {
                unit.startsWith("sec") -> now.minus(amount, ChronoUnit.SECONDS)
                unit.startsWith("min") -> now.minus(amount, ChronoUnit.MINUTES)
                unit.startsWith("hour") -> now.minus(amount, ChronoUnit.HOURS)
                unit.startsWith("day") -> now.minus(amount, ChronoUnit.DAYS)
                unit.startsWith("week") -> now.minus(amount * 7, ChronoUnit.DAYS)
                unit.startsWith("month") -> now.minus(amount, ChronoUnit.MONTHS)
                unit.startsWith("year") -> now.minus(amount, ChronoUnit.YEARS)
                else -> null
            }
            if (target != null) return target.toInstant().toEpochMilli()
        }

        for (formatter in DATE_FORMATTERS) {
            val parsed = formatter.tryParseDate(trimmed)
            if (parsed > 0L) return parsed
        }
        return 0L
    }

    private fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "").trim()
    }

    companion object {
        private val tsJson = Json { ignoreUnknownKeys = true }
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
        private val NOVEL_REGEX = Regex("""\b(?:novela|novel|light[-\s]?novel|web[-\s]?novel)\b""", RegexOption.IGNORE_CASE)
        private val RATING_REGEX = Regex("""^\d+(?:[.,]\d+)?/\d+$""")
        private val PURE_NUMBER_REGEX = Regex("""^\d+$""")
        private val CHAPTER_BADGE_REGEX = Regex("""\b(?:cap\.?|capitulo|chapter)\s*\d+""", RegexOption.IGNORE_CASE)
        private val CTA_CHAPTER_REGEX = Regex("""^(?:leer|empezar|continuar|primer|ultimo|último)\b""", RegexOption.IGNORE_CASE)
        private val RELATIVE_TIME_SUFFIX_REGEX = Regex("""\s+hace\s+\d+.*$""", RegexOption.IGNORE_CASE)
        private val NEW_BADGE_SUFFIX_REGEX = Regex("""\s+(?:Nuevo|New)(?:\s+\d+)?\s*$""", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_REGEX = Regex("""(\d+(?:[.,]\d+)?)""")
        private val TS_READER_REGEX = Regex("""ts_reader\.run\((\{.*?\})\);?""", RegexOption.DOT_MATCHES_ALL)
        private val RELATIVE_ES_REGEX = Regex("""hace\s+(\d+)\s+(segundo|segundos|minuto|minutos|hora|horas|dia|dias|semana|semanas|mes|meses|ano|anos)""")
        private val RELATIVE_EN_REGEX = Regex("""(\d+)\s+(second|seconds|minute|minutes|hour|hours|day|days|week|weeks|month|months|year|years)\s+ago""")

        private val EXCLUDED_IMAGE_TERMS = listOf(
            "avatar",
            "gravatar",
            "logo",
            "banner",
            "icon",
            "loading",
            "placeholder",
            "blank.gif",
        )

        private val SPANISH_LOCALE = Locale.forLanguageTag("es-ES")
        private val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("MMMM d, yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("d 'de' MMM 'de' yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT),
        )
    }
}
