package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.HttpException
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

object FilmeOnlineUkProvider : Provider {

    override val name = "FilmeOnlineUK"
    override val baseUrl = "https://filmeonline.uk"
    override val logo = "https://i0.wp.com/filmeonline.uk/wp-content/uploads/2024/09/logonew.png?fit=458%2C77&ssl=1"
    override val language = "ro"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor { chain ->
                        chain.proceed(
                            chain.request().newBuilder()
                                .header("User-Agent", USER_AGENT)
                                .header("Referer", baseUrl)
                                .build()
                        )
                    }
                    .build()

                return Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(Service::class.java)
            }
        }

        @Headers("User-Agent: $USER_AGENT")
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    private val service = Service.build(baseUrl)
    private val tvShowPageCache = ConcurrentHashMap<String, Boolean>()

    private suspend fun getOptionalPage(url: String): Document? {
        return try {
            service.getPage(url)
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
        }
    }

    override suspend fun getHome(): List<Category> {
        return coroutineScope {
            val moviesDeferred = async { runCatching { getMovies(1) }.getOrDefault(emptyList()) }
            val tvShowsDeferred = async { runCatching { getTvShows(1) }.getOrDefault(emptyList()) }

            buildList {
                moviesDeferred.await().takeIf { it.isNotEmpty() }?.let {
                    add(Category("Filme recente", it.take(20)))
                }
                tvShowsDeferred.await().takeIf { it.isNotEmpty() }?.let {
                    add(Category("Serii recente", it.take(20)))
                }
            }
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            if (page > 1) return emptyList()
            return listOf(
                Genre(id = "$baseUrl/category/filme-online/", name = "Filme Online"),
                Genre(id = "$baseUrl/category/seriale-online/", name = "Seriale Online"),
                Genre(id = "$baseUrl/category/emisiuni-online/", name = "Emisiuni Online"),
                Genre(id = "$baseUrl/category/seriale-romanesti/", name = "Seriale Românești"),
            )
        }

        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = if (page <= 1) {
            "$baseUrl/?s=$encoded"
        } else {
            "$baseUrl/page/$page/?s=$encoded"
        }

        return getOptionalPage(url)
            ?.let { document ->
                parseArchiveItems(document.select("article.pb-grid-post"))
            } ?: emptyList()
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val url = if (page <= 1) {
            "$baseUrl/category/filme-online/"
        } else {
            "$baseUrl/category/filme-online/page/$page/"
        }

        return getOptionalPage(url)
            ?.let { document ->
                parseArchiveItems(document.select("article.pb-grid-post"))
                    .filterIsInstance<Movie>()
            } ?: emptyList()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val pageSize = 24

        return discoverTvShowCandidates(page)
            .take(pageSize)
            .mapNotNull { seriesUrl ->
                runCatching {
                    val document = service.getPage(seriesUrl)
                    val poster = extractPoster(document)
                    val seasons = parseSeasons(document, seriesUrl, poster)
                    if (seasons.isEmpty()) null else buildTvShow(document, seriesUrl, seasons, poster)
                }.getOrNull()
            }
    }

    override suspend fun getMovie(id: String): Movie {
        val document = service.getPage(normalizeUrl(id))
        val pageTitle = extractTitle(document)
        val poster = extractPoster(document)
        val overview = extractOverview(document)

        return Movie(
            id = normalizeUrl(id),
            title = pageTitle,
            poster = poster,
            banner = poster,
            overview = overview,
            released = extractPublishedYear(document),
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val pageUrl = normalizeUrl(id)
        val document = service.getPage(pageUrl)
        return buildTvShow(document, pageUrl)
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val pageUrl = seasonId.substringBefore("#").ifBlank { seasonId }
        val seasonNumber = seasonId.substringAfter("#season-", missingDelimiterValue = "")
            .toIntOrNull()

        val document = service.getPage(pageUrl)
        return parseEpisodes(document, pageUrl, seasonNumber = seasonNumber, fallbackPoster = extractPoster(document))
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = when {
            id.startsWith("http") && page <= 1 -> id
            id.startsWith("http") -> "${id.trimEnd('/')}/page/$page/"
            page <= 1 -> "$baseUrl/category/${id.trim('/')}/"
            else -> "$baseUrl/category/${id.trim('/')}/page/$page/"
        }

        val fallbackName = id.substringAfterLast('/').replace('-', ' ').replace('_', ' ')
        val document = getOptionalPage(url) ?: return Genre(id = id, name = fallbackName, shows = emptyList())
        val shows = parseArchiveItems(document.select("article.pb-grid-post")).filterIsInstance<TvShow>()
        val name = document.selectFirst("h1.pb-archv-title, h1.entry-title, h1")?.text()?.trim()
            ?: fallbackName

        return Genre(id = id, name = name, shows = shows)
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val url = when {
            id.startsWith("http") && page <= 1 -> id
            id.startsWith("http") -> "${id.trimEnd('/')}/page/$page/"
            page <= 1 -> "$baseUrl/author/${id.trim('/')}/"
            else -> "$baseUrl/author/${id.trim('/')}/page/$page/"
        }

        val fallbackName = URLDecoder.decode(id.substringAfterLast('/'), "UTF-8").replace('+', ' ')
        val document = getOptionalPage(url) ?: return People(id = id, name = fallbackName)
        val name = document.selectFirst("h1.pb-archv-title, h1.entry-title, h1")?.text()?.trim()
            ?: fallbackName

        return People(
            id = id,
            name = name,
            filmography = parseArchiveItems(document.select("article.pb-grid-post")).filterIsInstance<TvShow>()
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val document = service.getPage(normalizeUrl(id))
        return document.select(".entry-content iframe[src], .entry-content iframe[data-src]").mapNotNull { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() }
                ?: iframe.attr("data-src").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null

            val finalUrl = normalizeUrl(src)
            if (finalUrl.isBlank()) return@mapNotNull null

            val host = runCatching {
                finalUrl.substringAfter("://").substringBefore("/").substringBefore("?")
                    .removePrefix("www.")
                    .substringBefore(".")
                    .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }.getOrNull().orEmpty().ifBlank { "Server" }

            Video.Server(
                id = finalUrl,
                name = host,
                src = finalUrl
            )
        }.distinctBy { it.src }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src, server)
    }

    private suspend fun parseArchiveItems(
        elements: List<Element>
    ): List<AppAdapter.Item> {
        return coroutineScope {
            elements.map { element ->
                async { parseArchiveItem(element) }
            }.awaitAll().filterNotNull()
        }
    }

    private suspend fun parseArchiveItem(
        element: Element
    ): AppAdapter.Item? {
        val href = element.selectFirst("a[href].post-thumbnail, h2.entry-title a[href]")?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val title = element.selectFirst("h2.entry-title a, h1.entry-title, img[alt]")?.text()?.trim()
            ?.ifBlank { null }
            ?: element.selectFirst("img[alt]")?.attr("alt")?.trim().orEmpty()

        val poster = element.selectFirst("img.wp-post-image, img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?.let(::normalizeUrl)

        return when {
            isTvShowTitle(title, href) -> TvShow(
                id = href,
                title = title,
                poster = poster,
                banner = poster
            )

            isEpisodeTitle(title, href) -> TvShow(
                id = episodeSeriesUrl(href),
                title = title,
                poster = poster,
                banner = poster
            )

            isTvShowPage(href, title) -> TvShow(
                id = href,
                title = title,
                poster = poster,
                banner = poster
            )

            else -> Movie(
                id = href,
                title = title,
                poster = poster,
                banner = poster
            )
        }
    }

    private suspend fun isTvShowPage(href: String, title: String): Boolean {
        val cached = tvShowPageCache[href]
        if (cached != null) return cached

        val result = runCatching {
            val document = getOptionalPage(href) ?: return@runCatching false
            parseSeasons(document, href, extractPoster(document)).isNotEmpty() ||
                document.select(".entry-content figure.wp-block-table a[href*='episodul-']").isNotEmpty() ||
                title.contains("sezonul", ignoreCase = true) ||
                title.contains("episodul", ignoreCase = true)
        }.getOrDefault(false)

        tvShowPageCache[href] = result
        return result
    }

    private fun isEpisodeTitle(title: String, href: String): Boolean {
        val lowerTitle = title.lowercase()
        val lowerHref = href.lowercase()
        return lowerHref.contains("episodul-") || lowerTitle.contains("episodul")
    }

    private fun buildEpisodeSeriesCandidates(href: String): List<String> {
        val normalized = normalizeUrl(href)
        val path = normalized
            .removePrefix(baseUrl)
            .trim('/')
            .substringBefore('?')

        val slug = path.substringAfterLast('/')
        val baseSlug = slug
            .replace(Regex("""(?i)-sezonul-\d+.*$"""), "")
            .replace(Regex("""(?i)-episodul-\d+.*$"""), "")
            .trim('-', ' ')
            .trim()

        return buildList {
            add(normalized)
            if (baseSlug.isNotBlank() && baseSlug != slug) {
                add("$baseUrl/$baseSlug/")
            }
        }.distinct()
    }

    private fun episodeSeriesUrl(href: String): String {
        return buildEpisodeSeriesCandidates(href).lastOrNull() ?: normalizeUrl(href)
    }

    private suspend fun discoverTvShowCandidates(page: Int): List<String> = coroutineScope {
        val archiveUrls = listOf(
            buildArchiveUrl("seriale-online", page),
            buildArchiveUrl("seriale-romanesti", page),
            buildArchiveUrl("emisiuni-online", page),
        )

        archiveUrls
            .map { url ->
                async {
                    val document = getOptionalPage(url) ?: return@async emptyList()
                    document.select("a[href]")
                        .mapNotNull { anchor ->
                            val href = anchor.attr("href").trim()
                            when {
                                href.isBlank() -> null
                                href.startsWith("$baseUrl/category/") -> null
                                href.startsWith("$baseUrl/tag/") -> null
                                href.startsWith("$baseUrl/author/") -> null
                                href.startsWith("$baseUrl/page/") -> null
                                href == "$baseUrl/" -> null
                                href == "$baseUrl/contact/" -> null
                                href == "$baseUrl/disclaimer/" -> null
                                else -> normalizeUrl(href)
                            }
                        }
                }
            }
            .flatMap { it.await() }
            .distinct()
    }

    private fun buildArchiveUrl(slug: String, page: Int): String {
        return if (page <= 1) {
            "$baseUrl/category/$slug/"
        } else {
            "$baseUrl/category/$slug/page/$page/"
        }
    }

    private fun buildTvShow(
        document: Document,
        pageUrl: String,
        seasons: List<Season>? = null,
        poster: String? = extractPoster(document)
    ): TvShow {
        val pageTitle = extractTitle(document)
        val overview = extractOverview(document)
        val parsedSeasons = seasons ?: parseSeasons(document, pageUrl, poster)

        return TvShow(
            id = pageUrl,
            title = pageTitle,
            poster = poster,
            banner = poster,
            overview = overview,
            released = extractPublishedYear(document),
            seasons = parsedSeasons.ifEmpty {
                listOf(
                    Season(
                        id = "$pageUrl#season-1",
                        number = 1,
                        title = "Sezonul 1",
                        poster = poster,
                        episodes = parseEpisodes(document, pageUrl, seasonNumber = 1, fallbackPoster = poster)
                    )
                )
            }
        )
    }

    private fun isTvShowTitle(title: String, href: String): Boolean {
        val lowerTitle = title.lowercase()
        val lowerHref = href.lowercase()
        return lowerHref.contains("/seriale-online/") ||
            lowerHref.contains("/seriale-romanesti/") ||
            lowerHref.contains("/emisiuni-online/") ||
            lowerTitle.contains("episodul") ||
            lowerTitle.contains("sezonul")
    }

    private fun parseSeasons(document: Document, pageUrl: String, poster: String?): List<Season> {
        val grouped = document.select(".entry-content figure.wp-block-table a[href*='episodul-']")
            .mapNotNull { link ->
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val seasonNumber = parseSeasonNumber(href) ?: return@mapNotNull null
                val episodeNumber = parseEpisodeNumber(href) ?: return@mapNotNull null
                Triple(seasonNumber, episodeNumber, href)
            }
            .groupBy({ it.first }, { it.second to it.third })

        return grouped.toSortedMap().map { (seasonNumber, episodes) ->
            Season(
                id = "$pageUrl#season-$seasonNumber",
                number = seasonNumber,
                title = "Sezonul $seasonNumber",
                poster = poster,
                episodes = episodes
                    .sortedBy { it.first }
                    .map { (_, href) ->
                        Episode(
                            id = href,
                            number = parseEpisodeNumber(href) ?: 1,
                            title = extractEpisodeTitle(href),
                            poster = poster
                        )
                    }
            )
        }
    }

    private fun parseEpisodes(
        document: Document,
        pageUrl: String,
        seasonNumber: Int?,
        fallbackPoster: String?
    ): List<Episode> {
        val links = document.select(".entry-content figure.wp-block-table a[href*='episodul-']")
            .mapNotNull { link ->
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val hrefSeason = parseSeasonNumber(href) ?: return@mapNotNull null
                if (seasonNumber != null && hrefSeason != seasonNumber) return@mapNotNull null
                val episodeNumber = parseEpisodeNumber(href) ?: return@mapNotNull null
                Episode(
                    id = href,
                    number = episodeNumber,
                    title = link.text().trim().ifBlank { extractEpisodeTitle(href) },
                    poster = fallbackPoster
                )
            }

        if (links.isNotEmpty()) return links

        return listOf(
            Episode(
                id = pageUrl,
                number = 1,
                title = extractTitle(document),
                poster = fallbackPoster
            )
        )
    }

    private fun extractTitle(document: Document): String {
        return document.selectFirst("article.pb-singular h1.entry-title, article.pb-singular h1, h1.entry-title, h1")
            ?.text()
            ?.trim()
            .orEmpty()
    }

    private fun extractPoster(document: Document): String? {
        return document.selectFirst("article.pb-singular img.wp-post-image, .post-thumbnail img.wp-post-image, .post-thumbnail img")
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizeUrl)
    }

    private fun extractOverview(document: Document): String? {
        return document.select(".entry-content p")
            .map { it.text().trim() }
            .firstOrNull { it.isNotBlank() && !it.startsWith("Sursa", ignoreCase = true) && !it.startsWith("Urmariti", ignoreCase = true) }
    }

    private fun extractPublishedYear(document: Document): String? {
        return document.selectFirst("time.entry-date")
            ?.attr("datetime")
            ?.substring(0, 4)
    }

    private fun parseSeasonNumber(url: String): Int? {
        return Regex("""sezonul-(\d+)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun parseEpisodeNumber(url: String): Int? {
        return Regex("""episodul-(\d+)""", RegexOption.IGNORE_CASE)
            .find(url)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    }

    private fun extractEpisodeTitle(url: String): String {
        return url.substringAfterLast('/')
            .replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeUrl(url: String?): String {
        val raw = url?.trim().orEmpty()
        return when {
            raw.isBlank() -> ""
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            raw.startsWith("//") -> "https:$raw"
            else -> "$baseUrl/${raw.removePrefix("/")}"
        }
    }
}
