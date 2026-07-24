package com.streamflixreborn.streamflix.providers

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
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
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit

/**
 * M4uHD Movies is a server-rendered site. Keep this provider deliberately
 * limited to parsing the HTML pages; its player buttons contain opaque values
 * for client-side AJAX requests rather than playable URLs.
 */
object M4UhdMoviesProvider : Provider {
    private const val URL = "https://m4uhdmovies.net/"

    override val baseUrl = URL
    override val name = "M4uHD Movies"
    override val logo = "${URL}images/logo-index.png"
    override val language = "en"

    private val service = Service.build()

    override suspend fun getHome(): List<Category> {
        val document = service.getHome()
        return document.select("h2 > a.header-title, h3 > a.header-title")
            .mapNotNull { heading ->
                val row = heading.parent()?.nextElementSibling()
                    ?.takeIf { it.hasClass("row") } ?: return@mapNotNull null
                Category(heading.text().trim(), row.select("div.item").map(::parseListingItem))
            }
            .filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank() || page > 1) return emptyList()
        return service.search(query.toSlug()).select("div.item").map(::parseListingItem)
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        service.getMovies(pagePath("movies", page)).select("div.item").map(::parseListingItem).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        service.getTvShows(pagePath("tvseries", page)).select("div.item").map(::parseListingItem).filterIsInstance<TvShow>()

    override suspend fun getMovie(id: String): Movie = parseMovie(service.getPage(id), id)

    override suspend fun getTvShow(id: String): TvShow = parseTvShow(service.getPage(id), id)

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (showId, seasonNumber) = seasonId.split('|', limit = 2).let {
            it.first() to it.getOrNull(1)?.toIntOrNull()
        }
        val season = service.getPage(showId).select("div.season").firstOrNull { element ->
            seasonNumber == element.selectFirst("p")?.text()?.substringAfterLast(":")?.trim()?.toIntOrNull()
        } ?: return emptyList()

