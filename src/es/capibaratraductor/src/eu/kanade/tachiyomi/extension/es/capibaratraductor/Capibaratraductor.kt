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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Capibaratraductor : KeiSource() {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val url = buildPagedUrl(baseUrl, page)
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = extractMangaEntries(document, POPULAR_KEYS)
            .map { it.toSManga() }
            .distinctBy { it.url }
        val hasNextPage = hasNextPage(document, page)
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val url = buildPagedUrl(baseUrl, page)
        val document = client.get(url, Fetch.DOCUMENT).asJsoup()
        val mangas = extractMangaEntries(document, LATEST_KEYS)
            .map { it.toSManga() }
            .distinctBy { it.url }
        val hasNextPage = hasNextPage(document, page)
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
        if (query.isNotBlank()) {
            urlBuilder.addQueryParameter("q", query.trim())
        }
        if (page > 1) {
            urlBuilder.addQueryParameter("page", page.toString())
        }

        var sortOption = "default"
        var scanQuery = ""
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sortOption = filter.selectedValue
                is ScanFilter -> scanQuery = filter.state.trim()
                else -> Unit
            }
        }

        val document = client.get(urlBuilder.build().toString(), Fetch.DOCUMENT).asJsoup()
        var entries = extractMangaEntries(document, ALL_SECTION_KEYS)

        val normalizedQuery = normalizeText(query)
        if (normalizedQuery.isNotBlank()) {
            entries = entries.filter { entry ->
                normalizeText(entry.title).contains(normalizedQuery) ||
                    normalizeText(entry.url).contains(normalizedQuery)
            }
        }

        val normalizedScan = normalizeText(scanQuery)
        if (normalizedScan.isNotBlank()) {
            entries = entries.filter { entry ->
                normalizeText(entry.scanName).contains(normalizedScan) ||
                    normalizeText(entry.url).contains(normalizedScan)
            }
        }

        val sorted = when (sortOption) {
            "az" -> entries.sortedBy { it.title.lowercase(Locale.ROOT) }
            "za" -> entries.sortedByDescending { it.title.lowercase(Locale.ROOT) }
            else -> entries
        }

        val mangas = sorted.map { it.toSManga() }.distinctBy { it.url }
        val hasNextPage = hasNextPage(document, page) && normalizedQuery.isBlank() && normalizedScan.isBlank()
        return MangasPage(mangas, hasNextPage)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        if (isNovelUrl(manga.url)) {
            throw Exception("Este título es una novela de texto y no es compatible con lectores de imágenes.")
        }
        if (!fetchDetails && !fetchChapters) {
            return SMangaUpdate(manga, chapters)
        }

        val fullUrl = toAbsoluteUrl(manga.url)
        val document = client.get(fullUrl, Fetch.DOCUMENT).asJsoup()
        val astroObjects = extractAstroMangaObjects(document)
        val primaryDto = astroObjects.firstOrNull { dto ->
            val dtoUrl = dto.mangaUrl ?: dto.url
            dtoUrl != null && manga.url.contains(dtoUrl.trimEnd('/'))
        } ?: astroObjects.firstOrNull()

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(manga, document, primaryDto)
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            parseChapterList(document, primaryDto)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga ?: manga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (isNovelUrl(chapter.url)) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val fullUrl = toAbsoluteUrl(chapter.url)
        val document = client.get(fullUrl, Fetch.DOCUMENT).asJsoup()

        if (isNovelDocument(document)) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val imageUrls = mutableListOf<String>()

        extractAstroPayloads(document).forEach { root ->
            collectImageUrlsFromJson(root, imageUrls)
        }

        if (imageUrls.isEmpty()) {
            extractImageUrlsFromScripts(document, imageUrls)
        }

        if (imageUrls.isEmpty()) {
            val readerContainers = document.select(
                "#reader img, .reader img, [class*=reader] img, [class*=chapter-content] img, [class*=pages] img, main article img, main section img",
            )
            val fallbackImgs = if (readerContainers.isNotEmpty()) {
                readerContainers
            } else {
                document.select("main img")
            }
            fallbackImgs.forEach { img ->
                if (!isExcludedHeaderOrCover(img)) {
                    extractImageUrl(img)?.let { imageUrls.add(it) }
                }
            }
        }

        val cleanedPages = imageUrls
            .map { toAbsoluteUrl(it) }
            .filter { isValidComicPageUrl(it) }
            .distinct()

        if (cleanedPages.isEmpty()) {
            throw Exception("No se encontraron páginas de imágenes para este capítulo o corresponde a una novela de texto.")
        }

        return cleanedPages.mapIndexed { index, url ->
            Page(index, imageUrl = url)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val path = url.encodedPath
        if (isNovelUrl(path) || !path.contains("/manga/")) {
            return null
        }
        return SManga.create().apply {
            setUrlWithoutDomain(url.toString())
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        ScanFilter(),
    )

    private fun parseMangaDetails(base: SManga, document: Document, dto: MangaDto?): SManga {
        val result = SManga.create().apply {
            url = base.url
            title = base.title
            thumbnail_url = base.thumbnail_url
        }

        val rawTitle = dto?.title?.takeIf { it.isNotBlank() }
            ?: dto?.name?.takeIf { it.isNotBlank() }
            ?: extractCleanHeadingTitle(document)
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
            ?: base.title
        result.title = cleanTitle(rawTitle)

        val cover = dto?.cover ?: dto?.thumbnail ?: dto?.image ?: dto?.poster
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("main img[src*=mangas], main img[alt*=${result.title.take(10)}], main img")?.let { extractImageUrl(it) }
        if (!cover.isNullOrBlank()) {
            result.thumbnail_url = toAbsoluteUrl(cover)
        }

        val rawDescription = dto?.description ?: dto?.synopsis ?: dto?.sinopsis ?: dto?.summary
            ?: document.selectFirst("[class*=sinopsis], [class*=synopsis], [class*=description], #sinopsis, #description")?.text()
            ?: document.selectFirst("meta[property=og:description], meta[name=description]")?.attr("content")
        result.description = rawDescription?.trim()?.takeIf { it.isNotBlank() }

        result.author = dto?.author?.takeIf { it.isNotBlank() }
            ?: dto?.scanName?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("[class*=author], [class*=autor]")?.text()?.trim()
        result.artist = dto?.artist?.takeIf { it.isNotBlank() } ?: result.author

        val statusText = dto?.status ?: dto?.estado
            ?: document.select("[class*=status], [class*=estado], span, div.badge")
                .firstOrNull { el -> parseStatus(el.text()) != SManga.UNKNOWN }
                ?.text()
            ?: ""
        result.status = parseStatus(statusText)

        val genreSet = LinkedHashSet<String>()
        if (dto != null) {
            (dto.genres + dto.tags + dto.categories).forEach { el ->
                extractGenreName(el)?.let { genreSet.add(it) }
            }
        }
        document.select("[class*=genre] a, [class*=genre] span, [class*=tag] a, [class*=tag] span, a[href*=genre], a[href*=genero]")
            .forEach { el ->
                genreSet.add(el.text().trim())
            }

        val cleanGenres = genreSet
            .map { it.trim() }
            .filter { isValidGenre(it) }
            .distinct()
        if (cleanGenres.isNotEmpty()) {
            result.genre = cleanGenres.joinToString(", ")
        }

        result.initialized = true
        return result
    }

    private fun parseChapterList(document: Document, dto: MangaDto?): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        if (dto != null && dto.chapters.isNotEmpty()) {
            dto.chapters.forEach { ch ->
                val rawUrl = ch.chapterUrl ?: ch.url ?: ch.slug ?: return@forEach
                if (isNovelUrl(rawUrl)) return@forEach
                val chapterNum = ch.number?.floatOrNull
                val rawTitle = ch.title?.takeIf { it.isNotBlank() }
                    ?: ch.name?.takeIf { it.isNotBlank() }
                    ?: chapterNum?.let { "Capítulo ${formatChapterNumber(it)}" }
                    ?: "Capítulo"
                val dateStr = ch.releasedAt ?: ch.publishedAt ?: ch.createdAt ?: ch.date ?: ""

                chapters.add(
                    SChapter.create().apply {
                        setUrlWithoutDomain(toAbsoluteUrl(rawUrl))
                        name = cleanChapterName(rawTitle, rawUrl)
                        chapter_number = chapterNum ?: extractChapterNumber(name)
                        date_upload = parseDate(dateStr)
                    },
                )
            }
        }

        if (chapters.isEmpty()) {
            val container = document.selectFirst("[class*=chapter-list], [class*=chapters], #chapters, ul:has(a[href*=/chapters/])")
                ?: document
            container.select("a[href*=/chapters/], a[href*=/capitulo/]").forEach { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank() || isNovelUrl(href)) return@forEach

                val fullText = link.text().trim()
                if (isCtaButtonText(fullText)) return@forEach

                val titleEl = link.selectFirst("h3, h4, .chapter-title, [class*=title], strong")
                val rawName = titleEl?.text()?.trim()?.takeIf { it.isNotBlank() } ?: fullText
                val dateEl = link.selectFirst("time, [class*=date], [class*=time]")
                val dateText = dateEl?.attr("datetime")?.takeIf { it.isNotBlank() }
                    ?: dateEl?.text()?.trim()
                    ?: extractRelativeDateText(fullText)

                val cleanedName = cleanChapterName(rawName, href)
                chapters.add(
                    SChapter.create().apply {
                        setUrlWithoutDomain(toAbsoluteUrl(href))
                        name = cleanedName
                        chapter_number = extractChapterNumber(cleanedName)
                        date_upload = parseDate(dateText)
                    },
                )
            }
        }

        return chapters.distinctBy { it.url }
    }

    private fun extractMangaEntries(document: Document, preferredKeys: List<String>): List<MangaEntry> {
        val results = mutableListOf<MangaEntry>()
        val payloads = extractAstroPayloads(document)

        payloads.forEach { root ->
            if (root is JsonObject) {
                preferredKeys.forEach { key ->
                    root[key]?.let { collectMangasFromJson(it, results) }
                }
                root.entries
                    .filter { it.key !in preferredKeys }
                    .forEach { (_, value) -> collectMangasFromJson(value, results) }
            } else {
                collectMangasFromJson(root, results)
            }
        }

        if (results.isEmpty()) {
            document.select("a[href*=/manga/]").forEach { link ->
                val href = link.absUrl("href").ifBlank { link.attr("href") }
                if (href.isBlank() || isNovelUrl(href)) return@forEach
                val card = link.closest("article, li, div[class*=card], div[class*=item]") ?: link
                if (hasNovelBadge(card)) return@forEach

                val title = extractCardTitle(card, link)
                if (title.isBlank()) return@forEach
                val cover = card.selectFirst("img")?.let { extractImageUrl(it) }

                results.add(
                    MangaEntry(
                        url = toRelativeUrl(href),
                        title = title,
                        cover = cover?.let { toAbsoluteUrl(it) },
                        scanName = card.selectFirst("[class*=scan]")?.text().orEmpty(),
                    ),
                )
            }
        }

        return results.filterNot { isNovelUrl(it.url) }.distinctBy { it.url }
    }

    private fun collectMangasFromJson(element: JsonElement, out: MutableList<MangaEntry>) {
        when (element) {
            is JsonArray -> element.forEach { collectMangasFromJson(it, out) }
            is JsonObject -> {
                val dto = runCatching { jsonParser.decodeFromJsonElement<MangaDto>(element) }.getOrNull()
                val rawUrl = dto?.mangaUrl ?: dto?.url
                val rawTitle = dto?.title ?: dto?.name
                if (!rawUrl.isNullOrBlank() && !rawTitle.isNullOrBlank() && rawUrl.contains("/manga/")) {
                    val typeInfo = listOfNotNull(dto.type, dto.format, dto.category, dto.contentType)
                        .joinToString(" ")
                    if (!isNovelUrl(rawUrl) && !isNovelIndicator(typeInfo)) {
                        out.add(
                            MangaEntry(
                                url = toRelativeUrl(rawUrl),
                                title = cleanTitle(rawTitle),
                                cover = (dto.cover ?: dto.thumbnail ?: dto.image ?: dto.poster)?.let { toAbsoluteUrl(it) },
                                scanName = dto.scanName.orEmpty(),
                            ),
                        )
                    }
                } else {
                    element.values.forEach { collectMangasFromJson(it, out) }
                }
            }
            else -> Unit
        }
    }

    private fun extractAstroMangaObjects(document: Document): List<MangaDto> {
        val list = mutableListOf<MangaDto>()
        extractAstroPayloads(document).forEach { root ->
            collectMangaDtos(root, list)
        }
        return list
    }

    private fun collectMangaDtos(element: JsonElement, out: MutableList<MangaDto>) {
        when (element) {
            is JsonArray -> element.forEach { collectMangaDtos(it, out) }
            is JsonObject -> {
                val dto = runCatching { jsonParser.decodeFromJsonElement<MangaDto>(element) }.getOrNull()
                if (dto != null && (!dto.title.isNullOrBlank() || !dto.name.isNullOrBlank() || dto.chapters.isNotEmpty())) {
                    out.add(dto)
                }
                element.values.forEach { collectMangaDtos(it, out) }
            }
            else -> Unit
        }
    }

    private fun extractAstroPayloads(document: Document): List<JsonElement> {
        return document.select("astro-island[props]").mapNotNull { island ->
            val rawProps = island.attr("props").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            runCatching {
                val parsed = jsonParser.parseToJsonElement(rawProps)
                decodeAstroValue(parsed)
            }.getOrNull()
        }
    }

    private fun decodeAstroValue(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> {
            if (element.size == 2 && element[0] is JsonPrimitive && (element[0] as JsonPrimitive).intOrNull != null) {
                val tag = (element[0] as JsonPrimitive).intOrNull
                val value = element[1]
                when (tag) {
                    0 -> decodeAstroObjectOrPrimitive(value)
                    1 -> JsonArray((value as? JsonArray)?.map(::decodeAstroValue).orEmpty())
                    else -> decodeAstroObjectOrPrimitive(value)
                }
            } else {
                JsonArray(element.map(::decodeAstroValue))
            }
        }
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> decodeAstroValue(v) })
        else -> element
    }

    private fun decodeAstroObjectOrPrimitive(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, v) -> decodeAstroValue(v) })
        is JsonArray -> JsonArray(element.map(::decodeAstroValue))
        else -> element
    }

    private fun collectImageUrlsFromJson(element: JsonElement, out: MutableList<String>) {
        when (element) {
            is JsonArray -> element.forEach { collectImageUrlsFromJson(it, out) }
            is JsonObject -> {
                val pageKeys = listOf("pages", "images", "panels", "imagenes", "chapterPages")
                pageKeys.forEach { key ->
                    element[key]?.let { collectRawImageStrings(it, out) }
                }
                element.entries
                    .filter { it.key !in pageKeys }
                    .forEach { (_, v) -> collectImageUrlsFromJson(v, out) }
            }
            else -> Unit
        }
    }

    private fun collectRawImageStrings(element: JsonElement, out: MutableList<String>) {
        when (element) {
            is JsonPrimitive -> {
                val str = element.contentOrNull?.trim().orEmpty()
                if (isValidComicPageUrl(str) && !isCoverUrl(str)) {
                    out.add(str)
                }
            }
            is JsonArray -> element.forEach { collectRawImageStrings(it, out) }
            is JsonObject -> {
                val urlVal = (element["url"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["src"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["image"] as? JsonPrimitive)?.contentOrNull
                    ?: (element["imageUrl"] as? JsonPrimitive)?.contentOrNull
                if (!urlVal.isNullOrBlank() && isValidComicPageUrl(urlVal) && !isCoverUrl(urlVal)) {
                    out.add(urlVal)
                } else {
                    element.values.forEach { collectRawImageStrings(it, out) }
                }
            }
        }
    }

    private fun extractImageUrlsFromScripts(document: Document, out: MutableList<String>) {
        val urlRegex = Regex("""https?://[^\s"'<>]+\.(?:webp|jpe?g|png|avif)(?:\?[^\s"'<>]*)?""", RegexOption.IGNORE_CASE)
        document.select("script").forEach { script ->
            val data = script.data()
            if (data.contains("chapters") || data.contains("pages") || data.contains("images") || data.contains("r2.capibaratraductor.com")) {
                urlRegex.findAll(data).forEach { match ->
                    val candidate = match.value
                    if (isValidComicPageUrl(candidate) && !isCoverUrl(candidate)) {
                        out.add(candidate)
                    }
                }
            }
        }
    }

    private fun extractImageUrl(img: Element): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-cfsrc", "data-echo", "srcset", "src")
        for (attr in attrs) {
            val raw = img.absUrl(attr).ifBlank { img.attr(attr).trim() }
            if (raw.isBlank()) continue
            val candidate = if (attr == "srcset") {
                raw.split(",").firstOrNull()?.trim()?.split(Regex("""\s+"""))?.firstOrNull().orEmpty()
            } else {
                raw
            }
            if (candidate.isNotBlank() && !candidate.startsWith("data:image/") && !isPlaceholderUrl(candidate)) {
                return candidate
            }
        }
        return null
    }

    private fun extractCleanHeadingTitle(document: Document): String? {
        val heading = document.selectFirst("main h1, h1") ?: return null
        val cloned = heading.clone()
        cloned.select("span.badge, .tag, small").remove()
        return cloned.text().trim().takeIf { it.isNotBlank() }
    }

    private fun extractCardTitle(card: Element, link: Element): String {
        val titleEl = card.selectFirst("h2, h3, h4, .title, [class*=title]")
        if (titleEl != null) {
            val cloned = titleEl.clone()
            cloned.select("span.badge, .tag, small").remove()
            val text = cloned.text().trim()
            if (text.isNotBlank()) return cleanTitle(text)
        }
        val attrTitle = link.attr("title").trim().ifBlank { card.selectFirst("img")?.attr("alt")?.trim().orEmpty() }
        if (attrTitle.isNotBlank()) return cleanTitle(attrTitle)
        val clonedLink = link.clone()
        clonedLink.select("span.badge, .tag, small").remove()
        return cleanTitle(clonedLink.text())
    }

    private fun cleanTitle(raw: String): String = raw
        .replace(Regex("""\s+"""), " ")
        .trim()

    private fun cleanChapterName(raw: String, url: String): String {
        var cleaned = raw
            .replace(Regex("""\s+hace\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:Nuevo|New)(?:\s+\d+)?\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

        if (!Regex("""\d""").containsMatchIn(cleaned)) {
            val urlNum = Regex("""/chapters?/(\d+(?:[._-]\d+)?)""", RegexOption.IGNORE_CASE)
                .find(url)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace('_', '.')
                ?.replace('-', '.')
            if (!urlNum.isNullOrBlank()) {
                cleaned = if (cleaned.isBlank() || cleaned.equals("Capítulo", ignoreCase = true)) {
                    "Capítulo $urlNum"
                } else {
                    "$cleaned $urlNum"
                }
            }
        }
        return cleaned.ifBlank { "Capítulo" }
    }

    private fun extractChapterNumber(name: String): Float {
        val match = Regex("""(\d+(?:[.,]\d+)?)""").find(name)
        return match?.groupValues?.getOrNull(1)?.replace(',', '.')?.toFloatOrNull() ?: -1f
    }

    private fun formatChapterNumber(number: Float): String {
        return if (number % 1f == 0f) number.toInt().toString() else number.toString()
    }

    private fun parseStatus(raw: String): Int {
        val norm = normalizeText(raw)
        return when {
            norm.contains("emision") || norm.contains("publicandose") || norm.contains("en curso") ||
                norm.contains("activo") || norm.contains("ongoing") || norm.contains("publishing") -> SManga.ONGOING
            norm.contains("finalizado") || norm.contains("completado") || norm.contains("terminado") ||
                norm.contains("concluido") || norm.contains("completed") || norm.contains("finished") -> SManga.COMPLETED
            norm.contains("pausa") || norm.contains("pausado") || norm.contains("espera") ||
                norm.contains("hiatus") -> SManga.ON_HIATUS
            norm.contains("cancelado") || norm.contains("dropeado") || norm.contains("abandonado") ||
                norm.contains("cancelled") || norm.contains("canceled") || norm.contains("dropped") -> SManga.CANCELLED
            norm.contains("licenciado") || norm.contains("licensed") -> SManga.LICENSED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 0L

        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        runCatching { return OffsetDateTime.parse(trimmed).toInstant().toEpochMilli() }

        val norm = normalizeText(trimmed)
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        if (norm == "hoy" || norm == "today" || norm.contains("justo ahora") || norm.contains("just now")) {
            return now.toInstant().toEpochMilli()
        }
        if (norm == "ayer" || norm == "yesterday") {
            return now.minus(1, ChronoUnit.DAYS).toInstant().toEpochMilli()
        }

        val relMatch = Regex("""(?:hace\s+)?(\d+)\s+(segundo|minuto|hora|dia|semana|mes|ano|second|minute|hour|day|week|month|year)e?s?""")
            .find(norm)
        if (relMatch != null) {
            val amount = relMatch.groupValues[1].toLongOrNull() ?: 0L
            val unit = relMatch.groupValues[2]
            val calculated = when {
                unit.startsWith("segundo") || unit.startsWith("second") -> now.minus(amount, ChronoUnit.SECONDS)
                unit.startsWith("minuto") || unit.startsWith("minute") -> now.minus(amount, ChronoUnit.MINUTES)
                unit.startsWith("hora") || unit.startsWith("hour") -> now.minus(amount, ChronoUnit.HOURS)
                unit.startsWith("dia") || unit.startsWith("day") -> now.minus(amount, ChronoUnit.DAYS)
                unit.startsWith("semana") || unit.startsWith("week") -> now.minus(amount * 7, ChronoUnit.DAYS)
                unit.startsWith("mes") || unit.startsWith("month") -> now.minus(amount, ChronoUnit.MONTHS)
                unit.startsWith("ano") || unit.startsWith("year") -> now.minus(amount, ChronoUnit.YEARS)
                else -> null
            }
            if (calculated != null) return calculated.toInstant().toEpochMilli()
        }

        return DATE_FORMATTERS.firstNotNullOfOrNull { formatter ->
            formatter.tryParseDate(trimmed).takeIf { it > 0L }
        } ?: 0L
    }

    private fun extractRelativeDateText(text: String): String {
        return Regex("""(hace\s+\d+\s+\S+|ayer|hoy)""", RegexOption.IGNORE_CASE)
            .find(text)?.value.orEmpty()
    }

    private fun isCtaButtonText(text: String): Boolean {
        val norm = normalizeText(text)
        return norm.startsWith("leer ") || norm.startsWith("empezar") ||
            norm.startsWith("continuar") || norm.startsWith("primer ") ||
            norm.startsWith("ultimo ")
    }

    private fun extractGenreName(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull?.trim()
        is JsonObject -> (element["name"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: (element["title"] as? JsonPrimitive)?.contentOrNull?.trim()
            ?: (element["label"] as? JsonPrimitive)?.contentOrNull?.trim()
        else -> null
    }

    private fun isValidGenre(genre: String): Boolean {
        if (genre.isBlank()) return false
        if (Regex("""^\d+(?:[.,]\d+)?/\d+$""").matches(genre)) return false
        if (Regex("""^\d+$""").matches(genre)) return false
        if (Regex("""^cap\.?\s*\d+$""", RegexOption.IGNORE_CASE).matches(genre)) return false
        if (parseStatus(genre) != SManga.UNKNOWN) return false
        if (isNovelIndicator(genre)) return false
        return true
    }

    private fun isNovelUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("/writings/") ||
            lower.contains("/light-novel/") ||
            lower.contains("/web-novel/") ||
            lower.contains("/novela/") ||
            lower.contains("/novel/")
    }

    private fun isNovelIndicator(text: String): Boolean {
        val norm = normalizeText(text)
        return norm == "novela" || norm == "novel" || norm == "light novel" || norm == "web novel" ||
            norm.contains("light-novel") || norm.contains("web-novel")
    }

    private fun hasNovelBadge(card: Element): Boolean {
        return card.select("span, .badge, .tag, div[class*=badge]").any { isNovelIndicator(it.text()) }
    }

    private fun isNovelDocument(document: Document): Boolean {
        val hasNovelBadge = document.select("main span.badge, main [class*=badge], header [class*=badge]")
            .any { isNovelIndicator(it.text()) }
        val hasNovelContainer = document.selectFirst("[class*=novel-reader], [class*=prose].novel-content") != null
        return hasNovelBadge || hasNovelContainer
    }

    private fun isExcludedHeaderOrCover(img: Element): Boolean {
        if (img.closest("header, nav, footer") != null) return true
        val cls = img.className().lowercase(Locale.ROOT)
        val alt = img.attr("alt").lowercase(Locale.ROOT)
        val src = img.attr("src").lowercase(Locale.ROOT)
        return cls.contains("cover") || cls.contains("portada") || cls.contains("avatar") ||
            cls.contains("logo") || alt.contains("portada") || alt.contains("logo") ||
            isCoverUrl(src)
    }

    private fun isCoverUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("portada-") || lower.contains("/cover") || lower.contains("/avatar") || lower.contains("/logo")
    }

    private fun isPlaceholderUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.endsWith(".svg") || lower.contains("blank.gif") || lower.contains("placeholder") ||
            lower.contains("loading") || lower.contains("avatar") || lower.contains("logo") ||
            lower.contains("icon") || lower.contains("banner")
    }

    private fun isValidComicPageUrl(url: String): Boolean {
        if (url.isBlank() || url.startsWith("data:image/")) return false
        if (isPlaceholderUrl(url)) return false
        val lower = url.lowercase(Locale.ROOT)
        return lower.startsWith("http") && (
            lower.contains(".webp") || lower.contains(".jpg") || lower.contains(".jpeg") ||
                lower.contains(".png") || lower.contains(".avif") || lower.contains("r2.capibaratraductor.com")
            )
    }

    private fun hasNextPage(document: Document, currentPage: Int): Boolean {
        val nextLink = document.selectFirst("a[rel=next], a[href*=page=${currentPage + 1}]")
        return nextLink != null
    }

    private fun buildPagedUrl(url: String, page: Int): String {
        if (page <= 1) return url
        return url.toHttpUrl().newBuilder().addQueryParameter("page", page.toString()).build().toString()
    }

    private fun toAbsoluteUrl(pathOrUrl: String): String {
        return when {
            pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://") -> pathOrUrl
            pathOrUrl.startsWith("//") -> "https:$pathOrUrl"
            else -> baseUrl.trimEnd('/') + "/" + pathOrUrl.trimStart('/')
        }
    }

    private fun toRelativeUrl(pathOrUrl: String): String {
        return if (pathOrUrl.startsWith("http://") || pathOrUrl.startsWith("https://")) {
            "/" + pathOrUrl.toHttpUrl().encodedPath.trimStart('/')
        } else {
            "/" + pathOrUrl.trimStart('/')
        }
    }

    private fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input, Normalizer.Form.NFD)
        return Regex("""\p{InCombiningDiacriticalMarks}+""")
            .replace(decomposed, "")
            .lowercase(Locale.ROOT)
            .trim()
    }

    private class MangaEntry(
        val url: String,
        val title: String,
        val cover: String?,
        val scanName: String,
    ) {
        fun toSManga(): SManga = SManga.create().apply {
            url = this@MangaEntry.url
            title = this@MangaEntry.title
            thumbnail_url = this@MangaEntry.cover
        }
    }

    companion object {
        private val POPULAR_KEYS = listOf("popular", "trending", "destacados", "top", "heroInicial", "mangas")
        private val LATEST_KEYS = listOf("ultimos", "ultimosCapitulos", "latest", "recent", "recientes", "updates", "heroInicial")
        private val ALL_SECTION_KEYS = (POPULAR_KEYS + LATEST_KEYS).distinct()

        private val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy/MM/dd", Locale.ROOT),
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale("es", "ES")),
            DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH),
        )
    }
}
