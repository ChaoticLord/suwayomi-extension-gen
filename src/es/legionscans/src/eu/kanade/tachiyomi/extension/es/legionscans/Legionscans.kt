package eu.kanade.tachiyomi.extension.es.legionscans

import keiyoushi.annotation.Source
import keiyoushi.multisrc.mangathemesia.MangaThemesia
import okhttp3.OkHttpClient
import kotlin.time.Duration.Companion.seconds
import keiyoushi.network.rateLimit

@Source
class Legionscans : MangaThemesia() {
    override val baseUrl = "https://legionscans.com"
    override val mangaUrlDirectory = "/manga"

    override fun OkHttpClient.Builder.configureClient(): OkHttpClient.Builder = rateLimit(permits = 2, period = 1.seconds)
}