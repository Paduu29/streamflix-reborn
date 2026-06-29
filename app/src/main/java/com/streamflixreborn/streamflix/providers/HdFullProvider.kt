package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
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
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale

object HdFullProvider : Provider {

    override val name = "HdFull"
    override val baseUrl = "https://hdfull.one"
    override val language = "es"
    override val logo = "https://hdfullcdn.cc/favicon.ico"

    private const val TAG = "HdFullProvider"
    private const val EMBED_WIDTH = 920
    private const val EMBED_HEIGHT = 360
    private const val CACHE_USERNAME = "username"
    private const val CACHE_PASSWORD = "password"
    private const val MISSING_CREDENTIALS_MESSAGE =
        "HdFull requires a saved username and password in provider settings."

    private var webViewResolver: WebViewResolver? = null
    private val authMutex = Mutex()
    private var sessionPrimed = false
    private var activeSessionCookies: String? = null

    private class MissingCredentialsException : IllegalStateException(MISSING_CREDENTIALS_MESSAGE)

    fun init(context: Context) {
        webViewResolver = WebViewResolver(context)
        sessionPrimed = false
        activeSessionCookies = null
        clearSessionCookies()
    }

    fun clearSessionCookies() {
        val cookieManager = CookieManager.getInstance()
        listOf(
            "https://hdfull.one",
            "https://www.hdfull.one",
            "https://hdfull.sbs",
            "https://www.hdfull.sbs",
            "https://hdfullcdn.cc",
            "https://www.hdfullcdn.cc",
        ).forEach { url ->
            cookieManager.setCookie(url, "cf_clearance=; Max-Age=0; path=/")
            cookieManager.setCookie(url, "guid=; Max-Age=0; path=/")
            cookieManager.setCookie(url, "PHPSESSID=; Max-Age=0; path=/")
        }
        cookieManager.flush()
    }

    private fun getResolver(): WebViewResolver {
        return webViewResolver ?: WebViewResolver(StreamFlixApp.instance).also {
            webViewResolver = it
        }
    }

    private data class ProviderLink(
        val type: String,
        val domain: String,
        val embedBuilder: ((String) -> String)?,
        val linkBuilder: (String) -> String,
    )

    private data class HdLink(
        val id: String,
        val provider: String,
        val code: String,
        val lang: String,
        val quality: String,
    )

    private val providerMap = mapOf(
        "1" to ProviderLink("s", "https://powvideo.org", { "https://powvideo.org/embed-$it-${EMBED_WIDTH}x$EMBED_HEIGHT.html" }) { "https://powvideo.org/$it" },
        "2" to ProviderLink("s", "https://streamplay.to", { "https://streamplay.to/embed-$it-${EMBED_WIDTH}x$EMBED_HEIGHT.html" }) { "https://streamplay.to/$it" },
        "4" to ProviderLink("s", "https://upstream.to", { "https://upstream.to/embed-$it-${EMBED_WIDTH}x$EMBED_HEIGHT.html" }) { "https://upstream.to/$it" },
        "5" to ProviderLink("s", "https://cloudvideo.tv", { "https://cloudvideo.tv/embed-$it-${EMBED_WIDTH}x$EMBED_HEIGHT.html" }) { "https://cloudvideo.tv/$it" },
        "6" to ProviderLink("s", "https://streamtape.com", { "https://streamtape.com/e/$it" }) { "https://streamtape.com/v/$it" },
        "8" to ProviderLink("d", "https://www.filefactory.com", null) { "https://www.filefactory.com/file/$it" },
        "10" to ProviderLink("d", "https://rapidgator.net", null) { "https://rapidgator.net/file/$it" },
        "12" to ProviderLink("s", "https://gamovideo.com", { "https://gamovideo.com/embed-$it-${EMBED_WIDTH}x$EMBED_HEIGHT.html" }) { "https://gamovideo.com/$it" },
        "15" to ProviderLink("s", "https://mixdrop.bz", { "https://mixdrop.bz/e/$it" }) { "https://mixdrop.bz/f/$it" },
        "22" to ProviderLink("d", "https://mexa.sh", null) { "https://mexa.sh/$it" },
        "23" to ProviderLink("d", "https://1fichier.com", null) { "https://1fichier.com/?$it" },
        "24" to ProviderLink("d", "https://katfile.online", null) { "https://katfile.online/$it" },
        "27" to ProviderLink("d", "http://nitroflare.com", null) { "http://nitroflare.com/view/$it" },
        "31" to ProviderLink("s", "https://vidoza.net", { "https://vidoza.net/embed-$it-${EMBED_WIDTH}x$EMBED_HEIGHT.html" }) { "https://vidoza.net/$it" },
        "35" to ProviderLink("d", "https://uptobox.com", null) { "https://uptobox.com/$it" },
        "38" to ProviderLink("d", "https://clicknupload.cc", null) { "https://clicknupload.cc/$it" },
        "40" to ProviderLink("s", "https://vidmoly.me", { "https://vidmoly.biz/embed-$it-${EMBED_WIDTH}x$EMBED_HEIGHT.html" }) { "https://vidmoly.me/w/$it" },
        "45" to ProviderLink("s", "https://waaw.tv", { "https://hqq.tv/player/embed_player.php?vid=$it&autoplay=no" }) { "https://waaw.tv/f/$it" },
    )

