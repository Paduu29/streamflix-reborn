package com.streamflixreborn.streamflix.extractors

import android.text.Html
import android.util.Base64
import androidx.core.net.toUri
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import java.util.concurrent.TimeUnit

class VidsrcNetExtractor : Extractor() {

    override val name = "Vidsrc.net"
    override val mainUrl = "https://vsembed.su"
    override val aliasUrls = listOf("https://vsembed.ru", "https://vidsrc-embed.ru")

    fun server(videoType: Video.Type): Video.Server {
        return Video.Server(
            id = name,
            name = name,
            src = when (videoType) {
                is Video.Type.Episode -> {
                    val id = videoType.tvShow.imdbId ?: videoType.tvShow.id
                    val idType = if (videoType.tvShow.imdbId != null) "imdb" else "tmdb"
                    "$mainUrl/embed/tv?$idType=$id&season=${videoType.season.number}&episode=${videoType.number}"
                }

                is Video.Type.Movie -> {
                    val id = videoType.imdbId ?: videoType.id
                    val idType = if (videoType.imdbId != null) "imdb" else "tmdb"
                    "$mainUrl/embed/movie?$idType=$id"
                }
            },
        )
    }

    override suspend fun extract(link: String): Video {
        val service = Service.build(mainUrl)
        val initialDoc = service.get(link)

        extractDirectStream(initialDoc.toString(), link, service)?.let { return it }

        val iframeSource = initialDoc.selectFirst("iframe#player_iframe")?.attr("src")
            ?: Regex("""src\s*:\s*["']([^"']*/(?:pro)?rcp/[^"']+)["']""")
                .find(initialDoc.toString())?.groupValues?.get(1)

        val iframedoc = iframeSource
            ?.let { resolveUrl(link, it) }
            ?: throw Exception("Can't retrieve player iframe or master URLs")

        val doc = service.get(iframedoc, link)
        extractDirectStream(doc.toString(), iframedoc, service)?.let { return it }

        val prorcp = if (iframedoc.toUri().path?.startsWith("/prorcp/") == true) {
            iframedoc
        } else {
            Regex("""src\s*:\s*["'](/prorcp/[^"']+)["']""")
                .find(doc.toString())?.groupValues?.get(1)
                ?.let { baseOrigin(iframedoc) + it }
                ?: throw Exception("Can't retrieve prorcp")
        }

        val script = if (prorcp == iframedoc) {
            doc.toString()
        } else {
            service.get(prorcp, referer = iframedoc).toString()
        }

        extractDirectStream(script, prorcp, service)?.let { return it }

        val playerId = Regex("Playerjs.*file: ([a-zA-Z0-9]*?) ,")
            .find(script)?.groupValues?.get(1)
            ?: ""

        val decryptedData =
            if (playerId.isNotBlank()) {
                val encryptedSource =
                    Regex("""<div id="$playerId" style="display:none;">\s*(.*?)\s*</div>""")
                        .find(script)?.groupValues?.get(1)
                        ?: throw Exception("Can't retrieve encrypted source")

                decrypt(playerId, encryptedSource)
            } else {
                Regex("""Playerjs.*file: "([^"]*?)" ,""")
                    .find(script)?.groupValues?.get(1)
            }

        if (decryptedData.isNullOrBlank())
            throw Exception("Can't retrieve file")

        val streamUrl = decryptedData.split(" or ")
            .firstOrNull()
            ?.replace(Regex("\\{[a-z]\\d+\\}"), "quibblezoomfable.com")
            ?: throw Exception("No stream found after decryption")

        return buildVideo(streamUrl, script, iframedoc)
    }

    private suspend fun extractDirectStream(
        script: String,
        playerUrl: String,
        service: Service,
    ): Video? {
        val masterUrls = Regex("""master_urls\s*=\s*["']([^"']+)["']""")
            .find(script)?.groupValues?.get(1)
            ?: return null

        val tokens = mutableMapOf<String, String>()
        val resolvedCandidates = masterUrls.split(" or ")
            .mapNotNull { candidate ->
                runCatching {
                    val placeholder = Regex("__TOKEN(?:PG)?__")
                    if (!placeholder.containsMatchIn(candidate)) return@runCatching candidate

                    val streamUri = candidate.toUri()
                    val origin = "${streamUri.scheme}://${streamUri.authority}"
                    val token = tokens[origin]
                        ?: service.get("$origin/generate.php", playerUrl).text().trim()
                            .also { tokens[origin] = it }
                    if (token.isBlank()) error("Empty stream token from $origin")
                    candidate.replace(placeholder, token)
                }.getOrNull()
            }

        val streamUrl = withContext(Dispatchers.IO) {
            resolvedCandidates.mapNotNull { candidate ->
                probePlaylist(candidate, playerUrl)
            }
        }
            .sortedByDescending(PlaylistCandidate::isMediaPlaylist)
            .firstOrNull()?.url
            ?: throw Exception("No working VSEmbed playlist found")

        return buildVideo(streamUrl, script, playerUrl)
    }

