package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.JsUnpacker
import com.streamflixreborn.streamflix.utils.WebViewResolver
import org.jsoup.Jsoup

class PowvideoExtractor : Extractor() {

    override val name = "Powvideo"
    override val mainUrl = "https://powvideo.org"
    override val aliasUrls = listOf("https://powwideo.org")

    override suspend fun extract(link: String): Video {
        val resolver = WebViewResolver(StreamFlixApp.instance)
        val result = resolver.getResult(
            url = link,
            headers = mapOf("Referer" to "https://hdfull.one/"),
            completion = { currentUrl, pageHtml, _ ->
                currentUrl.contains("/video-", ignoreCase = true) &&
                    pageHtml.contains("jwplayer", ignoreCase = true)
            },
            shouldAllowNavigation = { url, isMainFrame ->
                if (!isMainFrame) true else isAllowedPowvideoNavigation(url)
            },
            valueScript = PLAYER_STATE_SCRIPT
        )

        return extractVideo(result.html, link, decodeJsValue(result.evaluatedValue))
    }

    private fun extractVideo(html: String, requestedUrl: String, playerState: String?): Video {
        val document = Jsoup.parse(html).apply { setBaseUri(requestedUrl) }
        val referer = normalizeReferer(document.baseUri().ifBlank { requestedUrl })

        val candidates = buildList {
            playerState?.takeIf { it.isNotBlank() }?.let(::add)
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

        val source = extractSource(candidates)
            ?: throw IllegalStateException("No Powvideo media source found")

        val subtitles = extractSubtitles(candidates).orEmpty()

        return Video(
            source = absolutize(source, document.baseUri().ifBlank { requestedUrl }),
            subtitles = subtitles,
            headers = mapOf(
                "Referer" to referer,
                "Origin" to referer.removeSuffix("/"),
                "User-Agent" to USER_AGENT
            )
        )
    }

    private fun extractSource(candidates: List<String>): String? {
        val combined = candidates.joinToString("\n")
        extractDirectSource(combined)?.let { return it }

        val variableMap = buildVariableMap(combined)
        extractVariableBackedSource(combined, variableMap)?.let { return it }

        return candidates.firstNotNullOfOrNull(::extractDirectSource)
    }

    private fun extractDirectSource(content: String): String? {
        val patterns = listOf(
            Regex("""files\s*:\s*\[\s*\{[^}]*m\s*:\s*["']([^"'#]+(?:m3u8|mp4|m4v|webm|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""\bm\s*:\s*["']([^"'#]+(?:m3u8|mp4|m4v|webm|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*["']([^"'#]+(?:m3u8|mp4|m4v|webm|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']file["']\s*:\s*["']([^"'#]+(?:m3u8|mp4|m4v|webm|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""["']src["']\s*:\s*["']([^"'#]+(?:m3u8|mp4|m4v|webm|mpd)[^"']*)["']""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^"'\\s>]+(?:m3u8|mp4|m4v|webm|mpd)[^"'\\s<]*""", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { regex ->
            regex.find(content)?.groupValues?.getOrNull(1) ?: regex.find(content)?.value
        }
    }

    private fun buildVariableMap(content: String): Map<String, String> {
        val assignments = Regex(
            """(?:var\s+)?([A-Za-z_$][A-Za-z0-9_$]*)\s*=\s*["']((?:https?:)?//[^"'#]+(?:m3u8|mp4|m4v|webm|mpd)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        return assignments.findAll(content).associate { match ->
            match.groupValues[1] to match.groupValues[2]
        }
    }

    private fun extractVariableBackedSource(content: String, variableMap: Map<String, String>): String? {
        val variablePatterns = listOf(
            Regex("""files\s*:\s*\[\s*\{[^}]*m\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""", RegexOption.IGNORE_CASE),
            Regex("""sources\s*:\s*\[\s*\{[^}]*file\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""", RegexOption.IGNORE_CASE),
            Regex("""\bm\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""", RegexOption.IGNORE_CASE),
            Regex("""\bfile\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""", RegexOption.IGNORE_CASE),
            Regex("""\bsrc\s*:\s*([A-Za-z_$][A-Za-z0-9_$]*)""", RegexOption.IGNORE_CASE)
        )
        return variablePatterns.firstNotNullOfOrNull { regex ->
            regex.find(content)?.groupValues?.getOrNull(1)?.let(variableMap::get)
        }
    }

    private fun extractSubtitles(candidates: List<String>): List<Video.Subtitle>? {
        val trackBlock = Regex("""tracks\s*:\s*\[(.*?)]""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(candidates.joinToString("\n"))
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

    private fun normalizeReferer(url: String): String {
        val uri = Uri.parse(url)
        return "${uri.scheme}://${uri.host}/"
    }

    private fun decodeJsValue(value: String?): String? {
        if (value.isNullOrBlank() || value == "null") return null
        return value.removeSurrounding("\"")
            .replace("\\\\", "\\")
            .replace("\\u003C", "<")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
    }

    private fun isAllowedPowvideoNavigation(url: String): Boolean {
        val host = Uri.parse(url).host?.lowercase().orEmpty()
        if (host.isBlank()) return false
        if (host == "powvideo.org" || host == "www.powvideo.org") return true
        if (host == "powwideo.org" || host == "www.powwideo.org") return true
        if (host == "www.google.com" || host == "google.com") return true
        if (host == "www.gstatic.com" || host == "gstatic.com") return true
        if (host == "recaptcha.net" || host == "www.recaptcha.net") return true
        return false
    }

    companion object {
        private const val PLAYER_STATE_SCRIPT =
            """(function(){try{var p=typeof jwplayer==='function'?jwplayer('vplayer'):null;var item=p&&p.getPlaylistItem?p.getPlaylistItem():null;var playlist=p&&p.getPlaylist?p.getPlaylist():null;return JSON.stringify({item:item,playlist:playlist});}catch(e){return '';}})();"""
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }
}