    override suspend fun getHome(): List<Category> = coroutineScope {
        ensureStoredCredentials()
        parseHomeCategories(getDocument(baseUrl))
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre(id = "/peliculas/date", name = "Peliculas"),
                Genre(id = "/series/date", name = "Series"),
                Genre(id = "/tags-peliculas/action", name = "Accion"),
                Genre(id = "/tags-peliculas/comedy", name = "Comedia"),
                Genre(id = "/tags-tv/drama", name = "Drama"),
                Genre(id = "/tags-tv/science-fiction", name = "Ciencia Ficcion"),
            )
        }

        return try {
            val home = getDocument(baseUrl)
            val csrf = home.selectFirst("input[name=__csrf_magic]")?.attr("value")
                ?: throw IllegalStateException("HdFull search csrf missing")

            val body = FormBody.Builder()
                .add("__csrf_magic", csrf)
                .add("menu", "search")
                .add("query", query)
                .build()

            val request = Request.Builder()
                .url("$baseUrl/buscar")
                .post(body)
                .header("Referer", "$baseUrl")
                .build()

            val html = execute(request)
            val doc = Jsoup.parse(html).apply { setBaseUri(baseUrl) }
            parseCards(doc)
        } catch (error: MissingCredentialsException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "search failed", error)
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try {
        val path = if (page <= 1) "/peliculas" else "/peliculas/date/$page"
        parseCards(getDocument("$baseUrl$path")).filterIsInstance<Movie>()
    } catch (error: MissingCredentialsException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "getMovies failed", error)
        emptyList()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = try {
        val path = if (page <= 1) "/series" else "/series/date/$page"
        parseCards(getDocument("$baseUrl$path")).filterIsInstance<TvShow>()
    } catch (error: MissingCredentialsException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "getTvShows failed", error)
        emptyList()
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = getDocument(mediaUrl(id, isMovie = true))
        return Movie(
            id = id,
            title = doc.selectFirst("#summary-title")?.text().orEmpty(),
            overview = doc.selectFirst(".show-overview-text")?.text()?.trim(),
            released = extractDetailValue(doc, "Año")?.takeIf { it.isNotBlank() }?.let { "$it-01-01" },
            rating = extractDetailValue(doc, "IMDB Rating")?.toDoubleOrNull(),
            poster = doc.selectFirst(".show-poster img")?.absUrl("src")?.normalizeThumb(),
            genres = doc.select(".show-details a[href*='/tags-peliculas/']").mapNotNull { genreFromAnchor(it) },
            imdbId = doc.selectFirst("a[href*='imdb.com/title/']")?.attr("href")?.substringAfter("/title/")?.substringBefore("/"),
            trailer = Regex("""var\s+trailer\s*=\s*'([^']*)'""").find(doc.outerHtml())?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = getDocument(mediaUrl(id, isMovie = false))
        val showNumericId = Regex("""var\s+sid\s*=\s*'([^']+)'""")
            .find(doc.outerHtml())
            ?.groupValues
            ?.getOrNull(1)
            ?: throw IllegalStateException("HdFull show sid missing")

        val poster = doc.selectFirst(".show-poster img")?.absUrl("src")?.normalizeThumb()
        val seasons = doc.select("a[href*='/temporada-']")
            .mapNotNull { seasonLink ->
                val href = seasonLink.attr("href")
                val seasonNumber = Regex("""temporada-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                Season(
                    id = "$id|$showNumericId|$seasonNumber",
                    number = seasonNumber,
                    title = seasonLink.text().ifBlank { "Temporada $seasonNumber" },
                    poster = poster,
                )
            }
            .distinctBy { it.number }
            .sortedBy { it.number }

        return TvShow(
            id = id,
            title = doc.selectFirst("#summary-title")?.text().orEmpty(),
            overview = doc.selectFirst(".show-overview-text")?.text()?.trim(),
            released = extractDetailValue(doc, "Año")?.takeIf { it.isNotBlank() }?.let { "$it-01-01" },
            rating = extractDetailValue(doc, "IMDB Rating")?.toDoubleOrNull(),
            poster = poster,
            genres = doc.select(".show-details a[href*='/tags-tv/']").mapNotNull { genreFromAnchor(it) },
            imdbId = doc.selectFirst("a[href*='imdb.com/title/']")?.attr("href")?.substringAfter("/title/")?.substringBefore("/"),
            seasons = seasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val parts = seasonId.split("|")
        if (parts.size < 3) return emptyList()

        val showSlug = parts[0]
        val showId = parts[1]
        val seasonNumber = parts[2].toIntOrNull() ?: return emptyList()

        return try {
            val body = FormBody.Builder()
                .add("action", "season")
                .add("start", "0")
                .add("limit", "0")
                .add("show", showId)
                .add("season", seasonNumber.toString())
                .build()

            val request = Request.Builder()
                .url("$baseUrl/a/episodes")
                .post(body)
                .header("Referer", "$baseUrl/serie/$showSlug/temporada-$seasonNumber")
                .header("X-Requested-With", "XMLHttpRequest")
                .build()

            val response = execute(request)
            val array = JSONArray(response)
            List(array.length()) { index ->
                array.getJSONObject(index).toEpisode()
            }.sortedBy { it.number }
        } catch (error: MissingCredentialsException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "getEpisodesBySeason failed", error)
            emptyList()
        }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val path = if (id.startsWith("/")) id else "/$id"
        val url = if (page <= 1) "$baseUrl$path" else "$baseUrl$path/$page"
        return try {
            Genre(
                id = id,
                name = path.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) },
                shows = parseCards(getDocument(url)).filterIsInstance<Show>()
            )
        } catch (error: MissingCredentialsException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "getGenre failed", error)
            Genre(id = id, name = path)
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw Exception("Not yet implemented")
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        return try {
            val url = when {
                id.startsWith("http://") || id.startsWith("https://") -> id
                videoType is Video.Type.Movie -> mediaUrl(id, isMovie = true)
                else -> mediaUrl(id, isMovie = false)
            }

            val doc = getDocument(url)
            val links = decodeLinks(doc)
            links.mapNotNull { link ->
                val provider = providerMap[link.provider] ?: return@mapNotNull null
                if (provider.type != "s") return@mapNotNull null

                val embedUrl = provider.embedBuilder?.invoke(link.code) ?: return@mapNotNull null
                val host = provider.domain.removePrefix("https://").removePrefix("http://").removePrefix("www.")
                val quality = link.quality.ifBlank { "unknown" }
                val lang = link.lang.ifBlank { "UNK" }

                Video.Server(
                    id = link.id,
                    name = "$host [$lang/$quality]",
                    src = embedUrl
                )
            }.distinctBy { it.src }
        } catch (error: MissingCredentialsException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "getServers failed", error)
            emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return try {
            Extractor.extract(server.src, server)
        } catch (error: Exception) {
            if (!error.message.orEmpty().contains("No extractors found")) {
                throw error
            }
            Log.w(TAG, "Falling back to generic video resolver for ${server.src}")
            resolveGenericVideo(server.src, server)
        }
    }

    private suspend fun getDocument(url: String): Document {
        ensureInteractiveAccess(url)

        return try {
            fetchDocument(url)
        } catch (error: IllegalStateException) {
            if (!isAuthFailure(error) || !hasStoredCredentials()) {
                throw error
            }

            Log.w(TAG, "Retrying HdFull request after clearing stale session", error)
            clearSessionCookies()
            sessionPrimed = false
            ensureInteractiveAccess(url)
            fetchDocument(url)
        }
    }

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("Referer", baseUrl)
            .build()

        val html = execute(request)
        return Jsoup.parse(html).apply { setBaseUri(baseUrl) }
    }

    private suspend fun ensureInteractiveAccess(targetUrl: String) {
        authMutex.withLock {
            ensureStoredCredentials()

            if (hasLoginCookie(targetUrl)) {
                sessionPrimed = true
                return@withLock
            }

            runCatching {
                execute(
                    Request.Builder()
                        .url(targetUrl)
                        .header("Referer", baseUrl)
                        .build()
                )
            }

            if (hasCloudflareClearance(targetUrl) && attemptAutomaticLogin(targetUrl)) {
                sessionPrimed = hasLoginCookie(targetUrl)
                if (sessionPrimed) {
                    return@withLock
                }
            }

            Log.d(TAG, "Launching interactive WebView for $targetUrl")
            getResolver().get(
                url = targetUrl,
                completion = { currentUrl, html, cookies ->
                    if (cookies.isNotBlank()) {
                        activeSessionCookies = mergeCookieStrings(activeSessionCookies, cookies)
                    }
                    cookies.contains("cf_clearance=") && !isCloudflareChallengePage(currentUrl, html)
                }
            )

            if (hasCloudflareClearance(targetUrl) && attemptAutomaticLogin(targetUrl)) {
                sessionPrimed = hasLoginCookie(targetUrl)
                if (sessionPrimed) {
                    return@withLock
                }
            }

            sessionPrimed = hasLoginCookie(targetUrl)
        }
    }

    private fun hasStoredCredentials(): Boolean {
        return storedUsername().isNotBlank() && storedPassword().isNotBlank()
    }

    private fun storedUsername(): String {
        return UserPreferences.getProviderCache(this, CACHE_USERNAME).trim()
    }

    private fun storedPassword(): String {
        return UserPreferences.getProviderCache(this, CACHE_PASSWORD)
    }

    private fun hasLoginCookie(url: String): Boolean {
        val cookies = collectCookies(url)
        return cookies.contains("guid=") && cookies.contains("cf_clearance=")
    }

    private fun hasCloudflareClearance(url: String): Boolean {
        return collectCookies(url).contains("cf_clearance=")
    }

    private fun requiresInteractiveAccess(currentUrl: String, html: String): Boolean {
        if (html.isBlank()) return true
        val lowerHtml = html.lowercase(Locale.ROOT)
        val lowerUrl = currentUrl.lowercase(Locale.ROOT)
        return lowerUrl.contains("/login") ||
            lowerHtml.contains("just a moment") ||
            lowerHtml.contains("cf-browser-verification") ||
            lowerHtml.contains("challenge-running") ||
            lowerHtml.contains("popup_login_form") ||
            lowerHtml.contains("dologin('#popup_login_result')") ||
            lowerHtml.contains("name=\"password\"")
    }

    private fun isCloudflareChallengePage(currentUrl: String, html: String): Boolean {
        if (html.isBlank()) return true
        val lowerHtml = html.lowercase(Locale.ROOT)
        val lowerUrl = currentUrl.lowercase(Locale.ROOT)
        return lowerUrl.contains("/cdn-cgi/challenge") ||
            lowerHtml.contains("just a moment") ||
            lowerHtml.contains("cf-browser-verification") ||
            lowerHtml.contains("challenge-running") ||
            lowerHtml.contains("challenges.cloudflare.com") ||
            lowerHtml.contains("cf-mitigated")
    }

    private fun isLoggedIn(currentUrl: String, html: String, cookies: String): Boolean {
        val lowerHtml = html.lowercase(Locale.ROOT)
        val lowerUrl = currentUrl.lowercase(Locale.ROOT)
        return cookies.contains("guid=") &&
            cookies.contains("cf_clearance=") &&
            !lowerUrl.contains("/login") &&
            !lowerHtml.contains("popup_login_form") &&
            !lowerHtml.contains("dologin('#popup_login_result')")
    }

    private fun execute(request: Request): String {
        val requestWithCookies = request.newBuilder()
            .header("Cookie", collectCookies(request.url.toString()))
            .build()

        NetworkClient.default.newCall(requestWithCookies).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HdFull request failed: ${response.code}")
            }
            return body
        }
    }

    private fun isAuthFailure(error: IllegalStateException): Boolean {
        return error.message.orEmpty().contains("HdFull request failed: 403")
    }

    private fun ensureStoredCredentials() {
        if (!hasStoredCredentials()) {
            throw MissingCredentialsException()
        }
    }

    private suspend fun attemptAutomaticLogin(targetUrl: String): Boolean {
        if (!hasStoredCredentials()) return false
        val request = buildLoginRequest() ?: return false

        return try {
            execute(request)
            activeSessionCookies = mergeCookieStrings(
                activeSessionCookies,
                rawCookieHeader(targetUrl, allowedNames = setOf("guid", "PHPSESSID", "language")),
            )
            hasLoginCookie(targetUrl)
        } catch (error: Exception) {
            Log.w(TAG, "Automatic HdFull login failed", error)
            false
        }
    }

    private fun buildLoginRequest(): Request? {
        val username = storedUsername()
        val password = storedPassword()

        if (username.isBlank() || password.isBlank()) {
            return null
        }

        val body = FormBody.Builder()
            .add("username", username)
            .add("password", password)

        val formBody = body.build()

        return Request.Builder()
            .url("$baseUrl/a/login")
            .post(formBody)
            .header("Referer", "$baseUrl/login")
            .header("Origin", baseUrl)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .build()
    }

    private fun parseCards(doc: Document): List<AppAdapter.Item> {
        val anchors = doc.select(
            "a[href*='/pelicula/'], a[href*='/serie/'], a[href*='/movie/'], a[href*='/show/']"
        )

        return anchors.mapNotNull { anchor ->
            val href = anchor.attr("href").ifBlank { return@mapNotNull null }
            val absoluteUrl = when {
                href.startsWith("http") -> href
                href.startsWith("/") -> "$baseUrl$href"
                else -> "$baseUrl/$href"
            }
            val image = anchor.selectFirst("img")
            val title = sequenceOf(
                image?.attr("title"),
                image?.attr("alt"),
                image?.attr("original-title"),
                anchor.attr("title"),
                anchor.text().trim(),
            ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return@mapNotNull null
            val poster = sequenceOf(
                image?.absUrl("src"),
                image?.attr("src"),
                anchor.attr("data-src"),
            ).firstOrNull { !it.isNullOrBlank() }?.let { normalizeUrl(it!!) }
            val rating = anchor.selectFirst(".rating")?.text()
                ?.replace("\\s+".toRegex(), "")
                ?.toDoubleOrNull()

            when {
                href.contains("/pelicula/") -> Movie(
                    id = absoluteUrl.substringAfter("/pelicula/").substringBefore('?').trimEnd('/'),
                    title = title,
                    poster = poster,
                    rating = rating,
                )
                href.contains("/serie/") -> TvShow(
                    id = absoluteUrl.substringAfter("/serie/").substringBefore('?').substringBefore("/temporada-").trimEnd('/'),
                    title = title,
                    poster = poster,
                    rating = rating,
                )
                else -> null
            }
        }.distinctBy {
            when (it) {
                is Movie -> "m:${it.id}"
                is TvShow -> "t:${it.id}"
            }
        }
    }

    private fun parseHomeCategories(doc: Document): List<Category> {
        val cardAnchors = doc.select(
            "a[href*='/pelicula/'], a[href*='/serie/'], a[href*='/movie/'], a[href*='/show/']"
        )

        val groupedBySection = linkedMapOf<String, MutableList<AppAdapter.Item>>()

        cardAnchors.forEach { anchor ->
            val item = parseCard(anchor) ?: return@forEach
            val sectionName = anchor.closestSectionTitle()
            groupedBySection.getOrPut(sectionName) { mutableListOf() }.add(item)
        }

        return groupedBySection
            .mapNotNull { (name, items) ->
                val deduped = items.distinctBy {
                    when (it) {
                        is Movie -> "m:${it.id}"
                        is TvShow -> "t:${it.id}"
                        else -> it.hashCode().toString()
                    }
                }
                deduped.takeIf { it.isNotEmpty() }?.let { Category(name = name, list = it) }
            }
    }

    private fun parseCard(anchor: org.jsoup.nodes.Element): AppAdapter.Item? {
        val href = anchor.attr("href").ifBlank { return null }
        val absoluteUrl = when {
            href.startsWith("http") -> href
            href.startsWith("/") -> "$baseUrl$href"
            else -> "$baseUrl/$href"
        }
        val image = anchor.selectFirst("img")
        val title = sequenceOf(
            image?.attr("title"),
            image?.attr("alt"),
            image?.attr("original-title"),
            anchor.attr("title"),
            anchor.text().trim(),
        ).firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
        val poster = sequenceOf(
            image?.absUrl("src"),
            image?.attr("src"),
            anchor.attr("data-src"),
        ).firstOrNull { !it.isNullOrBlank() }?.let { normalizeUrl(it!!) }
        val rating = anchor.selectFirst(".rating")?.text()
            ?.replace("\\s+".toRegex(), "")
            ?.toDoubleOrNull()

        return when {
            href.contains("/pelicula/") -> Movie(
                id = absoluteUrl.substringAfter("/pelicula/").substringBefore('?').trimEnd('/'),
                title = title,
                poster = poster,
                rating = rating,
            )
            href.contains("/serie/") -> TvShow(
                id = absoluteUrl.substringAfter("/serie/").substringBefore('?').substringBefore("/temporada-").trimEnd('/'),
                title = title,
                poster = poster,
                rating = rating,
            )
            else -> null
        }
    }

    private fun org.jsoup.nodes.Element.closestSectionTitle(): String {
        val heading = previousElementSibling()?.takeIf { it.tagName() in setOf("h1", "h2", "h3", "h4", "h5") }
            ?: parent()?.previousElementSibling()?.takeIf { it.tagName() in setOf("h1", "h2", "h3", "h4", "h5") }

        val headingText = heading?.text()?.trim().orEmpty()
        if (headingText.isNotBlank()) return headingText

        val container = parents().firstOrNull { parent ->
            parent.selectFirst("h1, h2, h3, h4, h5, .title, .section-title, .block-title") != null
        }
        return container
            ?.selectFirst("h1, h2, h3, h4, h5, .title, .section-title, .block-title")
            ?.text()
            ?.trim()
            .orEmpty()
            .ifBlank { "HdFull" }
    }

    private suspend fun resolveGenericVideo(url: String, server: Video.Server, depth: Int = 0): Video {
        if (depth > 2) {
            throw IllegalStateException("HdFull generic resolver depth exceeded for $url")
        }

        val html = execute(
            Request.Builder()
                .url(url)
                .header("Referer", baseUrl)
                .build()
        )
        val document = Jsoup.parse(html).apply { setBaseUri(url) }

        val nestedUrl = sequenceOf(
            document.selectFirst("iframe[src]")?.absUrl("src"),
            document.selectFirst("video[src]")?.absUrl("src"),
            document.selectFirst("source[src]")?.absUrl("src"),
            Regex("""(?:file|src|source|url)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.groupValues
                ?.getOrNull(1)
        ).firstOrNull { it?.isNotBlank() == true }

        if (!nestedUrl.isNullOrBlank() && nestedUrl != url && nestedUrl.startsWith("http")) {
            if (looksLikeDirectMediaUrl(nestedUrl)) {
                return directVideo(nestedUrl, url)
            }
            return try {
                Extractor.extract(nestedUrl, server)
            } catch (_: Exception) {
                resolveGenericVideo(nestedUrl, server, depth + 1)
            }
        }

        val mediaUrl = sequenceOf(
            Regex("""https?://[^"'\s>]+\.(?:m3u8|mp4|m4v|webm|mpd)(?:\?[^"'\s>]*)?""", RegexOption.IGNORE_CASE)
                .find(html)
                ?.value,
            Regex("""https?://[^"'\s>]+""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.value }
                .firstOrNull { candidate ->
                    candidate.contains("m3u8", ignoreCase = true) ||
                        candidate.contains("mp4", ignoreCase = true) ||
                        candidate.contains("video", ignoreCase = true)
                }
        ).firstOrNull { it?.isNotBlank() == true }

        val resolved = mediaUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("No generic media url found for $url")

        return if (looksLikeDirectMediaUrl(resolved)) {
            directVideo(resolved, url)
        } else {
            Video(
                source = resolved,
                headers = mapOf(
                    "Referer" to url,
                    "User-Agent" to NetworkClient.USER_AGENT
                ),
                extraBuffering = true
            )
        }
    }

    private fun looksLikeDirectMediaUrl(url: String): Boolean {
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains(".m3u8") ||
            lower.contains(".mp4") ||
            lower.contains(".m4v") ||
            lower.contains(".webm") ||
            lower.contains(".mpd")
    }

    private fun directVideo(mediaUrl: String, referer: String): Video {
        return Video(
            source = mediaUrl,
            headers = mapOf(
                "Referer" to referer,
                "User-Agent" to NetworkClient.USER_AGENT
            ),
            extraBuffering = true
        )
    }

    private fun extractDetailValue(doc: Document, label: String): String? {
        return doc.select(".show-details p").firstNotNullOfOrNull { paragraph ->
            val title = paragraph.selectFirst("span")?.text()?.removeSuffix(":")?.trim()
            if (title != label) return@firstNotNullOfOrNull null

            paragraph.children()
                .drop(1)
                .joinToString(" ") { it.text().trim() }
                .ifBlank { paragraph.ownText().trim() }
                .ifBlank { null }
        }
    }

    private fun genreFromAnchor(element: org.jsoup.nodes.Element): Genre? {
        val href = element.attr("href").ifBlank { return null }
        val name = element.text().trim().ifBlank { return null }
        return Genre(id = href, name = name)
    }

    private fun decodeLinks(doc: Document): List<HdLink> {
        val encoded = Regex("""var\s+ad\s*=\s*'([^']+)'""")
            .find(doc.outerHtml())
            ?.groupValues
            ?.getOrNull(1)
            ?: return emptyList()

        val decoded = decodeAd(encoded)
        val array = JSONArray(decoded)
        return List(array.length()) { index ->
            val item = array.getJSONObject(index)
            HdLink(
                id = item.optString("id"),
                provider = item.optString("provider"),
                code = item.optString("code"),
                lang = item.optString("lang"),
                quality = item.optString("quality"),
            )
        }
    }

    private fun decodeAd(encoded: String): String {
        val base64Decoded = String(Base64.decode(encoded, Base64.DEFAULT), Charsets.ISO_8859_1)
        return obfs(base64Decoded, 126 - 14)
    }

    private fun obfs(input: String, key: Int, limit: Int = 126): String {
        val chars = CharArray(input.length)
        input.forEachIndexed { index, char ->
            val code = char.code
            chars[index] = if (code <= limit) {
                ((code + key) % limit).toChar()
            } else {
                char
            }
        }
        return String(chars)
    }

    private fun JSONObject.toEpisode(): Episode {
        val seasonNumber = optString("season").toIntOrNull() ?: 0
        val episodeNumber = optString("episode").toIntOrNull() ?: 0
        val permalink = optString("permalink")
        val episodeUrl = "$baseUrl/serie/$permalink/temporada-$seasonNumber/episodio-$episodeNumber"
        val titleObject = optJSONObject("title")
        val title = titleObject?.optString("es").takeUnless { it.isNullOrBlank() }
            ?: titleObject?.optString("en")
        val posterPath = optString("thumbnail").ifBlank {
            optJSONObject("show")?.optString("thumbnail").orEmpty()
        }

        return Episode(
            id = episodeUrl,
            number = episodeNumber,
            title = title,
            released = optString("date_aired").substringBefore(" ").takeIf { it.isNotBlank() },
            poster = thumbnailUrl(posterPath),
        )
    }

    private fun mediaUrl(id: String, isMovie: Boolean): String {
        return if (id.startsWith("http://") || id.startsWith("https://")) {
            id
        } else {
            val typePath = if (isMovie) "pelicula" else "serie"
            "$baseUrl/$typePath/${id.trimStart('/')}"
        }
    }

    private fun thumbnailUrl(path: String?): String? {
        val value = path?.trim().orEmpty()
        if (value.isBlank()) return null
        return when {
            value.startsWith("http://") || value.startsWith("https://") -> value
            value.startsWith("/") -> "$baseUrl$value"
            else -> "https://hdfullcdn.cc/tthumb/312x176/$value"
        }
    }

    private fun String.normalizeThumb(): String = when {
        startsWith("http://") || startsWith("https://") -> this
        startsWith("/") -> "$baseUrl$this"
        else -> this
    }

    private fun normalizeUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"
        }
    }

    private fun collectCookies(url: String): String {
        val normalizedUrl = normalizeRequestUrl(url)
        if (isHdFullUrl(normalizedUrl)) {
            return activeSessionCookies.orEmpty()
        }

        val cookieManager = android.webkit.CookieManager.getInstance()
        val candidates = linkedSetOf<String>()
        val normalizedBase = normalizeRequestUrl(baseUrl)
        val alternateHost = if (normalizedUrl.contains("hdfull.one")) {
            "https://hdfull.sbs/"
        } else {
            "https://hdfull.one/"
        }

        listOf(normalizedUrl, normalizedBase, alternateHost).forEach { candidate ->
            runCatching { cookieManager.getCookie(candidate) }
                .getOrNull()
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.forEach { candidates.add(it) }
        }

        return candidates.joinToString("; ")
    }

    private fun rawCookieHeader(url: String, allowedNames: Set<String>? = null): String {
        val cookieManager = android.webkit.CookieManager.getInstance()
        val candidates = linkedSetOf<String>()
        val normalizedUrl = normalizeRequestUrl(url)
        val normalizedBase = normalizeRequestUrl(baseUrl)
        val alternateHost = if (normalizedUrl.contains("hdfull.one")) {
            "https://hdfull.sbs/"
        } else {
            "https://hdfull.one/"
        }

        listOf(normalizedUrl, normalizedBase, alternateHost).forEach { candidate ->
            runCatching { cookieManager.getCookie(candidate) }
                .getOrNull()
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.filter { cookie ->
                    val cookieName = cookie.substringBefore("=", cookie)
                    allowedNames?.contains(cookieName) != false
                }
                ?.forEach { candidates.add(it) }
        }

        return candidates.joinToString("; ")
    }

    private fun isHdFullUrl(url: String): Boolean {
        val host = runCatching { url.toHttpUrl().host }.getOrNull().orEmpty()
        return host == "hdfull.one" || host == "www.hdfull.one" || host == "hdfull.sbs" || host == "www.hdfull.sbs"
    }

    private fun mergeCookieStrings(vararg cookieStrings: String?): String {
        val cookiesByName = linkedMapOf<String, String>()

        cookieStrings.forEach { cookieString ->
            cookieString.orEmpty()
                .split(";")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .forEach { cookie ->
                    val cookieName = cookie.substringBefore("=", cookie)
                    cookiesByName[cookieName] = cookie
                }
        }

        return cookiesByName.values.joinToString("; ")
    }

    private fun normalizeRequestUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            else -> "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"
        }
    }
}
