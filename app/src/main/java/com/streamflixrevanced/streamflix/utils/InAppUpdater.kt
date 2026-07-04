package com.streamflixrevanced.streamflix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.streamflixrevanced.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

object InAppUpdater {

    private const val GITHUB_OWNER = "paduu29"
    private const val GITHUB_REPO = "streamflix-revanced"

    private data class Version(val name: String) : Comparable<Version> {
        override operator fun compareTo(other: Version): Int {
            val thisParts = this.name.split(".").toTypedArray()
            val thatParts = other.name.split(".").toTypedArray()
            for (i in 0 until max(thisParts.size, thatParts.size)) {
                val thisPart = thisParts.getOrNull(i)?.toIntOrNull() ?: 0
                val thatPart = thatParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (thisPart < thatPart) return -1
                if (thisPart > thatPart) return 1
            }
            return 0
        }
    }

    suspend fun getReleaseUpdate(): GitHub.Release? {
        val latestRelease = GitHub.Releases.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        if (Version(latestRelease.tagName.substringAfter("v")) > Version(currentVersion)) {
            return latestRelease
        }
        return null
    }

    suspend fun getNewReleases(): List<GitHub.Release> {
        val releases = GitHub.Releases.getReleases(GITHUB_OWNER, GITHUB_REPO)
        val currentVersion = BuildConfig.VERSION_NAME

        val newReleases = releases
            .filter { Version(it.tagName.substringAfter("v")) > Version(currentVersion) }

        return newReleases
    }

    suspend fun downloadApk(context: Context, asset: GitHub.Release.Asset): File {
        context.cacheDir.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.delete() }

        val apk = withContext(Dispatchers.IO) {
            File.createTempFile(
                "${File(asset.name).nameWithoutExtension}-",
                ".apk",
                context.cacheDir
            )
        }

        val apkBytes = withContext(Dispatchers.IO) {
            GitHub.downloadBytes(asset.url)
        }

        FileOutputStream(apk).use { output ->
            apkBytes.inputStream().use { input ->
                input.copyTo(output)
            }
        }

        validateApk(context, apk, asset)
        return apk
    }

    fun installApk(context: Context, uri: Uri) {
        val apkFile = File(uri.path!!)
        val apkUri = FileProvider.getUriForFile(
            context,
            BuildConfig.APPLICATION_ID + ".provider",
            apkFile
        )
        val mimeType = MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(apkFile.extension)
            ?: "application/vnd.android.package-archive"

        val intent = Intent(Intent.ACTION_VIEW).also { intent ->
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            intent.setDataAndType(apkUri, mimeType)
        }
        context.startActivity(intent)
    }

    @Suppress("DEPRECATION")
    private fun validateApk(context: Context, apk: File, asset: GitHub.Release.Asset) {
        if (!apk.exists() || apk.length() <= 0L) {
            throw IllegalStateException("Downloaded APK is empty")
        }

        if (asset.size > 0 && apk.length() != asset.size.toLong()) {
            throw IllegalStateException(
                "Downloaded APK size mismatch: expected ${asset.size}, got ${apk.length()}"
            )
        }

        val header = ByteArray(4)
        apk.inputStream().use { input ->
            if (input.read(header) != header.size) {
                throw IllegalStateException("Downloaded APK is too small")
            }
        }

        val isZip = header[0] == 0x50.toByte() &&
            header[1] == 0x4B.toByte() &&
            (header[2] == 0x03.toByte() || header[2] == 0x05.toByte() || header[2] == 0x07.toByte())
        if (!isZip) {
            throw IllegalStateException("Downloaded file is not an APK archive")
        }

        val archiveInfo = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        if (archiveInfo == null) {
            throw IllegalStateException("Downloaded APK package metadata is invalid")
        }
    }
}
