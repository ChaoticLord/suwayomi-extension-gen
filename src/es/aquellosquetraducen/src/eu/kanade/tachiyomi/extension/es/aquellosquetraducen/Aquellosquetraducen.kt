package eu.kanade.tachiyomi.extension.es.aquellosquetraducen

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
import keiyoushi.utils.tryParseDate
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
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
abstract class Aquellosquetraducen : KeiSource() {

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    private suspend fun fetchDocument(url: String): Document =
        client.get(url, Fetch.DOCUMENT).asJsoup()

    private fun isNovelCard(element: Element): Boolean {
        if (element.closest("#carousel-novelas") != null) return true
        val href = element.absUrl("href").lowercase(Locale.ROOT)
        if (href.contains("/novela") || href.contains("novel")) return true
        val text = element.text().lowercase(Locale.ROOT)
        return Regex("""\b(novela|novel|light novel|web novel)\b""").containsMatchIn(text)
    }

    private fun cleanTitle(element: Element): String {
        val titleEl = element.selectFirst("h2, h3, h4, .title, [class*=title]")
        val raw = if (titleEl != null) {
            val clone = titleEl.clone()
            clone.select("span.badge, .tag, small").remove()
            clone.text()
        } else {
            element.attr("title").ifBlank {
                element.selectFirst("img")?.attr("alt").orEmpty()
            }
        }
        return raw.trim()
    }

    private fun parseCardList(elements: List<Element>): List<SManga> {
        val seen = HashSet<String>()
        val list = ArrayList<SManga>()
        for (el in elements) {
            val link = if (el.tagName() == "a") el else el.selectFirst("a[href]") ?: continue
            if (isNovelCard(el) || isNovelCard(link)) continue
            val href = link.absUrl("href")
            if (href.isBlank() || href.contains("discord.gg") || href.contains("paypal") || href.contains("patreon")) continue
            if (href.endsWith("index.html") || href.endsWith("proyectos.html") || href.endsWith("marcados.html")) continue

            val title = cleanTitle(el).ifBlank { cleanTitle(link) }
            if (title.isBlank()) continue

            val manga = SManga.create().apply {
                setUrlWithoutDomain(href)
                this.title = title
                thumbnail_url = extractImageUrl(el.selectFirst("img"))
            }
            if (seen.add(manga.url)) {
                list.add(manga)
            }
        }
        return list
    }

    override suspend fun getPopularManga(page: Int): MangasPage {
        val doc = fetchDocument("$baseUrl/index.html")
        val cards = doc.select("#carousel-populares a[href], #carousel-manhwas a[href], #carousel-manhuas a[href], #carousel-mangas-color a[href], #carousel-mangas a[href]")
        return MangasPage(parseCardList(cards), false)
    }

    override suspend fun getLatestUpdates(page: Int): MangasPage {
        val doc = fetchDocument("$baseUrl/proyectos.html")
        val cards = doc.select("main a[href], section a[href], .carousel a[href], .card a[href]")
        val mangas = parseCardList(cards)
        return if (mangas.isNotEmpty()) {
            MangasPage(mangas, false)
        } else {
            getPopularManga(page)
        }
    }

    override suspend fun getSearchMangaList(page: Int, query: String, filters: FilterList): MangasPage {
        val doc = fetchDocument("$baseUrl/index.html")
        val categoryFilter = filters.filterIsInstance<CategoryFilter>().firstOrNull()?.selectedValue() ?: "all"

        val selector = when (categoryFilter) {
            "manhwas" -> "#carousel-manhwas a[href]"
            "manhuas" -> "#carousel-manhuas a[href]"
            "mangas_color" -> "#carousel-mangas-color a[href]"
            "mangas" -> "#carousel-mangas a[href]"
            else -> "#carousel-populares a[href], #carousel-manhwas a[href], #carousel-manhuas a[href], #carousel-mangas-color a[href], #carousel-mangas a[href]"
        }

        val allMangas = parseCardList(doc.select(selector))
        val filtered = if (query.isBlank()) {
            allMangas
        } else {
            val q = query.trim().lowercase(Locale.ROOT)
            allMangas.filter { it.title.lowercase(Locale.ROOT).contains(q) }
        }
        return MangasPage(filtered, false)
    }

