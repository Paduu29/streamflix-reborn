package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url

class VidnestExtractor : Extractor() {

    override val name = "Vidnest"
    override val mainUrl = "https://vidnest.io"
    override val aliasUrls = listOf("https://vidnest.fun")

    private val apiClient = NetworkClient.default

    fun extractSubtitles(text: String): List<Video.Subtitle> {
        val tracksBlock = Regex("""tracks\s*:\s*\[(.*?)]""", RegexOption.DOT_MATCHES_ALL)
            .find(text)?.groupValues?.get(1) ?: return emptyList()

        val objectRegex = Regex("""\{(.*?)\}""", RegexOption.DOT_MATCHES_ALL)

        return objectRegex.findAll(tracksBlock).mapNotNull { match ->
            val obj = match.groupValues[1]

            val kind = Regex("""kind\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            if (kind != "captions") return@mapNotNull null

            val rawFile = Regex("""file\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            val label = Regex("""label\s*:\s*"([^"]+)"""").find(obj)?.groupValues?.get(1)
            val default = Regex(""""default"\s*:\s*(true|false)""")
                .find(obj)?.groupValues?.get(1)?.toBoolean() ?: false

            if (rawFile == null || label == null) return@mapNotNull null

            val file = Regex("""https://[^\s"']+""")
                .find(rawFile)?.value ?: return@mapNotNull null

            Video.Subtitle(
                file = file,
                label = label,
                initialDefault = default,
                default = if (UserPreferences.serverAutoSubtitlesDisabled) false else default
            )
        }.toList()
    }

    override suspend fun extract(link: String): Video {
        val newResult = runCatching { extractNewApi(link) }.getOrNull()
        if (newResult != null) return newResult

        return extractLegacy(link)
    }

    private suspend fun extractNewApi(link: String): Video {
        val url = link.toHttpUrl()
        val mediaIndex = url.pathSegments.indexOfFirst { it.equals("movie", true) || it.equals("tv", true) }
        val tmdbId = url.pathSegments.getOrNull(mediaIndex + 1)
            ?: throw Exception("VidNest URL is missing TMDB id")
        val isTv = url.pathSegments.firstOrNull()?.equals("tv", ignoreCase = true) == true
        val season = url.queryParameter("season") ?: url.queryParameter("seasonId")
        val episode = url.queryParameter("episode") ?: url.queryParameter("episodeId")

        val endpointSuffix = if (isTv) {
            "/tv/$tmdbId/${season ?: "1"}/${episode ?: "1"}"
        } else {
            "/movie/$tmdbId"
        }
        val endpoints = listOf(
            "https://new.vidnest.fun/moviebox$endpointSuffix",
            "https://new.vidnest.fun/hollymoviehd$endpointSuffix",
            "https://new.vidnest.fun/nextgencloudfabric$endpointSuffix",
        )

        var lastError: Exception? = null
        for (endpoint in endpoints) {
            try {
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", NetworkClient.USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Referer", "https://vidnest.fun/")
                    .header("Origin", "https://vidnest.fun")
                    .build()
                val body = apiClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
                    response.body?.string().orEmpty()
                }
                val payload = decodeResponse(body)
                val source = findSources(payload)
                    .sortedByDescending { resolution(it.optString("resolution")) }
                    .firstOrNull { it.optString("url").startsWith("http") }
                    ?: throw Exception("No playable source")

                val sourceUrl = source.optString("url")
                return Video(
                    source = sourceUrl,
                    type = if (source.optString("type").equals("hls", true) ||
                        sourceUrl.contains(".m3u8", true)
                    ) "application/x-mpegURL" else null,
                    subtitles = parseApiSubtitles(payload.optJSONArray("subtitles")),
                    headers = mapOf(
                        "User-Agent" to NetworkClient.USER_AGENT,
                    ),
                    useServerSubtitleSetting = true,
                )
            } catch (error: Exception) {
                lastError = error
            }
        }
        throw lastError ?: Exception("VidNest API returned no source")
    }

