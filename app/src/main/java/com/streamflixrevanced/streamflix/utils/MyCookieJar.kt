import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class MyCookieJar : CookieJar {
    private val cookieManager by lazy { CookieManager.getInstance() }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cookie ->
            cookieManager.setCookie(url.toString(), cookie.toString())
        }
        cookieManager.flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieString = runCatching { cookieManager.getCookie(url.toString()) }.getOrNull().orEmpty()
        if (cookieString.isBlank()) return emptyList()

        return cookieString.split(";")
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .mapNotNull { Cookie.parse(url, it) }
    }
}
