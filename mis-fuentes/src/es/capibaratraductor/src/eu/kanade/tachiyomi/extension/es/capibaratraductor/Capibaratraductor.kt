package eu.kanade.tachiyomi.extension.es.capibaratraductor

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
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Capibaratraductor : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("biblioteca")
            .addQueryParameter("orden", "popular")
            .addQueryParameter("pagina", page.toString())
            .build()
            .toString()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("biblioteca")
            .addQueryParameter("orden", "recientes")
            .addQueryParameter("pagina", page.toString())
            .build()
            .toString()

        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("biblioteca")
            .addQueryParameter("pagina", page.toString())

        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query.trim())
        }

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> {
                    if (filter.selectedValue.isNotBlank()) {
                        urlBuilder.addQueryParameter("orden", filter.selectedValue)
                    }
                }
                is StatusFilter -> {
                    if (filter.selectedValue.isNotBlank()) {
                        urlBuilder.addQueryParameter("estado", filter.selectedValue)
                    }
                }
                is GenreFilter -> {
                    val selectedGenres = filter.state.filter { it.state }.map { it.slug }
                    if (selectedGenres.isNotEmpty()) {
                        urlBuilder.addQueryParameter("generos", selectedGenres.joinToString(","))
                    }
                }
                else -> Unit
            }
        }

        val document = client.get(urlBuilder.build().toString(), Fetch.DOCUMENT).asJsoup()
        return parseMangaList(document)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val fullUrl = resolveUrl(manga.url)
        val document = client.get(fullUrl, Fetch.DOCUMENT).asJsoup()

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document, fullUrl)
        } else {
            null
        }

        val updatedChapters = if (fetchChapters) {
            parseChapterList(document)
        } else {
            null
        }

        return SMangaUpdate(updatedManga ?: manga, updatedChapters ?: chapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val fullUrl = resolveUrl(chapter.url)
        val document = client.get(fullUrl, Fetch.DOCUMENT).asJsoup()

        if (isNovelDocument(document, fullUrl)) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val scriptImages = extractImagesFromScripts(document)
        if (scriptImages.isNotEmpty()) {
            return scriptImages.mapIndexed { index, imageUrl ->
                Page(index, imageUrl = imageUrl)
            }
        }

        val readerImages = document.select(READER_IMG_SELECTOR)
            .asSequence()
            .filterNot { img -> isHeaderOrCoverImage(img) }
            .mapNotNull { img -> extractImageUrl(img) }
            .distinct()
            .toList()

        if (readerImages.isEmpty()) {
            if (hasTextNovelBody(document)) {
                throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
            }
            throw Exception("No se encontraron páginas de imágenes en este capítulo.")
        }

        return readerImages.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val document = client.get(url.toString(), Fetch.DOCUMENT).asJsoup()
        if (isNovelDocument(document, url.toString())) {
            return null
        }
        val manga = parseMangaDetails(document, url.toString())
        return if (manga.title.isNotBlank()) manga else null
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(FilterData.sortOptions),
        StatusFilter(FilterData.statusOptions),
        GenreFilter(FilterData.createGenres()),
    )

    private fun parseMangaList(document: Document): MangasPage {
        val hasNextPage = document.selectFirst(NEXT_PAGE_SELECTOR) != null
        val cards = document.select(CATALOG_CARD_SELECTOR)

        val mangas = cards.mapNotNull { card ->
            if (isNovelCard(card)) {
                return@mapNotNull null
            }

            val link = card.selectFirst("a[href]") ?: return@mapNotNull null
            val href = link.absUrl("href")
            if (href.isBlank() || NOVEL_URL_REGEX.containsMatchIn(href)) {
                return@mapNotNull null
            }

            val title = extractCardTitle(card, link)
            if (title.isBlank()) {
                return@mapNotNull null
            }

            SManga.create().apply {
                this.title = title
                thumbnail_url = card.selectFirst("img")?.let(::extractImageUrl)
                setUrlWithoutDomain(href)
            }
        }.distinctBy { it.url }

        return MangasPage(mangas, hasNextPage)
    }

    private fun parseMangaDetails(document: Document, fullUrl: String): SManga {
        val rawTitle = document.selectFirst("h1, .series-title, .manga-title, .post-title h1")
            ?.clone()
            ?.apply { select(".badge, .tag, small, .status").remove() }
            ?.text()
            ?.trim()
            .orEmpty()

        val description = document.selectFirst(".synopsis, .description, .summary__content, [itemprop=description], .manga-synopsis")
            ?.text()
            ?.trim()

        val author = extractMetaValue(document, listOf("autor", "author"))
        val artist = extractMetaValue(document, listOf("artista", "ilustrador", "artist"))

        val statusText = extractStatusText(document)
        val status = parseStatus(statusText)

        val genres = document.select(".genres a, .genre-tag, .tags a, a[href*=genero], a[href*=genre], .badges span")
            .map { it.text().trim() }
            .filter { tag -> isValidGenre(tag) }
            .distinct()

        val coverUrl = document.selectFirst(".series-cover img, .manga-cover img, .summary_image img, .cover img, img[class*=cover], img[class*=portada]")
            ?.let(::extractImageUrl)

        return SManga.create().apply {
            title = rawTitle
            this.description = description
            this.author = author
            this.artist = artist
            this.status = status
            genre = genres.joinToString(", ").ifBlank { null }
            thumbnail_url = coverUrl
            setUrlWithoutDomain(fullUrl)
        }
    }

    private fun parseChapterList(document: Document): List<SChapter> {
        val containers = document.select(CHAPTER_LIST_SELECTOR)
        return containers.mapNotNull { element ->
            val anchor = if (element.tagName() == "a") element else element.selectFirst("a[href]")
            anchor ?: return@mapNotNull null

            val href = anchor.absUrl("href")
            if (href.isBlank()) return@mapNotNull null

            val anchorTextLower = anchor.text().trim().lowercase(Locale.ROOT)
            if (CTA_PREFIXES.any { anchorTextLower.startsWith(it) }) {
                return@mapNotNull null
            }

            val titleNode = element.selectFirst(".chapter-title, .chapternum, .chapter-name, strong, h3, h4")
            val rawName = (titleNode?.text() ?: anchor.text()).trim()
            val cleanedName = cleanChapterName(rawName, href)
            if (cleanedName.isBlank()) return@mapNotNull null

            val dateText = element.selectFirst("time, .chapter-date, .chapter-release-date, .date, [class*=date], [class*=time]")
                ?.let { it.attr("datetime").ifBlank { it.text() } }
                ?.trim()
                .orEmpty()
                .ifBlank { extractRelativeDateFragment(rawName) }

            SChapter.create().apply {
                name = cleanedName
                date_upload = parseDate(dateText)
                setUrlWithoutDomain(href)
            }
        }.distinctBy { it.url }
    }

    private fun extractCardTitle(card: Element, link: Element): String {
        val titleEl = card.selectFirst("h2, h3, h4, .title, .tt, .post-title, [class*=title]")
        val cleanedFromNode = titleEl?.clone()
            ?.apply { select(".badge, .tag, small, .status, .chapter, .cap").remove() }
            ?.text()
            ?.trim()
            .orEmpty()

        val candidate = cleanedFromNode
            .ifBlank { link.attr("title").trim() }
            .ifBlank { card.selectFirst("img")?.attr("alt")?.trim().orEmpty() }

        return candidate
            .replace(TRAILING_CHAPTER_NOISE_REGEX, "")
            .trim()
    }

    private fun isNovelCard(card: Element): Boolean {
        val badgesText = card.select(".badge, .tag, .type, .format, [class*=type], [class*=badge]")
            .joinToString(" ") { it.text() }
        return NOVEL_TEXT_REGEX.containsMatchIn(badgesText)
    }

    private fun isNovelDocument(document: Document, url: String): Boolean {
        if (NOVEL_URL_REGEX.containsMatchIn(url)) return true
        val badgesText = document.select(".series-type, .manga-type, .badge, .tag, .breadcrumb, [class*=type]")
            .joinToString(" ") { it.text() }
        return NOVEL_TEXT_REGEX.containsMatchIn(badgesText)
    }

    private fun hasTextNovelBody(document: Document): Boolean {
        val textLength = document.selectFirst(".reading-content, .chapter-content, .novel-content, article")
            ?.select("p")
            ?.sumOf { it.text().length }
            ?: 0
        return textLength > 400
    }

    private fun extractImagesFromScripts(document: Document): List<String> {
        val scripts = document.select("script").joinToString("\n") { it.data() }
        if (scripts.isBlank()) return emptyList()

        val arrayMatch = SCRIPT_IMAGES_ARRAY_REGEX.find(scripts) ?: return emptyList()
        val arrayBody = arrayMatch.groupValues.getOrNull(1).orEmpty()

        return URL_IN_QUOTES_REGEX.findAll(arrayBody)
            .map { it.groupValues[1].replace("\\/", "/") }
            .filter { url -> isValidImageUrl(url) }
            .distinct()
            .toList()
    }

    private fun isHeaderOrCoverImage(img: Element): Boolean {
        val classes = (img.className() + " " + (img.parent()?.className().orEmpty())).lowercase(Locale.ROOT)
        if (COVER_CLASS_TOKENS.any { classes.contains(it) }) return true
        return img.parents().any { parent ->
            val tag = parent.tagName().lowercase(Locale.ROOT)
            val parentClass = parent.className().lowercase(Locale.ROOT)
            tag == "header" || parentClass.contains("header") || parentClass.contains("sidebar") || parentClass.contains("recommend")
        }
    }

    private fun extractImageUrl(img: Element): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-cfsrc", "data-echo")
        for (attr in attrs) {
            val candidate = img.absUrl(attr).ifBlank { img.attr(attr).trim() }
            if (isValidImageUrl(candidate)) return resolveUrl(candidate)
        }

        val srcset = img.attr("srcset").trim()
        if (srcset.isNotBlank()) {
            val firstSrc = srcset.split(",").firstOrNull()?.trim()?.split(Regex("""\s+"""))?.firstOrNull().orEmpty()
            if (isValidImageUrl(firstSrc)) return resolveUrl(firstSrc)
        }

        val src = img.absUrl("src").ifBlank { img.attr("src").trim() }
        return if (isValidImageUrl(src)) resolveUrl(src) else null
    }

    private fun isValidImageUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("data:image/")) return false
        if (lower.endsWith(".svg")) return false
        return INVALID_IMAGE_TOKENS.none { lower.contains(it) }
    }

    private fun extractMetaValue(document: Document, labels: List<String>): String? {
        val rows = document.select(".post-content_item, .meta-item, .info-row, li, tr")
        for (row in rows) {
            val text = normalizeText(row.text())
            if (labels.any { label -> text.startsWith("$label:") || text.contains("$label ") }) {
                val valueEl = row.selectFirst(".summary-content, span:last-child, td:last-child, a")
                val value = valueEl?.text()?.trim()
                if (!value.isNullOrBlank() && !labels.contains(normalizeText(value))) {
                    return value
                }
            }
        }
        return null
    }

    private fun extractStatusText(document: Document): String {
        val direct = document.selectFirst(".status, [class*=status], [class*=estado]")?.text()?.trim()
        if (!direct.isNullOrBlank()) return direct

        val meta = extractMetaValue(document, listOf("estado", "status"))
        if (!meta.isNullOrBlank()) return meta

        val badges = document.select(".badge, .tag, .info-badge, span")
        for (badge in badges) {
            val txt = badge.text().trim()
            if (parseStatus(txt) != SManga.UNKNOWN) {
                return txt
            }
        }
        return ""
    }

    private fun parseStatus(raw: String): Int {
        val normalized = normalizeText(raw)
        return when {
            normalized.contains("emision") || normalized.contains("publicandose") || normalized.contains("en curso") || normalized.contains("activo") || normalized.contains("ongoing") || normalized.contains("publishing") -> SManga.ONGOING
            normalized.contains("finalizado") || normalized.contains("completado") || normalized.contains("terminado") || normalized.contains("concluido") || normalized.contains("completed") || normalized.contains("finished") -> SManga.COMPLETED
            normalized.contains("pausa") || normalized.contains("pausado") || normalized.contains("espera") || normalized.contains("hiatus") -> SManga.ON_HIATUS
            normalized.contains("cancelado") || normalized.contains("dropeado") || normalized.contains("abandonado") || normalized.contains("cancelled") || normalized.contains("dropped") -> SManga.CANCELLED
            normalized.contains("licenciado") || normalized.contains("licensed") -> SManga.LICENSED
            else -> SManga.UNKNOWN
        }
    }

    private fun isValidGenre(tag: String): Boolean {
        if (tag.isBlank()) return false
        if (SCORE_OR_NUMBER_REGEX.matches(tag)) return false
        if (CHAPTER_COUNT_REGEX.matches(tag)) return false
        if (parseStatus(tag) != SManga.UNKNOWN) return false
        if (NOVEL_TEXT_REGEX.matches(tag)) return false
        return true
    }

    private fun cleanChapterName(raw: String, href: String): String {
        var cleaned = raw
            .replace(RELATIVE_DATE_TAIL_REGEX, "")
            .replace(NEW_BADGE_TAIL_REGEX, "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (ONLY_CHAPTER_WORD_REGEX.matches(cleaned)) {
            val numberFromUrl = CHAPTER_NUMBER_IN_URL_REGEX.find(href)?.groupValues?.getOrNull(1)
            if (!numberFromUrl.isNullOrBlank()) {
                cleaned = "$cleaned $numberFromUrl"
            }
        }
        return cleaned
    }

    private fun extractRelativeDateFragment(raw: String): String {
        return RELATIVE_DATE_TAIL_REGEX.find(raw)?.value?.trim().orEmpty()
    }

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val normalized = normalizeText(raw)
        val now = ZonedDateTime.now(ZoneOffset.UTC)

        if (normalized == "hoy" || normalized == "today" || normalized.contains("recien") || normalized.contains("ahora")) {
            return now.toInstant().toEpochMilli()
        }
        if (normalized == "ayer" || normalized == "yesterday") {
            return now.minusDays(1).toInstant().toEpochMilli()
        }

        val relMatch = RELATIVE_AMOUNT_REGEX.find(normalized)
        if (relMatch != null) {
            val amount = relMatch.groupValues[1].toLongOrNull() ?: 0L
            val unit = relMatch.groupValues[2]
            val instant = when {
                unit.startsWith("seg") || unit.startsWith("sec") -> now.minus(amount, ChronoUnit.SECONDS)
                unit.startsWith("min") -> now.minus(amount, ChronoUnit.MINUTES)
                unit.startsWith("hor") || unit.startsWith("hour") -> now.minus(amount, ChronoUnit.HOURS)
                unit.startsWith("dia") || unit.startsWith("day") -> now.minus(amount, ChronoUnit.DAYS)
                unit.startsWith("sem") || unit.startsWith("week") -> now.minusWeeks(amount)
                unit.startsWith("mes") || unit.startsWith("month") -> now.minusMonths(amount)
                unit.startsWith("an") || unit.startsWith("year") -> now.minusYears(amount)
                else -> null
            }
            if (instant != null) {
                return instant.toInstant().toEpochMilli()
            }
        }

        runCatching { return Instant.parse(raw).toEpochMilli() }

        for (formatter in DATE_FORMATTERS) {
            val parsed = formatter.tryParseDate(raw.trim())
            if (parsed != null && parsed > 0L) {
                return parsed
            }
        }

        return 0L
    }

    private fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "").lowercase(Locale.ROOT).trim()
    }

    private fun resolveUrl(pathOrUrl: String): String {
        if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            return pathOrUrl
        }
        return baseUrl.toHttpUrl().resolve(pathOrUrl)?.toString() ?: "$baseUrl/${pathOrUrl.trimStart('/')}"
    }

    companion object {
        private const val CATALOG_CARD_SELECTOR =
            ".manga-card, .series-card, .bsx, .page-item-detail, article.manga, .catalog-grid > div:has(a[href])"

        private const val NEXT_PAGE_SELECTOR =
            "a.next, .pagination a[rel=next], .nav-previous a, a[aria-label*=Siguiente], a:contains(Siguiente), a:contains(›)"

        private const val CHAPTER_LIST_SELECTOR =
            ".chapter-list li, .chapters-list a, ul.main li.wp-manga-chapter, #chapterlist li, .eplister li, .chapter-item"

        private const val READER_IMG_SELECTOR =
            ".reading-content img, #readerarea img, .reader-area img, .chapter-content img, .page-break img"

        private val CTA_PREFIXES = listOf("leer ", "empezar", "continuar", "primer ", "ultimo ", "último ")
        private val INVALID_IMAGE_TOKENS = listOf("avatar", "logo", "banner", "icon", "loading", "placeholder", "blank.gif")
        private val COVER_CLASS_TOKENS = listOf("cover", "portada", "thumb", "avatar")

        private val NOVEL_URL_REGEX = Regex("""/(?:novela|novel|light-novel)/""", RegexOption.IGNORE_CASE)
        private val NOVEL_TEXT_REGEX = Regex("""\b(?:novela|novel|light\s+novel|web\s+novel)\b""", RegexOption.IGNORE_CASE)
        private val TRAILING_CHAPTER_NOISE_REGEX = Regex("""\s+(?:Cap\.?|Capítulo)\s*\d+.*$""", RegexOption.IGNORE_CASE)
        private val SCORE_OR_NUMBER_REGEX = Regex("""^\d+(?:[.,]\d+)?(?:/\d+)?$""")
        private val CHAPTER_COUNT_REGEX = Regex("""^(?:Cap\.?|Capítulo)\s*\d+$""", RegexOption.IGNORE_CASE)

        private val RELATIVE_DATE_TAIL_REGEX = Regex("""\s+hace\s+\d+.*$""", RegexOption.IGNORE_CASE)
        private val NEW_BADGE_TAIL_REGEX = Regex("""\s+(?:Nuevo|New)(?:\s+\d+)?\s*$""", RegexOption.IGNORE_CASE)
        private val ONLY_CHAPTER_WORD_REGEX = Regex("""^(?:Cap\.?|Capítulo|Chapter)$""", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_IN_URL_REGEX = Regex("""(?:cap|capitulo|chapter)[/-]?(\d+(?:[.-]\d+)?)""", RegexOption.IGNORE_CASE)
        private val RELATIVE_AMOUNT_REGEX = Regex("""(?:hace\s+)?(\d+)\s+(segundos?|seg|seconds?|sec|minutos?|min|minutes?|horas?|hours?|dias?|days?|semanas?|weeks?|meses|mes|months?|anos?|ano|years?)""", RegexOption.IGNORE_CASE)
        private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")

        private val SCRIPT_IMAGES_ARRAY_REGEX = Regex("""(?:images|pages|chapterImages)\s*[:=]\s*\[(.*?)]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val URL_IN_QUOTES_REGEX = Regex("""["'](https?:\\?/\\?/[^"']+|/[^"']+\.(?:jpg|jpeg|png|webp|avif))["']""", RegexOption.IGNORE_CASE)

        private val SPANISH_LOCALE: Locale = Locale.forLanguageTag("es")
        private val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("yyyy-MM-dd", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", SPANISH_LOCALE),
            DateTimeFormatter.ofPattern("d MMM yyyy", SPANISH_LOCALE),
        )
    }
}
