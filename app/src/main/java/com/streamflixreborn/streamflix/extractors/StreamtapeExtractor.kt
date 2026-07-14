package com.streamflixreborn.streamflix.extractors

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import kotlinx.coroutines.delay
import okhttp3.ResponseBody
import org.jsoup.nodes.Document
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.Streaming
import retrofit2.http.Url

class StreamtapeExtractor : Extractor() {

    override val name = "Streamtape"
    override val mainUrl = "https://streamtape.com"
    override val aliasUrls = listOf("https://streamta.site")

    override suspend fun extract(link: String): Video {
        val service = StreamtapeExtractorService.build(mainUrl)
        var botLink: String? = null

        for (attempt in 1..MAX_SOURCE_ATTEMPTS) {
            botLink = extractBotLink(service.getSource(link))
            if (botLink != null) break

            if (attempt < MAX_SOURCE_ATTEMPTS) {
                Log.w(TAG, "botlink payload missing on attempt $attempt; retrying")
                delay(SOURCE_RETRY_DELAY_MS * attempt)
            }
        }

        val finalVideoUrl = normalizeBotLink(
            botLink ?: throw Exception(
                "botlink JavaScript not found after $MAX_SOURCE_ATTEMPTS attempts",
            ),
        )

        val response = service.getVideo(finalVideoUrl)
        val sourceUrl = (response.raw() as okhttp3.Response).networkResponse?.request?.url?.toString()
            ?: throw Exception("Can't retrieve URL")

        val video = Video(
            source = sourceUrl,
            subtitles = listOf()
        )
        return video
    }

    private fun extractBotLink(source: Document): String? {
        val match = BOTLINK_REGEX.find(source.html()) ?: return null
        val prefix = match.groupValues[1]
        val encodedParams = match.groupValues[2]
        val substringIndex = match.groupValues[3].toIntOrNull() ?: return null
        if (substringIndex !in 0..encodedParams.length) return null

        // The `id` is commonly in the first JavaScript fragment, so validate the
        // complete reconstructed URL rather than only the substring fragment.
        val reconstructed = prefix + encodedParams.substring(substringIndex)
        val decoded = reconstructed
            .replace("\\/", "/")
            .replace("&amp;", "&")

        return decoded.takeIf { url ->
            REQUIRED_QUERY_PARAMETERS.all { parameter -> "$parameter=" in url }
        }
    }

    private fun normalizeBotLink(botLink: String): String {
        val absoluteUrl = when {
            botLink.startsWith("//") -> "https:$botLink"
            botLink.startsWith("/") -> "$mainUrl$botLink"
            botLink.startsWith("http://") || botLink.startsWith("https://") -> botLink
            else -> "$mainUrl/${botLink.trimStart('/')}"
        }
        return if ("stream=" in absoluteUrl) absoluteUrl else "$absoluteUrl&stream=1"
    }

    private companion object {
        const val TAG = "StreamtapeExtractor"
        const val MAX_SOURCE_ATTEMPTS = 3
        const val SOURCE_RETRY_DELAY_MS = 500L

        val REQUIRED_QUERY_PARAMETERS = listOf("id", "expires", "ip", "token")
        val BOTLINK_REGEX = Regex(
            """document\.getElementById\(['\"]botlink['\"]\)\.innerHTML\s*=\s*['\"]([^'\"]+)['\"]\s*\+\s*\(['\"]([^'\"]+)['\"]\)\.substring\s*\(\s*(\d+)\s*\)""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }

    private interface StreamtapeExtractorService {
        companion object {
            fun build(baseUrl: String): StreamtapeExtractorService {
                val retrofit = Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()

                return retrofit.create(StreamtapeExtractorService::class.java)
            }
        }

        @GET
        @Headers(
            "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Cache-Control: no-cache",
        )
        suspend fun getSource(@Url url: String): Document

        @GET
        @Streaming
        @Headers("User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
        suspend fun getVideo(@Url url: String): Response<ResponseBody>
    }
}
