package com.streamflixreborn.streamflix.extractors

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class VidFastExtractor : Extractor() {

    override val name = "VidFast"
    override val mainUrl = "https://vidfast.pro"
    override val aliasUrls = listOf("https://vidfast.vc")

    fun server(videoType: Video.Type): Video.Server {
        val src = when (videoType) {
            is Video.Type.Movie -> {
                val id = videoType.imdbId ?: videoType.id
                "$mainUrl/movie/$id"
            }

            is Video.Type.Episode -> {
                val id = videoType.tvShow.imdbId ?: videoType.tvShow.id
                "$mainUrl/tv/$id/${videoType.season.number}/${videoType.number}"
            }
        }

        return Video.Server(id = name, name = name, src = src)
    }

    override suspend fun extract(link: String): Video {
        val subtitles = withContext(Dispatchers.IO) {
            runCatching { getSubtitles(link) }.getOrDefault(emptyList())
        }

        return captureManifest(link, subtitles)
    }

    private fun getSubtitles(link: String): List<Video.Subtitle> {
        val path = link.toHttpUrl().pathSegments
        val type = path.firstOrNull { it == "movie" || it == "tv" } ?: return emptyList()
        val typeIndex = path.indexOf(type)
        val id = path.getOrNull(typeIndex + 1) ?: return emptyList()

        val subtitleUrl = "$mainUrl/wyzie".toHttpUrl().newBuilder()
            .addQueryParameter("id", id)
            .apply {
                if (type == "tv") {
                    path.getOrNull(typeIndex + 2)?.let { addQueryParameter("season", it) }
                    path.getOrNull(typeIndex + 3)?.let { addQueryParameter("episode", it) }
                }
            }
            .build()

        val request = Request.Builder()
            .url(subtitleUrl)
            .header("Referer", "$mainUrl/")
            .header("User-Agent", NetworkClient.USER_AGENT)
            .build()

        val body = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }

        val preferredLanguage = UserPreferences.providerLanguage
        var defaultAssigned = false
        val items = JSONArray(body)

        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val file = item.optString("url")
                if (file.isBlank()) continue

                val language = item.optString("language")
                val isDefault = !defaultAssigned &&
                        !preferredLanguage.isNullOrBlank() &&
                        language.equals(preferredLanguage, ignoreCase = true)
                if (isDefault) defaultAssigned = true

                add(
                    Video.Subtitle(
                        label = item.optString("display", language),
                        file = file,
                        default = isDefault,
                    )
                )
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun captureManifest(
        link: String,
        subtitles: List<Video.Subtitle>,
    ): Video = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val webView = WebView(StreamFlixApp.instance.applicationContext)
            val handler = Handler(Looper.getMainLooper())
            val resolved = AtomicBoolean(false)

            fun destroyWebView() {
                handler.post {
                    webView.stopLoading()
                    webView.loadUrl("about:blank")
                    webView.destroy()
                }
            }

            val timeout = Runnable {
                if (resolved.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resumeWithException(Exception("Timeout waiting for VidFast stream"))
                }
                destroyWebView()
            }

            handler.postDelayed(timeout, STREAM_TIMEOUT_MS)
            continuation.invokeOnCancellation {
                resolved.set(true)
                handler.removeCallbacks(timeout)
                destroyWebView()
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = NetworkClient.USER_AGENT
                mediaPlaybackRequiresUserGesture = false
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val streamUrl = request?.url?.toString().orEmpty()
                    if (streamUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true) &&
                        resolved.compareAndSet(false, true)
                    ) {
                        handler.removeCallbacks(timeout)
                        if (continuation.isActive) {
                            val requestHeaders = request?.requestHeaders.orEmpty()
                            val referer = requestHeaders.entries.firstOrNull {
                                it.key.equals("Referer", ignoreCase = true)
                            }?.value ?: "$mainUrl/"
                            val origin = requestHeaders.entries.firstOrNull {
                                it.key.equals("Origin", ignoreCase = true)
                            }?.value ?: referer.removeSuffix("/")

                            continuation.resume(
                                Video(
                                    source = streamUrl,
                                    subtitles = subtitles,
                                    headers = mapOf(
                                        "Referer" to referer,
                                        "Origin" to origin,
                                    ),
                                )
                            )
                        }
                        destroyWebView()
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            webView.loadUrl(link)
        }
    }

    private companion object {
        private const val STREAM_TIMEOUT_MS = 45_000L
        private val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