        return season.select("button.episode").map { episode ->
            val match = EPISODE_PATTERN.find(episode.text())
            Episode(
                id = episode.attr("idepisode"),
                number = match?.groupValues?.get(2)?.toIntOrNull() ?: 0,
                title = episode.text().trim(),
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val document = service.getPage(if (page == 1) id else "$id/page/$page")
        return Genre(
            id = id,
            name = document.selectFirst("h1.header-title")?.text()?.trim().orEmpty(),
            shows = document.select("div.item").map(::parseListingItem).filterIsInstance<Show>(),
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val document = service.getPage(if (page == 1) "search/$id" else "search/$id/page/$page")
        return People(
            id = id,
            name = document.selectFirst("h1.header-title")?.text()?.trim().orEmpty(),
            filmography = document.select("div.item").map(::parseListingItem).filterIsInstance<Show>(),
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val document = service.getPage(id.substringBefore('|'))
        val directLinks = document.select("iframe[src], video source[src], source[src]")
            .mapNotNull { it.absUrl("src").takeIf(String::isNotBlank) }
            .distinct()
        return directLinks.mapIndexed { index, link ->
            Video.Server(id = link, name = "Server ${index + 1}", src = link)
        }
    }

    override suspend fun getVideo(server: Video.Server): Video =
        Extractor.extract(server.src.ifBlank { server.id }, server)

    private fun parseListingItem(item: Element): AppAdapter.Item {
        val link = item.selectFirst("a[href]")
        val id = link?.attr("href").orEmpty().removePrefix("/")
        val title = link?.attr("title")?.trim().takeUnless { it.isNullOrBlank() }
            ?: item.selectFirst(".title-mv")?.text()?.trim().orEmpty()
        val poster = item.selectFirst("img.imagecover")?.absUrl("src")
            ?.takeIf(String::isNotBlank)
        val quality = item.selectFirst(".quality")?.text()?.trim()
        val year = YEAR_PATTERN.find(title)?.value
        val episode = EPISODE_PATTERN.find(quality.orEmpty())

        return if (episode != null || id.contains("--")) {
            TvShow(id = id, title = title, released = year, quality = quality, poster = poster,
                seasons = episode?.let { listOf(Season(number = it.groupValues[1].toInt(), episodes = listOf(Episode(number = it.groupValues[2].toInt())))) }
                    ?: emptyList())
        } else {
            Movie(id = id, title = title, released = year, quality = quality, poster = poster)
        }
    }

    private fun parseMovie(document: Document, id: String): Movie = Movie(
        id = id,
        title = detailTitle(document),
        overview = document.selectFirst("p.movies_plot")?.text()?.trim(),
        released = detailValue(document, "Release date"),
        runtime = detailValue(document, "Runtime")?.removeSuffix("min")?.trim()?.toIntOrNull(),
        quality = detailValue(document, "Quality"),
        poster = document.selectFirst("img.movies_cover")?.absUrl("src"),
        banner = document.selectFirst("img.movies_cover")?.absUrl("src"),
        genres = detailLinks(document, "Genre").map { Genre(it.first, it.second) },
        directors = detailLinks(document, "Director").map { People(id = it.first, name = it.second) },
        cast = detailLinks(document, "Starring").map { People(id = it.first, name = it.second) },
    )

    private fun parseTvShow(document: Document, id: String): TvShow {
        val seasons = document.select("div.season").mapNotNull { season ->
            val number = season.selectFirst("p")?.text()?.substringAfterLast(":")?.trim()?.toIntOrNull() ?: return@mapNotNull null
            Season(
                id = "$id|$number",
                number = number,
                episodes = season.select("button.episode").map { button ->
                    Episode(id = button.attr("idepisode"), number = EPISODE_PATTERN.find(button.text())?.groupValues?.get(2)?.toIntOrNull() ?: 0)
                },
            )
        }
        return TvShow(
            id = id,
            title = detailTitle(document),
            overview = document.selectFirst("p.movies_plot")?.text()?.trim(),
            released = detailValue(document, "Release date"),
            runtime = detailValue(document, "Runtime")?.removeSuffix("min")?.trim()?.toIntOrNull(),
            poster = document.selectFirst("img.movies_cover")?.absUrl("src"),
            banner = document.selectFirst("img.movies_cover")?.absUrl("src"),
            genres = detailLinks(document, "Genre").map { Genre(it.first, it.second) },
            cast = detailLinks(document, "Starring").map { People(id = it.first, name = it.second) },
            seasons = seasons,
        )
    }

    private fun detailTitle(document: Document) = document.selectFirst("h1.tittv")?.text()?.removePrefix("Watch ")?.trim().orEmpty()

    private fun detailValue(document: Document, label: String): String? = document.select(".h3-detail")
        .firstOrNull { it.text().startsWith("$label:") }?.selectFirst("span")?.text()?.trim()
        ?.takeUnless { it.isNullOrBlank() || it.equals("N/A", true) }

    private fun detailLinks(document: Document, label: String): List<Pair<String, String>> = document.select(".h3-detail")
        .firstOrNull { it.text().startsWith("$label:") }?.select("a")?.map { it.attr("href") to it.text().trim().trimEnd(',') } ?: emptyList()

    private fun pagePath(type: String, page: Int) = if (page <= 1) "new-$type" else "new-$type/page/$page"
    private fun String.toSlug() = lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private val YEAR_PATTERN = Regex("\\b(?:19|20)\\d{2}\\b")
    private val EPISODE_PATTERN = Regex("S(\\d+)[-:]E(\\d+)", RegexOption.IGNORE_CASE)

    private interface Service {
        companion object {
            fun build(): Service = Retrofit.Builder()
                .baseUrl(URL)
                .addConverterFactory(JsoupConverterFactory.create())
                .client(OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build())
                .build().create(Service::class.java)
        }

        @GET("home") suspend fun getHome(): Document
        @GET("search/{query}") suspend fun search(@Path("query") query: String): Document
        @GET("{path}") suspend fun getPage(@Path("path", encoded = true) path: String): Document
        @GET("{path}") suspend fun getMovies(@Path("path", encoded = true) path: String): Document
        @GET("{path}") suspend fun getTvShows(@Path("path", encoded = true) path: String): Document
    }
}
