package com.streamflixreborn.streamflix.providers

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.extractors.ScreenscapeExtractor
import com.streamflixreborn.streamflix.extractors.VideasyExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/** Redflix English provider. Redflix supplies the catalogue and its playback embeds. */
object RedflixProvider : Provider {

    private const val URL = "https://redflix.one/"
    private val service = RedflixService.build()

    override val baseUrl = URL
    override val name = "Redflix"
    override val logo = "https://redflix.one/redflix-192x192.png"
    override val language = "en"

    override suspend fun getHome(): List<Category> = coroutineScope {
        val region = runCatching { service.home().locationLabel() }.getOrDefault("Your Region")
        val responses = awaitAll(
            async { service.trendingDay() },
            async { service.trendingWeek() },
            async { service.newReleasesMovies() },
            async { service.newReleasesTv() },
            async { service.popularMovies() },
            async { service.animeMovies() },
            async { service.animeTv() },
            async { service.kDramas() },
            async { service.popularTv() },
            async { service.genreMovies(27) },
            async { service.genreMovies(878) },
            async { service.genreTv(10765) },
            async { service.genreMovies(16) },
            async { service.genreTv(16) },
            async { service.genreMovies(28) },
            async { service.genreTv(10759) },
        )

        val trendingToday = responses[0].results()
        val anime = responses[5].results() + responses[6].results()
        val action = responses[14].results() + responses[15].results()
        val sciFi = responses[10].results() + responses[11].results()
        val animation = responses[12].results() + responses[13].results()

        listOf(
            Category("Top 10 Today", trendingToday.take(10)),
            Category("Popular Movies", responses[4].results(isMovie = true)),
            Category("Trending Anime", anime),
            Category("K-Dramas", responses[7].results(isMovie = false)),
            Category("TV Shows", responses[8].results(isMovie = false)),
            Category("Horror", responses[9].results()),
            Category("Action & Adventure", action),
            Category("Sci-Fi & Fantasy", sciFi),
            Category("Animation", animation),
        ).filter { it.list.isNotEmpty() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return genreShortcuts.map { (id, name) -> Genre(id, name) }
        }
        return service.search(query, page).items()
    }

    override suspend fun getMovies(page: Int): List<Movie> =
        service.movies(page).items(isMovie = true).filterIsInstance<Movie>()

    override suspend fun getTvShows(page: Int): List<TvShow> =
        service.tvShows(page).items(isMovie = false).filterIsInstance<TvShow>()

    override suspend fun getMovie(id: String): Movie = service.movieDetails(id.toTmdbId()).toMovie(id.toTmdbId())

    override suspend fun getTvShow(id: String): TvShow = service.tvDetails(id.toTmdbId()).toTvShow(id.toTmdbId())

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val (showId, seasonNumber) = seasonId.split("-", limit = 2)
        return service.season(showId.toTmdbId(), seasonNumber.toInt()).array("episodes").map { episode ->
            Episode(
                id = episode.string("id"),
                number = episode.int("episode_number"),
                title = episode.stringOrNull("name"),
                released = episode.stringOrNull("air_date"),
                poster = image(episode.stringOrNull("still_path")),
                overview = episode.stringOrNull("overview"),
            )
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val name = genreShortcuts.firstOrNull { it.first == id }?.second ?: id
        val movies = service.genreMovies(id.toIntOrNull() ?: 0, page).results(isMovie = true).filterIsInstance<Show>()
        val shows = service.genreTv(id.toIntOrNull() ?: 0, page).results(isMovie = false).filterIsInstance<Show>()
        return Genre(id = id, name = name, shows = movies + shows)
    }

