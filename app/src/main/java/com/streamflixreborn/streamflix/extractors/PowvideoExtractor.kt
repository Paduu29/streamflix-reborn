package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import okhttp3.OkHttpClient
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url

class PowvideoExtractor : Extractor() {

    override val name = "Powvideo"
    override val mainUrl = "https://powvideo.org"

    override suspend fun extract(link: String): Video {
        val referer = origin(link)
        val document = Service.build(mainUrl).get(link, referer)
        return extractVideo(document, link, referer)
    }

    private fun extractVideo(document: Document, pageUrl: String, referer: String): Video {
        val candidates = buildList {
            add(document.outerHtml())
            document.select("script").forEach { script ->
                val content = script.data().ifBlank { script.html() }
                if (content.isNotBlank()) {
                    add(content)
                    if (content.contains("eval(function(p,a,c,k,e,d)")) {
                        JsUnpacker(content).unpack()?.let(::add)
                    }
                }
            }
        }

        val source = candidates.firstNotNullOfOrNull(::extractSource)
            ?: throw IllegalStateException("No Powvideo media source found")

        val subtitles = candidates.firstNotNullOfOrNull(::extractSubtitles).orEmpty()

        return Video(
            source = absolutize(source, pageUrl),
            subtitles = subtitles,
            headers = mapOf(
                "Referer" to referer,
                "Origin" to referer.removeSuffix("/"),
                "User-Agent" to USER_AGENT
            )
        )
    }

    private fun extractSource(content: String): String? {
        val patterns = listOf(
            Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"'#]+(?:m3u8|mp4|m4v|webm|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']file["']\s*:\s*["']([^"'#]+(?:m3u8|mp4|m4v|webm|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']src["']\s*:\s*["']([^"'#]+(?:m3u8|mp4|m4v|webm|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\\s>]+(?:m3u8|mp4|m4v|webm|mpd)[^"'\\s<]*""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(content)?.groupValues?.getOrNull(1) ?: regex.find(content)?.value
        }
    }

    private fun extractSubtitles(content: String): List<Video.Subtitle>? {
        val trackBlock = Regex("""tracks\s*:\s*\[(.*?)]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(content)
            ?.groupValues
            ?.getOrNull(1)
            ?: return null

        val subtitles = Regex(
            """file\s*:\s*["']([^"']+)["']\s*,\s*label\s*:\s*["']([^"']*)["'][^}]*kind\s*:\s*["']captions["']""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        ).findAll(trackBlock).map {
            Video.Subtitle(
                label = it.groupValues[2].ifBlank { "Subtitle" },
                file = it.groupValues[1]
            )
        }.toList()

        return subtitles.ifEmpty { null }
    }

    private fun absolutize(url: String, pageUrl: String): String {
        if (url.startsWith("http")) return url
        val uri = Uri.parse(pageUrl)
        return "${uri.scheme}://${uri.host}$url"
    }

    private fun origin(url: String): String {
        val uri = Uri.parse(url)
        return "${uri.scheme}://${uri.host}/"
    }

    private interface Service {
        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("User-Agent") userAgent: String = USER_AGENT
        ): Document

        companion object {
            fun build(baseUrl: String): Service {
                return Retrofit.Builder()
                    .baseUrl(baseUrl)
                    .client(OkHttpClient.Builder().build())
                    .addConverterFactory(JsoupConverterFactory.create())
                    .build()
                    .create(Service::class.java)
            }
        }
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
