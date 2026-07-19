package com.streamflixreborn.streamflix.extractors

import android.net.Uri
import com.google.gson.JsonParser
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.WebViewResolver

/** Extracts the media URL from Screenscape's player page. */
class ScreenscapeExtractor : Extractor() {

    override val name = "Screenscape"
    override val mainUrl = "https://screenscape.me"

    private val resolver by lazy { WebViewResolver(StreamFlixApp.instance) }

    override suspend fun extract(link: String): Video {
        val result = resolver.getResult(
            url = link,
            headers = mapOf(
                "Referer" to "https://redflix.one/",
                "User-Agent" to NetworkClient.USER_AGENT,
            ),
            completion = { _, html, _ ->
                html.contains("<video", ignoreCase = true) ||
                    html.contains(".m3u8", ignoreCase = true) ||
                    html.contains(".mp4", ignoreCase = true)
            },
            valueScript = """
                (function() {
                    var videos = Array.from(document.querySelectorAll('video'));
                    for (var i = 0; i < videos.length; i++) {
                        var video = videos[i];
                        var source = video.currentSrc || video.src ||
                            Array.from(video.querySelectorAll('source'))
                                .map(function(item) { return item.src; })
                                .find(function(item) { return !!item; });
                        if (source) return source;
                    }
                    return '';
                })();
            """.trimIndent(),
            pageReadyScriptProvider = { _, _, _ ->
                """
                    (function() {
                        function activate() {
                            Array.from(document.querySelectorAll('video')).forEach(function(video) {
                                video.muted = true;
                                video.autoplay = true;
                                video.setAttribute('playsinline', '');
                                var playback = video.play();
                                if (playback && playback.catch) playback.catch(function() {});
                            });
                            var button = document.querySelector(
                                'button[aria-label*="play" i], [role="button"][aria-label*="play" i], [class*="play" i]'
                            );
                            if (button) {
                                try { button.click(); } catch (e) {}
                            }
                        }
                        activate();
                        setTimeout(activate, 300);
                        setTimeout(activate, 1000);
                    })();
                """.trimIndent()
            },
            requireEvaluatedValue = true,
        )

        val source = result.evaluatedValue
            ?.let { runCatching { JsonParser.parseString(it).asString }.getOrNull() }
            ?.takeIf { it.startsWith("http") }
            ?: throw Exception("Media source not found in $link")

        val uri = Uri.parse(link)
        val origin = if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null

        return Video(
            source = source,
            type = if (source.contains(".m3u8", ignoreCase = true)) "application/x-mpegURL" else null,
            headers = buildMap {
                put("Referer", link)
                put("User-Agent", NetworkClient.USER_AGENT)
                origin?.let { put("Origin", it) }
            },
        )
    }
}
