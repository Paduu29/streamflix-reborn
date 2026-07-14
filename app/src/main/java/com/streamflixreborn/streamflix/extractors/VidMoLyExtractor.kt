package com.streamflixreborn.streamflix.extractors

import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.models.Video
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Url
import org.jsoup.nodes.Document
import okhttp3.OkHttpClient
import java.net.URI

open class VidMoLyExtractor : Extractor() {
    override val name = "VidMoLy"
    override val mainUrl = "https://vidmoly.me/"
    override val aliasUrls = listOf("https://vidmoly.net", "https://vidmoly.biz")

    override suspend fun extract(link: String): Video {
        val origin = URI(link).let { "${it.scheme}://${it.authority}/" }
        val service = Service.build(origin)

        val document = service.get(link, origin)

        val hlsUrl = extractHlsUrl(document)
            ?: throw Exception("Could not find HLS source in the webpage")

        return Video(
            source = hlsUrl,
            subtitles = extractSubtitles(document),
            headers = mapOf(
                "Referer" to origin,
                "User-Agent" to USER_AGENT
            )
        )
    }

    private fun extractHlsUrl(document: Document): String? {
        return Regex(
            """sources\s*:\s*\[\s*\{\s*file\s*:\s*['"]([^'"]+)['"]""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(document.toString())?.groupValues?.get(1)
    }

    private fun extractSubtitles(document: Document): List<Video.Subtitle> {
        val tracks = Regex(
            """baseTracks\s*=\s*\[(.*?)]\s*;""",
            RegexOption.DOT_MATCHES_ALL,
        ).find(document.toString())?.groupValues?.get(1) ?: return emptyList()

        return runCatching {
            Regex(
                """\{[^}]*?file\s*:\s*['"]([^'"]+)['"][^}]*?label\s*:\s*['"]([^'"]+)['"][^}]*?kind\s*:\s*['"]captions['"][^}]*?\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).findAll(tracks).map { match ->
                Video.Subtitle(
                    label = match.groupValues[2],
                    file = match.groupValues[1],
                    default = match.value.contains(
                        Regex("""["']?default["']?\s*:\s*true"""),
                    ),
                )
            }.toList()
        }.getOrDefault(emptyList())
    }

    class ToDomain: VidMoLyExtractor(){
        override val mainUrl: String = "https://vidmoly.to/"
    }

    private interface Service {
        companion object {
            fun build(baseUrl: String): Service = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(OkHttpClient.Builder().build())
                .addConverterFactory(JsoupConverterFactory.create())
                .build()
                .create(Service::class.java)
        }

        @GET
        suspend fun get(
            @Url url: String,
            @Header("Referer") referer: String,
            @Header("Accept") accept: String = "text/html",
            @Header("User-Agent") userAgent: String = USER_AGENT,
        ): Document
    }

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    }
}