    override suspend fun fetchMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val url = if (manga.url.startsWith("http")) manga.url else "$baseUrl${manga.url}"
        val doc = fetchDocument(url)

        val updatedManga = if (fetchDetails) {
            SManga.create().apply {
                this.url = manga.url
                title = doc.selectFirst("h1, h2.manga-title, .title")?.text()?.trim()?.ifBlank { manga.title } ?: manga.title
                thumbnail_url = extractImageUrl(doc.selectFirst(".cover img, .portada img, main img")) ?: manga.thumbnail_url
                description = doc.selectFirst(".sinopsis, .synopsis, .description, [class*=desc]")?.text()?.trim()
                author = doc.selectFirst(".author, [class*=autor]")?.text()?.trim()
                val statusText = doc.select(".status, [class*=status], [class*=estado], .badge").text()
                status = parseStatus(statusText)
                genre = doc.select(".genres a, .genre, .tags a, .badge")
                    .map { it.text().trim() }
                    .filter { g ->
                        g.isNotBlank() &&
                            !Regex("""^\d+(?:[.,]\d+)?/\d+$""").matches(g) &&
                            !Regex("""^\d+$""").matches(g) &&
                            !Regex("""^(en emisi[oó]n|finalizado|en curso|pausado|cancelado|cap\.?\s*\d+)$""", RegexOption.IGNORE_CASE).matches(g)
                    }
                    .distinct()
                    .joinToString(", ")
            }
        } else {
            manga
        }

        val updatedChapters = if (fetchChapters) {
            val chapterLinks = doc.select("#chapters a[href], .chapters a[href], .chapter-list a[href], ul li a[href]")
            chapterLinks.mapNotNull { el ->
                val href = el.absUrl("href")
                if (href.isBlank()) return@mapNotNull null
                val rawText = el.text().trim()
                if (Regex("""^(leer|empezar|continuar|primer|último)\b""", RegexOption.IGNORE_CASE).containsMatchIn(rawText)) {
                    return@mapNotNull null
                }
                val cleanName = rawText
                    .replace(Regex("""\s+hace\s+\d+.*$""", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("""\s+(?:Nuevo|New)(?:\s+\d+)?\s*$""", RegexOption.IGNORE_CASE), "")
                    .trim()
                val dateText = el.selectFirst(".date, .time, small, span")?.text()?.trim().orEmpty()
                SChapter.create().apply {
                    setUrlWithoutDomain(href)
                    name = cleanName.ifBlank { "Capítulo" }
                    date_upload = parseDate(dateText)
                }
            }.distinctBy { it.url }
        } else {
            chapters
        }

        return SMangaUpdate(updatedManga ?: manga, updatedChapters)
    }

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val url = if (chapter.url.startsWith("http")) chapter.url else "$baseUrl${chapter.url}"
        val doc = fetchDocument(url)

        val isNovel = doc.select(".badge, .tag").any {
            Regex("""\b(novela|novel|light novel|web novel)\b""", RegexOption.IGNORE_CASE).containsMatchIn(it.text())
        }
        val domImages = doc.select("#readerarea img, .reader img, .chapter-content img, main img")
            .mapNotNull { extractImageUrl(it) }
            .filterNot { it.contains("logo", true) || it.contains("avatar", true) || it.contains("reclutar", true) }

        val images = domImages.ifEmpty { extractScriptImages(doc) }

        if (images.isEmpty()) {
            if (isNovel) {
                throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
            }
            throw Exception("No se encontraron páginas de imágenes para este capítulo.")
        }

