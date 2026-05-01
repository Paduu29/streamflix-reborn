package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.concurrent.TimeUnit

class DownloadManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DownloadManager"
        private const val DOWNLOAD_DIR_NAME = "streamflix_downloads"
        private const val BUFFER_SIZE = 65536

        @Volatile
        private var INSTANCE: DownloadManager? = null

        fun getInstance(context: Context): DownloadManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _downloadProgress = MutableStateFlow<Map<String, DownloadProgressInfo>>(emptyMap())
    val downloadProgress: StateFlow<Map<String, DownloadProgressInfo>> = _downloadProgress.asStateFlow()

    private val activeDownloads = mutableMapOf<String, Boolean>()

    data class DownloadProgressInfo(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val progress: Int,
        val status: DownloadStatus,
        val speed: Long = 0,
        val etaSeconds: Long = -1,
    )

    enum class DownloadStatus {
        DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    }

    fun getDownloadDir(): File {
        val dir = File(context.filesDir, DOWNLOAD_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getMovieDir(movieId: String): File {
        val dir = File(getDownloadDir(), "movie_$movieId")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getEpisodeDir(tvShowId: String, seasonNumber: Int, episodeNumber: Int): File {
        val dir = File(getDownloadDir(), "episode_${tvShowId}_s${seasonNumber}e${episodeNumber}")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getVideoFile(dir: File): File {
        return File(dir, "video.mp4")
    }

    fun getVideoFileTs(dir: File): File {
        return File(dir, "video.ts")
    }

    fun downloadVideo(
        downloadId: String,
        url: String,
        headers: Map<String, String> = emptyMap(),
        outputDir: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
        onComplete: (File) -> Unit = {},
        onError: (Exception) -> Unit = {},
    ) {
        if (activeDownloads[downloadId] == true) {
            Log.w(TAG, "Download $downloadId is already active")
            return
        }

        activeDownloads[downloadId] = true

        scope.launch {
            try {
                if (isHlsUrl(url)) {
                    downloadHls(downloadId, url, headers, outputDir, onProgress, onComplete, onError)
                } else {
                    downloadDirect(downloadId, url, headers, outputDir, onProgress, onComplete, onError)
                }
            } catch (e: Exception) {
                activeDownloads.remove(downloadId)
                Log.e(TAG, "Download failed: ${e.message}", e)
                onError(e)
            }
        }
    }

    private fun isHlsUrl(url: String): Boolean {
        return url.contains(".m3u8", ignoreCase = true) || url.contains("m3u8", ignoreCase = true)
    }

    private fun downloadDirect(
        downloadId: String,
        url: String,
        headers: Map<String, String>,
        outputDir: File,
        onProgress: (Long, Long) -> Unit,
        onComplete: (File) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val outputFile = getVideoFile(outputDir)

        val requestBuilder = Request.Builder().url(url)
        headers.forEach { (key, value) ->
            requestBuilder.addHeader(key, value)
        }

        val request = requestBuilder.build()

        val startTime = System.currentTimeMillis()
        var lastProgressUpdate = 0L
        var lastDownloadedBytes = 0L

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                activeDownloads.remove(downloadId)
                scope.launch {
                    onError(e)
                }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                if (!response.isSuccessful) {
                    activeDownloads.remove(downloadId)
                    val errorMsg = "HTTP ${response.code}: ${response.message}"
                    response.close()
                    scope.launch {
                        onError(Exception(errorMsg))
                    }
                    return
                }

                val contentLength = response.body?.contentLength() ?: -1

                response.body?.byteStream()?.use { inputStream ->
                    outputFile.outputStream().use { outputStream ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var downloadedBytes = 0L
                        var bytesRead: Int

                        while (activeDownloads[downloadId] == true) {
                            bytesRead = inputStream.read(buffer)
                            if (bytesRead == -1) break

                            outputStream.write(buffer, 0, bytesRead)
                            downloadedBytes += bytesRead

                            val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                            val speed = downloadedBytes * 1000 / elapsed
                            val remainingBytes = if (contentLength > 0) contentLength - downloadedBytes else -1
                            val etaSeconds = if (speed > 0 && remainingBytes > 0) remainingBytes / speed else -1

                            val progress = if (contentLength > 0) {
                                ((downloadedBytes.toFloat() / contentLength) * 100).toInt()
                            } else {
                                0
                            }

                            _downloadProgress.value = _downloadProgress.value + mapOf(
                                downloadId to DownloadProgressInfo(
                                    downloadedBytes = downloadedBytes,
                                    totalBytes = contentLength,
                                    progress = progress,
                                    status = DownloadStatus.DOWNLOADING,
                                    speed = speed,
                                    etaSeconds = etaSeconds,
                                )
                            )

                            onProgress(downloadedBytes, contentLength)
                        }
                    }
                }

                finishDownload(downloadId, outputFile, activeDownloads[downloadId] != true, onComplete, onError)
            }
        })
    }

    private fun downloadHls(
        downloadId: String,
        url: String,
        headers: Map<String, String>,
        outputDir: File,
        onProgress: (Long, Long) -> Unit,
        onComplete: (File) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        try {
            val requestBuilder = Request.Builder().url(url)
            headers.forEach { (key, value) ->
                requestBuilder.addHeader(key, value)
            }

            val playlistUrl = URL(url)
            val playlistText = client.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to fetch HLS playlist: HTTP ${response.code}")
                }
                response.body?.string() ?: throw Exception("Empty HLS playlist")
            }

            val segmentUrls = parseHlsPlaylist(playlistText, playlistUrl)

            if (segmentUrls.isEmpty()) {
                throw Exception("No segments found in HLS playlist")
            }

            Log.d(TAG, "Found ${segmentUrls.size} segments to download")

            val outputFile = getVideoFileTs(outputDir)
            var totalDownloadedBytes = 0L
            var estimatedTotalBytes = 0L

            val tempDir = File(outputDir, "temp_segments")
            tempDir.mkdirs()

            val startTime = System.currentTimeMillis()

            for ((index, segmentUrl) in segmentUrls.withIndex()) {
                if (activeDownloads[downloadId] != true) {
                    tempDir.deleteRecursively()
                    _downloadProgress.value = _downloadProgress.value + mapOf(
                        downloadId to DownloadProgressInfo(
                            downloadedBytes = 0,
                            totalBytes = 0,
                            progress = 0,
                            status = DownloadStatus.CANCELLED,
                        )
                    )
                    return
                }

                val segmentRequest = Request.Builder().url(segmentUrl).apply {
                    headers.forEach { (key, value) ->
                        addHeader(key, value)
                    }
                }.build()

                val segmentFile = File(tempDir, "seg_${String.format("%04d", index)}.ts")

                client.newCall(segmentRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw Exception("Failed to download segment $index: HTTP ${response.code}")
                    }

                    val segmentLength = response.body?.contentLength() ?: -1
                    if (segmentLength > 0 && estimatedTotalBytes == 0L) {
                        estimatedTotalBytes = segmentLength * segmentUrls.size
                    }

                    response.body?.byteStream()?.use { inputStream ->
                        FileOutputStream(segmentFile).use { outputStream ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            var bytesRead: Int

                            while (activeDownloads[downloadId] == true) {
                                bytesRead = inputStream.read(buffer)
                                if (bytesRead == -1) break

                                outputStream.write(buffer, 0, bytesRead)
                                totalDownloadedBytes += bytesRead
                            }
                        }
                    }
                }

                val elapsed = (System.currentTimeMillis() - startTime).coerceAtLeast(1)
                val speed = totalDownloadedBytes * 1000 / elapsed
                val remainingBytes = if (estimatedTotalBytes > 0) estimatedTotalBytes - totalDownloadedBytes else -1
                val etaSeconds = if (speed > 0 && remainingBytes > 0) remainingBytes / speed else -1

                val progress = if (estimatedTotalBytes > 0) {
                    ((totalDownloadedBytes.toFloat() / estimatedTotalBytes) * 100).toInt().coerceAtMost(100)
                } else {
                    ((index + 1).toFloat() / segmentUrls.size * 100).toInt()
                }

                _downloadProgress.value = _downloadProgress.value + mapOf(
                    downloadId to DownloadProgressInfo(
                        downloadedBytes = totalDownloadedBytes,
                        totalBytes = estimatedTotalBytes,
                        progress = progress,
                        status = DownloadStatus.DOWNLOADING,
                        speed = speed,
                        etaSeconds = etaSeconds,
                    )
                )

                onProgress(totalDownloadedBytes, estimatedTotalBytes)
            }

            if (activeDownloads[downloadId] == true) {
                outputFile.outputStream().use { output ->
                    for (i in 0 until segmentUrls.size) {
                        val segmentFile = File(tempDir, "seg_${String.format("%04d", i)}.ts")
                        if (segmentFile.exists()) {
                            segmentFile.inputStream().use { it.copyTo(output) }
                        }
                    }
                }

                tempDir.deleteRecursively()

                _downloadProgress.value = _downloadProgress.value + mapOf(
                    downloadId to DownloadProgressInfo(
                        downloadedBytes = outputFile.length(),
                        totalBytes = outputFile.length(),
                        progress = 100,
                        status = DownloadStatus.COMPLETED,
                    )
                )

                onComplete(outputFile)
            } else {
                tempDir.deleteRecursively()
                outputFile.delete()
                _downloadProgress.value = _downloadProgress.value + mapOf(
                    downloadId to DownloadProgressInfo(
                        downloadedBytes = 0,
                        totalBytes = 0,
                        progress = 0,
                        status = DownloadStatus.CANCELLED,
                    )
                )
            }
        } catch (e: Exception) {
            activeDownloads.remove(downloadId)
            Log.e(TAG, "HLS download failed: ${e.message}", e)
            File(outputDir, "temp_segments").deleteRecursively()
            scope.launch {
                onError(e)
            }
        }
    }

    private fun parseHlsPlaylist(playlistText: String, baseUrl: URL): List<String> {
        val lines = playlistText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val segmentUrls = mutableListOf<String>()

        var isVariantPlaylist = false
        val variantUrls = mutableListOf<Pair<String, Int>>()

        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                isVariantPlaylist = true
            } else if (isVariantPlaylist && !line.startsWith("#")) {
                val bandwidth = extractBandwidth(lines)
                variantUrls.add(Pair(resolveUrl(line, baseUrl), bandwidth))
            } else if (!line.startsWith("#") && !isVariantPlaylist) {
                segmentUrls.add(resolveUrl(line, baseUrl))
            }
        }

        if (isVariantPlaylist && variantUrls.isNotEmpty()) {
            variantUrls.sortByDescending { it.second }
            val selectedVariantUrl = selectVariantUrl(variantUrls)
            Log.d(TAG, "Fetching variant playlist: $selectedVariantUrl")

            val variantRequest = Request.Builder().url(selectedVariantUrl).build()
            val variantText = client.newCall(variantRequest).execute().use { response ->
                if (!response.isSuccessful) {
                    throw Exception("Failed to fetch variant playlist: HTTP ${response.code}")
                }
                response.body?.string() ?: ""
            }

            val variantBaseUrl = URL(selectedVariantUrl)
            return parseHlsPlaylist(variantText, variantBaseUrl)
        }

        return segmentUrls
    }

    private fun selectVariantUrl(variants: List<Pair<String, Int>>): String {
        val index = when (UserPreferences.downloadQuality) {
            UserPreferences.DownloadQuality.BEST -> 0
            UserPreferences.DownloadQuality.HIGH -> ((variants.size - 1) * 0.25f).toInt()
            UserPreferences.DownloadQuality.MEDIUM -> ((variants.size - 1) * 0.5f).toInt()
            UserPreferences.DownloadQuality.LOW -> variants.lastIndex
        }.coerceIn(0, variants.lastIndex)

        return variants[index].first
    }

    private fun extractBandwidth(lines: List<String>): Int {
        for (line in lines) {
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bandwidthMatch = Regex("BANDWIDTH=(\\d+)").find(line)
                if (bandwidthMatch != null) {
                    return bandwidthMatch.groupValues[1].toInt()
                }
            }
        }
        return 0
    }

    private fun resolveUrl(path: String, baseUrl: URL): String {
        return if (path.startsWith("http")) {
            path
        } else {
            URL(baseUrl, path).toString()
        }
    }

    private fun finishDownload(
        downloadId: String,
        outputFile: File,
        isCancelled: Boolean,
        onComplete: (File) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        activeDownloads.remove(downloadId)

        if (isCancelled) {
            outputFile.delete()
            scope.launch {
                _downloadProgress.value = _downloadProgress.value + mapOf(
                    downloadId to DownloadProgressInfo(
                        downloadedBytes = 0,
                        totalBytes = 0,
                        progress = 0,
                        status = DownloadStatus.CANCELLED,
                    )
                )
            }
        } else {
            scope.launch {
                _downloadProgress.value = _downloadProgress.value + mapOf(
                    downloadId to DownloadProgressInfo(
                        downloadedBytes = outputFile.length(),
                        totalBytes = outputFile.length(),
                        progress = 100,
                        status = DownloadStatus.COMPLETED,
                    )
                )
                onComplete(outputFile)
            }
        }
    }

    fun cancelDownload(downloadId: String) {
        activeDownloads[downloadId] = false
    }

    fun deleteDownload(downloadId: String, videoDir: File) {
        cancelDownload(downloadId)
        videoDir.deleteRecursively()
    }

    fun getDownloadedFileSize(dir: File): Long {
        val mp4File = getVideoFile(dir)
        if (mp4File.exists()) return mp4File.length()
        val tsFile = getVideoFileTs(dir)
        if (tsFile.exists()) return tsFile.length()
        return 0
    }

    fun getAvailableSpace(): Long {
        val dir = getDownloadDir()
        return dir.freeSpace
    }

    fun getTotalDownloadedSize(): Long {
        val dir = getDownloadDir()
        return dir.walkTopDown().filter { it.isFile }.map { it.length() }.sum()
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }
}
