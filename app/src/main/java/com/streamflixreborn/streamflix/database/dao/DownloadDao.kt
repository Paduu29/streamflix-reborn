package com.streamflixreborn.streamflix.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.streamflixreborn.streamflix.models.Download
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {

    @Query("SELECT * FROM downloads ORDER BY createdAt DESC")
    fun getAllDownloads(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE id = :id")
    fun getDownloadById(id: String): Download?

    @Query("SELECT * FROM downloads WHERE status = 'DOWNLOADING' OR status = 'QUEUED' OR status = 'PAUSED' ORDER BY createdAt DESC")
    fun getActiveDownloads(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE status = 'COMPLETED' ORDER BY completedAt DESC")
    fun getCompletedDownloads(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE contentType = 'MOVIE' AND status = 'COMPLETED' ORDER BY completedAt DESC")
    fun getCompletedMovies(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE contentType = 'EPISODE' AND status = 'COMPLETED' ORDER BY tvShowTitle, seasonNumber, episodeNumber")
    fun getCompletedEpisodes(): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE tvShowId = :tvShowId AND status = 'COMPLETED' ORDER BY seasonNumber, episodeNumber")
    fun getCompletedEpisodesForTvShow(tvShowId: String): Flow<List<Download>>

    @Query("SELECT * FROM downloads WHERE tvShowId = :tvShowId AND seasonNumber = :seasonNumber AND status = 'COMPLETED'")
    fun getCompletedEpisodesForSeason(tvShowId: String, seasonNumber: Int): List<Download>

    @Query("SELECT * FROM downloads WHERE videoUrl = :videoUrl LIMIT 1")
    fun getDownloadByVideoUrl(videoUrl: String): Download?

    @Query("SELECT COUNT(*) FROM downloads WHERE id = :id AND status = 'COMPLETED'")
    fun isDownloaded(id: String): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE videoUrl = :videoUrl AND status = 'COMPLETED'")
    fun isVideoUrlDownloaded(videoUrl: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(download: Download): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(downloads: List<Download>): List<Long>

    @Update
    fun update(download: Download)

    @Query("UPDATE downloads SET status = :status, progress = :progress, downloadedSize = :downloadedSize WHERE id = :id")
    fun updateProgress(id: String, status: Download.DownloadStatus, progress: Int, downloadedSize: Long)

    @Query("UPDATE downloads SET status = :status, localFilePath = :localFilePath, completedAt = :completedAt WHERE id = :id")
    fun markAsCompleted(id: String, status: Download.DownloadStatus, localFilePath: String, completedAt: Long)

    @Query("UPDATE downloads SET status = :status, errorMessage = :errorMessage WHERE id = :id")
    fun markAsFailed(id: String, status: Download.DownloadStatus, errorMessage: String?)

    @Query("UPDATE downloads SET status = 'PAUSED' WHERE id = :id")
    fun pauseDownload(id: String)

    @Query("UPDATE downloads SET status = 'QUEUED' WHERE id = :id")
    fun resumeDownload(id: String)

    @Query("UPDATE downloads SET status = 'CANCELLED' WHERE id = :id")
    fun cancelDownload(id: String)

    @Query("DELETE FROM downloads WHERE id = :id")
    fun deleteById(id: String)

    @Delete
    fun delete(download: Download)

    @Query("DELETE FROM downloads WHERE status = 'CANCELLED' OR status = 'FAILED'")
    fun deleteCompletedFailedDownloads()

    @Query("DELETE FROM downloads")
    fun deleteAll()
}