    private fun probePlaylist(url: String, playerUrl: String): PlaylistCandidate? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("Referer", playerUrl)
                .header("Origin", baseOrigin(playerUrl))
                .header("Accept-Encoding", "identity")
                .build()

            playlistProbeClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val manifest = response.body?.string().orEmpty()
                if (!manifest.lineSequence().any { it.trim() == "#EXTM3U" }) return null

                PlaylistCandidate(
                    url = url,
                    isMediaPlaylist = manifest.lineSequence().any {
                        it.trimStart().startsWith("#EXTINF:")
                    },
                )
            }
        }.getOrNull()
    }

    private fun buildVideo(streamUrl: String, script: String, playerUrl: String): Video {
        val regex = Regex(
            """default_subtitles\s*=\s*["']([^"']+)["']""",
            RegexOption.DOT_MATCHES_ALL
        )

        val subtitlesRaw = regex.find(script)?.groupValues?.get(1) ?: ""
        val subtitles = if (subtitlesRaw.isNotBlank()) {
            /* Subtitles are selected based on the provider's language, except when the language is English */
            val preferredSubtitle =
                if (subtitlesRaw.isNotEmpty() &&
                    !UserPreferences.providerLanguage.isNullOrEmpty() && UserPreferences.providerLanguage != "en")
                    UserPreferences.providerLanguage.orEmpty()
                else ""

            var alreadySet = false
            @Suppress("DEPRECATION")
            subtitlesRaw.split(",")
                .mapNotNull { item ->
                    val language = item.substringAfter("[")
                        .substringBefore("]")
                    val url = item.substringAfter("]")
                    if (url.isBlank()) return@mapNotNull null
                    Video.Subtitle(
                        label = Html.fromHtml(language).toString(),
                        file = resolveUrl(playerUrl, url),
                        default = if (alreadySet == false && preferredSubtitle.isNotEmpty() && language.contains(
                                preferredSubtitle, ignoreCase = true
                            )) {
                            alreadySet = true
                            true
                        } else false
                    )
                }
        } else emptyList()
        return Video(
            source = streamUrl,
            subtitles = subtitles,
            headers = mapOf(
                "Referer" to playerUrl,
                "Origin" to baseOrigin(playerUrl),
                "Accept-Encoding" to "identity",
            ),
        )
    }

    private fun resolveUrl(baseUrl: String, url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> baseOrigin(baseUrl) + url
            else -> baseUrl.substringBeforeLast("/", baseUrl) + "/" + url
        }
    }

    private fun baseOrigin(url: String): String {
        val uri = url.toUri()
        return "${uri.scheme}://${uri.authority}"
    }

    private data class PlaylistCandidate(
        val url: String,
        val isMediaPlaylist: Boolean,
    )

    private fun decrypt(id: String, encrypted: String): String {
        return when (id) {
            "NdonQLf1Tzyx7bMG" -> NdonQLf1Tzyx7bMG(encrypted)
            "sXnL9MQIry" -> sXnL9MQIry(encrypted)
            "IhWrImMIGL" -> IhWrImMIGL(encrypted)
            "xTyBxQyGTA" -> xTyBxQyGTA(encrypted)
            "ux8qjPHC66" -> ux8qjPHC66(encrypted)
            "eSfH1IRMyL" -> eSfH1IRMyL(encrypted)
            "KJHidj7det" -> KJHidj7det(encrypted)
            "o2VSUnjnZl" -> o2VSUnjnZl(encrypted)
            "Oi3v1dAlaM" -> Oi3v1dAlaM(encrypted)
            "TsA2KGDGux" -> TsA2KGDGux(encrypted)
            "JoAHUMCLXV" -> JoAHUMCLXV(encrypted)
            else -> throw Exception("Encryption type not implemented yet: $id")
        }
    }

    private fun NdonQLf1Tzyx7bMG(a: String): String {
        val b = 3
        val c = mutableListOf<String>()
        for (d in a.indices step b) {
            c.add(a.substring(d, minOf(d + b, a.length)))
        }
        return c.reversed().joinToString("")
    }

    private fun sXnL9MQIry(a: String): String {
        val b = "pWB9V)[*4I`nJpp?ozyB~dbr9yt!_n4u"
        val d = a.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        var c = ""
        for (e in d.indices) {
            c += (d[e].code xor b[e % b.length].code).toChar()
        }
        var e = ""
        for (ch in c) {
            e += (ch.code - 3).toChar()
        }
        return String(Base64.decode(e, Base64.DEFAULT))
    }

    private fun IhWrImMIGL(a: String): String {
        val b = a.reversed()
        val c = b.map { ch ->
            when {
                (ch in 'a'..'m') || (ch in 'A'..'M') -> (ch.code + 13).toChar()
                (ch in 'n'..'z') || (ch in 'N'..'Z') -> (ch.code - 13).toChar()
                else -> ch
            }
        }.joinToString("")
        val d = c.reversed()
        return String(Base64.decode(d, Base64.DEFAULT))
    }

    private fun xTyBxQyGTA(a: String): String {
        val b = a.reversed()
        val c = b.filterIndexed { index, _ -> index % 2 == 0 }
        return Base64.decode(c, Base64.DEFAULT).toString(Charsets.UTF_8)
    }

    private fun ux8qjPHC66(a: String): String {
        val b = a.reversed()
        val c = "X9a(O;FMV2-7VO5x;Ao\u0005:dN1NoFs?j,"
        val d = b.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        var e = ""
        for (i in d.indices) {
            e += (d[i].code xor c[i % c.length].code).toChar()
        }
        return e
    }

    private fun eSfH1IRMyL(a: String): String {
        val b = a.reversed()
        val c = b.map { (it.code - 1).toChar() }.joinToString("")
        val d = c.chunked(2).map { it.toInt(16).toChar() }.joinToString("")
        return d
    }

    private fun KJHidj7det(a: String): String {
        val b = a.substring(10, a.length - 16)
        val c = "3SAY~#%Y(V%>5d/Yg\"\$G[Lh1rK4a;7ok"
        val d = String(Base64.decode(b, Base64.DEFAULT))
        val e = c.repeat((d.length + c.length - 1) / c.length).take(d.length)
        var f = ""
        for (i in d.indices) {
            f += (d[i].code xor e[i].code).toChar()
        }
        return f
    }

    private fun o2VSUnjnZl(a: String): String {
        val shift = 3

        return a.map { char ->
            when (char) {
                in 'a'..'z' -> {
                    val shifted = char - shift
                    if (shifted < 'a') shifted + 26 else shifted
                }
                in 'A'..'Z' -> {
                    val shifted = char - shift
                    if (shifted < 'A') shifted + 26 else shifted
                }
                else -> char
            }
        }.joinToString("")
    }

    private fun Oi3v1dAlaM(a: String): String {
        val b = a.reversed()
        val c = b.replace("-", "+").replace("_", "/")
        val d = String(Base64.decode(c, Base64.DEFAULT))
        var e = ""
        val f = 5
        for (ch in d) {
            e += (ch.code - f).toChar()
        }
        return e
    }

    private fun TsA2KGDGux(a: String): String {
        val b = a.reversed()
        val c = b.replace("-", "+").replace("_", "/")
        val d = String(Base64.decode(c, Base64.DEFAULT))
        var e = ""
        val f = 7
        for (ch in d) {
            e += (ch.code - f).toChar()
        }
        return e
    }

    private fun JoAHUMCLXV(a: String): String {
        val b = a.reversed()
        val c = b.replace("-", "+").replace("_", "/")
        val d = String(Base64.decode(c, Base64.DEFAULT))
        var e = ""
        val f = 3
        for (ch in d) {
            e += (ch.code - f).toChar()
        }
        return e
    }



    private interface Service {

        companion object {
            private val client = OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .connectTimeout(30, TimeUnit.SECONDS)
                .dns(DnsResolver.doh)
                .build()

            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()

                return retrofit.create(Service::class.java)
            }
        }

        @GET
        suspend fun get(
            @Url url: String,
            @Header("referer") referer: String = ""
        ): Document
    }

    private companion object {
        val playlistProbeClient: OkHttpClient = OkHttpClient.Builder()
            .readTimeout(15, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .dns(DnsResolver.doh)
            .build()
    }
}