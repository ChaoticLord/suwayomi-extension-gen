package eu.kanade.tachiyomi.extension.es.legionscans

import eu.kanade.tachiyomi.multisrc.mangathemesia.MangaThemesia
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import keiyoushi.annotation.Source
import keiyoushi.network.rateLimit
import okhttp3.OkHttpClient
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Source
abstract class Legionscans : MangaThemesia() {
    override val mangaUrlDirectory = "/wp/manga"

    override val dateFormat: DateTimeFormatter = DateTimeFormatterBuilder()
        .parseCaseInsensitive()
        .appendPattern("MMMM d, yyyy")
        .toFormatter(Locale.forLanguageTag("es"))

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder =
        rateLimit(permits = 2, period = 1.seconds)

    override suspend fun getPopularManga(page: Int): MangasPage =
        super.getPopularManga(page).filterOutNovels()

    override suspend fun getLatestUpdates(page: Int): MangasPage =
        super.getLatestUpdates(page).filterOutNovels()

    override suspend fun getSearchMangaList(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = super.getSearchMangaList(page, query, filters).filterOutNovels()

    override suspend fun getPageList(chapter: SChapter): List<Page> {
        val pages = super.getPageList(chapter)
        if (pages.isEmpty()) {
            throw Exception("Este capítulo es una novela de texto y no es compatible con lectores de imágenes.")
        }
        return pages
    }

    private fun MangasPage.filterOutNovels(): MangasPage {
        val filtered = mangas.filterNot { manga ->
            manga.url.contains("/novel", ignoreCase = true) ||
                manga.title.contains("[Novela]", ignoreCase = true) ||
                manga.title.contains("(Novela)", ignoreCase = true) ||
                manga.title.contains("[Novel]", ignoreCase = true) ||
                manga.title.contains("(Novel)", ignoreCase = true)
        }
        return MangasPage(filtered, hasNextPage)
    }
}
