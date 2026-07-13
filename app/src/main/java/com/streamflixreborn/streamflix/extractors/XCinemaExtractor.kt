package com.streamflixreborn.streamflix.extractors

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RelativeLayout
import androidx.media3.common.MimeTypes
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.NetworkClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class XCinemaExtractor : Extractor() {

    override val name = "xCinema"
    override val mainUrl = "https://www.xcinema.ro"
    override val aliasUrls = listOf("https://xcinema.ro")

    private val mutex = Mutex()

    companion object {
        private const val TIMEOUT_MS = 120_000L
    }

    override suspend fun extract(link: String): Video {
        return mutex.withLock {
            resolveViaVisibleWebView(link)
        }
    }

    private suspend fun resolveViaVisibleWebView(url: String): Video = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val activity = StreamFlixApp.currentActivity
                ?: run {
                    continuation.resumeWithException(Exception("No active activity available for xCinema WebView"))
                    return@suspendCancellableCoroutine
                }

            val webView = WebView(activity)
            val timeoutHandler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    continuation.resumeWithException(Exception("Timeout waiting for xCinema stream"))
                    cleanup(webView, null)
                }
            }
            timeoutHandler.postDelayed(timeoutRunnable, TIMEOUT_MS)

            val dialogRef = arrayOfNulls<AlertDialog>(1)
            val cursorRef = arrayOfNulls<ImageView>(1)
            val cursorX = floatArrayOf(0f)
            val cursorY = floatArrayOf(0f)
            val isTv = activity.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)

            fun updateCursor() {
                cursorRef[0]?.let { cursor ->
                    cursor.translationX = cursorX[0] - 40f
                    cursor.translationY = cursorY[0] - 40f
                    cursor.bringToFront()
                }
            }

            fun simulateClick() {
                webView.requestFocus()
                val location = IntArray(2)
                webView.getLocationOnScreen(location)
                val relX = cursorX[0] - location[0]
                val relY = cursorY[0] - location[1]
                val downTime = android.os.SystemClock.uptimeMillis()
                val props = MotionEvent.PointerProperties().apply {
                    id = 0
                    toolType = MotionEvent.TOOL_TYPE_FINGER
                }
                val coords = MotionEvent.PointerCoords().apply {
                    x = relX
                    y = relY
                    pressure = 1f
                    size = 1f
                }

                val eventDown = MotionEvent.obtain(
                    downTime,
                    downTime,
                    MotionEvent.ACTION_DOWN,
                    1,
                    arrayOf(props),
                    arrayOf(coords),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0
                )
                webView.dispatchTouchEvent(eventDown)

                coords.x += 1f
                coords.y += 1f
                val eventUp = MotionEvent.obtain(
                    downTime,
                    android.os.SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    1,
                    arrayOf(props),
                    arrayOf(coords),
                    0,
                    0,
                    1f,
                    1f,
                    0,
                    0,
                    InputDevice.SOURCE_TOUCHSCREEN,
                    0
                )
                webView.dispatchTouchEvent(eventUp)
                eventDown.recycle()
                eventUp.recycle()
            }

            fun finishWithVideo(video: Video) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                cleanup(webView, dialogRef[0])
                if (continuation.isActive) {
                    continuation.resume(video)
                }
            }

            fun finishWithError(error: Exception) {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                cleanup(webView, dialogRef[0])
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                }
            }

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = NetworkClient.USER_AGENT
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                loadWithOverviewMode = true
                useWideViewPort = true
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            webView.setInitialScale(90)

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val requestUrl = request?.url?.toString().orEmpty()
                    val mimeType = when {
                        requestUrl.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                        requestUrl.contains(".mp4", ignoreCase = true) -> "video/mp4"
                        isLikelyHlsManifestUrl(requestUrl) -> MimeTypes.APPLICATION_M3U8
                        else -> null
                    }

                    if (mimeType != null) {
                        finishWithVideo(
                            Video(
                                source = requestUrl,
                                headers = buildHeaders(url, requestUrl),
                                type = mimeType
                            )
                        )
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    view?.evaluateJavascript(xCinemaScaleScript(), null)
                }
            }

            val root = createVisibleContainer(activity, webView, isTv, cursorRef, cursorX, cursorY, ::updateCursor, ::simulateClick, dialogRef)

            dialogRef[0] = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_NoActionBar_Fullscreen)
                .setView(root)
                .setCancelable(true)
                .setOnCancelListener {
                    finishWithError(Exception("xCinema WebView cancelled"))
                }
                .create()

            dialogRef[0]?.show()

            if (isTv) {
                root.post {
                    cursorX[0] = root.width / 2f
                    cursorY[0] = root.height / 2f
                    updateCursor()
                    root.requestFocus()
                }
            }

            webView.loadUrl(url, pageHeaders(url))

            continuation.invokeOnCancellation {
                timeoutHandler.removeCallbacks(timeoutRunnable)
                cleanup(webView, dialogRef[0])
            }
        }
    }

    private fun createVisibleContainer(
        activity: Activity,
        webView: WebView,
        isTv: Boolean,
        cursorRef: Array<ImageView?>,
        cursorX: FloatArray,
        cursorY: FloatArray,
        updateCursor: () -> Unit,
        simulateClick: () -> Unit,
        dialogRef: Array<AlertDialog?>
    ): View {
        return object : RelativeLayout(activity) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (event.keyCode) {
                        KeyEvent.KEYCODE_BACK -> {
                            dialogRef[0]?.cancel()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> if (isTv) {
                            cursorY[0] -= 45f
                            updateCursor()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> if (isTv) {
                            cursorY[0] += 45f
                            updateCursor()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> if (isTv) {
                            cursorX[0] -= 45f
                            updateCursor()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> if (isTv) {
                            cursorX[0] += 45f
                            updateCursor()
                            return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> if (isTv) {
                            simulateClick()
                            return true
                        }
                    }
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            layoutParams = ViewGroup.LayoutParams(-1, -1)
            setBackgroundColor(Color.BLACK)
            isFocusable = isTv
            isFocusableInTouchMode = isTv

            val webContainer = FrameLayout(activity).apply {
                setBackgroundColor(Color.WHITE)
            }
            addView(webContainer, RelativeLayout.LayoutParams(-1, -1))

            (webView.parent as? ViewGroup)?.removeView(webView)
            webContainer.addView(webView, FrameLayout.LayoutParams(-1, -1))

            if (isTv) {
                cursorRef[0] = ImageView(activity).apply {
                    setImageResource(android.R.drawable.ic_menu_mylocation)
                    setColorFilter(Color.RED)
                    layoutParams = FrameLayout.LayoutParams(80, 80)
                    elevation = 100f
                }
                addView(cursorRef[0])
            }
        }
    }

    private fun pageHeaders(referer: String): Map<String, String> {
        return mapOf(
            "User-Agent" to NetworkClient.USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ro-RO,ro;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to referer,
        )
    }

    private fun buildHeaders(referer: String, source: String): Map<String, String> {
        val origin = runCatching {
            val uri = source.toHttpUrlOrNull() ?: return@runCatching null
            "${uri.scheme}://${uri.host}"
        }.getOrNull()

        return buildMap {
            put("User-Agent", NetworkClient.USER_AGENT)
            put("Referer", referer)
            if (!origin.isNullOrBlank()) {
                put("Origin", origin)
            }
        }
    }

    private fun xCinemaScaleScript(): String {
        return """
            (function() {
              try {
                var root = document.documentElement;
                var body = document.body;
                if (!root || !body) return 'missing';
                root.style.zoom = '90%';
                body.style.zoom = '90%';
                body.style.transformOrigin = '0 0';
                body.style.webkitTransformOrigin = '0 0';
                body.style.width = '117.647%';
                body.style.maxWidth = '117.647%';
                return 'ok';
              } catch (e) {
                return 'err';
              }
            })();
        """.trimIndent()
    }

    private fun isLikelyHlsManifestUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("cfglobalcdn.com/silverlight/") &&
            lower.contains("/flv/api/files/videos/") &&
            !lower.contains("/frag-") &&
            !lower.endsWith(".js") &&
            !lower.endsWith(".css") &&
            !lower.endsWith(".png") &&
            !lower.endsWith(".jpg") &&
            !lower.endsWith(".jpeg") &&
            !lower.endsWith(".webp")
    }

    private fun cleanup(webView: WebView, dialog: AlertDialog?) {
        Handler(Looper.getMainLooper()).post {
            runCatching { dialog?.dismiss() }
            runCatching { webView.stopLoading() }
            runCatching { webView.webViewClient = WebViewClient() }
            runCatching { webView.destroy() }
        }
    }
}