    private suspend fun extractLegacy(link: String): Video {
        val service = Service.build(mainUrl)
        val doc = service.get(link)

        val scriptTags = doc.select("script[type=text/javascript]")

        var m3u8: String? = null

        var subtitles : List<Video.Subtitle> = emptyList();

        for (script in scriptTags) {
            val scriptData = script.data()
            if ("jwplayer" in scriptData && "sources" in scriptData && "file" in scriptData) {
                val fileRegex = Regex("""file\s*:\s*["']([^"']+)["']""")
                val match = fileRegex.find(scriptData)
                if (match != null) {
                    m3u8 = match.groupValues[1]
                    subtitles = extractSubtitles(scriptData)
                    break
                }
            }
        }

        if (m3u8 == null) {
            throw Exception("Stream URL not found in script tags")
        }

        return Video(
            source = m3u8,
            subtitles = subtitles,
            useServerSubtitleSetting = true
        )
    }

    private fun decodeResponse(body: String): JSONObject {
        val response = JSONObject(body)
        if (!response.has("data") || response.optString("data").isBlank()) return response
        val decoded = decodeCustomBase64(response.getString("data"))
        return runCatching { JSONObject(decoded) }.getOrElse {
            throw Exception("VidNest API returned invalid data")
        }
    }

    private fun findSources(payload: JSONObject): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        payload.optJSONArray("url")?.let { result += sourceObjects(it, "link") }
        payload.optJSONArray("streams")?.let { result += sourceObjects(it, "url") }
        payload.optJSONArray("sources")?.let { result += sourceObjects(it, "url") }
        payload.optJSONArray("downloads")?.let { result += sourceObjects(it, "url") }
        payload.optString("url").takeIf { it.startsWith("http") }?.let {
            result += JSONObject().put("url", it)
        }
        return result
    }

    private fun sourceObjects(array: JSONArray, urlKey: String): List<JSONObject> =
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val source = item.optString(urlKey).takeIf { it.startsWith("http") } ?: return@mapNotNull null
            JSONObject(item.toString()).put("url", source)
        }

    private fun parseApiSubtitles(array: JSONArray?): List<Video.Subtitle> =
        (0 until (array?.length() ?: 0)).mapNotNull { index ->
            val item = array?.optJSONObject(index) ?: return@mapNotNull null
            val file = item.optString("url", item.optString("file"))
                .takeIf { it.startsWith("http") } ?: return@mapNotNull null
            val label = item.optString("lang", item.optString("label", "Unknown"))
            Video.Subtitle(
                file = file,
                label = label,
                initialDefault = label.equals("English", true) || label.equals("en", true),
                default = if (UserPreferences.serverAutoSubtitlesDisabled) false else
                    label.equals("English", true) || label.equals("en", true),
            )
        }

    private fun resolution(value: String): Int =
        Regex("\\d+").find(value)?.value?.toIntOrNull() ?: 0

    private fun decodeCustomBase64(value: String): String {
        val alphabet = "RB0fpH8ZEyVLkv7c2i6MAJ5u3IKFDxlS1NTsnGaqmXYdUrtzjwObCgQP94hoeW+/="
        val output = ByteArray((value.length * 3) / 4 + 3)
        var outputSize = 0
        var offset = 0
        while (offset < value.length) {
            val chunk = value.substring(offset, minOf(offset + 4, value.length)).padEnd(4, '=')
            val digits = chunk.map { character ->
                alphabet.indexOf(character).takeIf { it >= 0 } ?: 64
            }
            output[outputSize++] = ((digits[0] shl 2) or (digits[1] shr 4)).toByte()
            if (digits[2] < 64) {
                output[outputSize++] = (((digits[1] and 15) shl 4) or (digits[2] shr 2)).toByte()
            }
            if (digits[3] < 64) {
                output[outputSize++] = (((digits[2] and 3) shl 6) or digits[3]).toByte()
            }
            offset += 4
        }
        return output.copyOf(outputSize).toString(Charsets.UTF_8)
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                return retrofit.create(Service::class.java)
            }
        }

        @GET
        suspend fun get(@Url url: String): Document
    }
}
