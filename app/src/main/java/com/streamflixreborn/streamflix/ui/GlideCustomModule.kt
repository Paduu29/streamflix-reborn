package com.streamflixreborn.streamflix.ui

import android.content.Context
import com.bumptech.glide.Glide
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.integration.okhttp3.OkHttpUrlLoader
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.module.AppGlideModule
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
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
        private const val HDFULL_CDN_ACCEPT =
            "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7"
        private const val HDFULL_CDN_ACCEPT_LANGUAGE =
            "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7"
        private const val HDFULL_CDN_REFERER = "https://hdfull.one/"
        private const val HDFULL_CDN_SEC_CH_UA =
            "\"Google Chrome\";v=\"149\", \"Chromium\";v=\"149\", \"Not)A;Brand\";v=\"24\""
        private const val HDFULL_CDN_SEC_CH_UA_MOBILE = "?0"
        private const val HDFULL_CDN_SEC_CH_UA_PLATFORM = "\"macOS\""
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
                if (original.header("User-Agent") == null) {
                    requestBuilder.header("User-Agent", NetworkClient.USER_AGENT)
                }
                if (original.header("Accept") == null) {
                    requestBuilder.header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                }
                if (original.header("Accept-Language") == null) {
                    requestBuilder.header(
                        "Accept-Language",
                        if (host.contains("hdfullcdn.cc")) HDFULL_CDN_ACCEPT_LANGUAGE
                        else "it-IT,it;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                }
                if (host.contains("hdfullcdn.cc")) {
                    requestBuilder.header("User-Agent", HDFULL_CDN_USER_AGENT)
                    requestBuilder.header("Accept", HDFULL_CDN_ACCEPT)
                    requestBuilder.header("Referer", HDFULL_CDN_REFERER)
                    requestBuilder.header("Priority", HDFULL_CDN_PRIORITY)
                    requestBuilder.header("Sec-CH-UA", HDFULL_CDN_SEC_CH_UA)
                    requestBuilder.header("Sec-CH-UA-Mobile", HDFULL_CDN_SEC_CH_UA_MOBILE)
                    requestBuilder.header("Sec-CH-UA-Platform", HDFULL_CDN_SEC_CH_UA_PLATFORM)

                }
                chain.proceed(requestBuilder.build())
            }
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
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
            if (url.host.endsWith("hdfullcdn.cc")) {
                return
            }
            NetworkClient.cookieJar.saveFromResponse(url, cookies)
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            if (url.host.endsWith("hdfullcdn.cc")) {
                return emptyList()
            }
            return NetworkClient.cookieJar.loadForRequest(url)
        }
    }
}
