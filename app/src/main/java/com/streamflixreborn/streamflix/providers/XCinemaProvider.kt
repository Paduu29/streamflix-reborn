package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.WebViewResolver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

object XCinemaProvider : Provider {

    override val name = "xCinema"
    override val baseUrl = "https://www.xcinema.ro"
    override val logo = "$baseUrl/img/1779010584752-25522126ed5d3e39b5e3c9ef591d1cc0.webp"
    override val language = "ro"

    private const val TAG = "XCinemaProvider"

    private val providerMutex = Mutex()
    private var webViewResolver: WebViewResolver? = null

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    private fun pageHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to NetworkClient.USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ro-RO,ro;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
        )
    }

    private fun normalizeUrl(url: String?, referer: String = baseUrl): String? {
        val value = url?.trim().orEmpty()
        if (value.isBlank()) return null

        return when {
            value.startsWith("//") -> "https:$value"
            value.startsWith("http", ignoreCase = true) -> value
            value.startsWith("/") -> "$baseUrl$value"
            value.startsWith("embed/") -> "$baseUrl/$value"
            value.startsWith("serial/") || value.startsWith("film/") || value.startsWith("movie/") -> "$baseUrl/$value"
            referer.startsWith(baseUrl) && value.startsWith("?") -> "$referer$value"
            else -> "$baseUrl/$value"
        }
    }

    private suspend fun getDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .headers(okhttp3.Headers.headersOf("Referer", baseUrl, "User-Agent", NetworkClient.USER_AGENT))
            .build()

        return try {
            NetworkClient.default.newCall(request).execute().use { response ->
                val html = response.body?.string().orEmpty()
                if (response.isSuccessful && html.isNotBlank()) {
                    Jsoup.parse(html, url)
                } else {
                    throw IllegalStateException("xCinema request failed: ${response.code}")
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("xCinema request failed: ${e.message}")
//            Log.d(TAG, "Falling back to WebView for $url", e)
//            val html = providerMutex.withLock { getResolver().get(url, pageHeaders(url)) }
//            Jsoup.parse(html, url)
        }
    }

    private fun parseItem(element: Element): AppAdapter.Item? {
        val anchor = element.selectFirst("a.stretched-link[href], a[itemprop=url][href], a[href^='/serial/'][href], a[href^='/film/'][href], a[href^='/movie/'][href]")
            ?: element.selectFirst("a[href]:not([href='/login'])")
            ?: return null
        val href = anchor.attr("href").trim()
        if (href.isBlank() || href.equals("/login", ignoreCase = true) || href.equals("login", ignoreCase = true)) return null

        val title = element.selectFirst("[itemprop=name], small.d-block, h2, h3, .card-body small, .card-body .text-truncate")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifBlank { anchor.attr("title").trim() }
        if (title.isBlank()) return null

        val poster = element.selectFirst("img")
            ?.attr("data-src")
            ?.ifBlank { element.selectFirst("img")?.attr("src") }
            ?.trim()
            .orEmpty()

        val id = href.removePrefix("/").substringBefore('?')
        val isShow = element.attr("itemtype").contains("TVSeries", ignoreCase = true) ||
                href.startsWith("/serial/")

        return if (isShow) {
            TvShow(id = id, title = title, poster = normalizeUrl(poster, "$baseUrl/$id"))
        } else {
            Movie(id = id, title = title, poster = normalizeUrl(poster, "$baseUrl/$id"))
        }
    }

    private fun parseListingItems(document: Document): List<AppAdapter.Item> {
        return document.select("article").mapNotNull(::parseItem).distinctBy {
            when (it) {
                is Movie -> "movie:${it.id}"
                is TvShow -> "show:${it.id}"
                else -> it.hashCode().toString()
            }
        }
    }

    private fun parseGenres(): List<Genre> {
        return listOf(
            Genre(id = "actiune", name = "Actiune"),
            Genre(id = "aventura", name = "Aventura"),
            Genre(id = "comedie", name = "Comedie"),
            Genre(id = "craciun", name = "Craciun"),
            Genre(id = "documentar", name = "Documentar"),
            Genre(id = "dragoste", name = "Dragoste"),
            Genre(id = "western", name = "Western"),
            Genre(id = "sport", name = "Sport"),
            Genre(id = "marvel-dc", name = "Marvel - DC"),
            Genre(id = "fara-subtitrare", name = "Fara Subtitrare"),
            Genre(id = "romanesti", name = "Romanesti"),
            Genre(id = "thriller", name = "Thriller"),
            Genre(id = "stiintifico-fantastic", name = "Stiintifico-Fantastic"),
            Genre(id = "biografie", name = "Biografie"),
            Genre(id = "groaza", name = "Groaza"),
            Genre(id = "drama", name = "Drama"),
            Genre(id = "muzical", name = "Muzical"),
            Genre(id = "mister", name = "Mister"),
            Genre(id = "istoric", name = "Istoric"),
        )
    }

    override suspend fun getHome(): List<Category> = providerMutex.withLock {
        val document = getDocument(baseUrl)
        val categories = mutableListOf<Category>()

        document.select("main section, section").forEach { section ->
            val heading = section.selectFirst("h1, h2, h3")?.text()?.trim().orEmpty()
            if (heading.isBlank()) return@forEach

            val items = section.select("article").mapNotNull(::parseItem)
            if (items.isNotEmpty()) {
                categories.add(Category(heading, items.take(12)))
            }
        }

        if (categories.isEmpty()) {
            val fallback = parseListingItems(document).take(12)
            if (fallback.isNotEmpty()) {
                categories.add(Category(Category.FEATURED, fallback))
            }
        }

        categories
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) return parseGenres()

        val encoded = URLEncoder.encode(query, "UTF-8")
        val pageSuffix = if (page > 1) "?page=$page" else ""
        return try {
            parseListingItems(getDocument("$baseUrl/cauta?q=$encoded$pageSuffix"))
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val pageSuffix = if (page > 1) "?page=$page" else ""
        return try {
            parseListingItems(getDocument("$baseUrl/filme$pageSuffix")).filterIsInstance<Movie>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val pageSuffix = if (page > 1) "?page=$page" else ""
        return try {
            parseListingItems(getDocument("$baseUrl/seriale$pageSuffix")).filterIsInstance<TvShow>()
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        if (id.equals("login", ignoreCase = true)) throw Exception("Found login id")
        val document = getDocument(normalizeUrl(id) ?: "$baseUrl/$id")
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.trim().orEmpty()
        val overview = document.selectFirst(".overview p, .overview, .container .col-md-7 .mb-4 p, .description p, .description")
            ?.text()
            ?.trim()

        return Movie(
            id = id.removePrefix("/"),
            title = title,
            overview = overview,
            poster = normalizeUrl(poster, "$baseUrl/$id"),
            genres = document.select("nav a[href^='/'], .breadcrumb-item a[href^='/']")
                .mapNotNull { anchor ->
                    val href = anchor.attr("href").trim().removePrefix("/")
                    if (href.isBlank() || href == "serial" || href == "filme") return@mapNotNull null
                    Genre(id = href, name = anchor.text().trim())
                }
                .distinctBy { it.id }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val document = getDocument(normalizeUrl(id) ?: "$baseUrl/$id")
        val title = document.selectFirst("h1")?.text()?.trim().orEmpty()
        val poster = document.selectFirst("meta[property='og:image']")?.attr("content")?.trim().orEmpty()
        val overview = document.selectFirst(".mb-4 p, .overview p, .overview")?.text()?.trim()

        val seasons = document.select("a[href*='/sezonul-']").mapNotNull { anchor ->
            val href = anchor.attr("href").trim().removePrefix("/")
            val number = Regex("""sezonul-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: anchor.text().trim().let { Regex("""(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?: return@mapNotNull null
            Season(
                id = "$id/sezonul-$number",
                number = number,
                title = "Sezonul $number",
            )
        }.distinctBy { it.id }.sortedBy { it.number }

        return TvShow(
            id = id.removePrefix("/"),
            title = title,
            overview = overview,
            poster = normalizeUrl(poster, "$baseUrl/$id"),
            genres = document.select("nav a[href^='/'], .breadcrumb-item a[href^='/']")
                .mapNotNull { anchor ->
                    val href = anchor.attr("href").trim().removePrefix("/")
                    if (href.isBlank() || href == "serial" || href == id.removePrefix("/")) return@mapNotNull null
                    Genre(id = href, name = anchor.text().trim())
                }
                .distinctBy { it.id },
            seasons = seasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        return try {
            val url = normalizeUrl(seasonId) ?: "$baseUrl/$seasonId"
            val document = getDocument(url)
            document.select("article, .episode-card, .card:has(a[href*='/episodul-'])").mapNotNull { element ->
                val anchor = element.selectFirst("a[href*='/episodul-']") ?: return@mapNotNull null
                val href = anchor.attr("href").trim().removePrefix("/")
                val title = element.selectFirst("h6, h5, h4, small")
                    ?.text()
                    ?.trim()
                    .orEmpty()
                val number = Regex("""episodul-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: title.let { Regex("""(\d+)""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                    ?: 0
                Episode(
                    id = href,
                    number = number,
                    title = element.selectFirst("small:last-of-type, small")?.text()?.trim().orEmpty().ifBlank { title },
                    poster = normalizeUrl(element.selectFirst("img")?.attr("data-src")
                        ?.ifBlank { element.selectFirst("img")?.attr("src") }, "$baseUrl/$href")
                )
            }.sortedBy { it.number }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val normalizedId = id.removePrefix("/")
        val pageSuffix = if (page > 1) "?page=$page" else ""
        return try {
            val document = getDocument("$baseUrl/$normalizedId$pageSuffix")
            val shows = parseListingItems(document).filterIsInstance<Show>()
            Genre(
                id = normalizedId,
                name = document.selectFirst("h1")?.text()?.trim().orEmpty().ifBlank {
                    normalizedId.replace("-", " ").replaceFirstChar { it.uppercase() }
                },
                shows = shows
            )
        } catch (_: Exception) {
            Genre(
                id = normalizedId,
                name = normalizedId.replace("-", " ").replaceFirstChar { it.uppercase() }
            )
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw Exception("xCinema does not expose people pages")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = normalizeUrl(id) ?: when (videoType) {
            is Video.Type.Movie -> "$baseUrl/${id.removePrefix("/")}"
            is Video.Type.Episode -> "$baseUrl/${id.removePrefix("/")}"
        }

        return try {
            val document = getDocument(url)
            val servers = mutableListOf<Video.Server>()

            val sourceElements = document.select("button.video-source-btn[data-src], button.video-source-btn, iframe[src], iframe[data-src], [data-src*='/embed/']")
            sourceElements.forEachIndexed { index, element ->
                val label = when {
                    element.tagName().equals("button", ignoreCase = true) -> element.text().trim()
                    element.hasAttr("title") -> element.attr("title").trim()
                    else -> element.text().trim()
                }.ifBlank {
                    when {
                        element.hasAttr("data-src") -> {
                            val rawSrc = element.attr("data-src").trim()
                            rawSrc.substringAfter("//").substringBefore("/").removePrefix("www.")
                                .substringBefore(".")
                                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        }
                        element.hasAttr("src") -> {
                            val rawSrc = element.attr("src").trim()
                            rawSrc.substringAfter("//").substringBefore("/").removePrefix("www.")
                                .substringBefore(".")
                                .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
                        }
                        else -> "xCinema ${index + 1}"
                    }
                }
                servers.add(
                    Video.Server(
                        id = url,
                        name = if (label.isBlank()) "xCinema" else label,
                        src = url
                    )
                )
            }

            if (servers.isEmpty()) {
                servers.add(
                    Video.Server(
                        id = url,
                        name = "xCinema",
                        src = url
                    )
                )
            }

            servers.distinctBy { it.src.ifBlank { it.id } }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load servers for $id", e)
            listOf(Video.Server(id = url, name = "xCinema", src = url))
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src.ifBlank { server.id }, server)
    }
}