    override suspend fun getPeople(id: String, page: Int): People =
        People(id = id, name = id)

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val tmdbId = id.toTmdbId()
        val path = when (videoType) {
            is Video.Type.Movie -> "movie/$tmdbId"
            is Video.Type.Episode -> "tv/${videoType.tvShow.id.toTmdbId()}/${videoType.season.number}/${videoType.number}"
        }
        val servers = mutableListOf<Video.Server>()
        servers += Video.Server(
            name = "Screenscape",
            id = "Screenscape",
            src = "https://screenscape.me/embed?tmdb=$tmdbId&type=${if (videoType is Video.Type.Movie) "movie" else "tv"}",
        )
        servers += VideasyExtractor().servers(videoType, language).map { it.copy(name = "Videasy - ${it.name}") }
        servers += Video.Server(
            name = "VidNest",
            id = "VidNest",
            src = "https://vidnest.fun/$path?autoNext=true&nextButton=false&title=true&poster=true&autoPlay=true",
        ).let(::listOf)
        return servers
    }

    override suspend fun getVideo(server: Video.Server): Video = Extractor.extract(server.src, server)

    private fun Document.toCategories(): List<Category> = select("script[type=application/ld+json]").mapNotNull { script ->
        runCatching { JsonParser.parseString(script.data()).asJsonObject }.getOrNull()
    }.filter { it.stringOrNull("@type") == "ItemList" }.map { list ->
        Category(list.stringOrNull("name") ?: "Redflix", list.items())
    }

    private fun Document.locationLabel(): String {
        val match = Regex("locationLabel\\\\\":\\\\\"([^\\\\\"]+)").find(html())
        return match?.groupValues?.getOrNull(1) ?: "Your Region"
    }

    private fun Document.items(isMovie: Boolean? = null): List<AppAdapter.Item> =
        select("script[type=application/ld+json]").flatMap { script ->
            runCatching { JsonParser.parseString(script.data()).asJsonObject }.getOrNull()?.items(isMovie).orEmpty()
        }.distinctBy { it.id() }

    private fun JsonObject.items(isMovie: Boolean? = null): List<AppAdapter.Item> {
        if (stringOrNull("@type") != "ItemList") return emptyList()
        return array("itemListElement").mapNotNull { item ->
            val url = item.stringOrNull("url") ?: return@mapNotNull null
            val movie = url.contains("type=movie")
            if (isMovie != null && isMovie != movie) return@mapNotNull null
            val id = url.substringAfter("id=").substringBefore("&")
            if (movie) Movie(id = id, title = item.stringOrNull("name") ?: "")
            else TvShow(id = id, title = item.stringOrNull("name") ?: "")
        }
    }

    private fun JsonObject.results(isMovie: Boolean? = null): List<AppAdapter.Item> =
        array("results").mapNotNull { item ->
            val movie = item.stringOrNull("media_type") == "movie" || item.has("title")
            if (isMovie != null && movie != isMovie) return@mapNotNull null
            if (movie) {
                Movie(
                    id = item.string("id"),
                    title = item.string("title"),
                    overview = item.stringOrNull("overview"),
                    released = item.stringOrNull("release_date"),
                    rating = item.doubleOrNull("vote_average"),
                    poster = image(item.stringOrNull("poster_path")),
                    banner = image(item.stringOrNull("backdrop_path")),
                )
            } else {
                TvShow(
                    id = item.string("id"),
                    title = item.string("name"),
                    overview = item.stringOrNull("overview"),
                    released = item.stringOrNull("first_air_date"),
                    rating = item.doubleOrNull("vote_average"),
                    poster = image(item.stringOrNull("poster_path")),
                    banner = image(item.stringOrNull("backdrop_path")),
                )
            }
        }

    private fun JsonObject.toMovie(id: String) = Movie(
        id = id,
        title = string("title"),
        overview = stringOrNull("overview"),
        released = stringOrNull("release_date"),
        runtime = intOrNull("runtime"),
        rating = doubleOrNull("vote_average"),
        poster = image(stringOrNull("poster_path")),
        banner = image(stringOrNull("backdrop_path")),
        genres = array("genres").map { Genre(it.string("id"), it.string("name")) },
        cast = objectOrNull("credits")?.array("cast")?.map {
            People(it.string("id"), it.string("name"), image(it.stringOrNull("profile_path")))
        } ?: emptyList(),
    )

    private fun JsonObject.toTvShow(id: String) = TvShow(
        id = id,
        title = string("name"),
        overview = stringOrNull("overview"),
        released = stringOrNull("first_air_date"),
        rating = doubleOrNull("vote_average"),
        poster = image(stringOrNull("poster_path")),
        banner = image(stringOrNull("backdrop_path")),
        seasons = array("seasons").filter { it.int("season_number") > 0 }.map {
            Season("$id-${it.int("season_number")}", it.int("season_number"), it.stringOrNull("name"), image(it.stringOrNull("poster_path")))
        },
        genres = array("genres").map { Genre(it.string("id"), it.string("name")) },
        cast = objectOrNull("credits")?.array("cast")?.map {
            People(it.string("id"), it.string("name"), image(it.stringOrNull("profile_path")))
        } ?: emptyList(),
    )

    private fun JsonObject.string(key: String): String = get(key)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
    private fun JsonObject.stringOrNull(key: String): String? = string(key).ifBlank { null }
    private fun JsonObject.int(key: String): Int = get(key)?.takeIf { !it.isJsonNull }?.asInt ?: 0
    private fun JsonObject.intOrNull(key: String): Int? = get(key)?.takeIf { !it.isJsonNull }?.asInt
    private fun JsonObject.doubleOrNull(key: String): Double? = get(key)?.takeIf { !it.isJsonNull }?.asDouble
    private fun JsonObject.array(key: String): List<JsonObject> = getAsJsonArray(key)?.mapNotNull { it.asJsonObject } ?: emptyList()
    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.items(): List<AppAdapter.Item> = items(null)
    private fun AppAdapter.Item.id(): String = when (this) { is Movie -> id; is TvShow -> id; else -> "" }
    private fun String.toTmdbId(): String = substringBefore("-").substringBefore("/")
    private fun image(path: String?): String? = path?.let { "https://image.tmdb.org/t/p/w500$it" }

    private val genreShortcuts = listOf(
        "28" to "Action",
        "12" to "Adventure",
        "16" to "Animation",
        "35" to "Comedy",
        "80" to "Crime",
        "99" to "Documentary",
        "18" to "Drama",
        "10751" to "Family",
        "14" to "Fantasy",
        "27" to "Horror",
        "9648" to "Mystery",
        "10749" to "Romance",
        "878" to "Science Fiction",
        "53" to "Thriller",
        "10752" to "War",
        "37" to "Western",
    )

    private interface RedflixService {
        @GET("/") suspend fun home(): Document
        @GET("movies") suspend fun movies(@Query("page") page: Int = 1): Document
        @GET("tv-shows") suspend fun tvShows(@Query("page") page: Int = 1): Document
        @GET("browse") suspend fun search(@Query("q") query: String, @Query("page") page: Int = 1): Document
        @GET("api/tmdb/trending/all/day") suspend fun trendingDay(@Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/trending/all/week") suspend fun trendingWeek(@Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/discover/movie") suspend fun newReleasesMovies(@Query("sort_by") sort: String = "primary_release_date.desc", @Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/discover/tv") suspend fun newReleasesTv(@Query("sort_by") sort: String = "first_air_date.desc", @Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/movie/popular") suspend fun popularMovies(@Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/tv/popular") suspend fun popularTv(@Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/discover/movie") suspend fun animeMovies(@Query("with_keywords") keywords: String = "210024", @Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/discover/tv") suspend fun animeTv(@Query("with_keywords") keywords: String = "210024", @Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/discover/tv") suspend fun kDramas(@Query("with_origin_country") country: String = "KR", @Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/discover/movie") suspend fun genreMovies(@Query("with_genres") genres: Int, @Query("page") page: Int = 1, @Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/discover/tv") suspend fun genreTv(@Query("with_genres") genres: Int, @Query("page") page: Int = 1, @Query("language") language: String = "en-US"): JsonObject
        @GET("api/tmdb/movie/{id}") suspend fun movieDetails(@Path("id") id: String, @Query("language") language: String = "en-US", @Query("append_to_response") append: String = "credits,videos,images,similar,recommendations"): JsonObject
        @GET("api/tmdb/tv/{id}") suspend fun tvDetails(@Path("id") id: String, @Query("language") language: String = "en-US", @Query("append_to_response") append: String = "credits,videos,images,similar,recommendations,content_ratings"): JsonObject
        @GET("api/tmdb/tv/{id}/season/{season}") suspend fun season(@Path("id") id: String, @Path("season") season: Int, @Query("language") language: String = "en-US"): JsonObject

        companion object {
            fun build(): RedflixService {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build()
                return Retrofit.Builder()
                    .baseUrl(URL)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                    .create(RedflixService::class.java)
            }
        }
    }
}
