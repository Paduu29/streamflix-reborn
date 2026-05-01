package com.streamflixreborn.streamflix.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity("downloads")
data class Download(
    @PrimaryKey
    var id: String = "",

    var contentType: ContentType = ContentType.MOVIE,

    var title: String = "",
    var subtitle: String? = null,
    var poster: String? = null,
    var banner: String? = null,

    var videoUrl: String = "",
    var headers: Map<String, String> = emptyMap(),
    var mimeType: String? = null,

    var localFilePath: String? = null,

    var status: DownloadStatus = DownloadStatus.QUEUED,
    var progress: Int = 0,
    var fileSize: Long = 0,
    var downloadedSize: Long = 0,

    var errorMessage: String? = null,

    var createdAt: Long = System.currentTimeMillis(),
    var completedAt: Long? = null,

    var tvShowId: String? = null,
    var tvShowTitle: String? = null,
    var seasonNumber: Int? = null,
    var episodeNumber: Int? = null,

    var quality: String? = null,
) {
    enum class ContentType {
        MOVIE, EPISODE
    }

    enum class DownloadStatus {
        QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    }
}
