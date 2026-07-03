package com.streamflixreborn.streamflix.ui

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
import com.streamflixreborn.streamflix.utils.ArtworkRequestHeaders
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.NetworkClient
import okhttp3.*
import okhttp3.OkHttpClient.Builder
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.io.InputStream
import java.security.SecureRandom
import java.util.*
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

@GlideModule
class GlideCustomModule : AppGlideModule() {
    private companion object {
        private const val HDFULL_CDN_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
        private const val HDFULL_CDN_ACCEPT =
            "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"
        private const val HDFULL_CDN_ACCEPT_LANGUAGE =
            "es-ES,es;q=0.9,en-US;q=0.8,en;q=0.7"
        private const val HDFULL_CDN_REFERER = "https://hdfull.one/"
        private const val HDFULL_CDN_SEC_CH_UA =
            "\"Not/A)Brand\";v=\"99\", \"Google Chrome\";v=\"116\", \"Chromium\";v=\"116\""
        private const val HDFULL_CDN_SEC_CH_UA_MOBILE = "?1"
        private const val HDFULL_CDN_SEC_CH_UA_PLATFORM = "\"Android\""
        private const val HDFULL_CDN_PRIORITY = "u=0, i"
    }

    private fun getOkHttpClient(context: Context): OkHttpClient {
        val appCache = Cache(File(context.cacheDir, "glide-okhttp-cache"), 10 * 1024 * 1024)

        val logging = HttpLoggingInterceptor().setLevel(HttpLoggingInterceptor.Level.BASIC)

        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
            }
        )
        val sslContext = SSLContext.getInstance("TLS").apply { init(null, trustAllCerts, SecureRandom()) }
        val trustManager = trustAllCerts[0] as X509TrustManager

        return Builder()
            .cache(appCache)
            .cookieJar(imageCookieJar)
            .addInterceptor { chain ->
                val original = chain.request()
                val requestBuilder = original.newBuilder()
                val host = original.url.host.lowercase(Locale.ROOT)
                val isHdFullCdn = host.contains("hdfullcdn.cc")
                val isHdFullOne = host.contains("hdfull.one") || host.contains("hdfull.sbs")

                if (original.header("User-Agent") == null) {
                    requestBuilder.header("User-Agent", NetworkClient.USER_AGENT)
                }
                if (original.header("Accept") == null) {
                    requestBuilder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                }
                if (original.header("Accept-Language") == null) {
                    requestBuilder.header(
                        "Accept-Language",
                        if (isHdFullCdn || isHdFullOne) HDFULL_CDN_ACCEPT_LANGUAGE
                        else "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                }
                if (isHdFullCdn || isHdFullOne) {
                    requestBuilder.header("User-Agent", HDFULL_CDN_USER_AGENT)
                    requestBuilder.header("Accept", HDFULL_CDN_ACCEPT)
                    requestBuilder.header("Referer", HDFULL_CDN_REFERER)
                    requestBuilder.header("Priority", HDFULL_CDN_PRIORITY)
                    requestBuilder.header("Sec-CH-UA", HDFULL_CDN_SEC_CH_UA)
                    requestBuilder.header("Sec-CH-UA-Mobile", HDFULL_CDN_SEC_CH_UA_MOBILE)
                    requestBuilder.header("Sec-CH-UA-Platform", HDFULL_CDN_SEC_CH_UA_PLATFORM)
                    requestBuilder.removeHeader("Upgrade-Insecure-Requests")

                    if (isHdFullOne) {
                        requestBuilder.header("Sec-Fetch-Dest", "image")
                        requestBuilder.header("Sec-Fetch-Mode", "no-cors")
                        requestBuilder.header("Sec-Fetch-Site", "same-origin")
                    } else if (isHdFullCdn) {
                        requestBuilder.header("Sec-Fetch-Dest", "image")
                        requestBuilder.header("Sec-Fetch-Mode", "no-cors")
                        requestBuilder.header("Sec-Fetch-Site", "cross-site")
                    }
                }
                chain.proceed(requestBuilder.build())
            }
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val headers = ArtworkRequestHeaders.headersFor(request.url)
                val strippedUrl = ArtworkRequestHeaders.stripHeaders(request.url)
                val fixedRequest = if (headers.isNotEmpty() || strippedUrl != request.url) {
                    request.newBuilder()
                        .url(strippedUrl)
                        .apply {
                            headers.forEach { (name, value) -> header(name, value) }
                        }
                        .build()
                } else {
                    request
                }
                chain.proceed(fixedRequest)
            }
            .addInterceptor(logging)
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .dns(DnsResolver.doh)
            .build()
    }

    override fun registerComponents(
        context: Context, glide: Glide, registry: com.bumptech.glide.Registry
    ) {
        val okHttpClient = getOkHttpClient(context)
        registry.replace(
            GlideUrl::class.java, InputStream::class.java, OkHttpUrlLoader.Factory(okHttpClient)
        )
    }

    private val imageCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            NetworkClient.cookieJar.saveFromResponse(url, cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return NetworkClient.cookieJar.loadForRequest(url)
        }
    }
}
