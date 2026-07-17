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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

object SerialeROProvider : Provider {

    override val name = "SerialeRO"
    override val baseUrl = "https://serialero.net/"
    override val logo = "${baseUrl}favicon.png"
    override val language = "ro"

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    private interface SerialeROService {
        @Headers("User-Agent: $USER_AGENT")
        @GET
        suspend fun getPage(@Url url: String): Document

        companion object {
            fun build(baseUrl: String): SerialeROService {
                val client = OkHttpClient.Builder()
                    .dns(DnsResolver.doh)
                    .cookieJar(com.streamflixreborn.streamflix.utils.NetworkClient.cookieJar)
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
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
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                    .create(SerialeROService::class.java)
            }
        }
    }

    private val service by lazy { SerialeROService.build(baseUrl) }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val movies = async { loadSection("${baseUrl}genre-movies/movies", "Filme") }
        val shows = async { loadSection("${baseUrl}genre-tv/tvshows", "Seriale") }
        val romanianMovies = async { loadSection("${baseUrl}gen-filme/filme", "Filme Românești") }
        val romanianShows = async { loadSection("${baseUrl}gen-seriale/seriale", "Seriale Românești") }

        listOfNotNull(
            movies.await(),
            shows.await(),
            romanianMovies.await(),
            romanianShows.await()
        )
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre(id = "genre-tv/tvshows", name = "Seriale"),
                Genre(id = "gen-seriale/seriale", name = "Seriale Românești"),
                Genre(id = "genre-tv/turkey", name = "Seriale Turcești"),
                Genre(id = "genre-tv/spain", name = "Seriale Spaniole"),
                Genre(id = "genre-tv/korea", name = "Seriale Coreene"),
                Genre(id = "genre-tv/china", name = "Seriale Chinezești"),
                Genre(id = "genre-tv/india", name = "Seriale Indiene"),
                Genre(id = "genre-movies/movies", name = "Filme"),
                Genre(id = "gen-filme/filme", name = "Filme Românești")
            )
        }

        val url = buildPagedUrl("${baseUrl}cautare.php?search=${encodeQuery(query)}", page)
        val document = service.getPage(url)
        return parseCards(document)
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val document = service.getPage(buildPagedUrl("${baseUrl}genre-movies/movies", page))
        return parseCards(document).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val document = service.getPage(buildPagedUrl("${baseUrl}genre-tv/tvshows", page))
        return parseCards(document).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie {
        val url = absoluteUrl(id)
        val document = service.getPage(url)
        val title = document.selectFirst("h1.mb-4")?.ownText()?.trim().orEmpty()
        val poster = normalizeImageUrl(document.selectFirst(".col-lg-3 img")?.attr("src"))
        val overview = document.selectFirst(".overwz")?.text()?.trim()
        val (genres, runtime, released) = parseMetadata(document)

        return Movie(
            id = url,
            title = title,
            overview = overview,
            runtime = runtime,
            trailer = document.selectFirst("iframe[src*='/zsrv/movie_trailer']")?.attr("src")
                ?.let { absoluteUrl(it) },
            quality = parseQuality(document),
            poster = poster,
            banner = poster,
            genres = genres,
            released = released,
            recommendations = parseRecommendations(document, movie = true)
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val url = absoluteUrl(id)
        val document = service.getPage(url)
        val title = document.selectFirst("h1.mb-4")?.ownText()?.trim().orEmpty()
        val poster = normalizeImageUrl(document.selectFirst(".col-lg-3 img")?.attr("src"))
        val overview = document.selectFirst(".overwz")?.text()?.trim()
        val (genres, runtime, released) = parseMetadata(document)
        val seasons = parseSeasons(document, url, poster)

        return TvShow(
            id = url,
            title = title,
            overview = overview,
            runtime = runtime,
            trailer = document.selectFirst("iframe[src*='/zsrv/movie_trailer']")?.attr("src")
                ?.let { absoluteUrl(it) },
            quality = parseQuality(document),
            poster = poster,
            banner = poster,
            genres = genres,
            released = released,
            seasons = seasons,
            recommendations = parseRecommendations(document, movie = false)
        )
    }

    internal fun parseSeasons(document: Document, pageUrl: String, poster: String?): List<Season> {
        val explicitSeasons = document.select(".sznott a[href]").mapNotNull { anchor ->
            val href = resolveUrl(pageUrl, anchor.attr("href"))
            val seasonNumber = findSeasonNumber(anchor.text(), href)
                ?: return@mapNotNull null

            Season(
                id = href,
                number = seasonNumber,
                title = "S$seasonNumber",
                poster = poster
            )
        }.distinctBy { it.id }.sortedBy { it.number }

        if (explicitSeasons.isNotEmpty()) return explicitSeasons

        // Some pages only expose one playable episode and omit the season navigation entirely.
        val seasonNumber = findSeasonNumber(
            pageUrl,
            document.title(),
            document.select("h1.mb-4, h6.ccc, h6.section-title").text(),
            document.selectFirst("input#season[value]")?.attr("value").orEmpty()
        )
            ?: findEmbeddedEpisodes(document, pageUrl).firstOrNull()?.season
            ?: 1
        return listOf(
            Season(
                id = pageUrl,
                number = seasonNumber,
                title = "S$seasonNumber",
                poster = poster
            )
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val url = seasonId
        val document = service.getPage(url)
        return parseEpisodes(document, url)
    }

    internal fun parseEpisodes(document: Document, pageUrl: String): List<Episode> {
        val embeddedEpisodes = findEmbeddedEpisodes(document, pageUrl)
        val seasonNumber = findSeasonNumber(
            pageUrl,
            document.select("h1, h2, h3, h6, title").text()
        ) ?: embeddedEpisodes.firstOrNull()?.season ?: 1

        val episodeUrls = Regex("""["'](\.\./zsrv/srv1eps\?search=[^"']+)["']""")
            .findAll(document.html())
            .mapNotNull { match ->
                match.groupValues.getOrNull(1)?.let { absoluteUrl(it) }
            }
            .toList()

        val episodes = document.select(".col-lg-3 a[onclick^=change]").mapNotNull { anchor ->
            val episodeNumber = anchor.attr("id").toIntOrNull()
                ?: Regex("""change\((\d+)\)""", RegexOption.IGNORE_CASE)
                    .find(anchor.attr("onclick"))
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()
                ?: return@mapNotNull null

            val episodeUrl = episodeUrls.getOrNull(episodeNumber - 1) ?: return@mapNotNull null

            Episode(
                id = episodeUrl,
                number = episodeNumber,
                title = anchor.text().replace(Regex("\\s+"), " ").trim().ifBlank {
                    "S$seasonNumber - Episodul $episodeNumber"
                },
                poster = normalizeImageUrl(document.selectFirst(".col-lg-3 img")?.attr("src"))
            )
        }.sortedBy { it.number }

        if (episodes.isNotEmpty()) return episodes

        val matchingEmbeddedEpisodes = embeddedEpisodes.filter { it.season == seasonNumber }
        if (matchingEmbeddedEpisodes.isNotEmpty()) {
            val poster = normalizeImageUrl(document.selectFirst(".col-lg-3 img")?.attr("src"))
            return matchingEmbeddedEpisodes.map { embedded ->
                Episode(
                    id = embedded.url,
                    number = embedded.episode,
                    title = "S$seasonNumber - Episodul ${embedded.episode}",
                    poster = poster
                )
            }
        }

        val poster = normalizeImageUrl(document.selectFirst(".col-lg-3 img")?.attr("src"))
        return episodeUrls.mapIndexed { index, episodeUrl ->
            Episode(
                id = episodeUrl,
                number = index + 1,
                title = "S$seasonNumber - Episodul ${index + 1}",
                poster = poster
            )
        }
    }

    private data class EmbeddedEpisode(val url: String, val season: Int, val episode: Int)

    private fun findEmbeddedEpisodes(document: Document, pageUrl: String): List<EmbeddedEpisode> {
        return document.select("iframe[src]").mapNotNull { iframe ->
            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val match = Regex("""(?:^|/)s(\d+)e(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
                .find(src)
                ?: return@mapNotNull null
            val season = match.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val episode = match.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            EmbeddedEpisode(resolveUrl(pageUrl, src), season, episode)
        }.distinctBy { it.url }
    }

    private fun findSeasonNumber(vararg values: String): Int? {
        val patterns = listOf(
            Regex("""(?:Sezonul|Season)\s*[-:#]?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\bS(\d+)\b""", RegexOption.IGNORE_CASE),
            Regex("""-s(\d+)(?:[/?#]|$)""", RegexOption.IGNORE_CASE)
        )
        return values.firstNotNullOfOrNull { value ->
            patterns.firstNotNullOfOrNull { pattern ->
                pattern.find(value)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val url = buildGenreUrl(id, page)
        val document = service.getPage(url)
        val name = document.selectFirst("h1, h2, h6.section-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: id.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.uppercaseChar() }

        return Genre(
            id = id,
            name = name,
            shows = parseCards(document).map { it as com.streamflixreborn.streamflix.models.Show }
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        return People(id = id, name = absoluteUrl(id))
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val url = absoluteUrl(id)
        val document = fetchServerDocument(url)
        val html = document.html()

        val labels = document.select("a[onclick^=change]").associate { anchor ->
            anchor.attr("id").ifBlank { anchor.text().trim() } to anchor.text().replace("SERVER -", "").trim()
        }
        val sources = linkedMapOf<String, String>()

        Regex("case\\s+(\\d+):\\s*src\\s*=\\s*([^;]+);", RegexOption.IGNORE_CASE)
            .findAll(html)
            .forEach { match ->
                val key = match.groupValues[1]
                val source = match.groupValues[2]
                    .trim()
                    .trim('"', '\'')
                if (source.isNotBlank()) {
                    sources[key] = resolveUrlPreservingFragment(url, source)
                }
            }

        if (sources.isEmpty()) {
            extractSequentialServerSources(html).forEachIndexed { index, source ->
                sources[(index + 1).toString()] = source
            }
        }

        return sources.mapNotNull { (key, src) ->
            val label = labels[key].orEmpty().ifBlank { "Server $key" }
            Video.Server(
                id = src,
                name = label,
                src = src
            )
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return Extractor.extract(server.src.ifBlank { server.id }, server)
    }

    private suspend fun loadSection(url: String, name: String): Category? {
        val document = service.getPage(url)
        val items = parseCards(document)
        return items.takeIf { it.isNotEmpty() }?.let { Category(name, it) }
    }

    private fun parseCards(document: Document): List<AppAdapter.Item> {
        return document.select("div.col-lg-2.col-md-3 a[href], div.col-lg-1.zzer a[href]").mapNotNull { anchor ->
            val href = absoluteUrl(anchor.attr("href"))
            val card = anchor.selectFirst(".package-item") ?: anchor
            val title = card.selectFirst(".titlez h5, h5, h3")?.text()?.trim().orEmpty()
            val poster = normalizeImageUrl(card.selectFirst("img")?.attr("src"))
            val year = card.text()
                .let { text ->
                    Regex("""(?:An|Year)[:\s]+(\d{4})""", RegexOption.IGNORE_CASE)
                        .find(text)
                        ?.groupValues
                        ?.getOrNull(1)
                }
            when {
                isTvShowUrl(href) -> TvShow(
                    id = href,
                    title = extractTitle(title, href),
                    poster = poster,
                    banner = poster,
                    released = year
                )
                isMovieUrl(href) -> Movie(
                    id = href,
                    title = extractTitle(title, href),
                    poster = poster,
                    banner = poster,
                    released = year
                )
                else -> null
            }
        }.distinctBy { item ->
            when (item) {
                is Movie -> item.id
                is TvShow -> item.id
                else -> item.hashCode().toString()
            }
        }
    }

    private fun parseRecommendations(document: Document, movie: Boolean): List<com.streamflixreborn.streamflix.models.Show> {
        val selector = if (movie) ".container-xxl.py-5 .row.g-4.justify-content-center .package-item" else ".container-xxl.py-5 .row.g-4.justify-content-center .package-item"
        return document.select(selector).mapNotNull { card ->
            val link = card.selectFirst("a[href]")?.attr("href") ?: return@mapNotNull null
            val url = absoluteUrl(link)
            val title = card.selectFirst(".titlez h5, h5, h3")?.text()?.trim().orEmpty()
            val poster = normalizeImageUrl(card.selectFirst("img")?.attr("src"))
            when {
                isTvShowUrl(url) -> TvShow(id = url, title = title, poster = poster, banner = poster)
                isMovieUrl(url) -> Movie(id = url, title = title, poster = poster, banner = poster)
                else -> null
            }
        }
    }

    private fun parseMetadata(document: Document): Triple<List<Genre>, Int?, String?> {
        val genres = document.select(".row.gy-2.gx-4.mb-4 .fa-film")
            .mapNotNull { row ->
                val text = row.parent()?.text().orEmpty()
                val genreText = text.substringAfter("Gen:").trim()
                    .takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                genreText.split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
            }
            .flatten()
            .distinct()
            .map { genre ->
                Genre(id = genre.lowercase().replace(" ", "-"), name = genre)
            }

        val runtime = document.selectFirst(".fa-clock")?.parent()?.text()
            ?.substringAfter("Durata:")
            ?.substringBefore("Quality:")
            ?.trim()
            ?.let { parseRuntimeMinutes(it) }

        val released = document.selectFirst(".fa-globe")?.parent()?.text()
            ?.substringAfter("An:")
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        return Triple(genres, runtime, released)
    }

    private fun parseRuntimeMinutes(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val hours = Regex("""(\d+)\s*h""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val minutes = Regex("""(\d+)\s*min""", RegexOption.IGNORE_CASE).find(value)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val total = hours * 60 + minutes
        return total.takeIf { it > 0 }
    }

    private fun parseQuality(document: Document): String? {
        val qualityRow = document.selectFirst(".fa-arrow-right")?.parent() ?: return null
        return qualityRow.selectFirst("strong, .ccc, span")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildGenreUrl(id: String, page: Int): String {
        val base = absoluteUrl(id)
        return if (page <= 1) base else {
            when {
                base.contains('?') -> "$base&page=$page"
                else -> "$base?page=$page"
            }
        }
    }

    private fun buildPagedUrl(base: String, page: Int): String {
        if (page <= 1) return base
        return when {
            base.contains('?') -> "$base&page=$page"
            else -> "$base?page=$page"
        }
    }

    private fun resolveUrl(referenceUrl: String, href: String): String {
        if (href.isBlank()) return referenceUrl
        if (href.startsWith("http://", true) || href.startsWith("https://", true)) return href.substringBefore("#")
        if (href.startsWith("//")) return "https:$href".substringBefore("#")

        return runCatching {
            java.net.URI(referenceUrl).resolve(href).toString()
        }.getOrElse {
            when {
                href.startsWith("/") -> baseUrl.trimEnd('/') + href
                referenceUrl.endsWith("/") -> referenceUrl + href.removePrefix("./")
                else -> referenceUrl.substringBeforeLast('/') + "/" + href.removePrefix("./")
            }
        }.substringBefore("#")
    }

    private fun resolveUrlPreservingFragment(referenceUrl: String, href: String): String {
        if (href.isBlank()) return referenceUrl
        if (href.startsWith("http://", true) || href.startsWith("https://", true)) return href
        if (href.startsWith("//")) return "https:$href"

        return runCatching {
            java.net.URI(referenceUrl).resolve(href).toString()
        }.getOrElse {
            when {
                href.startsWith("/") -> baseUrl.trimEnd('/') + href
                referenceUrl.endsWith("/") -> referenceUrl + href.removePrefix("./")
                else -> referenceUrl.substringBeforeLast('/') + "/" + href.removePrefix("./")
            }
        }
    }

    private fun encodeQuery(query: String): String {
        return java.net.URLEncoder.encode(query, Charsets.UTF_8.name())
    }

    private fun absoluteUrl(url: String): String {
        if (url.isBlank()) return baseUrl
        return when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("../") -> baseUrl.trimEnd('/') + "/" + url.removePrefix("../")
            url.startsWith("/") -> baseUrl.trimEnd('/') + url
            else -> baseUrl.trimEnd('/') + "/" + url.removePrefix("./")
        }.substringBefore("#")
    }

    private fun normalizeImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return absoluteUrl(url)
    }

    private suspend fun fetchServerDocument(pageUrl: String): Document {
        val document = service.getPage(pageUrl)
        val iframeUrl = document.selectFirst("iframe[src*='movie_srv'], iframe[src*='/zsrv/']")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveUrl(pageUrl, it) }

        return if (iframeUrl != null) {
            service.getPage(iframeUrl)
        } else {
            document
        }
    }

    private fun extractSequentialServerSources(html: String): List<String> {
        val arrayMatch = Regex(
            """var\s+_0x[a-f0-9]+\s*=\s*\[(.*?)\]\s*;""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).find(html)

        val arrayBody = arrayMatch?.groupValues?.getOrNull(1)
            ?: return emptyList()

        val values = Regex("""["']([^"']+)["']""")
            .findAll(arrayBody)
            .mapNotNull { match ->
                val value = match.groupValues.getOrNull(1)?.trim().orEmpty()
                when {
                    value.isBlank() -> null
                    value.equals("error!", ignoreCase = true) -> null
                    value.equals("log", ignoreCase = true) -> null
                    value.startsWith("http://", true) || value.startsWith("https://", true) -> value
                    value.startsWith("//") -> "https:$value"
                    value.startsWith("../") || value.startsWith("./") || value.startsWith("/") -> resolveUrlPreservingFragment(baseUrl, value)
                    value.contains("/zsrv/", ignoreCase = true) -> resolveUrlPreservingFragment(baseUrl, value)
                    else -> null
                }
            }
            .distinct()
            .toList()

        return values
    }

    private fun isMovieUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("/film-romanesc/") ||
                normalized.contains("/subtitrat-in-romana/film-") ||
                normalized.contains("/gen-filme/") ||
                normalized.contains("/genre-movies/") ||
                normalized.contains("/dublate/")
    }

    private fun isTvShowUrl(url: String): Boolean {
        val normalized = url.lowercase()
        return normalized.contains("/seriale-online/") ||
                normalized.contains("/seriale-subtitrate-in-romana/") ||
                normalized.contains("/gen-seriale/") ||
                normalized.contains("/genre-tv/") ||
                Regex("""-s\d+$""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    }

    private fun extractTitle(title: String, href: String): String {
        return title.ifBlank {
            href.substringAfterLast('/').replace('-', ' ').trim().replaceFirstChar { it.uppercaseChar() }
        }
    }
}
