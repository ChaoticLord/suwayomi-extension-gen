package eu.kanade.tachiyomi.extension.es.capibaratraductor

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
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Capibaratraductor : KeiSource() {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val doc = fetchPageDocument(page)
        return parseCatalogPage(doc, page, preferLatest = false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val doc = fetchPageDocument(page)
        return parseCatalogPage(doc, page, preferLatest = true)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val trimmedQuery = query.trim()
        var sortMode = "popular"
        var scanQuery = ""
        var statusFilter = ""
        var langFilter = ""

        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> sortMode = filter.selectedValue
                is ScanFilter -> scanQuery = filter.state.trim()
                is StatusFilter -> statusFilter = filter.selectedValue
                is LanguageFilter -> langFilter = filter.selectedValue
                else -> Unit
            }
        }

        val candidateUrls = buildList {
            if (trimmedQuery.isNotBlank()) {
                add(baseUrl.toHttpUrl().newBuilder().addPathSegment("buscar").addQueryParameter("q", trimmedQuery).addQueryParameter("page", page.toString()).build().toString())
                add(baseUrl.toHttpUrl().newBuilder().addPathSegment("search").addQueryParameter("q", trimmedQuery).addQueryParameter("page", page.toString()).build().toString())
            }
            add(buildPageUrl(page))
        }

        var doc: Document? = null
        for (candidate in candidateUrls) {
            val fetched = runCatching {
                client.get(candidate, Fetch.DOCUMENT).asJsoup()
            }.getOrNull()
            if (fetched != null && (extractMangaItemsFromDocument(fetched, sortMode == "latest").isNotEmpty() || candidate == candidateUrls.last())) {
                doc = fetched
                break
            }
        }

        val targetDoc = doc ?: return MangasPage(emptyList(), false)
        val rawItems = extractMangaItemsFromDocument(targetDoc, preferLatest = sortMode == "latest")
        val hasNextPage = hasNextPagination(targetDoc, page) && trimmedQuery.isBlank() && scanQuery.isBlank()

        val normalizedQuery = normalizeText(trimmedQuery)
        val normalizedScan = normalizeText(scanQuery)

        val filtered = rawItems
            .filterNot { isNovelItem(it) }
            .filter { item ->
                val matchesQuery = normalizedQuery.isBlank() ||
                    normalizeText(item.manga.title).contains(normalizedQuery) ||
                    normalizeText(item.manga.author.orEmpty()).contains(normalizedQuery) ||
                    normalizeText(item.manga.url).contains(normalizedQuery)
                val matchesScan = normalizedScan.isBlank() ||
                    normalizeText(item.scanSlug).contains(normalizedScan) ||
                    normalizeText(item.manga.author.orEmpty()).contains(normalizedScan)
                val matchesStatus = statusFilter.isBlank() || when (statusFilter) {
                    "ongoing" -> item.manga.status == SManga.ONGOING
                    "completed" -> item.manga.status == SManga.COMPLETED
                    "hiatus" -> item.manga.status == SManga.ON_HIATUS
                    "cancelled" -> item.manga.status == SManga.CANCELLED
                    else -> true
                }
                val matchesLang = langFilter.isBlank() || item.language.equals(langFilter, ignoreCase = true)
                matchesQuery && matchesScan && matchesStatus && matchesLang
            }

        val sorted = when (sortMode) {
            "latest" -> filtered.sortedByDescending { it.latestTimestamp }
            "az" -> filtered.sortedBy { normalizeText(it.manga.title) }
            else -> filtered
        }

        return MangasPage(sorted.map { it.manga }.distinctBy { it.url }, hasNextPage)
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

        if (isNovelUrl(manga.url)) {
            throw Exception("Esta obra es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val fullUrl = toAbsoluteUrl(manga.url)
        val doc = client.get(fullUrl, Fetch.DOCUMENT).asJsoup()

        if (isNovelDocument(doc)) {
            throw Exception("Esta obra es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val islands = extractUnwrappedAstroIslands(doc)
        val updatedManga = if (fetchDetails) {
            parseMangaDetails(doc, islands, manga)
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) {
            parseChapterList(doc, islands)
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga ?: manga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        if (isNovelUrl(chapter.url)) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val firstUrl = toAbsoluteUrl(chapter.url)
        val firstDoc = client.get(firstUrl, Fetch.DOCUMENT).asJsoup()

        if (isNovelDocument(firstDoc)) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }

        val islands = extractUnwrappedAstroIslands(firstDoc)
        val extractedPages = extractPagesFromAstroIslands(islands)
            .ifEmpty { extractPagesFromScripts(firstDoc) }
            .ifEmpty { extractPagesFromHtml(firstDoc) }
            .distinct()
            .toMutableList()

        val totalPages = detectTotalReaderPages(firstDoc, islands)
        if (extractedPages.size <= 1 && totalPages > 1) {
            val baseHttpUrl = firstUrl.toHttpUrl()
            for (pageIndex in 2..totalPages) {
                val pageUrl = baseHttpUrl.newBuilder()
                    .setQueryParameter("page", pageIndex.toString())
                    .build()
                    .toString()
                val nextDoc = client.get(pageUrl, Fetch.DOCUMENT).asJsoup()
                val nextIslands = extractUnwrappedAstroIslands(nextDoc)
                val nextUrls = extractPagesFromAstroIslands(nextIslands)
                    .ifEmpty { extractPagesFromScripts(nextDoc) }
                    .ifEmpty { extractPagesFromHtml(nextDoc) }
                nextUrls.forEach { url ->
                    if (url !in extractedPages) {
                        extractedPages.add(url)
                    }
                }
            }
        }

        if (extractedPages.isEmpty()) {
            if (looksLikeTextProse(firstDoc)) {
                throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
            }
            throw Exception("No se encontraron páginas de imágenes para este capítulo.")
        }

        return extractedPages.mapIndexed { index, imageUrl ->
            Page(index, imageUrl = imageUrl)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val rawPath = url.encodedPath
        if (isNovelUrl(rawPath)) return null

        val mangaPath = rawPath.replace(CHAPTER_PATH_SUFFIX_REGEX, "")
        if (!mangaPath.contains("/manga/") && mangaPath.trim('/').split('/').size < 2) {
            return null
        }

        val stub = SManga.create().apply {
            setUrlWithoutDomain(toAbsoluteUrl(mangaPath))
        }

        return runCatching {
            fetchMangaUpdate(stub, emptyList(), fetchDetails = true, fetchChapters = false).manga
        }.getOrNull()
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        Filter.Header("Filtros del catálogo de CapibaraTraductor"),
        Filter.Separator(),
        SortFilter(),
        ScanFilter(),
        StatusFilter(),
        LanguageFilter(),
    )

    private suspend fun fetchPageDocument(page: Int): Document =
        client.get(buildPageUrl(page), Fetch.DOCUMENT).asJsoup()

    private fun buildPageUrl(page: Int): String =
        if (page <= 1) {
            baseUrl
        } else {
            baseUrl.toHttpUrl().newBuilder()
                .addQueryParameter("page", page.toString())
                .build()
                .toString()
        }

    private fun parseCatalogPage(doc: Document, page: Int, preferLatest: Boolean): MangasPage {
        val rawItems = extractMangaItemsFromDocument(doc, preferLatest)
        val hasNextPage = hasNextPagination(doc, page)
        val filtered = rawItems
            .filterNot { isNovelItem(it) }
            .let { list ->
                if (preferLatest) {
                    list.sortedByDescending { it.latestTimestamp }
                } else {
                    list
                }
            }
            .map { it.manga }
            .distinctBy { it.url }

        return MangasPage(filtered, hasNextPage)
    }

    private fun hasNextPagination(doc: Document, currentPage: Int): Boolean {
        if (doc.selectFirst("a[rel=next], a[aria-label*=siguiente i], button[aria-label*=siguiente i]") != null) {
            return true
        }
        val nextPageToken = "page=${currentPage + 1}"
        if (doc.selectFirst("a[href*=$nextPageToken]") != null) {
            return true
        }
        val islands = extractUnwrappedAstroIslands(doc)
        var hasMore = false
        islands.forEach { root ->
            traverseJson(root) { obj ->
                val nextFlag = obj["hasMore"]?.asBooleanOrNull() ?: obj["hasNextPage"]?.asBooleanOrNull()
                if (nextFlag == true) hasMore = true
                val totalPages = obj["totalPages"]?.asIntOrNull() ?: obj["lastPage"]?.asIntOrNull()
                if (totalPages != null && totalPages > currentPage) hasMore = true
            }
        }
        return hasMore
    }

    private fun extractMangaItemsFromDocument(doc: Document, preferLatest: Boolean): List<ParsedMangaItem> {
        val islands = extractUnwrappedAstroIslands(doc)
        val items = mutableListOf<ParsedMangaItem>()

        val priorityKeys = if (preferLatest) {
            listOf("latest", "recent", "updates", "recents", "newChapters", "heroInicial", "popular", "featured", "mangas", "items", "data")
        } else {
            listOf("popular", "trending", "featured", "heroInicial", "top", "mangas", "items", "latest", "recent", "data")
        }

        islands.forEach { island ->
            val rootObj = island as? JsonObject ?: return@forEach
            val orderedKeys = priorityKeys.filter { rootObj.containsKey(it) } + rootObj.keys.filterNot { it in priorityKeys }
            orderedKeys.forEach { key ->
                val subtree = rootObj[key] ?: return@forEach
                collectMangaDtos(subtree).forEach { dto ->
                    dtoToParsedItem(dto)?.let { items.add(it) }
                }
            }
        }

        if (items.isEmpty()) {
            extractMangaItemsFromHtml(doc).forEach { items.add(it) }
        }

        return items.distinctBy { it.manga.url }
    }

    private fun collectMangaDtos(element: JsonElement): List<CapibaraMangaDto> {
        val results = mutableListOf<CapibaraMangaDto>()
        traverseJson(element) { obj ->
            val hasTitle = obj["title"]?.asStringOrNull()?.isNotBlank() == true ||
                obj["name"]?.asStringOrNull()?.isNotBlank() == true
            val hasMangaLink = obj["mangaUrl"]?.asStringOrNull()?.isNotBlank() == true ||
                obj["mangaSlug"]?.asStringOrNull()?.isNotBlank() == true ||
                obj["url"]?.asStringOrNull()?.contains("/manga/") == true
            if (hasTitle && hasMangaLink) {
                runCatching { json.decodeFromJsonElement<CapibaraMangaDto>(obj) }
                    .getOrNull()
                    ?.let { results.add(it) }
            }
        }
        return results
    }

    private fun dtoToParsedItem(dto: CapibaraMangaDto): ParsedMangaItem? {
        val title = cleanMangaTitle(dto.title ?: dto.name ?: return null)
        if (title.isBlank()) return null

        val rawUrl = dto.mangaUrl
            ?: dto.url
            ?: buildMangaUrlFromSlugs(dto.scanSlug, dto.mangaSlug ?: dto.slug)
            ?: return null

        val manga = SManga.create().apply {
            this.title = title
            setUrlWithoutDomain(toAbsoluteUrl(rawUrl))
            thumbnail_url = (dto.cover ?: dto.coverUrl ?: dto.image ?: dto.thumbnail)?.let { toAbsoluteUrl(it) }
            author = dto.scanName ?: extractPersonString(dto.author)
            artist = extractPersonString(dto.artist)
            description = dto.synopsis ?: dto.description ?: dto.summary
            status = parseStatus(dto.status?.asStringOrNull() ?: dto.state?.asStringOrNull())
            genre = extractGenresFromDto(dto).takeIf { it.isNotEmpty() }?.joinToString(", ")
        }

        val latestTs = dto.chapters.maxOfOrNull {
            parseDate(it.releasedAt ?: it.createdAt ?: it.publishedAt ?: "")
        } ?: 0L

        val typeHint = listOfNotNull(dto.type, dto.format, dto.category, dto.genreOrTagHint()).joinToString(" ")

        return ParsedMangaItem(
            manga = manga,
            scanSlug = dto.scanSlug.orEmpty(),
            language = dto.language.orEmpty(),
            typeHint = typeHint,
            latestTimestamp = latestTs,
        )
    }

    private fun extractMangaItemsFromHtml(doc: Document): List<ParsedMangaItem> {
        val results = mutableListOf<ParsedMangaItem>()
        val cards = doc.select("article, .manga-card, .card, a[href*=/manga/], a[href*=/writings/]")
        cards.forEach { card ->
            val link = if (card.tagName() == "a") card else card.selectFirst("a[href*=/manga/], a[href*=/writings/]") ?: return@forEach
            val href = link.absUrl("href").ifBlank { return@forEach }
            val title = extractCleanTitleFromCard(card, link)
            if (title.isBlank()) return@forEach

            val badgeText = card.select(".badge, .tag, span, small").text()
            val thumb = card.selectFirst("img")?.let { extractImageUrl(it) }

            val manga = SManga.create().apply {
                this.title = title
                setUrlWithoutDomain(href)
                thumbnail_url = thumb
            }
            results.add(
                ParsedMangaItem(
                    manga = manga,
                    scanSlug = "",
                    language = "",
                    typeHint = badgeText,
                    latestTimestamp = 0L,
                ),
            )
        }
        return results
    }

    private fun parseMangaDetails(doc: Document, islands: List<JsonObject>, fallback: SManga): SManga {
        var bestDto: CapibaraMangaDto? = null
        islands.forEach { root ->
            traverseJson(root) { obj ->
                val hasTitle = obj["title"]?.asStringOrNull()?.isNotBlank() == true ||
                    obj["name"]?.asStringOrNull()?.isNotBlank() == true
                val hasDetailFields = obj.containsKey("chapters") ||
                    obj.containsKey("synopsis") ||
                    obj.containsKey("description") ||
                    obj.containsKey("mangaSlug")
                if (hasTitle && hasDetailFields) {
                    val candidate = runCatching { json.decodeFromJsonElement<CapibaraMangaDto>(obj) }.getOrNull()
                    if (candidate != null && (bestDto == null || candidate.chapters.size >= (bestDto?.chapters?.size ?: 0))) {
                        bestDto = candidate
                    }
                }
            }
        }

        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")
            ?.replace(SITE_TITLE_SUFFIX_REGEX, "")
            ?.trim()
        val h1Title = doc.selectFirst("h1")?.let { cleanElementTextWithoutBadges(it) }
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        val metaDesc = doc.selectFirst("meta[name=description], meta[property=og:description]")?.attr("content")?.trim()

        val dto = bestDto
        val genreSet = linkedSetOf<String>()
        if (dto != null) {
            genreSet.addAll(extractGenresFromDto(dto))
        }
        doc.select(".genre, .genres a, .tags a, a[href*=genre], a[href*=genero], [class*=tag], [class*=genre]").forEach { el ->
            val text = el.text().trim()
            if (isValidGenre(text)) {
                genreSet.add(text)
            }
        }

        val rawStatus = dto?.status?.asStringOrNull()
            ?: dto?.state?.asStringOrNull()
            ?: extractStatusFromDocument(doc)

        return SManga.create().apply {
            url = fallback.url
            title = cleanMangaTitle(dto?.title ?: dto?.name ?: h1Title ?: ogTitle ?: fallback.title)
            thumbnail_url = (dto?.cover ?: dto?.coverUrl ?: dto?.image ?: dto?.thumbnail ?: ogImage ?: fallback.thumbnail_url)?.let {
                toAbsoluteUrl(it)
            }
            author = dto?.scanName ?: extractPersonString(dto?.author) ?: fallback.author
            artist = extractPersonString(dto?.artist) ?: fallback.artist
            description = (dto?.synopsis ?: dto?.description ?: dto?.summary ?: extractDescriptionFromHtml(doc) ?: metaDesc)
                ?.trim()
            status = parseStatus(rawStatus)
            genre = genreSet.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: fallback.genre
            initialized = true
        }
    }

    private fun parseChapterList(doc: Document, islands: List<JsonObject>): List<SChapter> {
        val chapters = mutableListOf<SChapter>()

        islands.forEach { root ->
            traverseJson(root) { obj ->
                val chaptersArray = obj["chapters"] as? JsonArray ?: return@traverseJson
                chaptersArray.forEach { entry ->
                    val chapObj = entry as? JsonObject ?: return@forEach
                    val dto = runCatching { json.decodeFromJsonElement<CapibaraChapterDto>(chapObj) }.getOrNull() ?: return@forEach
                    val rawUrl = dto.chapterUrl ?: dto.url ?: return@forEach
                    if (isNovelUrl(rawUrl)) return@forEach

                    val chapNum = (dto.number ?: dto.chapterNumber)?.toFloat()
                        ?: extractChapterNumber(dto.title ?: dto.name ?: rawUrl)
                    val rawName = (dto.title ?: dto.name)?.takeIf { it.isNotBlank() }
                        ?: if (chapNum >= 0f) "Capítulo ${formatChapterNumber(chapNum)}" else "Capítulo"

                    val chapter = SChapter.create().apply {
                        setUrlWithoutDomain(toAbsoluteUrl(rawUrl))
                        name = cleanChapterName(rawName, chapNum, rawUrl)
                        chapter_number = chapNum
                        date_upload = parseDate(dto.releasedAt ?: dto.createdAt ?: dto.publishedAt ?: "")
                    }
                    chapters.add(chapter)
                }
            }
        }

        if (chapters.isEmpty()) {
            val container = doc.selectFirst(".chapters-list, #chapters, [class*=chapter-list], [class*=chapters]") ?: doc
            container.select("a[href*=/chapters/], a[href*=/capitulo/]").forEach { link ->
                val href = link.absUrl("href").ifBlank { return@forEach }
                if (isNovelUrl(href)) return@forEach

                val rawText = link.text().trim()
                if (isCtaButtonText(rawText)) return@forEach

                val chapNum = extractChapterNumber(rawText.ifBlank { href })
                val dateText = link.selectFirst("time, .date, [class*=date], [class*=time]")?.text()
                    ?: link.parent()?.selectFirst("time, .date, [class*=date]")?.text()
                    ?: rawText

                val chapter = SChapter.create().apply {
                    setUrlWithoutDomain(href)
                    name = cleanChapterName(rawText.ifBlank { "Capítulo" }, chapNum, href)
                    chapter_number = chapNum
                    date_upload = parseDate(dateText)
                }
                chapters.add(chapter)
            }
        }

        return chapters.distinctBy { it.url }
    }

    private fun extractPagesFromAstroIslands(islands: List<JsonObject>): List<String> {
        val pageKeys = listOf("pages", "images", "chapterPages", "imgs", "slides", "panels", "media", "files")
        val collected = mutableListOf<Pair<Int, String>>()

        islands.forEach { root ->
            traverseJson(root) { obj ->
                pageKeys.forEach { key ->
                    val array = obj[key] as? JsonArray ?: return@forEach
                    array.forEachIndexed { idx, elem ->
                        when (elem) {
                            is JsonPrimitive -> {
                                val url = elem.asStringOrNull()
                                if (url != null && isValidPageImageUrl(url)) {
                                    collected.add(idx to toAbsoluteUrl(url))
                                }
                            }
                            is JsonObject -> {
                                val dto = runCatching { json.decodeFromJsonElement<CapibaraPageDto>(elem) }.getOrNull()
                                val rawUrl = dto?.url ?: dto?.imageUrl ?: dto?.src ?: dto?.image ?: dto?.path ?: dto?.file
                                if (rawUrl != null && isValidPageImageUrl(rawUrl)) {
                                    val order = dto.page ?: dto.order ?: dto.number ?: idx
                                    collected.add(order to toAbsoluteUrl(rawUrl))
                                }
                            }
                            else -> Unit
                        }
                    }
                }
            }
        }

        return collected
            .sortedBy { it.first }
            .map { it.second }
    }

    private fun extractPagesFromScripts(doc: Document): List<String> {
        val urls = mutableListOf<String>()
        doc.select("script").forEach { script ->
            val data = script.data()
            if (data.isBlank()) return@forEach
            IMAGE_URL_IN_SCRIPT_REGEX.findAll(data).forEach { match ->
                val candidate = match.groupValues[1].replace("\\/", "/")
                if (isValidPageImageUrl(candidate)) {
                    urls.add(toAbsoluteUrl(candidate))
                }
            }
        }
        return urls
    }

    private fun extractPagesFromHtml(doc: Document): List<String> {
        val container = doc.selectFirst(".reader, #reader, [class*=reader], [class*=chapter-content], main") ?: return emptyList()
        return container.select("img")
            .filter { img -> img.closest("header, nav, .cover, [class*=cover], [class*=thumb]") == null }
            .mapNotNull { extractImageUrl(it) }
            .filter { isValidPageImageUrl(it) }
    }

    private fun detectTotalReaderPages(doc: Document, islands: List<JsonObject>): Int {
        var maxPages = 1
        islands.forEach { root ->
            traverseJson(root) { obj ->
                val total = obj["totalPages"]?.asIntOrNull() ?: obj["pageCount"]?.asIntOrNull()
                if (total != null && total > maxPages) maxPages = total
            }
        }
        doc.select("select option, a[href*=page=]").forEach { el ->
            val num = el.attr("value").toIntOrNull()
                ?: PAGE_PARAM_REGEX.find(el.attr("href"))?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (num != null && num > maxPages) maxPages = num
        }
        return maxPages
    }

    private fun extractUnwrappedAstroIslands(doc: Document): List<JsonObject> {
        val result = mutableListOf<JsonObject>()
        doc.select("astro-island[props]").forEach { island ->
            val rawProps = island.attr("props")
            if (rawProps.isBlank()) return@forEach
            val parsed = runCatching { json.parseToJsonElement(rawProps) }.getOrNull() ?: return@forEach
            val unwrapped = unwrapAstroElement(parsed)
            if (unwrapped is JsonObject) {
                result.add(unwrapped)
            }
        }
        return result
    }

    private fun unwrapAstroElement(element: JsonElement): JsonElement = when (element) {
        is JsonArray -> {
            if (element.size == 2 && element[0] is JsonPrimitive) {
                val typeFlag = (element[0] as JsonPrimitive).intOrNull
                val payload = element[1]
                when (typeFlag) {
                    0 -> unwrapAstroElement(payload)
                    1 -> {
                        val items = (payload as? JsonArray)?.map { unwrapAstroElement(it) } ?: emptyList()
                        JsonArray(items)
                    }
                    else -> unwrapAstroElement(payload)
                }
            } else {
                JsonArray(element.map { unwrapAstroElement(it) })
            }
        }
        is JsonObject -> {
            JsonObject(element.mapValues { (_, value) -> unwrapAstroElement(value) })
        }
        else -> element
    }

    private fun traverseJson(element: JsonElement, onObject: (JsonObject) -> Unit) {
        when (element) {
            is JsonObject -> {
                onObject(element)
                element.values.forEach { traverseJson(it, onObject) }
            }
            is JsonArray -> element.forEach { traverseJson(it, onObject) }
            else -> Unit
        }
    }

    private fun extractCleanTitleFromCard(card: Element, link: Element): String {
        val titleEl = card.selectFirst("h1, h2, h3, h4, .title, [class*=title]")
        if (titleEl != null) {
            val cleaned = cleanElementTextWithoutBadges(titleEl)
            if (cleaned.isNotBlank()) return cleanMangaTitle(cleaned)
        }
        val attrTitle = link.attr("title").takeIf { it.isNotBlank() }
            ?: card.selectFirst("img")?.attr("alt")?.takeIf { it.isNotBlank() && !it.equals("cover", true) }
        if (!attrTitle.isNullOrBlank()) return cleanMangaTitle(attrTitle)
        return cleanMangaTitle(cleanElementTextWithoutBadges(link))
    }

    private fun cleanElementTextWithoutBadges(element: Element): String {
        val clone = element.clone()
        clone.select(".badge, .tag, small, [class*=badge], [class*=pill]").remove()
        return clone.text().trim()
    }

    private fun cleanMangaTitle(raw: String): String =
        raw.replace(WHITESPACE_REGEX, " ").trim()

    private fun cleanChapterName(raw: String, chapNum: Float, url: String): String {
        var cleaned = raw
            .replace(RELATIVE_DATE_TAIL_REGEX, "")
            .replace(NEW_BADGE_TAIL_REGEX, "")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        if ((cleaned.equals("Capítulo", ignoreCase = true) || cleaned.equals("Cap", ignoreCase = true)) && chapNum >= 0f) {
            cleaned = "Capítulo ${formatChapterNumber(chapNum)}"
        } else if (cleaned.isBlank()) {
            val numFromUrl = extractChapterNumber(url)
            cleaned = if (numFromUrl >= 0f) "Capítulo ${formatChapterNumber(numFromUrl)}" else "Capítulo"
        }
        return cleaned
    }

    private fun extractChapterNumber(text: String): Float {
        val match = CHAPTER_NUMBER_REGEX.find(text)
            ?: URL_CHAPTER_NUMBER_REGEX.find(text)
            ?: return -1f
        return match.groupValues[1].toFloatOrNull() ?: -1f
    }

    private fun formatChapterNumber(number: Float): String =
        if (number % 1f == 0f) number.toInt().toString() else number.toString()

    private fun isCtaButtonText(text: String): Boolean {
        val lower = normalizeText(text)
        return lower.startsWith("leer ") ||
            lower.startsWith("empezar") ||
            lower.startsWith("continuar") ||
            lower.startsWith("primer ") ||
            lower.startsWith("ultimo ") ||
            lower == "leer"
    }

    private fun extractImageUrl(img: Element): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-cfsrc", "data-echo", "srcset", "src")
        for (attr in attrs) {
            val raw = img.attr(attr).trim()
            if (raw.isBlank()) continue
            val candidate = if (attr == "srcset") {
                raw.substringBefore(",").trim().substringBefore(" ").trim()
            } else {
                raw
            }
            if (candidate.isBlank() || candidate.startsWith("data:image")) continue
            val abs = img.absUrl(attr).takeIf { attr != "srcset" && it.isNotBlank() } ?: toAbsoluteUrl(candidate)
            if (isValidPageImageUrl(abs) || abs.contains("cover", ignoreCase = true) || abs.contains("mangas/", ignoreCase = true)) {
                return abs
            }
        }
        return null
    }

    private fun isValidPageImageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.isBlank() || lower.startsWith("data:image")) return false
        if (INVALID_IMAGE_KEYWORDS.any { lower.contains(it) }) return false
        if (lower.endsWith(".svg")) return false
        return lower.startsWith("http") || lower.startsWith("/")
    }

    private fun isNovelItem(item: ParsedMangaItem): Boolean =
        isNovelUrl(item.manga.url) || isNovelText(item.typeHint)

    private fun isNovelUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("/writings/") ||
            lower.contains("/light-novel/") ||
            lower.contains("/web-novel/") ||
            lower.contains("/novel/") ||
            lower.contains("/novela/")
    }

    private fun isNovelText(text: String): Boolean {
        val lower = normalizeText(text)
        return lower.contains("novela") ||
            lower.contains("light novel") ||
            lower.contains("web novel") ||
            lower == "novel"
    }

    private fun isNovelDocument(doc: Document): Boolean {
        val badges = doc.select(".badge, .tag, [class*=badge], [class*=type], [class*=format]").text()
        return isNovelText(badges)
    }

    private fun looksLikeTextProse(doc: Document): Boolean {
        val paragraphs = doc.select("article p, .prose p, [class*=novel] p")
        val totalWords = paragraphs.sumOf { it.text().split(WHITESPACE_REGEX).size }
        return paragraphs.size >= 5 && totalWords >= 120
    }

    private fun extractGenresFromDto(dto: CapibaraMangaDto): List<String> {
        val out = linkedSetOf<String>()
        listOf(dto.genres, dto.tags, dto.categories).forEach { elem ->
            when (elem) {
                is JsonArray -> elem.forEach { item ->
                    val name = when (item) {
                        is JsonPrimitive -> item.asStringOrNull()
                        is JsonObject -> item["name"]?.asStringOrNull() ?: item["title"]?.asStringOrNull()
                        else -> null
                    }
                    if (name != null && isValidGenre(name)) out.add(name.trim())
                }
                is JsonPrimitive -> elem.asStringOrNull()?.split(",")?.forEach { part ->
                    if (isValidGenre(part)) out.add(part.trim())
                }
                else -> Unit
            }
        }
        dto.type?.takeIf { isValidGenre(it) }?.let { out.add(it.trim()) }
        return out.toList()
    }

    private fun isValidGenre(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return false
        if (RATING_REGEX.matches(trimmed) || NUMBER_ONLY_REGEX.matches(trimmed)) return false
        if (CHAPTER_COUNT_REGEX.matches(trimmed)) return false
        val norm = normalizeText(trimmed)
        if (norm in STATUS_WORDS || isNovelText(norm)) return false
        return true
    }

    private fun extractDescriptionFromHtml(doc: Document): String? =
        doc.selectFirst(".synopsis, .description, [class*=synopsis], [class*=description]")?.text()?.trim()

    private fun extractStatusFromDocument(doc: Document): String? {
        val statusNode = doc.selectFirst(".status, [class*=status], [class*=estado]")
        if (statusNode != null) return statusNode.text()
        return doc.select(".badge, .tag, span, p").firstOrNull { el ->
            val norm = normalizeText(el.text())
            STATUS_WORDS.any { norm.contains(it) }
        }?.text()
    }

    private fun parseStatus(raw: String?): Int {
        if (raw.isNullOrBlank()) return SManga.UNKNOWN
        val norm = normalizeText(raw)
        return when {
            norm.contains("emision") || norm.contains("en curso") || norm.contains("publicandose") ||
                norm.contains("activo") || norm.contains("ongoing") || norm.contains("publishing") -> SManga.ONGOING
            norm.contains("finalizado") || norm.contains("completado") || norm.contains("terminado") ||
                norm.contains("concluido") || norm.contains("completed") || norm.contains("finished") -> SManga.COMPLETED
            norm.contains("pausa") || norm.contains("pausado") || norm.contains("espera") ||
                norm.contains("hiatus") -> SManga.ON_HIATUS
            norm.contains("cancelado") || norm.contains("dropeado") || norm.contains("abandonado") ||
                norm.contains("cancelled") || norm.contains("dropped") -> SManga.CANCELLED
            norm.contains("licenciado") || norm.contains("licensed") -> SManga.LICENSED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return 0L

        runCatching { return Instant.parse(trimmed).toEpochMilli() }
        runCatching { return ZonedDateTime.parse(trimmed).toInstant().toEpochMilli() }

        val normalized = normalizeText(trimmed)
        val now = Instant.now()
        if (normalized == "hoy" || normalized == "today" || normalized.contains("recien") || normalized.contains("just now")) {
            return now.toEpochMilli()
        }
        if (normalized == "ayer" || normalized == "yesterday") {
            return now.minus(1, ChronoUnit.DAYS).toEpochMilli()
        }

        val relMatch = RELATIVE_DATE_REGEX.find(normalized)
        if (relMatch != null) {
            val amount = relMatch.groupValues[1].toLongOrNull() ?: 1L
            val unit = relMatch.groupValues[2]
            val baseZdt = ZonedDateTime.ofInstant(now, ZoneOffset.UTC)
            val result = when {
                unit.startsWith("seg") || unit.startsWith("sec") -> baseZdt.minusSeconds(amount)
                unit.startsWith("min") -> baseZdt.minusMinutes(amount)
                unit.startsWith("hor") || unit.startsWith("hour") -> baseZdt.minusHours(amount)
                unit.startsWith("dia") || unit.startsWith("day") -> baseZdt.minusDays(amount)
                unit.startsWith("sem") || unit.startsWith("week") -> baseZdt.minusWeeks(amount)
                unit.startsWith("mes") || unit.startsWith("month") -> baseZdt.minusMonths(amount)
                unit.startsWith("an") || unit.startsWith("year") -> baseZdt.minusYears(amount)
                else -> baseZdt
            }
            return result.toInstant().toEpochMilli()
        }

        for (formatter in DATE_FORMATTERS) {
            runCatching {
                return LocalDateTime.parse(trimmed, formatter).toInstant(ZoneOffset.UTC).toEpochMilli()
            }
            runCatching {
                return LocalDate.parse(trimmed, formatter).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }
        }

        return 0L
    }

    private fun extractPersonString(element: JsonElement?): String? = when (element) {
        is JsonPrimitive -> element.asStringOrNull()?.takeIf { it.isNotBlank() }
        is JsonObject -> element["name"]?.asStringOrNull()?.takeIf { it.isNotBlank() }
        is JsonArray -> element.mapNotNull { extractPersonString(it) }.takeIf { it.isNotEmpty() }?.joinToString(", ")
        else -> null
    }

    private fun CapibaraMangaDto.genreOrTagHint(): String =
        extractGenresFromDto(this).joinToString(" ")

    private fun buildMangaUrlFromSlugs(scanSlug: String?, mangaSlug: String?): String? {
        if (mangaSlug.isNullOrBlank()) return null
        return if (!scanSlug.isNullOrBlank()) {
            "/$scanSlug/manga/$mangaSlug"
        } else {
            "/manga/$mangaSlug"
        }
    }

    private fun toAbsoluteUrl(raw: String): String {
        val trimmed = raw.trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            trimmed.startsWith("/") -> "$baseUrl$trimmed"
            else -> "$baseUrl/$trimmed"
        }
    }

    private fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        return DIACRITICS_REGEX.replace(decomposed, "").trim()
    }

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content

    private fun JsonElement.asIntOrNull(): Int? =
        (this as? JsonPrimitive)?.intOrNull

    private fun JsonElement.asBooleanOrNull(): Boolean? =
        (this as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

    private class ParsedMangaItem(
        val manga: SManga,
        val scanSlug: String,
        val language: String,
        val typeHint: String,
        val latestTimestamp: Long,
    )

    companion object {
        private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
        private val WHITESPACE_REGEX = Regex("""\s+""")
        private val CHAPTER_PATH_SUFFIX_REGEX = Regex("""/chapters/.*$""")
        private val SITE_TITLE_SUFFIX_REGEX = Regex("""\s*[-|]\s*CapibaraTraductor.*$""", RegexOption.IGNORE_CASE)
        private val RELATIVE_DATE_TAIL_REGEX = Regex("""\s+hace\s+\d+.*$""", RegexOption.IGNORE_CASE)
        private val NEW_BADGE_TAIL_REGEX = Regex("""\s+(?:Nuevo|New)(?:\s+\d+)?\s*$""", RegexOption.IGNORE_CASE)
        private val CHAPTER_NUMBER_REGEX = Regex("""(?:cap[ií]tulo|cap\.?|ch\.?|chapter)\s*(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val URL_CHAPTER_NUMBER_REGEX = Regex("""/chapters?/(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val PAGE_PARAM_REGEX = Regex("""[?&]page=(\d+)""")
        private val IMAGE_URL_IN_SCRIPT_REGEX = Regex("""["'](https?:\\?/\\?/[^"']+\.(?:webp|jpe?g|png|avif)(?:\?[^"']*)?)["']""", RegexOption.IGNORE_CASE)
        private val RELATIVE_DATE_REGEX = Regex("""(?:hace\s+)?(\d+)\s+(segundos?|seg|minutos?|min|horas?|h|dias?|semanas?|mes(?:es)?|anos?|seconds?|minutes?|hours?|days?|weeks?|months?|years?)(?:\s+ago)?""")
        private val RATING_REGEX = Regex("""^\d+(?:[.,]\d+)?/\d+$""")
        private val NUMBER_ONLY_REGEX = Regex("""^\d+$""")
        private val CHAPTER_COUNT_REGEX = Regex("""^cap\.?\s*\d+$""", RegexOption.IGNORE_CASE)

        private val STATUS_WORDS = setOf(
            "en emision", "emision", "en curso", "ongoing", "publishing",
            "finalizado", "completado", "terminado", "completed", "finished",
            "pausado", "en pausa", "hiatus", "cancelado", "cancelled", "licenciado", "licensed",
        )

        private val INVALID_IMAGE_KEYWORDS = listOf(
            "avatar", "logo", "banner", "icon", "favicon", "loading", "placeholder", "blank.gif", "/cover", "portada", "thumb",
        )

        private val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("dd/MM/yyyy"),
            DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        )
    }
}
