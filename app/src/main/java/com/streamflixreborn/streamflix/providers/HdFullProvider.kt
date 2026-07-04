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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.util.Locale
import retrofit2.Retrofit
import retrofit2.converter.scalars.ScalarsConverterFactory
import retrofit2.http.FieldMap
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.HeaderMap
import retrofit2.http.POST
import retrofit2.http.Url
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl

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
    @Volatile
    private var authenticatedSessionReady: Boolean = false

    fun resetAuthenticationState() {
        authenticatedSessionReady = false
        Log.d(TAG, "Authentication state reset")
    }

    private val service by lazy {
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(NetworkClient.default)
            .addConverterFactory(ScalarsConverterFactory.create())
            .build()
            .create(HdFullService::class.java)
    }

    private class MissingCredentialsException : IllegalStateException(MISSING_CREDENTIALS_MESSAGE)

    private enum class AuthState {
        UNKNOWN,
        CLOUDFLARE,
        CHECK_PAGE,
        LOGOUT,
        LOGIN_PAGE,
        AUTO_LOGIN,
        AUTHENTICATED,
        DONE,
        FAILED,
    }

    private data class ResponseSnapshot(
        val code: Int,
        val body: String,
        val finalUrl: String,
    )

    private val hdFullCookieHosts = listOf(
        "https://hdfull.one/",
        "https://www.hdfull.one/",
        "https://hdfull.sbs/",
        "https://www.hdfull.sbs/",
    )

    private interface HdFullService {
        @GET
        suspend fun getHtml(
            @Url url: String,
            @HeaderMap headers: Map<String, String>,
        ): String

        @FormUrlEncoded
        @POST("buscar")
        suspend fun search(
            @FieldMap fields: Map<String, String>,
            @HeaderMap headers: Map<String, String>,
        ): String

        @FormUrlEncoded
        @POST("a/episodes")
        suspend fun episodes(
            @FieldMap fields: Map<String, String>,
            @HeaderMap headers: Map<String, String>,
        ): String
    }

    fun init(context: Context) {
        CookieManager.getInstance().setAcceptCookie(true)
        webViewResolver = WebViewResolver(context)
        logCookieSnapshot(baseUrl)
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
        retryWithAuthRecovery(baseUrl) {
            val doc = fetchDocument(baseUrl, baseUrl)
            parseHomeCategories(doc)
        }
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
            retryWithAuthRecovery(baseUrl) {
                val doc = searchWithWebView(query)
                parseSearchResults(doc)
            }
        } catch (error: MissingCredentialsException) {
            throw error
        } catch (error: Exception) {
            Log.e(TAG, "search failed", error)
            emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> = try {
        val path = if (page <= 1) "/peliculas" else "/peliculas/date/$page"
        retryWithAuthRecovery("$baseUrl$path") {
            val doc = fetchDocument("$baseUrl$path", baseUrl)
            parseCards(doc, includeEpisodes = false).filterIsInstance<Movie>()
        }
    } catch (error: MissingCredentialsException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "getMovies failed", error)
        emptyList()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> = try {
        val path = if (page <= 1) "/series" else "/series/date/$page"
        retryWithAuthRecovery("$baseUrl$path") {
            val doc = fetchDocument("$baseUrl$path", baseUrl)
            parseCards(doc, includeEpisodes = false).filterIsInstance<TvShow>()
        }
    } catch (error: MissingCredentialsException) {
        throw error
    } catch (error: Exception) {
        Log.e(TAG, "getTvShows failed", error)
        emptyList()
    }

    override suspend fun getMovie(id: String): Movie {
        val doc = fetchDocument(mediaUrl(id, isMovie = true), mediaUrl(id, isMovie = true))
        return Movie(
            id = id,
            title = doc.selectFirst("#summary-title")?.text().orEmpty(),
            overview = extractSynopsis(doc),
            released = extractDetailValue(doc, "Año")?.takeIf { it.isNotBlank() }?.let { "$it-01-01" },
            rating = extractDetailValue(doc, "IMDB Rating")?.toDoubleOrNull(),
            poster = doc.selectFirst(".show-poster img")?.absUrl("src")?.normalizeThumb(),
            genres = doc.select(".show-details a[href*='/tags-peliculas/']").mapNotNull { genreFromAnchor(it) },
            imdbId = doc.selectFirst("a[href*='imdb.com/title/']")?.attr("href")?.substringAfter("/title/")?.substringBefore("/"),
            trailer = Regex("""var\s+trailer\s*=\s*'([^']*)'""").find(doc.outerHtml())?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val doc = fetchDocument(mediaUrl(id, isMovie = false), mediaUrl(id, isMovie = false))
        val showNumericId = extractShowNumericId(doc).orEmpty()

        val poster = doc.selectFirst(".show-poster img")?.absUrl("src")?.normalizeThumb()
        val seasons = doc.select("a[href*='/temporada-']")
            .mapNotNull { seasonLink ->
                val href = seasonLink.attr("href")
                val seasonNumber = Regex("""temporada-(\d+)""").find(href)?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                Season(
                    id = "$id|$showNumericId|$seasonNumber",
                    number = seasonNumber,
                    title = seasonLink.selectFirst("[itemprop='name'], h5")?.text()?.trim().ifNullOrBlank {
                        seasonLink.text().normalizeDisplayText().ifBlank { "Temporada $seasonNumber" }
                    },
                    poster = poster,
                )
            }
            .distinctBy { it.number }
            .sortedBy { it.number }

        return TvShow(
            id = id,
            title = doc.selectFirst("#summary-title")?.text().orEmpty(),
            overview = extractSynopsis(doc),
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
        val showId = parts[1].ifBlank {
            extractShowNumericId(fetchDocument(mediaUrl(showSlug, isMovie = false), mediaUrl(showSlug, isMovie = false))).orEmpty()
        }
        val seasonNumber = parts[2].toIntOrNull() ?: return emptyList()
        if (showId.isBlank()) {
            Log.w(TAG, "getEpisodesBySeason: missing HdFull sid for $showSlug")
            return emptyList()
        }

        return try {
            retryWithAuthRecovery("$baseUrl/serie/$showSlug/temporada-$seasonNumber") {
                val response = episodesWithWebView(showSlug, showId, seasonNumber)
                val array = JSONArray(response)
                List(array.length()) { index ->
                    array.getJSONObject(index).toEpisode()
                }.sortedBy { it.number }
            }
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
            retryWithAuthRecovery(url) {
                val doc = fetchDocument(url, baseUrl)
                Genre(
                    id = id,
                    name = path.substringAfterLast('/').replace('-', ' ').replaceFirstChar { it.titlecase(Locale.ROOT) },
                    shows = parseCards(doc, includeEpisodes = false).filterIsInstance<Show>()
                )
            }
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

            val doc = retryWithAuthRecovery(url) {
                val document = fetchDocument(url, url)
                document
            }
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

    private suspend fun ensureAuthenticatedSession(targetUrl: String = baseUrl) {
        if (authenticatedSessionReady) return
        AuthManager.ensureAuthenticated(targetUrl)
    }

    private suspend fun fetchDocument(url: String, referer: String): Document {
        return requestDocument(
            url = url,
            referer = referer,
            headers = pageHeaders(url, referer),
        )
    }

    private suspend fun requestDocument(
        url: String,
        referer: String,
        headers: Map<String, String>,
        ensureAuth: Boolean = true,
        authRetriesRemaining: Int = 2,
    ): Document {
        val normalizedUrl = normalizeRequestUrl(url)
        if (ensureAuth) {
            ensureAuthenticatedSession(normalizedUrl)
        }

        Log.d(TAG, "HdFull request -> url=$normalizedUrl")
        logCookieSnapshot(normalizedUrl)

        return try {
            val snapshot = executeRequest(normalizedUrl, headers)
            val document = snapshot.body.toDocument(snapshot.finalUrl)
            val authFailed = snapshot.code == 403 ||
                looksLikeLoginPage(document, snapshot.finalUrl) ||
                looksLikeCloudflarePage(document, snapshot.finalUrl)

            if (authFailed) {
                if (snapshot.code == 403 && authenticatedSessionReady) {
                    Log.w(TAG, "OkHttp is blocked after authentication, falling back to WebView fetch -> url=$normalizedUrl")
                    return fetchDocumentWithWebView(normalizedUrl, headers)
                }

                if (authRetriesRemaining <= 0) {
                    throw IllegalStateException("HdFull returned an authentication page for $normalizedUrl")
                }

                authenticatedSessionReady = false
                Log.w(TAG, "Auth issue detected -> code=${snapshot.code} url=$normalizedUrl")
                AuthManager.ensureAuthenticated(normalizedUrl, force = true)
                val retried = requestDocument(
                    url = normalizedUrl,
                    referer = referer,
                    headers = headers,
                    ensureAuth = false,
                    authRetriesRemaining = authRetriesRemaining - 1,
                )
                Log.d(TAG, "Automatic retry result -> success url=$normalizedUrl")
                return retried
            }

            document
        } catch (error: Exception) {
            if (authRetriesRemaining > 0 && looksLikeAuthFailure(error)) {
                authenticatedSessionReady = false
                Log.w(TAG, "HdFull request needs browser authentication -> url=$normalizedUrl", error)
                AuthManager.ensureAuthenticated(normalizedUrl, force = true)
                val retried = requestDocument(
                    url = normalizedUrl,
                    referer = referer,
                    headers = headers,
                    ensureAuth = false,
                    authRetriesRemaining = authRetriesRemaining - 1,
                )
                Log.d(TAG, "Automatic retry result -> success url=$normalizedUrl")
                return retried
            }
            throw error
        }
    }

    private suspend fun executeRequest(url: String, headers: Map<String, String>): ResponseSnapshot {
        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        NetworkClient.default.newCall(requestBuilder.build()).execute().use { response ->
            val html = response.body?.string().orEmpty()
            return ResponseSnapshot(
                code = response.code,
                body = html,
                finalUrl = response.request.url.toString(),
            )
        }
    }

    private fun synchronizeCookies(finalUrl: String? = null) {
        val cookieManager = CookieManager.getInstance()
        val candidates = linkedSetOf<String>().apply {
            if (!finalUrl.isNullOrBlank()) {
                add(finalUrl)
                runCatching { finalUrl.toHttpUrl() }.getOrNull()?.let { url ->
                    add(url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString())
                }
            }
            addAll(hdFullCookieHosts)
        }

        val cookiesByName = linkedMapOf<String, String>()
        candidates.forEach { candidate ->
            runCatching { cookieManager.getCookie(candidate) }
                .getOrNull()
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.contains("=") }
                ?.forEach { cookie ->
                    cookiesByName[cookie.substringBefore("=")] = cookie
                }
        }

        if (cookiesByName.isNotEmpty()) {
            hdFullCookieHosts.forEach { target ->
                cookiesByName.values.forEach { cookie ->
                    cookieManager.setCookie(target, "$cookie; Path=/")
                }
            }
        }

        cookieManager.flush()
        Log.d(TAG, "Cookies synchronized for HDFull hosts")
        logCookieSnapshot(baseUrl)
        logCookieSnapshot("https://www.hdfull.one/")
        logCookieSnapshot("https://hdfull.sbs/")
        logCookieSnapshot("https://www.hdfull.sbs/")
        logCookieSnapshot("https://hdfullcdn.cc/")
        logCookieSnapshot("https://www.hdfullcdn.cc/")
    }

    private fun looksLikeAuthFailure(error: Throwable): Boolean {
        val message = error.message.orEmpty()
        return message.contains("403") ||
            message.contains("login", ignoreCase = true) ||
            message.contains("cloudflare", ignoreCase = true) ||
            message.contains("browser-verification", ignoreCase = true) ||
            message.contains("verification page", ignoreCase = true)
    }

    private suspend fun performDeterministicAuthentication(targetUrl: String) {
        ensureStoredCredentials()
        var state = AuthState.UNKNOWN
        var logoutAttempts = 0
        var lastLogoutScriptAt = 0L
        var loginInjected = false
        var authenticatedSince = 0L

        fun transition(next: AuthState, currentUrl: String, html: String) {
            if (state == next) return
            Log.d(
                TAG,
                "STATE_${state.name} -> STATE_${next.name} url=$currentUrl " +
                    "cloudflare=${looksLikeCloudflarePage(html, currentUrl)} " +
                    "loginForm=${looksLikeLoginPage(html, currentUrl)} " +
                    "logoutTriggered=${next == AuthState.LOGOUT} " +
                    "loginSucceeded=${next == AuthState.AUTHENTICATED || next == AuthState.DONE}"
            )
            state = next
        }

        val landingUrl = normalizeRequestUrl(targetUrl).takeIf { isAllowedHdFullAuthNavigation(it) } ?: baseUrl
        Log.d(TAG, "STATE_UNKNOWN url=$landingUrl cloudflare=false loginForm=false logoutTriggered=false loginSucceeded=false")

        val authResult = getResolver().getResult(
            url = landingUrl,
            headers = authHeaders(landingUrl),
            shouldAllowNavigation = { navigationUrl, _ -> isAllowedHdFullAuthNavigation(navigationUrl) },
            completion = completion@{ currentUrl, html, cookies ->
                val cloudflare = looksLikeCloudflarePage(html, currentUrl)
                val loginForm = looksLikeLoginPage(html, currentUrl)
                val authenticated = isAuthenticatedPage(currentUrl, html, cookies)
                Log.d(
                    TAG,
                    "Auth poll -> state=STATE_${state.name} url=$currentUrl cloudflare=$cloudflare loginForm=$loginForm authenticated=$authenticated logoutAttempts=$logoutAttempts"
                )

                when (state) {
                    AuthState.UNKNOWN -> transition(AuthState.CHECK_PAGE, currentUrl, html)
                    AuthState.CLOUDFLARE -> if (!cloudflare) transition(AuthState.CHECK_PAGE, currentUrl, html)
                    else -> Unit
                }

                when (state) {
                    AuthState.CHECK_PAGE -> when {
                        cloudflare -> transition(AuthState.CLOUDFLARE, currentUrl, html)
                        authenticated -> transition(AuthState.AUTHENTICATED, currentUrl, html)
                        loginForm -> transition(AuthState.LOGIN_PAGE, currentUrl, html)
                        logoutAttempts < 2 -> transition(AuthState.LOGOUT, currentUrl, html)
                        else -> transition(AuthState.FAILED, currentUrl, html)
                    }

                    AuthState.LOGOUT -> when {
                        cloudflare -> transition(AuthState.CLOUDFLARE, currentUrl, html)
                        loginForm -> transition(AuthState.LOGIN_PAGE, currentUrl, html)
                        logoutAttempts >= 2 && !isLogoutUrl(currentUrl) -> transition(AuthState.FAILED, currentUrl, html)
                    }

                    AuthState.LOGIN_PAGE -> when {
                        cloudflare -> transition(AuthState.CLOUDFLARE, currentUrl, html)
                        authenticated -> transition(AuthState.AUTHENTICATED, currentUrl, html)
                    }

                    AuthState.AUTO_LOGIN -> when {
                        cloudflare -> transition(AuthState.CLOUDFLARE, currentUrl, html)
                        authenticated -> transition(AuthState.AUTHENTICATED, currentUrl, html)
                    }

                    else -> Unit
                }

                if (state == AuthState.AUTHENTICATED) {
                    if (authenticatedSince == 0L) {
                        authenticatedSince = System.currentTimeMillis()
                    }
                    if (System.currentTimeMillis() - authenticatedSince >= 1500L) {
                        transition(AuthState.DONE, currentUrl, html)
                        return@completion true
                    }
                } else {
                    authenticatedSince = 0L
                }

                false
            },
            pageReadyScriptProvider = { currentUrl, html, _ ->
                when {
                    state == AuthState.LOGOUT && logoutAttempts < 2 -> {
                        val now = System.currentTimeMillis()
                        if (lastLogoutScriptAt == 0L || now - lastLogoutScriptAt > 3500L) {
                            logoutAttempts++
                            lastLogoutScriptAt = now
                            Log.d(TAG, "STATE_LOGOUT triggering logout attempt=$logoutAttempts url=$currentUrl")
                            "window.location.replace('$baseUrl/logout');"
                        } else {
                            null
                        }
                    }

                    state == AuthState.LOGIN_PAGE &&
                        !loginInjected &&
                        looksLikeLoginPage(html, currentUrl) &&
                        !looksLikeCloudflarePage(html, currentUrl) -> {
                        loginInjected = true
                        transition(AuthState.AUTO_LOGIN, currentUrl, html)
                        Log.d(TAG, "STATE_AUTO_LOGIN injecting native login automation url=$currentUrl")
                        buildLoginAutomationScript()
                    }

                    else -> null
                }
            }
        )

        synchronizeCookies(authResult.finalUrl)

        if (state != AuthState.DONE) {
            authenticatedSessionReady = false
            transition(AuthState.FAILED, targetUrl, "")
            throw IllegalStateException("HdFull authentication failed before reaching authenticated UI")
        }

        authenticatedSessionReady = true
    }

    private suspend fun <T> retryWithAuthRecovery(
        targetUrl: String,
        block: suspend () -> T,
    ): T {
        return try {
            block()
        } catch (error: Exception) {
            if (!hasStoredCredentials() || !looksLikeAuthFailure(error)) {
                throw error
            }

            authenticatedSessionReady = false
            Log.w(TAG, "Retrying after deterministic authentication -> url=$targetUrl", error)
            AuthManager.ensureAuthenticated(targetUrl, force = true)
            block()
        }
    }

    private suspend fun searchWithWebView(query: String): Document {
        val quotedQuery = JSONObject.quote(query)
        val result = getResolver().getResult(
            url = baseUrl,
            headers = authHeaders(baseUrl),
            shouldAllowNavigation = { navigationUrl, _ -> isAllowedHdFullAuthNavigation(navigationUrl) },
            valueScript = """
                (function() {
                    const node = document.getElementById('hdfull-search-result');
                    return node ? node.textContent : '';
                })();
            """.trimIndent(),
            completion = { currentUrl, html, _ ->
                val done = html.contains("id=\"hdfull-search-result\"", ignoreCase = true) &&
                    html.contains("data-done=\"1\"", ignoreCase = true)
                Log.d(
                    TAG,
                    "Search WebView poll -> url=$currentUrl cloudflare=${looksLikeCloudflarePage(html, currentUrl)} loginForm=${looksLikeLoginPage(html, currentUrl)} done=$done"
                )
                done
            },
            pageReadyScriptProvider = { currentUrl, html, _ ->
                if (looksLikeCloudflarePage(html, currentUrl) || looksLikeLoginPage(html, currentUrl)) {
                    null
                } else {
                    buildSearchSubmissionScript(quotedQuery)
                }
            }
        )

        val html = decodeEvaluatedString(result.evaluatedValue)
        if (html.isBlank()) {
            throw IllegalStateException("HdFull search WebView response was empty")
        }
        if (looksLikeCloudflarePage(html, result.finalUrl ?: baseUrl) || looksLikeLoginPage(html, result.finalUrl ?: baseUrl)) {
            authenticatedSessionReady = false
            throw IllegalStateException("HdFull search WebView response returned an authentication page")
        }
        Log.d(TAG, "Search WebView succeeded -> query=$query length=${html.length}")
        return html.toDocument(baseUrl)
    }

    private suspend fun episodesWithWebView(showSlug: String, showId: String, seasonNumber: Int): String {
        val seasonUrl = "$baseUrl/serie/$showSlug/temporada-$seasonNumber"
        val result = getResolver().getResult(
            url = seasonUrl,
            headers = authHeaders(seasonUrl),
            shouldAllowNavigation = { navigationUrl, _ -> isAllowedHdFullAuthNavigation(navigationUrl) },
            valueScript = """
                (function() {
                    const node = document.getElementById('hdfull-episodes-result');
                    return node ? node.textContent : '';
                })();
            """.trimIndent(),
            completion = { currentUrl, html, _ ->
                val done = html.contains("id=\"hdfull-episodes-result\"", ignoreCase = true) &&
                    html.contains("data-done=\"1\"", ignoreCase = true)
                Log.d(
                    TAG,
                    "Episodes WebView poll -> url=$currentUrl cloudflare=${looksLikeCloudflarePage(html, currentUrl)} loginForm=${looksLikeLoginPage(html, currentUrl)} done=$done"
                )
                done
            },
            pageReadyScriptProvider = { currentUrl, html, _ ->
                if (looksLikeCloudflarePage(html, currentUrl) || looksLikeLoginPage(html, currentUrl)) {
                    null
                } else {
                    buildEpisodesSubmissionScript(showId, seasonNumber)
                }
            }
        )

        val json = decodeEvaluatedString(result.evaluatedValue).trim()
        if (json.isBlank()) {
            throw IllegalStateException("HdFull episodes WebView response was empty")
        }
        if (!json.startsWith("[")) {
            throw IllegalStateException("HdFull episodes WebView response was not JSON: ${json.take(80)}")
        }
        Log.d(TAG, "Episodes WebView succeeded -> show=$showId season=$seasonNumber length=${json.length}")
        return json
    }

    private suspend fun fetchDocumentWithWebView(url: String, headers: Map<String, String>): Document {
        val result = getResolver().getResult(
            url = url,
            headers = headers,
            shouldAllowNavigation = { navigationUrl, _ -> isAllowedHdFullAuthNavigation(navigationUrl) },
            completion = { currentUrl, html, _ ->
                val cloudflare = looksLikeCloudflarePage(html, currentUrl)
                val loginForm = looksLikeLoginPage(html, currentUrl)
                val hasUsableHtml = html.length > 1000 &&
                    (html.contains("home-thumb-item", ignoreCase = true) ||
                        html.contains("section-title", ignoreCase = true) ||
                        html.contains("show-poster", ignoreCase = true) ||
                        html.contains("var ad", ignoreCase = true) ||
                        html.contains("grid-item", ignoreCase = true) ||
                        isAuthenticatedPage(currentUrl, html, ""))
                Log.d(
                    TAG,
                    "WebView fetch poll -> url=$currentUrl cloudflare=$cloudflare loginForm=$loginForm usable=$hasUsableHtml"
                )
                !cloudflare && !loginForm && hasUsableHtml
            }
        )

        val finalUrl = result.finalUrl ?: url
        val document = result.html.toDocument(finalUrl)
        if (looksLikeCloudflarePage(document, finalUrl) || looksLikeLoginPage(document, finalUrl)) {
            authenticatedSessionReady = false
            throw IllegalStateException("HdFull WebView fetch returned an authentication page for $url")
        }
        Log.d(TAG, "WebView fetch succeeded -> url=$url finalUrl=$finalUrl")
        return document
    }

    private object AuthManager {
        private val mutex = Mutex()
        private var activeRecovery: CompletableDeferred<Unit>? = null

        suspend fun ensureAuthenticated(targetUrl: String, force: Boolean = false) {
            if (!force && authenticatedSessionReady) {
                return
            }

            var shouldRunRecovery = false
            val recovery = mutex.withLock {
                if (!force && authenticatedSessionReady) {
                    return@withLock null
                }

                activeRecovery?.let {
                    Log.d(TAG, "STATE_UNKNOWN waiting for active authentication url=$targetUrl")
                    return@withLock it
                }

                val deferred = CompletableDeferred<Unit>()
                activeRecovery = deferred
                shouldRunRecovery = true
                deferred
            }

            if (recovery == null) {
                return
            }

            if (!shouldRunRecovery) {
                recovery.await()
                return
            }

            try {
                performDeterministicAuthentication(targetUrl)
                recovery.complete(Unit)
                mutex.withLock {
                    if (activeRecovery === recovery) {
                        activeRecovery = null
                    }
                }
            } catch (error: Throwable) {
                recovery.completeExceptionally(error)
                mutex.withLock {
                    if (activeRecovery === recovery) {
                        activeRecovery = null
                    }
                }
                throw error
            }
        }
    }

    private fun logCookieSnapshot(url: String) {
        Log.d(TAG, "Cookie snapshot -> url=${normalizeRequestUrl(url)} cookies=${cookieHeaderForLogging(url).ifBlank { "<empty>" }}")
    }

    private fun authHeaders(referer: String): Map<String, String> {
        return linkedMapOf(
            "User-Agent" to NetworkClient.USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "identity",
            "Referer" to referer,
            "Origin" to baseUrl,
        )
    }

    private fun pageHeaders(url: String, referer: String): Map<String, String> {
        return hdFullHeaders(url, linkedMapOf(
            "User-Agent" to NetworkClient.USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "identity",
            "Referer" to referer,
        ))
    }

    private fun searchHeaders(referer: String): Map<String, String> {
        return hdFullHeaders(baseUrl, linkedMapOf(
            "User-Agent" to NetworkClient.USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "identity",
            "Content-Type" to "application/x-www-form-urlencoded",
            "Referer" to referer,
            "Origin" to baseUrl,
        ))
    }

    private fun episodesHeaders(referer: String): Map<String, String> {
        return hdFullHeaders(baseUrl, linkedMapOf(
            "User-Agent" to NetworkClient.USER_AGENT,
            "Accept" to "application/json, text/javascript, */*; q=0.01",
            "Accept-Language" to "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "identity",
            "Content-Type" to "application/x-www-form-urlencoded",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to baseUrl,
            "Referer" to referer,
        ))
    }

    private fun hdFullHeaders(url: String, headers: LinkedHashMap<String, String>): Map<String, String> {
        val cookieHeader = cookieHeaderForLogging(url)
        if (cookieHeader.isNotBlank()) {
            headers["Cookie"] = cookieHeader
        }
        return headers
    }

    private fun Document.toDocument(url: String): Document {
        return this.apply { setBaseUri(url) }
    }

    private fun String.toDocument(url: String): Document {
        return Jsoup.parse(this).apply { setBaseUri(url) }
    }

    private fun looksLikeLoginPage(doc: Document, currentUrl: String): Boolean {
        return looksLikeLoginPage(doc.outerHtml(), currentUrl)
    }

    private fun looksLikeLoginPage(html: String, currentUrl: String): Boolean {
        val normalizedHtml = html.lowercase(Locale.ROOT)
        return normalizedHtml.contains("popup_login_form") ||
            normalizedHtml.contains("popup_login_result") ||
            normalizedHtml.contains("dologin('#popup_login_result')") ||
            normalizedHtml.contains("input type=\"password\"") ||
            normalizedHtml.contains("input type='password'") ||
            normalizedHtml.contains("name=\"password\"") ||
            normalizedHtml.contains("name='password'") ||
            normalizedHtml.contains("type=\"submit\"") && normalizedHtml.contains("login") ||
            normalizedHtml.contains("type='submit'") && normalizedHtml.contains("login")
    }

    private fun looksLikeCloudflarePage(doc: Document, currentUrl: String): Boolean {
        return looksLikeCloudflarePage(doc.outerHtml(), currentUrl)
    }

    private fun looksLikeCloudflarePage(html: String, currentUrl: String): Boolean {
        val normalizedHtml = html.lowercase(Locale.ROOT)
        val url = currentUrl.lowercase(Locale.ROOT)
        return url.contains("/cdn-cgi/") ||
            normalizedHtml.contains("just a moment") ||
            normalizedHtml.contains("cf-browser-verification") ||
            normalizedHtml.contains("challenge-running") ||
            normalizedHtml.contains("challenges.cloudflare.com")
    }

    private fun isAuthenticatedPage(currentUrl: String, html: String, @Suppress("UNUSED_PARAMETER") cookies: String): Boolean {
        val normalizedHtml = html.lowercase(Locale.ROOT)
        val normalizedUrl = currentUrl.lowercase(Locale.ROOT)
        val hasAuthenticatedUi = normalizedHtml.contains("/logout") ||
            normalizedHtml.contains("logout") ||
            normalizedHtml.contains("cerrar sesi") ||
            normalizedHtml.contains("mi cuenta") ||
            normalizedHtml.contains("account") ||
            normalizedHtml.contains("profile") ||
            normalizedHtml.contains("perfil") ||
            normalizedHtml.contains("user-menu") ||
            normalizedHtml.contains("user_panel") ||
            normalizedHtml.contains("user-panel")

        return hasAuthenticatedUi &&
            !normalizedUrl.contains("/login") &&
            !looksLikeLoginPage(html, currentUrl) &&
            !looksLikeCloudflarePage(html, currentUrl)
    }

    private fun hasStoredCredentials(): Boolean {
        return storedUsername().isNotBlank() && storedPassword().isNotBlank()
    }

    private fun isLogoutUrl(url: String): Boolean {
        return runCatching { normalizeUrl(url).toHttpUrl().encodedPath.lowercase(Locale.ROOT) == "/logout" }
            .getOrDefault(false)
    }

    private fun isAllowedHdFullAuthNavigation(url: String): Boolean {
        val host = runCatching { normalizeUrl(url).toHttpUrl().host.lowercase(Locale.ROOT) }
            .getOrDefault("")
        return host == "hdfull.one" ||
            host == "www.hdfull.one" ||
            host == "hdfull.sbs" ||
            host == "www.hdfull.sbs" ||
            host == "challenges.cloudflare.com"
    }

    private fun buildLoginAutomationScript(): String {
        val username = JSONObject.quote(storedUsername())
        val password = JSONObject.quote(storedPassword())

        return """
            (function() {
                const usernameValue = $username;
                const passwordValue = $password;

                const setValue = (element, value) => {
                    if (!element) return false;
                    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')?.set;
                    if (setter) {
                        setter.call(element, value);
                    } else {
                        element.value = value;
                    }
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                    element.dispatchEvent(new Event('change', { bubbles: true }));
                    return true;
                };

                const triggerSubmit = (form, passwordField) => {
                    const submitSelectors = [
                        '#popup_login_form button[type="submit"]',
                        '#popup_login_form input[type="submit"]',
                        'button[type="submit"]',
                        'input[type="submit"]',
                        'button[name="login"]',
                        '.btn-login',
                        '.login-button',
                        '.popup-login-button'
                    ];

                    const submitButton = document.querySelector(submitSelectors.join(', '));
                    if (submitButton) {
                        submitButton.click();
                        return true;
                    }

                    const loginForm = form ||
                        passwordField?.form ||
                        document.querySelector('#popup_login_form form') ||
                        document.querySelector('form[action*="login" i]') ||
                        document.querySelector('form');

                    if (!loginForm) return false;

                    if (typeof loginForm.requestSubmit === 'function') {
                        loginForm.requestSubmit();
                    } else {
                        const submitEvent = new Event('submit', { bubbles: true, cancelable: true });
                        loginForm.dispatchEvent(submitEvent);
                        if (!submitEvent.defaultPrevented) {
                            loginForm.submit();
                        }
                    }

                    return true;
                };

                const inputSelectors = [
                    'input[name="username"]',
                    'input[name="user"]',
                    'input[name="email"]',
                    'input[name*="login"]',
                    'input[id*="user"]',
                    'input[id*="login"]',
                    'input[placeholder*="user"]',
                    'input[placeholder*="email"]',
                    'input[type="text"]',
                    'input[type="email"]'
                ];
                const passwordSelectors = [
                    'input[type="password"]',
                    'input[name*="pass"]',
                    'input[id*="pass"]'
                ];

                const usernameField = document.querySelector(inputSelectors.join(', '));
                const passwordField = document.querySelector(passwordSelectors.join(', '));

                if (!passwordField) {
                    return false;
                }

                if (usernameField) {
                    setValue(usernameField, usernameValue);
                }
                setValue(passwordField, passwordValue);

                let attempted = false;
                if (typeof window.dologin === 'function') {
                    try {
                        attempted = window.dologin('#popup_login_result') !== false || attempted;
                    } catch (error) {
                        console.log('dologin failed', error);
                    }
                }

                attempted = triggerSubmit(passwordField.form, passwordField) || attempted;
                return attempted;
            })();
        """.trimIndent()
    }

    private fun buildSearchSubmissionScript(quotedQuery: String): String {
        return """
            (function() {
                if (window.__hdfullSearchStarted) return null;
                window.__hdfullSearchStarted = true;

                let result = document.getElementById('hdfull-search-result');
                if (!result) {
                    result = document.createElement('pre');
                    result.id = 'hdfull-search-result';
                    result.style.display = 'none';
                    document.documentElement.appendChild(result);
                }

                const csrf = document.querySelector('input[name="__csrf_magic"]')?.value || '';
                if (!csrf) {
                    result.dataset.done = '1';
                    result.dataset.status = 'missing-csrf';
                    result.textContent = '';
                    return null;
                }

                const params = new URLSearchParams();
                params.set('__csrf_magic', csrf);
                params.set('menu', 'search');
                params.set('query', $quotedQuery);

                fetch('/buscar', {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        'Accept': 'text/html, */*; q=0.01',
                        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                        'X-Requested-With': 'XMLHttpRequest'
                    },
                    body: params.toString()
                }).then(async response => {
                    result.dataset.status = String(response.status);
                    result.textContent = await response.text();
                    result.dataset.done = '1';
                }).catch(error => {
                    result.dataset.status = 'error';
                    result.textContent = String(error && error.message ? error.message : error);
                    result.dataset.done = '1';
                });

                return null;
            })();
        """.trimIndent()
    }

    private fun buildEpisodesSubmissionScript(showId: String, seasonNumber: Int): String {
        val quotedShowId = JSONObject.quote(showId)
        return """
            (function() {
                if (window.__hdfullEpisodesStarted) return null;
                window.__hdfullEpisodesStarted = true;

                let result = document.getElementById('hdfull-episodes-result');
                if (!result) {
                    result = document.createElement('pre');
                    result.id = 'hdfull-episodes-result';
                    result.style.display = 'none';
                    document.documentElement.appendChild(result);
                }

                const params = new URLSearchParams();
                params.set('action', 'season');
                params.set('start', '0');
                params.set('limit', '0');
                params.set('show', $quotedShowId);
                params.set('season', String($seasonNumber));
                params.set('elang', 'ALL');

                fetch('/a/episodes', {
                    method: 'POST',
                    credentials: 'include',
                    headers: {
                        'Accept': 'application/json, text/javascript, */*; q=0.01',
                        'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                        'X-Requested-With': 'XMLHttpRequest'
                    },
                    body: params.toString()
                }).then(async response => {
                    result.dataset.status = String(response.status);
                    result.textContent = await response.text();
                    result.dataset.done = '1';
                }).catch(error => {
                    result.dataset.status = 'error';
                    result.textContent = String(error && error.message ? error.message : error);
                    result.dataset.done = '1';
                });

                return null;
            })();
        """.trimIndent()
    }

    private fun decodeEvaluatedString(value: String?): String {
        val raw = value?.trim().orEmpty()
        if (raw.isBlank() || raw == "null") return ""
        return runCatching { JSONArray("[$raw]").getString(0) }
            .getOrElse { raw.trim('"') }
    }

    private fun storedUsername(): String {
        return UserPreferences.getProviderCache(this, CACHE_USERNAME).trim()
    }

    private fun storedPassword(): String {
        return UserPreferences.getProviderCache(this, CACHE_PASSWORD)
    }

    private fun ensureStoredCredentials() {
        if (!hasStoredCredentials()) {
            throw MissingCredentialsException()
        }
    }

    private fun parseCards(doc: Document, includeEpisodes: Boolean = true): List<AppAdapter.Item> {
        val items = mutableListOf<AppAdapter.Item>()
        val seenKeys = linkedSetOf<String>()

        doc.select(".home-thumb-item, .view").forEach { card ->
            val item = parseCard(card, includeEpisodes) ?: return@forEach
            val key = item.cardKey()
            if (seenKeys.add(key)) {
                items.add(item)
            }
        }

        return items
    }

    private fun parseSearchResults(doc: Document): List<AppAdapter.Item> {
        return parseCards(doc, includeEpisodes = false)
    }

    private fun parseHomeCategories(doc: Document): List<Category> {
        val headings = doc.select("h3.section-title")
        val categories = mutableListOf<Category>()

        headings.forEach { heading ->
            val title = heading.cleanSectionTitle()
            if (title.isBlank()) return@forEach

            val cards = mutableListOf<AppAdapter.Item>()
            val seenKeys = linkedSetOf<String>()
            val container = heading.nextElementSibling()

            var node = container
            while (node != null && !node.isSectionHeading()) {
                node.select(".home-thumb-item, .view").forEach { card ->
                    val item = parseCard(card, includeEpisodes = true) ?: return@forEach
                    val key = item.cardKey()
                    if (seenKeys.add(key)) {
                        cards.add(item)
                    }
                }
                node = node.nextElementSibling()
            }

            if (cards.isNotEmpty()) {
                categories.add(Category(name = title, list = cards))
            }
        }

        return categories
    }

    private fun parseCard(card: org.jsoup.nodes.Element, includeEpisodes: Boolean): AppAdapter.Item? {
        val anchor = card.selectFirst("a[href]") ?: return null
        val href = anchor.attr("href").trim()
        if (href.isBlank() || !isInternalContentLink(href)) {
            return null
        }

        val absoluteUrl = normalizeUrl(href)
        val poster = extractPosterUrl(card, anchor)
        val title = extractCardTitle(card, anchor).ifBlank { absoluteUrl.slugFallbackTitle() }
        val rating = card.selectFirst(".rating")?.text()
            ?.replace("\\s+".toRegex(), "")
            ?.toDoubleOrNull()

        return when {
            href.contains("/pelicula/") -> Movie(
                id = absoluteUrl.substringAfter("/pelicula/").substringBefore('?').trimEnd('/'),
                title = title,
                poster = poster,
                rating = rating,
            ).apply {
                itemType = AppAdapter.Type.MOVIE_GRID_MOBILE_ITEM
            }

            href.contains("/serie/") && href.contains("/episodio-") -> {
                if (!includeEpisodes) return null
                val item = parseEpisodeCard(card, anchor, absoluteUrl, title, poster)
                if (item != null) {
                    TvShow(
                        id = item.tvShow?.id ?: "",
                        title = item.tvShow?.title ?: "",
                        poster = item.poster ?: item.tvShow?.poster,
                    ).apply {
                        itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
                    }
                } else null
            }

            href.contains("/serie/") -> TvShow(
                id = absoluteUrl.substringAfter("/serie/").substringBefore('?').substringBefore("/temporada-").trimEnd('/'),
                title = title,
                poster = poster,
                rating = rating,
            ).apply {
                itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
            }

            else -> null
        }
    }

    private fun parseEpisodeCard(
        card: org.jsoup.nodes.Element,
        anchor: org.jsoup.nodes.Element,
        absoluteUrl: String,
        fallbackTitle: String,
        poster: String?
    ): Episode? {
        val match = Regex("""/serie/([^/]+)/temporada-(\d+)/episodio-(\d+)""", RegexOption.IGNORE_CASE)
            .find(absoluteUrl)
            ?: return null

        val showSlug = match.groupValues.getOrNull(1).orEmpty()
        val seasonNumber = match.groupValues.getOrNull(2)?.toIntOrNull() ?: return null
        val episodeNumber = match.groupValues.getOrNull(3)?.toIntOrNull() ?: return null
        val rawTitle = extractCardTitle(card, anchor).ifBlank { fallbackTitle }
        val showTitle = rawTitle.showTitleFromEpisodeSlug(showSlug)
        val episodeTitle = rawTitle.episodeCardTitle(seasonNumber, episodeNumber)

        val tvShow = TvShow(
            id = showSlug,
            title = showTitle.ifBlank { showSlug.slugToTitle() },
            poster = poster,
        ).apply {
            itemType = AppAdapter.Type.TV_SHOW_GRID_MOBILE_ITEM
        }

        val season = Season(
            id = "$showSlug||$seasonNumber",
            number = seasonNumber,
            title = "Temporada $seasonNumber",
            poster = poster,
            tvShow = tvShow,
        ).apply {
            itemType = AppAdapter.Type.SEASON_MOBILE_ITEM
        }

        return Episode(
            id = absoluteUrl,
            number = episodeNumber,
            title = episodeTitle.ifBlank { "Episodio $episodeNumber" },
            poster = poster,
            tvShow = tvShow,
            season = season,
        ).apply {
            itemType = AppAdapter.Type.EPISODE_MOBILE_ITEM
        }
    }

    private fun extractCardTitle(card: org.jsoup.nodes.Element, anchor: org.jsoup.nodes.Element): String {
        val image = card.selectFirst("img")
        return sequenceOf(
            card.selectFirst("meta[itemprop=name]")?.attr("content"),
            image?.attr("original-title"),
            image?.attr("alt"),
            image?.attr("title"),
            anchor.selectFirst("meta[itemprop=name]")?.attr("content"),
            anchor.attr("title"),
            anchor.text(),
            card.attr("title"),
        ).firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
            .normalizeDisplayText()
    }

    private fun extractPosterUrl(card: org.jsoup.nodes.Element, anchor: org.jsoup.nodes.Element): String? {
        val image = card.selectFirst("img")
        return sequenceOf(
            image?.absUrl("src"),
            image?.attr("src"),
            anchor.attr("data-src"),
            card.attr("data-src"),
        ).firstOrNull { !it.isNullOrBlank() }
            ?.let { it.normalizeThumb() }
    }

    private fun isInternalContentLink(href: String): Boolean {
        val normalized = normalizeUrl(href)
        val host = runCatching { normalized.toHttpUrl().host.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        return host == "hdfull.one" ||
            host == "www.hdfull.one" ||
            host == "hdfull.sbs" ||
            host == "www.hdfull.sbs"
    }

    private fun AppAdapter.Item.cardKey(): String {
        return when (this) {
            is Movie -> "movie:${id}"
            is TvShow -> "tv:${id}"
            is Episode -> "episode:${id}"
            else -> hashCode().toString()
        }
    }

    private fun org.jsoup.nodes.Element.isSectionHeading(): Boolean {
        return tagName().equals("h3", ignoreCase = true) && hasClass("section-title")
    }

    private fun org.jsoup.nodes.Element.closestSectionTitle(): String {
        val heading = previousElementSibling()?.takeIf { it.tagName() in setOf("h1", "h2", "h3", "h4", "h5") }
            ?: parent()?.previousElementSibling()?.takeIf { it.tagName() in setOf("h1", "h2", "h3", "h4", "h5") }

        val headingText = heading?.cleanSectionTitle().orEmpty()
        if (headingText.isNotBlank()) return headingText

        val container = parents().firstOrNull { parent ->
            parent.selectFirst("h1, h2, h3, h4, h5, .title, .section-title, .block-title") != null
        }
        return container
            ?.selectFirst("h1, h2, h3, h4, h5, .title, .section-title, .block-title")
            ?.cleanSectionTitle()
            .orEmpty()
            .ifBlank { "HdFull" }
    }

    private fun org.jsoup.nodes.Element.cleanSectionTitle(): String {
        val clone = clone()
        clone.select(".see-more, .more, .view-more").remove()

        return sequenceOf(
            clone.selectFirst("a")?.text(),
            clone.ownText(),
            clone.text(),
        ).map { it?.normalizeDisplayText() }
            .firstOrNull { !it.isNullOrBlank() }
            .orEmpty()
    }

    private fun String.normalizeDisplayText(): String {
        return replace("""\\[tnr]""".toRegex(), " ")
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun extractSynopsis(doc: Document): String? {
        val overview = doc.selectFirst(".show-overview-text") ?: return null
        val synopsis = StringBuilder()
        for (node in overview.childNodes()) {
            when (node) {
                is org.jsoup.nodes.TextNode -> synopsis.append(node.text())
                is org.jsoup.nodes.Element -> if (node.tagName() == "br") continue else break
            }
        }
        return sequenceOf(
            synopsis.toString(),
            overview.ownText(),
        ).map { it.normalizeDisplayText() }
            .firstOrNull { it.isNotBlank() }
    }

    private inline fun String?.ifNullOrBlank(fallback: () -> String): String {
        return if (this.isNullOrBlank()) fallback() else this
    }

    private fun String.showTitleFromEpisodeSlug(showSlug: String): String {
        val normalized = normalizeDisplayText()
        val stripped = normalized
            .replace(Regex("""\s+\d+x\d+\s*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+\d+x\d+$""", RegexOption.IGNORE_CASE), "")
            .normalizeDisplayText()
        return stripped.ifBlank { showSlug.slugToTitle() }
    }

    private fun String.episodeCardTitle(seasonNumber: Int, episodeNumber: Int): String {
        val normalized = normalizeDisplayText()
        return if (normalized.matches(Regex("""\d+x\d+""", RegexOption.IGNORE_CASE))) {
            "S$seasonNumber E$episodeNumber"
        } else {
            normalized
        }
    }

    private fun String.slugToTitle(): String {
        return replace('-', ' ')
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
            .split(' ')
            .joinToString(" ") { word ->
                word.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }
            }
    }

    private fun String.slugFallbackTitle(): String {
        return substringAfter("/serie/", substringAfterLast('/'))
            .substringBefore('?')
            .substringBefore("/temporada-")
            .substringBefore("/episodio-")
            .substringAfterLast('/')
            .slugToTitle()
    }

    private fun extractShowNumericId(doc: Document): String? {
        val html = doc.outerHtml()
        return sequenceOf(
            Regex("""var\s+sid\s*=\s*['"]?(\d+)['"]?""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1),
            Regex("""(?:let|const)\s+sid\s*=\s*['"]?(\d+)['"]?""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1),
            Regex("""["']sid["']\s*:\s*["']?(\d+)["']?""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.getOrNull(1),
            doc.selectFirst("[data-sid]")?.attr("data-sid"),
            doc.selectFirst("input[name='sid']")?.attr("value"),
            doc.selectFirst("input[name='show']")?.attr("value"),
        ).firstOrNull { !it.isNullOrBlank() }?.trim()
    }

    private suspend fun resolveGenericVideo(url: String, server: Video.Server, depth: Int = 0): Video {
        if (depth > 2) {
            throw IllegalStateException("HdFull generic resolver depth exceeded for $url")
        }

        val requestUrl = normalizeUrl(url)
        val request = okhttp3.Request.Builder()
            .url(requestUrl)
            .header("Referer", baseUrl)
            .header("User-Agent", NetworkClient.USER_AGENT)
            .build()

        val response = NetworkClient.default.newCall(request).execute()
        response.use {
            val html = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw IllegalStateException("Generic resolver request failed: ${it.code}")
            }

            val document = html.toDocument(requestUrl)

            val nestedUrl = sequenceOf(
                document.selectFirst("iframe[src]")?.absUrl("src"),
                document.selectFirst("video[src]")?.absUrl("src"),
                document.selectFirst("source[src]")?.absUrl("src"),
                Regex("""(?:file|src|source|url)\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
            ).firstOrNull { it?.isNotBlank() == true }

            if (!nestedUrl.isNullOrBlank() && nestedUrl != requestUrl && nestedUrl.startsWith("http")) {
                if (looksLikeDirectMediaUrl(nestedUrl)) {
                    return directVideo(nestedUrl, requestUrl)
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
                ?: throw IllegalStateException("No generic media url found for $requestUrl")

            return if (looksLikeDirectMediaUrl(resolved)) {
                directVideo(resolved, requestUrl)
            } else {
                Video(
                    source = resolved,
                    headers = mapOf(
                        "Referer" to requestUrl,
                        "User-Agent" to NetworkClient.USER_AGENT
                    ),
                    extraBuffering = true
                )
            }
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
                .normalizeDisplayText()
                .ifBlank { paragraph.ownText().normalizeDisplayText() }
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
            value.startsWith("http://") || value.startsWith("https://") -> value.rewriteHdFullThumbHost()
            value.startsWith("/") -> "$baseUrl$value"
            else -> "https://hdfullcdn.cc/tthumb/312x176/$value"
        }
    }

    private fun String.normalizeThumb(): String = when {
        startsWith("http://") || startsWith("https://") -> rewriteHdFullThumbHost()
        startsWith("/") -> "$baseUrl$this"
        else -> thumbnailUrl(this) ?: this
    }

    private fun String.rewriteHdFullThumbHost(): String {
        return when {
            contains("://hdfull.one/tthumb/") -> replaceFirst("://hdfull.one/", "://hdfullcdn.cc/")
            contains("://www.hdfull.one/tthumb/") -> replaceFirst("://www.hdfull.one/", "://hdfullcdn.cc/")
            contains("://hdfull.sbs/tthumb/") -> replaceFirst("://hdfull.sbs/", "://hdfullcdn.cc/")
            contains("://www.hdfull.sbs/tthumb/") -> replaceFirst("://www.hdfull.sbs/", "://hdfullcdn.cc/")
            else -> this
        }
    }

    private fun normalizeUrl(url: String): String {
        return if (url.startsWith("http://") || url.startsWith("https://")) {
            url
        } else {
            "$baseUrl${if (url.startsWith("/")) "" else "/"}$url"
        }
    }

    private fun cookieHeaderForLogging(url: String): String {
        val cookieManager = CookieManager.getInstance()
        val host = runCatching { normalizeUrl(url).toHttpUrl().host.lowercase(Locale.ROOT) }.getOrNull().orEmpty()
        val candidates = if (host == "hdfull.one" || host == "www.hdfull.one" || host == "hdfull.sbs" || host == "www.hdfull.sbs") {
            listOf(
                normalizeRequestUrl(url),
                "https://hdfull.one/",
                "https://www.hdfull.one/",
                "https://hdfull.sbs/",
                "https://www.hdfull.sbs/",
            )
        } else {
            listOf(normalizeRequestUrl(url))
        }

        val cookiesByName = linkedMapOf<String, String>()
        candidates.forEach { candidate ->
            runCatching { cookieManager.getCookie(candidate) }
                .getOrNull()
                ?.split(";")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.forEach { cookie ->
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
