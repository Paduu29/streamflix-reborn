package com.streamflixreborn.streamflix.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Download
import com.streamflixreborn.streamflix.utils.DownloadManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.jvm.java

class DownloadService : android.app.Service() {

    companion object {
        const val CHANNEL_ID = "download_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_CANCEL = "com.streamflixreborn.streamflix.CANCEL_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "download_id"

        fun startService(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, DownloadService::class.java)
            context.stopService(intent)
        }
    }

    private lateinit var notificationManager: NotificationManager
    private lateinit var downloadManager: DownloadManager
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        downloadManager = DownloadManager.getInstance(this)
        createNotificationChannel()
        startForeground(
            NOTIFICATION_ID,
            createNotification("Starting download...", 0, null)
        )
        observeDownloads()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                val downloadId = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                if (downloadId != null) {
                    cancelDownload(downloadId)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows download progress"
                setSound(null, null)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        contentText: String,
        progress: Int,
        downloadId: String?,
    ): Notification {

        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        val targetActivity = if (isTv) {
            MainTvActivity::class.java
        } else {
            MainMobileActivity::class.java
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, targetActivity),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("StreamFlix Downloads")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_menu_downloads)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (progress > 0) {
            builder.setProgress(100, progress, false)
        } else {
            builder.setProgress(100, 0, true)
        }

        if (downloadId != null) {
            val cancelIntent = Intent(this, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            val cancelPendingIntent = PendingIntent.getService(
                this,
                0,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_settings_close, "Cancel", cancelPendingIntent)
        }

        return builder.build()
    }

    private fun observeDownloads() {
        serviceScope.launch {
            downloadManager.downloadProgress.collectLatest { progressMap ->
                val activeDownloads = progressMap.filter { it.value.status == DownloadManager.DownloadStatus.DOWNLOADING }

                if (activeDownloads.isEmpty()) {
                    stopForeground(true)
                    stopSelf()
                    return@collectLatest
                }

                val (downloadId, activeDownload) = activeDownloads.entries.first()
                val database = AppDatabase.getInstance(this@DownloadService)
                val download = database.downloadDao().getDownloadById(downloadId)

                val contentText = if (download != null) {
                    "${download.title} - ${activeDownload.progress}%"
                } else {
                    "Downloading... ${activeDownload.progress}%"
                }

                val notification = createNotification(contentText, activeDownload.progress, downloadId)
                notificationManager.notify(NOTIFICATION_ID, notification)
            }
        }
    }

    private fun cancelDownload(downloadId: String) {
        downloadManager.cancelDownload(downloadId)
        val database = AppDatabase.getInstance(this)
        database.downloadDao().cancelDownload(downloadId)
        database.episodeDao().markAsNotDownloaded(downloadId.removePrefix("episode_"))
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