        return images.mapIndexed { index, imgUrl ->
            Page(index, imageUrl = imgUrl)
        }
    }

    private fun extractScriptImages(doc: Document): List<String> {
        val urlRegex = Regex("""https?://[^"'\s<>\\]+\.(?:jpe?g|png|webp|avif)(?:\?[^"'\s<>\\]*)?""", RegexOption.IGNORE_CASE)
        return doc.select("script")
            .flatMap { script -> urlRegex.findAll(script.data()).map { it.value }.toList() }
            .distinct()
            .filterNot { it.contains("logo", true) || it.contains("avatar", true) || it.contains("reclutar", true) || it.contains("cover", true) || it.contains("portada", true) }
    }

    override suspend fun getMangaByUrl(url: HttpUrl): SManga? {
        val doc = fetchDocument(url.toString())
        val title = doc.selectFirst("h1, h2.manga-title, .title")?.text()?.trim() ?: return null
        return SManga.create().apply {
            setUrlWithoutDomain(url.toString())
            this.title = title
            thumbnail_url = extractImageUrl(doc.selectFirst(".cover img, .portada img, main img"))
        }
    }

    override fun getFilterList(data: JsonElement?): FilterList = FilterList(
        CategoryFilter(),
    )

    private class CategoryFilter : Filter.Select<String>(
        "Categoría",
        arrayOf("Todas", "Manhwas", "Manhuas", "Mangas a Color", "Mangas"),
    ) {
        private val options = arrayOf("all", "manhwas", "manhuas", "mangas_color", "mangas")
        fun selectedValue(): String = options[state]
    }

    private fun extractImageUrl(img: Element?): String? {
        if (img == null) return null
        val attrs = listOf("data-src", "data-lazy-src", "data-original", "data-url", "data-cfsrc", "data-echo", "src")
        for (attr in attrs) {
            val candidate = img.absUrl(attr).ifBlank { img.attr(attr) }
            if (candidate.isBlank() || candidate.startsWith("data:image/")) continue
            val lower = candidate.lowercase(Locale.ROOT)
            if (lower.endsWith(".svg") || lower.contains("blank.gif") || lower.contains("placeholder") || lower.contains("loading")) continue
            return candidate
        }
        val srcset = img.attr("srcset").trim()
        if (srcset.isNotBlank()) {
            val first = srcset.split(",").firstOrNull()?.trim()?.split(" ")?.firstOrNull()
            if (!first.isNullOrBlank() && !first.startsWith("data:image/")) return first
        }
        return null
    }

    private fun parseStatus(raw: String): Int {
        val normalized = Normalizer.normalize(raw.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{InCombiningDiacriticalMarks}+"""), "")
        return when {
            normalized.contains("emision") || normalized.contains("en curso") || normalized.contains("ongoing") -> SManga.ONGOING
            normalized.contains("finalizado") || normalized.contains("completado") || normalized.contains("completed") -> SManga.COMPLETED
            normalized.contains("pausa") || normalized.contains("hiatus") -> SManga.ON_HIATUS
            normalized.contains("cancelado") || normalized.contains("cancelled") -> SManga.CANCELLED
            normalized.contains("licenciado") || normalized.contains("licensed") -> SManga.LICENSED
            else -> SManga.UNKNOWN
        }
    }

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return 0L
        val lower = raw.lowercase(Locale.ROOT).trim()
        val now = ZonedDateTime.now(ZoneOffset.UTC)
        if (lower.contains("hoy") || lower.contains("today")) return now.toInstant().toEpochMilli()
        if (lower.contains("ayer") || lower.contains("yesterday")) return now.minusDays(1).toInstant().toEpochMilli()

        val relMatch = Regex("""(\d+)\s*(minuto|minutos|hora|horas|d[ií]a|d[ií]as|semana|semanas|mes|meses|a[nñ]o|a[nñ]os)""").find(lower)
        if (relMatch != null) {
            val amount = relMatch.groupValues[1].toLongOrNull() ?: 0L
            val unit = relMatch.groupValues[2]
            val dt = when {
                unit.startsWith("min") -> now.minus(amount, ChronoUnit.MINUTES)
                unit.startsWith("hora") -> now.minus(amount, ChronoUnit.HOURS)
                unit.startsWith("d") -> now.minus(amount, ChronoUnit.DAYS)
                unit.startsWith("semana") -> now.minus(amount * 7, ChronoUnit.DAYS)
                unit.startsWith("mes") -> now.minusMonths(amount)
                else -> now.minusYears(amount)
            }
            return dt.toInstant().toEpochMilli()
        }

        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy", Locale.ROOT)
        return formatter.tryParseDate(raw)
    }
}
