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
import keiyoushi.source.KeiSource
import keiyoushi.utils.asJsoup
import keiyoushi.utils.parseAs
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.time.Duration.Companion.seconds
import keiyoushi.network.rateLimit
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Capibaratraductor : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage {
        val primaryUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/manga-custom")
            .addQueryParameter("order", "popular")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("nsfw", "false")
            .build()

        val response = client.get(primaryUrl.toString(), Fetch.XHR)
        val parsed = parseMangaListResponse(response, page)
        if (parsed.mangas.isNotEmpty() || parsed.hasNextPage) {
            return parsed
        }

        val fallbackUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/landing/trending")
            .addQueryParameter("period", "week")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("nsfw", "false")
            .build()
        return parseMangaListResponse(client.get(fallbackUrl.toString(), Fetch.XHR), page)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val primaryUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/manga-custom")
            .addQueryParameter("order", "latest")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("nsfw", "false")
            .build()

        val response = client.get(primaryUrl.toString(), Fetch.XHR)
        val parsed = parseMangaListResponse(response, page)
        if (parsed.mangas.isNotEmpty() || parsed.hasNextPage) {
            return parsed
        }

        val fallbackUrl = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/landing/recently-added")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("nsfw", "false")
            .build()
        return parseMangaListResponse(client.get(fallbackUrl.toString(), Fetch.XHR), page)
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val urlBuilder = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/manga-custom")
            .addQueryParameter("limit", PAGE_SIZE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("nsfw", "false")

        val trimmedQuery = query.trim()
        if (trimmedQuery.isNotEmpty()) {
            urlBuilder.addQueryParameter("search", trimmedQuery)
        }

        var order = "popular"
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> order = filter.selectedValue()
                is StatusFilter -> filter.selectedValue().takeIf { it.isNotEmpty() }?.let {
                    urlBuilder.addQueryParameter("status", it)
                }
                is TypeFilter -> filter.selectedValue().takeIf { it.isNotEmpty() }?.let {
                    urlBuilder.addQueryParameter("type", it)
                }
                is GenreFilter -> filter.selectedValue().takeIf { it.isNotEmpty() }?.let {
                    urlBuilder.addQueryParameter("genre", it)
                }
                else -> Unit
            }
        }
        urlBuilder.addQueryParameter("order", order)

        val response = client.get(urlBuilder.build().toString(), Fetch.XHR)
        val result = parseMangaListResponse(response, page)

        if (trimmedQuery.isNotEmpty() && result.mangas.isNotEmpty()) {
            val normalizedQuery = normalizeText(trimmedQuery)
            val matched = result.mangas.filter { normalizeText(it.title).contains(normalizedQuery) }
            if (matched.isNotEmpty() && matched.size < result.mangas.size && result.mangas.size >= PAGE_SIZE) {
                return MangasPage(matched, result.hasNextPage)
            }
        }
        return result
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        SortFilter(),
        StatusFilter(),
        TypeFilter(),
        GenreFilter(),
    )

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val fullUrl = toAbsoluteUrl(manga.url)
        val document = client.get(fullUrl, Fetch.DOCUMENT).asJsoup()
        val islandProps = extractMangaIslandObject(document)

        val updatedManga = if (fetchDetails) {
            parseMangaDetails(document, islandProps, manga)
        } else {
            null
        }

        val updatedChapters = if (fetchChapters) {
            parseChapterList(document, islandProps, manga.url)
        } else {
            null
        }

        return SMangaUpdate(manga = updatedManga ?: manga, chapters = updatedChapters ?: chapters,)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val info = parseChapterRoute(chapter.url)
            ?: throw Exception("No se pudo resolver la ruta del capitulo: ${chapter.url}")

        if (isNovelUrl(chapter.url)) {
            throw Exception("Este capitulo es una novela de texto y no es compatible con lectores de imagenes.")
        }

        val encodedManga = URLEncoder.encode(info.mangaSlug, "UTF-8")
        val encodedChapter = URLEncoder.encode(info.chapterNum, "UTF-8")

        val apiPath = if (info.isJoint) {
            "$baseUrl/api/joint/$encodedManga/chapter/$encodedChapter/pages"
        } else {
            "$baseUrl/api/manga-custom/$encodedManga/chapter/$encodedChapter/pages"
        }

        val headers = Headers.Builder().apply {
            add("Referer", toAbsoluteUrl(chapter.url))
            if (!info.isJoint && info.scanSlug.isNotBlank()) {
                add("x-organization", info.scanSlug)
            }
        }.build()

        var imageUrls = runCatching {
            extractPagesFromResponse(client.get(apiPath, headers, Fetch.XHR))
        }.getOrDefault(emptyList())

        if (imageUrls.isEmpty() && !info.isJoint) {
            val jointPath = "$baseUrl/api/joint/$encodedManga/chapter/$encodedChapter/pages"
            imageUrls = runCatching {
                extractPagesFromResponse(client.get(jointPath, Fetch.XHR))
            }.getOrDefault(emptyList())
        }

        if (imageUrls.isEmpty()) {
            val doc = client.get(toAbsoluteUrl(chapter.url), Fetch.DOCUMENT).asJsoup()
            if (isNovelDocument(doc)) {
                throw Exception("Este capitulo es una novela de texto y no es compatible con lectores de imagenes.")
            }
            imageUrls = extractPagesFromDocument(doc)
        }

        if (imageUrls.isEmpty()) {
            throw Exception("No se encontraron paginas para este capitulo.")
        }

        return imageUrls.mapIndexed { index, imgUrl ->
            Page(index, imageUrl = imgUrl)
        }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val cleanPath = url.encodedPath.replace(Regex("""/chapters/.*$"""), "")
        if (!cleanPath.contains("/manga/")) return null

        val stub = SManga.create().apply {
            this.url = cleanPath
        }
        return fetchMangaUpdate(stub, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    private fun parseMangaListResponse(response: Response, currentPage: Int): MangasPage {
        val rawElement = runCatching { response.parseAs<JsonElement>() }.getOrNull()
            ?: return MangasPage(emptyList(), false)

        val items = mutableListOf<MangaItemDto>()
        var hasNextPage = false

        when (rawElement) {
            is JsonArray -> {
                rawElement.forEach { el ->
                    runCatching { decodeMangaItem(el.jsonObject) }.getOrNull()?.let { items.add(it) }
                }
                hasNextPage = items.size >= PAGE_SIZE
            }
            is JsonObject -> {
                val arrayKeys = listOf("data", "items", "mangas", "results")
                val arr = arrayKeys.firstNotNullOfOrNull { key -> rawElement[key] as? JsonArray }
                arr?.forEach { el ->
                    runCatching { decodeMangaItem(el.jsonObject) }.getOrNull()?.let { items.add(it) }
                }
                val paginationObj = rawElement["pagination"] as? JsonObject
                val explicitNext = rawElement["hasNextPage"]?.jsonPrimitive?.booleanOrNull
                    ?: rawElement["hasMore"]?.jsonPrimitive?.booleanOrNull
                    ?: paginationObj?.get("hasNextPage")?.jsonPrimitive?.booleanOrNull
                    ?: paginationObj?.get("hasMore")?.jsonPrimitive?.booleanOrNull
                val totalPages = rawElement["totalPages"]?.jsonPrimitive?.intOrNull
                    ?: paginationObj?.get("totalPages")?.jsonPrimitive?.intOrNull

                hasNextPage = when {
                    explicitNext != null -> explicitNext
                    totalPages != null -> currentPage < totalPages
                    else -> items.size >= PAGE_SIZE
                }
            }
            else -> Unit
        }

        val mangas = items
            .filterNot { isNovelItem(it) }
            .mapNotNull { toSMangaOrNull(it) }
            .distinctBy { it.url }

        return MangasPage(mangas, hasNextPage)
    }

    private fun decodeMangaItem(obj: JsonObject): MangaItemDto {
        val organizationObj = obj["organization"] as? JsonObject
        val scanSlug = obj["scanSlug"]?.jsonPrimitive?.contentOrNull
            ?: organizationObj?.get("slug")?.jsonPrimitive?.contentOrNull
        val scanName = obj["scanName"]?.jsonPrimitive?.contentOrNull
            ?: organizationObj?.get("name")?.jsonPrimitive?.contentOrNull

        return MangaItemDto(
            id = obj["id"],
            title = obj["title"]?.jsonPrimitive?.contentOrNull,
            name = obj["name"]?.jsonPrimitive?.contentOrNull,
            alternativeTitle = obj["alternativeTitle"]?.jsonPrimitive?.contentOrNull,
            slug = obj["slug"]?.jsonPrimitive?.contentOrNull,
            mangaSlug = obj["mangaSlug"]?.jsonPrimitive?.contentOrNull,
            mangaUrl = obj["mangaUrl"]?.jsonPrimitive?.contentOrNull,
            url = obj["url"]?.jsonPrimitive?.contentOrNull,
            cover = obj["cover"]?.jsonPrimitive?.contentOrNull,
            coverUrl = obj["coverUrl"]?.jsonPrimitive?.contentOrNull,
            imageUrl = obj["imageUrl"]?.jsonPrimitive?.contentOrNull,
            thumbnail = obj["thumbnail"]?.jsonPrimitive?.contentOrNull,
            scanName = scanName,
            scanSlug = scanSlug,
            isJoint = obj["isJoint"]?.jsonPrimitive?.booleanOrNull,
            isNovel = obj["isNovel"]?.jsonPrimitive?.booleanOrNull,
            type = obj["type"]?.jsonPrimitive?.contentOrNull,
            format = obj["format"]?.jsonPrimitive?.contentOrNull,
            mangaType = obj["mangaType"]?.jsonPrimitive?.contentOrNull,
            demography = obj["demography"]?.jsonPrimitive?.contentOrNull,
            status = obj["status"]?.jsonPrimitive?.contentOrNull,
            badge = obj["badge"]?.jsonPrimitive?.contentOrNull,
            badges = (obj["badges"] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull },
            genres = obj["genres"]?.let { it as? JsonArray },
            categories = obj["categories"]?.let { it as? JsonArray },
            description = obj["description"]?.jsonPrimitive?.contentOrNull,
            synopsis = obj["synopsis"]?.jsonPrimitive?.contentOrNull,
            author = obj["author"]?.jsonPrimitive?.contentOrNull,
            artist = obj["artist"]?.jsonPrimitive?.contentOrNull,
        )
    }

    private fun toSMangaOrNull(item: MangaItemDto): SManga? {
        val rawTitle = item.title ?: item.name ?: return null
        val slug = item.mangaSlug ?: item.slug
        val rawUrl = item.mangaUrl ?: item.url ?: when {
            slug != null && item.isJoint == true -> "/joint/manga/$slug"
            slug != null && !item.scanSlug.isNullOrBlank() -> "/${item.scanSlug}/manga/$slug"
            else -> null
        } ?: return null

        return SManga.create().apply {
            title = rawTitle.trim()
            setUrlWithoutDomain(toAbsoluteUrl(rawUrl))
            thumbnail_url = (item.cover ?: item.coverUrl ?: item.imageUrl ?: item.thumbnail)
                ?.takeIf { it.isNotBlank() }
                ?.let { toAbsoluteUrl(it) }
            author = item.author?.takeIf { it.isNotBlank() }
            artist = item.artist?.takeIf { it.isNotBlank() }
            description = (item.description ?: item.synopsis)?.takeIf { it.isNotBlank() }
            status = parseStatus(item.status)
        }
    }

    private fun isNovelItem(item: MangaItemDto): Boolean {
        if (item.isNovel == true) return true
        val url = item.mangaUrl ?: item.url
        if (url != null && isNovelUrl(url)) return true

        val markers = buildList {
            item.type?.let { add(it) }
            item.format?.let { add(it) }
            item.mangaType?.let { add(it) }
            item.demography?.let { add(it) }
            item.badge?.let { add(it) }
            item.badges?.let { addAll(it) }
            item.genres?.mapNotNull { extractLabel(it) }?.let { addAll(it) }
            item.categories?.mapNotNull { extractLabel(it) }?.let { addAll(it) }
        }

        return markers.any { isNovelMarker(it) }
    }

    private fun isNovelMarker(raw: String): Boolean {
        val norm = normalizeText(raw)
        return norm == "novela" || norm == "novel" || norm == "light novel" || norm == "web novel" || norm == "novela ligera"
    }

    private fun isNovelUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("/novela/") || lower.contains("/novel/")
    }

    private fun isNovelDocument(doc: Document): Boolean {
        val badgeTexts = doc.select("span, a.badge, [class*=badge], [class*=tag]")
            .map { it.text().trim() }
        return badgeTexts.any { isNovelMarker(it) }
    }

    private fun parseMangaDetails(document: Document, islandProps: JsonObject?, fallback: SManga): SManga {
        val mangaObj = islandProps?.get("manga") as? JsonObject ?: islandProps

        val title = mangaObj?.get("title")?.jsonPrimitive?.contentOrNull
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")?.trim()
            ?: fallback.title

        val cover = mangaObj?.get("cover")?.jsonPrimitive?.contentOrNull
            ?: mangaObj?.get("coverUrl")?.jsonPrimitive?.contentOrNull
            ?: mangaObj?.get("imageUrl")?.jsonPrimitive?.contentOrNull
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: fallback.thumbnail_url

        val description = mangaObj?.get("description")?.jsonPrimitive?.contentOrNull
            ?: mangaObj?.get("synopsis")?.jsonPrimitive?.contentOrNull
            ?: mangaObj?.get("shortDescription")?.jsonPrimitive?.contentOrNull
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: fallback.description

        val altTitle = mangaObj?.get("alternativeTitle")?.jsonPrimitive?.contentOrNull
        val fullDescription = buildString {
            if (!description.isNullOrBlank()) append(description.trim())
            if (!altTitle.isNullOrBlank() && !altTitle.equals(title, ignoreCase = true)) {
                if (isNotEmpty()) append("\n\n")
                append("Titulos alternativos: ").append(altTitle.trim())
            }
        }.ifBlank { null }

        val authorText = mangaObj?.get("author")?.jsonPrimitive?.contentOrNull
            ?: document.selectFirst("[class*=author], [class*=autor]")?.text()?.trim()
        val artistText = mangaObj?.get("artist")?.jsonPrimitive?.contentOrNull
            ?: document.selectFirst("[class*=artist], [class*=artista]")?.text()?.trim()
        val scanText = (mangaObj?.get("organization") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            ?: mangaObj?.get("scanName")?.jsonPrimitive?.contentOrNull
            ?: islandProps?.get("organization")?.let { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }

        val statusRaw = mangaObj?.get("status")?.jsonPrimitive?.contentOrNull
            ?: document.select(".status, [class*=status], [class*=estado], span, div")
                .firstOrNull { isStatusText(it.ownText()) }
                ?.ownText()
        val parsedStatus = parseStatus(statusRaw)

        val rawGenres = mutableListOf<String>()
        listOf("genres", "categories", "tags").forEach { key ->
            (mangaObj?.get(key) as? JsonArray)?.mapNotNullTo(rawGenres) { extractLabel(it) }
        }
        mangaObj?.get("demography")?.jsonPrimitive?.contentOrNull?.let { rawGenres.add(it) }
        if (rawGenres.isEmpty()) {
            document.select("a[href*=genre], a[href*=genero], [class*=genre], [class*=genero]")
                .mapTo(rawGenres) { it.text().trim() }
        }

        val cleanGenres = rawGenres
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filterNot { isInvalidGenreTag(it) }
            .distinct()

        return SManga.create().apply {
            url = fallback.url
            this.title = title.trim()
            thumbnail_url = cover?.takeIf { it.isNotBlank() }?.let { toAbsoluteUrl(it) }
            this.description = fullDescription
            author = authorText?.takeIf { it.isNotBlank() } ?: scanText
            artist = artistText?.takeIf { it.isNotBlank() }
            status = if (parsedStatus != SManga.UNKNOWN) parsedStatus else fallback.status
            genre = cleanGenres.joinToString(", ").ifBlank { null }
        }
    }

    private fun parseChapterList(document: Document, islandProps: JsonObject?, mangaUrl: String): List<SChapter> {
        val scanlatorName = (islandProps?.get("organization") as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull
            ?: (islandProps?.get("manga") as? JsonObject)?.get("scanName")?.jsonPrimitive?.contentOrNull

        val htmlChapters = document.select("li.ficha-fila, li[id^=cap-]").mapNotNull { row ->
            val link = row.selectFirst("a[href*=/chapters/]") ?: return@mapNotNull null
            val href = link.absUrl("href").ifBlank { toAbsoluteUrl(link.attr("href")) }
            if (href.isBlank()) return@mapNotNull null

            val rawText = link.selectFirst("span.font-bold, span.tabular-nums, span")?.text()?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: link.text().trim()

            if (isCtaChapterText(rawText)) return@mapNotNull null

            val cleanName = cleanChapterName(rawText, href)
            val number = row.attr("data-numero").toFloatOrNull()
                ?: extractChapterNumber(href, cleanName)
            val timeAttr = row.selectFirst("time")?.attr("datetime")
            val timeText = row.selectFirst("time")?.text()?.trim()
            val uploadDate = parseDate(timeAttr?.takeIf { it.isNotBlank() } ?: timeText.orEmpty())

            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = cleanName
                chapter_number = number
                date_upload = uploadDate
                scanlator = scanlatorName
            }
        }

        if (htmlChapters.isNotEmpty()) {
            return htmlChapters.distinctBy { it.url }
        }

        val mangaObj = islandProps?.get("manga") as? JsonObject
        val islandChapters = (islandProps?.get("chapters") as? JsonArray)
            ?: (mangaObj?.get("chapters") as? JsonArray)

        if (islandChapters != null) {
            val mapped = islandChapters.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val num = obj["number"]?.jsonPrimitive?.floatOrNull
                val numStr = obj["number"]?.jsonPrimitive?.contentOrNull
                val chUrl = obj["chapterUrl"]?.jsonPrimitive?.contentOrNull
                    ?: obj["url"]?.jsonPrimitive?.contentOrNull
                    ?: numStr?.let { "${mangaUrl.trimEnd('/')}/chapters/$it" }
                    ?: return@mapNotNull null
                val titleText = obj["title"]?.jsonPrimitive?.contentOrNull
                    ?: numStr?.let { "Capitulo $it" }
                    ?: return@mapNotNull null
                val releasedAt = obj["releasedAt"]?.jsonPrimitive?.contentOrNull
                    ?: obj["createdAt"]?.jsonPrimitive?.contentOrNull
                    ?: ""

                SChapter.create().apply {
                    setUrlWithoutDomain(toAbsoluteUrl(chUrl))
                    name = cleanChapterName(titleText, chUrl)
                    chapter_number = num ?: extractChapterNumber(chUrl, name)
                    date_upload = parseDate(releasedAt)
                    scanlator = scanlatorName
                }
            }
            if (mapped.isNotEmpty()) {
                return mapped.distinctBy { it.url }
            }
        }

        return document.select("a[href*=/chapters/]").mapNotNull { link ->
            val rawText = link.text().trim()
            if (rawText.isBlank() || isCtaChapterText(rawText)) return@mapNotNull null
            val href = link.absUrl("href").ifBlank { toAbsoluteUrl(link.attr("href")) }
            if (href.isBlank()) return@mapNotNull null

            val cleanName = cleanChapterName(rawText, href)
            SChapter.create().apply {
                setUrlWithoutDomain(href)
                name = cleanName
                chapter_number = extractChapterNumber(href, cleanName)
                date_upload = parseDate(link.parent()?.selectFirst("time")?.attr("datetime").orEmpty())
                scanlator = scanlatorName
            }
        }.distinctBy { it.url }
    }

    private fun extractPagesFromResponse(response: Response): List<String> {
        if (!response.isSuccessful) {
            response.close()
            return emptyList()
        }
        val rawElement = runCatching { response.parseAs<JsonElement>() }.getOrNull() ?: return emptyList()
        return extractImageUrlsFromJson(rawElement)
    }

    private fun extractImageUrlsFromJson(element: JsonElement): List<String> {
        val urls = mutableListOf<String>()
        when (element) {
            is JsonArray -> element.forEach { item ->
                when (item) {
                    is JsonPrimitive -> item.contentOrNull?.let { urls.add(it) }
                    is JsonObject -> extractUrlFromPageObject(item)?.let { urls.add(it) }
                    else -> Unit
                }
            }
            is JsonObject -> {
                val listCandidate = element["pages"] ?: element["images"] ?: element["data"]
                if (listCandidate is JsonArray) {
                    urls.addAll(extractImageUrlsFromJson(listCandidate))
                } else if (listCandidate is JsonObject) {
                    urls.addAll(extractImageUrlsFromJson(listCandidate))
                }
            }
            else -> Unit
        }
        return urls.map { toAbsoluteUrl(it) }.filter { isValidImageUrl(it) }.distinct()
    }

    private fun extractUrlFromPageObject(obj: JsonObject): String? {
        return obj["imageUrl"]?.jsonPrimitive?.contentOrNull
            ?: obj["url"]?.jsonPrimitive?.contentOrNull
            ?: obj["src"]?.jsonPrimitive?.contentOrNull
            ?: obj["image"]?.jsonPrimitive?.contentOrNull
    }

    private fun extractPagesFromDocument(doc: Document): List<String> {
        doc.select("astro-island[props]").forEach { island ->
            val propsStr = island.attr("props").takeIf { it.isNotBlank() } ?: return@forEach
            val parsed = runCatching { propsStr.parseAs<JsonElement>() }.getOrNull() ?: return@forEach
            val unwrapped = unwrapAstroJson(parsed)
            val urls = extractImageUrlsFromJson(unwrapped)
            if (urls.isNotEmpty()) return urls
        }

        return doc.select("main img, article img, [class*=reader] img, [class*=lector] img")
            .mapNotNull { extractImageUrl(it) }
            .distinct()
    }

    private fun extractImageUrl(img: Element): String? {
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-cfsrc", "data-echo", "srcset", "src")
        for (attr in attrs) {
            val value = if (attr == "srcset") {
                img.attr("srcset").split(",").firstOrNull()?.trim()?.split(Regex("""\s+"""))?.firstOrNull()
            } else {
                img.absUrl(attr).ifBlank { img.attr(attr) }
            }
            if (!value.isNullOrBlank()) {
                val abs = toAbsoluteUrl(value)
                if (isValidImageUrl(abs)) return abs
            }
        }
        return null
    }

    private fun isValidImageUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        if (lower.startsWith("data:image/") || lower.endsWith(".svg")) return false
        val blocked = listOf("avatar", "logo", "banner", "icon", "loading", "placeholder", "blank.gif", "portada", "cover")
        return blocked.none { lower.contains(it) }
    }

    private fun extractMangaIslandObject(document: Document): JsonObject? {
        val islands = document.select("astro-island[props]")
        for (island in islands) {
            val component = island.attr("component-url")
            val propsRaw = island.attr("props")
            if (propsRaw.isBlank()) continue
            val jsonEl = runCatching { propsRaw.parseAs<JsonElement>() }.getOrNull() ?: continue
            val unwrapped = unwrapAstroJson(jsonEl) as? JsonObject ?: continue
            if (component.contains("MangaDetail", ignoreCase = true) || unwrapped.containsKey("manga")) {
                return unwrapped
            }
        }
        return null
    }

    private fun unwrapAstroJson(element: JsonElement): JsonElement {
        return when (element) {
            is JsonArray -> {
                if (element.size == 2 && element[0] is JsonPrimitive) {
                    val tag = element[0].jsonPrimitive.intOrNull
                    if (tag == 0) {
                        return unwrapAstroJson(element[1])
                    }
                    if (tag == 1 && element[1] is JsonArray) {
                        return JsonArray(element[1].jsonArray.map { unwrapAstroJson(it) })
                    }
                }
                JsonArray(element.map { unwrapAstroJson(it) })
            }
            is JsonObject -> {
                val mapped = element.entries.associate { (k, v) -> k to unwrapAstroJson(v) }
                JsonObject(mapped)
            }
            else -> element
        }
    }

    private fun extractLabel(element: JsonElement): String? {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull
            is JsonObject -> element["name"]?.jsonPrimitive?.contentOrNull
                ?: element["label"]?.jsonPrimitive?.contentOrNull
                ?: element["title"]?.jsonPrimitive?.contentOrNull
            else -> null
        }
    }

    private fun parseChapterRoute(rawUrl: String): ChapterRouteInfo? {
        val path = runCatching { toAbsoluteUrl(rawUrl).toHttpUrl().encodedPath }.getOrNull() ?: rawUrl
        val chapterMatch = Regex("""/chapters/([^/?#]+)""").find(path) ?: return null
        val chapterNum = chapterMatch.groupValues[1]

        val jointMatch = Regex("""^/joint/manga/([^/?#]+)""").find(path)
        if (jointMatch != null) {
            return ChapterRouteInfo("joint", jointMatch.groupValues[1], chapterNum, isJoint = true)
        }

        val customMatch = Regex("""^/([^/]+)/manga/([^/?#]+)""").find(path)
        if (customMatch != null) {
            val scanSlug = customMatch.groupValues[1]
            val mangaSlug = customMatch.groupValues[2]
            return ChapterRouteInfo(scanSlug, mangaSlug, chapterNum, isJoint = scanSlug == "joint")
        }

        return null
    }

    private fun isCtaChapterText(text: String): Boolean {
        val norm = normalizeText(text)
        return norm.startsWith("leer ") ||
            norm.startsWith("empezar") ||
            norm.startsWith("continuar") ||
            norm.startsWith("primer ") ||
            norm.startsWith("ultimo ")
    }

    private fun cleanChapterName(raw: String, chapterUrl: String): String {
        var cleaned = raw
            .replace(Regex("""\s+hace\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+(?:Nuevo|New)(?:\s+\d+)?\s*$""", RegexOption.IGNORE_CASE), "")
            .trim()
        if (cleaned.equals("Capitulo", ignoreCase = true) || cleaned.equals("Cap.", ignoreCase = true)) {
            val fromUrl = Regex("""/chapters/([^/?#]+)""").find(chapterUrl)?.groupValues?.get(1)
            if (!fromUrl.isNullOrBlank()) {
                cleaned = "Cap. $fromUrl"
            }
        }
        return cleaned
    }

    private fun extractChapterNumber(url: String, name: String): Float {
        val fromUrl = Regex("""/chapters/(\d+(?:\.\d+)?)""").find(url)?.groupValues?.get(1)?.toFloatOrNull()
        if (fromUrl != null) return fromUrl
        return Regex("""(\d+(?:\.\d+)?)""").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: -1f
    }

    private fun parseDate(raw: String): Long {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return 0L

        runCatching { Instant.parse(trimmed).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { ZonedDateTime.parse(trimmed).toInstant().toEpochMilli() }.getOrNull()?.let { return it }

        val norm = normalizeText(trimmed)
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        when {
            norm == "hoy" || norm == "today" || norm.contains("ahora") || norm.contains("momento") ->
                return now.toInstant().toEpochMilli()
            norm == "ayer" || norm == "yesterday" ->
                return now.minusDays(1).toInstant().toEpochMilli()
        }

        val relMatch = Regex("""(\d+)\s*(seg|segundo|segundos|min|minuto|minutos|hora|horas|dia|dias|semana|semanas|mes|meses|ano|anos|second|seconds|minute|minutes|hour|hours|day|days|week|weeks|month|months|year|years)""").find(norm)
        if (relMatch != null) {
            val amount = relMatch.groupValues[1].toLongOrNull() ?: 0L
            val unit = relMatch.groupValues[2]
            val instant = when {
                unit.startsWith("seg") || unit.startsWith("sec") -> now.minus(amount, ChronoUnit.SECONDS)
                unit.startsWith("min") -> now.minus(amount, ChronoUnit.MINUTES)
                unit.startsWith("hor") || unit.startsWith("hou") -> now.minus(amount, ChronoUnit.HOURS)
                unit.startsWith("dia") || unit.startsWith("day") -> now.minus(amount, ChronoUnit.DAYS)
                unit.startsWith("sem") || unit.startsWith("wee") -> now.minusWeeks(amount)
                unit.startsWith("mes") || unit.startsWith("mon") -> now.minusMonths(amount)
                unit.startsWith("ano") || unit.startsWith("yea") -> now.minusYears(amount)
                else -> now
            }
            return instant.toInstant().toEpochMilli()
        }

        for (formatter in DATE_FORMATTERS) {
            val parsed = formatter.tryParseDate(trimmed)
            if (parsed != 0L) return parsed
            runCatching {
                LocalDate.parse(trimmed, formatter).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull()?.let { return it }
        }
        return 0L
    }

    private fun isStatusText(raw: String): Boolean {
        val norm = normalizeText(raw)
        return norm in listOf(
            "en emision", "emision", "publicandose", "en curso", "ongoing", "publishing",
            "finalizado", "completado", "terminado", "completed", "finished",
            "pausado", "en pausa", "hiatus", "cancelado", "cancelled", "licenciado", "licensed",
        )
    }

    private fun parseStatus(raw: String?): Int {
        if (raw.isNullOrBlank()) return SManga.UNKNOWN
        val norm = normalizeText(raw)
        return when {
            norm.contains("emision") || norm.contains("publicandose") || norm.contains("en curso") ||
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

    private fun isInvalidGenreTag(tag: String): Boolean {
        val norm = normalizeText(tag)
        if (Regex("""^\d+(?:[.,]\d+)?/\d+$""").matches(norm)) return true
        if (Regex("""^\d+$""").matches(norm)) return true
        if (Regex("""^cap\.?\s*\d+$""").matches(norm)) return true
        if (isStatusText(norm) || isNovelMarker(norm)) return true
        return false
    }

    private fun normalizeText(input: String): String {
        val decomposed = Normalizer.normalize(input.lowercase(Locale.ROOT).trim(), Normalizer.Form.NFD)
        return decomposed.replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
    }

    private fun toAbsoluteUrl(raw: String): String {
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> "$baseUrl/$raw"
        }
    }

    private class ChapterRouteInfo(
        val scanSlug: String,
        val mangaSlug: String,
        val chapterNum: String,
        val isJoint: Boolean,
    )

    companion object {
        private const val PAGE_SIZE = 24
        private val DATE_FORMATTERS = listOf(
            DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT),
            DateTimeFormatter.ofPattern("dd-MM-yyyy", Locale.ROOT),
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale("es", "ES")),
        )
    }
}
