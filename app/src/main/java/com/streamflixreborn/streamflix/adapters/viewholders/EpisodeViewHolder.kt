package com.streamflixreborn.streamflix.adapters.viewholders

import android.view.View
import android.view.animation.AnimationUtils
import android.widget.ImageView
import androidx.navigation.findNavController
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.databinding.ItemEpisodeContinueWatchingMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemEpisodeContinueWatchingTvBinding
import com.streamflixreborn.streamflix.databinding.ItemEpisodeMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemEpisodeTvBinding
import com.streamflixreborn.streamflix.fragments.home.HomeMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragment
import com.streamflixreborn.streamflix.fragments.home.HomeTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.season.SeasonMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.season.SeasonTvFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowMobileFragmentDirections
import com.streamflixreborn.streamflix.fragments.tv_show.TvShowTvFragmentDirections
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.ui.ShowOptionsMobileDialog
import com.streamflixreborn.streamflix.ui.ShowOptionsTvDialog
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.utils.loadTvShowCardArtwork
import com.streamflixreborn.streamflix.utils.toActivity
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.utils.DownloadManager
import com.streamflixreborn.streamflix.models.Download
import com.streamflixreborn.streamflix.providers.Provider
import androidx.core.content.ContextCompat
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.os.Handler
import android.os.Looper
import android.widget.Toast

class EpisodeViewHolder(
    private val _binding: ViewBinding
) : RecyclerView.ViewHolder(
    _binding.root
) {

    private val context = itemView.context
    private val database: AppDatabase
        get() = AppDatabase.getInstance(context)
    private lateinit var episode: Episode

    fun bind(episode: Episode) {
        this.episode = episode

        when (_binding) {
            is ItemEpisodeMobileBinding -> displayMobileItem(_binding)
            is ItemEpisodeTvBinding -> displayTvItem(_binding)
            is ItemEpisodeContinueWatchingMobileBinding -> displayContinueWatchingMobileItem(_binding)
            is ItemEpisodeContinueWatchingTvBinding -> displayContinueWatchingTvItem(_binding)
        }
    }


    private fun displayMobileItem(binding: ItemEpisodeMobileBinding) {
        val downloadId = "episode_${episode.id}"
        val existingDownload = database.downloadDao().getDownloadById(downloadId)
        val isDownloaded = existingDownload?.status == Download.DownloadStatus.COMPLETED || episode.isDownloaded
        val localPath = existingDownload?.localFilePath ?: episode.localFilePath

        binding.root.apply {
            setOnClickListener {
                val bundle = android.os.Bundle().apply {
                    putString("id", episode.id)
                    putString("title", episode.tvShow?.title ?: "")
                    putString(
                        "subtitle",
                        episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        )
                    )
                    putParcelable("videoType", Video.Type.Episode(
                        id = episode.id,
                        number = episode.number,
                        title = episode.title,
                        poster = episode.poster,
                        overview = episode.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = episode.tvShow?.id ?: "",
                            title = episode.tvShow?.title ?: "",
                            poster = episode.tvShow?.poster,
                            banner = episode.tvShow?.banner,
                            releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                            imdbId = episode.tvShow?.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episode.season?.number ?: 0,
                            title = episode.season?.title,
                        ),
                        isDownloaded = isDownloaded,
                        localFilePath = localPath,
                    ))
                    putBoolean("isLocalFile", isDownloaded && !localPath.isNullOrEmpty())
                    if (isDownloaded && !localPath.isNullOrEmpty()) {
                        putString("localFilePath", localPath)
                    }
                }
                findNavController().navigate(R.id.player, bundle)
            }
            setOnLongClickListener {
                ShowOptionsMobileDialog(context, episode)
                    .show()
                true
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeInfo.text = context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeTitle.text = episode.title ?: context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeReleased.apply {
            text = episode.released?.let { " • ${it.format("yyyy-MM-dd")}" }
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvEpisodeOverview.text = episode.overview ?: ""

        setupDownloadButtonMobile(binding)
    }

    private fun setupDownloadButtonMobile(binding: ItemEpisodeMobileBinding) {
        val downloadId = "episode_${episode.id}"
        val existingDownload = database.downloadDao().getDownloadById(downloadId)
        val isDownloaded = existingDownload?.status == Download.DownloadStatus.COMPLETED || episode.isDownloaded
        val localPath = existingDownload?.localFilePath ?: episode.localFilePath

        binding.btnEpisodeDownload.visibility = when {
            existingDownload?.status == Download.DownloadStatus.DOWNLOADING -> View.GONE
            existingDownload?.status == Download.DownloadStatus.QUEUED -> View.GONE
            isDownloaded -> View.GONE
            else -> View.VISIBLE
        }

        binding.pbEpisodeDownloadProgress.visibility = when {
            existingDownload?.status == Download.DownloadStatus.DOWNLOADING -> View.VISIBLE
            existingDownload?.status == Download.DownloadStatus.QUEUED -> View.VISIBLE
            else -> View.GONE
        }

        binding.btnEpisodeCancelDownload.visibility = when {
            existingDownload?.status == Download.DownloadStatus.DOWNLOADING -> View.VISIBLE
            existingDownload?.status == Download.DownloadStatus.QUEUED -> View.VISIBLE
            else -> View.GONE
        }

        binding.btnEpisodePlayDownload.visibility = when {
            isDownloaded -> View.VISIBLE
            else -> View.GONE
        }

        binding.btnEpisodeDownload.setOnClickListener {
            startDownload(episode)
        }

        binding.btnEpisodeCancelDownload.setOnClickListener {
            DownloadManager.getInstance(context).cancelDownload(downloadId)
            database.downloadDao().cancelDownload(downloadId)
            database.episodeDao().markAsNotDownloaded(episode.id)
            binding.btnEpisodeDownload.visibility = View.VISIBLE
            binding.pbEpisodeDownloadProgress.visibility = View.GONE
            binding.btnEpisodeCancelDownload.visibility = View.GONE
            binding.btnEpisodePlayDownload.visibility = View.GONE
            Handler(Looper.getMainLooper()).post {
                Toast.makeText(context, context.getString(R.string.download_cancelled), Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnEpisodePlayDownload.setOnClickListener {
            if (localPath != null) {
                val videoType = Video.Type.Episode(
                    id = episode.id,
                    number = episode.number,
                    title = episode.title,
                    poster = episode.poster,
                    overview = episode.overview,
                    tvShow = Video.Type.Episode.TvShow(
                        id = episode.tvShow?.id ?: "",
                        title = episode.tvShow?.title ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                        releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                        imdbId = episode.tvShow?.imdbId,
                    ),
                    season = Video.Type.Episode.Season(
                        number = episode.season?.number ?: 0,
                        title = episode.season?.title,
                    ),
                )
                val subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                    context.getString(
                        R.string.player_subtitle_tv_show,
                        season.number,
                        episode.number,
                        episode.title ?: context.getString(
                            R.string.episode_number,
                            episode.number
                        )
                    )
                } ?: context.getString(
                    R.string.player_subtitle_tv_show_episode_only,
                    episode.number,
                    episode.title ?: context.getString(
                        R.string.episode_number,
                        episode.number
                    )
                )
                val bundle = android.os.Bundle().apply {
                    putString("id", episode.id)
                    putString("title", episode.tvShow?.title ?: "")
                    putString("subtitle", subtitle)
                    putParcelable("videoType", videoType)
                    putString("localFilePath", localPath)
                    putBoolean("isLocalFile", true)
                }
                itemView.findNavController().navigate(R.id.player, bundle)
            }
        }
    }

    private fun displayTvItem(binding: ItemEpisodeTvBinding) {
        val downloadId = "episode_${episode.id}"
        val existingDownload = database.downloadDao().getDownloadById(downloadId)
        val isDownloaded = existingDownload?.status == Download.DownloadStatus.COMPLETED || episode.isDownloaded
        val localPath = existingDownload?.localFilePath ?: episode.localFilePath

        binding.root.apply {
            setOnClickListener {
                val bundle = android.os.Bundle().apply {
                    putString("id", episode.id)
                    putString("title", episode.tvShow?.title ?: "")
                    putString(
                        "subtitle",
                        episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        )
                    )
                    putParcelable("videoType", Video.Type.Episode(
                        id = episode.id,
                        number = episode.number,
                        title = episode.title,
                        poster = episode.poster,
                        overview = episode.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = episode.tvShow?.id ?: "",
                            title = episode.tvShow?.title ?: "",
                            poster = episode.tvShow?.poster,
                            banner = episode.tvShow?.banner,
                            releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                            imdbId = episode.tvShow?.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episode.season?.number ?: 0,
                            title = episode.season?.title,
                        ),
                        isDownloaded = isDownloaded,
                        localFilePath = localPath,
                    ))
                    putBoolean("isLocalFile", isDownloaded && !localPath.isNullOrEmpty())
                    if (isDownloaded && !localPath.isNullOrEmpty()) {
                        putString("localFilePath", localPath)
                    }
                }
                findNavController().navigate(R.id.player, bundle)
            }
            setOnLongClickListener {
                ShowOptionsTvDialog(context, episode)
                    .show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true
            }
        }

        binding.ivEpisodePoster.apply {
            clipToOutline = true
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .fallback(R.drawable.glide_fallback_cover)
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeInfo.text = context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeTitle.text = episode.title ?: context.getString(
            R.string.episode_number,
            episode.number
        )

        binding.tvEpisodeReleased.apply {
            text = episode.released?.format("EEEE - MMMM dd, yyyy")
            visibility = when {
                text.isNullOrEmpty() -> View.GONE
                else -> View.VISIBLE
            }
        }
        binding.tvEpisodeOverview.text = episode.overview ?: ""

        setupDownloadButtonTv(binding)
    }

    private fun setupDownloadButtonTv(binding: ItemEpisodeTvBinding) {
        val downloadId = "episode_${episode.id}"
        val existingDownload = database.downloadDao().getDownloadById(downloadId)
        val isDownloaded = existingDownload?.status == Download.DownloadStatus.COMPLETED || episode.isDownloaded
        val localPath = existingDownload?.localFilePath ?: episode.localFilePath

        binding.actionButtonGroup.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val zoomIn = AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                binding.actionButtonGroup.startAnimation(zoomIn)
                zoomIn.fillAfter = true
                binding.root.clearAnimation()
                val zoomOut = AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                binding.root.startAnimation(zoomOut)
                zoomOut.fillAfter = true
            } else {
                binding.actionButtonGroup.clearAnimation()
            }
        }

        binding.btnEpisodeDownload.visibility = when {
            existingDownload?.status == Download.DownloadStatus.DOWNLOADING -> View.GONE
            existingDownload?.status == Download.DownloadStatus.QUEUED -> View.GONE
            isDownloaded -> View.GONE
            else -> View.VISIBLE
        }

        binding.pbEpisodeDownloadProgress.visibility = when {
            existingDownload?.status == Download.DownloadStatus.DOWNLOADING -> View.VISIBLE
            existingDownload?.status == Download.DownloadStatus.QUEUED -> View.VISIBLE
            else -> View.GONE
        }

        binding.btnEpisodeCancelDownload.visibility = when {
            existingDownload?.status == Download.DownloadStatus.DOWNLOADING -> View.VISIBLE
            existingDownload?.status == Download.DownloadStatus.QUEUED -> View.VISIBLE
            else -> View.GONE
        }

        binding.btnEpisodePlayDownload.visibility = when {
            isDownloaded -> View.VISIBLE
            else -> View.GONE
        }

        binding.actionButtonGroup.setOnClickListener {
            when {
                binding.btnEpisodeDownload.visibility == View.VISIBLE -> startDownload(episode)
                binding.btnEpisodeCancelDownload.visibility == View.VISIBLE -> {
                    DownloadManager.getInstance(context).cancelDownload(downloadId)
                    database.downloadDao().cancelDownload(downloadId)
                    database.episodeDao().markAsNotDownloaded(episode.id)
                    binding.btnEpisodeDownload.visibility = View.VISIBLE
                    binding.pbEpisodeDownloadProgress.visibility = View.GONE
                    binding.btnEpisodeCancelDownload.visibility = View.GONE
                    binding.btnEpisodePlayDownload.visibility = View.GONE
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.download_cancelled), Toast.LENGTH_SHORT).show()
                    }
                }
                binding.btnEpisodePlayDownload.visibility == View.VISIBLE && localPath != null -> {
                    val videoType = Video.Type.Episode(
                        id = episode.id,
                        number = episode.number,
                        title = episode.title,
                        poster = episode.poster,
                        overview = episode.overview,
                        tvShow = Video.Type.Episode.TvShow(
                            id = episode.tvShow?.id ?: "",
                            title = episode.tvShow?.title ?: "",
                            poster = episode.tvShow?.poster,
                            banner = episode.tvShow?.banner,
                            releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                            imdbId = episode.tvShow?.imdbId,
                        ),
                        season = Video.Type.Episode.Season(
                            number = episode.season?.number ?: 0,
                            title = episode.season?.title,
                        ),
                    )
                    val subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                        context.getString(
                            R.string.player_subtitle_tv_show,
                            season.number,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        )
                    } ?: context.getString(
                        R.string.player_subtitle_tv_show_episode_only,
                        episode.number,
                        episode.title ?: context.getString(
                            R.string.episode_number,
                            episode.number
                        )
                    )
                    val bundle = android.os.Bundle().apply {
                        putString("id", episode.id)
                        putString("title", episode.tvShow?.title ?: "")
                        putString("subtitle", subtitle)
                        putParcelable("videoType", videoType)
                        putString("localFilePath", localPath)
                        putBoolean("isLocalFile", true)
                    }
                    itemView.findNavController().navigate(R.id.player, bundle)
                }
            }
        }

        val hasAction = binding.btnEpisodeDownload.visibility == View.VISIBLE ||
                        binding.btnEpisodeCancelDownload.visibility == View.VISIBLE ||
                        binding.btnEpisodePlayDownload.visibility == View.VISIBLE

        binding.actionButtonGroup.isFocusable = hasAction
        binding.actionButtonGroup.isFocusableInTouchMode = hasAction
        binding.root.nextFocusDownId = if (hasAction) R.id.actionButtonGroup else View.NO_ID
    }

    private fun startDownload(episode: Episode) {
        val provider = UserPreferences.currentProvider
        if (provider == null) {
            Toast.makeText(context, "No provider selected", Toast.LENGTH_SHORT).show()
            return
        }
        val downloadManager = DownloadManager.getInstance(context)
        val seasonNumber = episode.season?.number ?: 1
        val downloadId = "episode_${episode.id}"

        itemView.findViewTreeLifecycleOwner()?.lifecycleScope?.launch(Dispatchers.IO) {
            try {
                val existingDownload = database.downloadDao().getDownloadById(downloadId)
                if (existingDownload?.status == Download.DownloadStatus.COMPLETED) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.download_already_completed), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }
                if (existingDownload?.status == Download.DownloadStatus.DOWNLOADING || existingDownload?.status == Download.DownloadStatus.QUEUED) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.download_already_in_progress), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.download_resolving_url, episode.title ?: "Episode ${episode.number}"), Toast.LENGTH_SHORT).show()
                }

                val videoType = Video.Type.Episode(
                    id = episode.id,
                    number = episode.number,
                    title = episode.title,
                    poster = episode.poster,
                    overview = episode.overview,
                    tvShow = Video.Type.Episode.TvShow(
                        id = episode.tvShow?.id ?: "",
                        title = episode.tvShow?.title ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                        releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                        imdbId = episode.tvShow?.imdbId,
                    ),
                    season = Video.Type.Episode.Season(
                        number = seasonNumber,
                        title = episode.season?.title ?: "",
                    ),
                )
                val servers = provider.getServers(episode.id, videoType)
                if (servers.isEmpty()) {
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, context.getString(R.string.download_no_servers), Toast.LENGTH_SHORT).show()
                    }
                    return@launch
                }

                val video = provider.getVideo(servers.first())
                val tvShowId = episode.tvShow?.id ?: "unknown"
                val tvShowTitle = episode.tvShow?.title ?: ""
                val outputDir = downloadManager.getEpisodeDir(tvShowId, seasonNumber, episode.number)
                val downloadEntry = Download(
                    id = downloadId,
                    contentType = Download.ContentType.EPISODE,
                    title = episode.title ?: "Episode ${episode.number}",
                    subtitle = "S${seasonNumber} E${episode.number}",
                    poster = episode.poster,
                    banner = episode.tvShow?.banner,
                    videoUrl = video.source,
                    headers = video.headers ?: emptyMap(),
                    mimeType = video.type,
                    status = Download.DownloadStatus.DOWNLOADING,
                    tvShowId = tvShowId,
                    tvShowTitle = tvShowTitle,
                    seasonNumber = seasonNumber,
                    episodeNumber = episode.number,
                )
                database.downloadDao().insert(downloadEntry)

                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.download_starting, episode.title ?: "Episode ${episode.number}"), Toast.LENGTH_LONG).show()
                }

                downloadManager.downloadVideo(
                    downloadId = downloadId,
                    url = video.source,
                    headers = video.headers ?: emptyMap(),
                    outputDir = outputDir,
                    onProgress = { downloaded, total ->
                        val progress = if (total > 0) ((downloaded.toFloat() / total) * 100).toInt() else 0
                        database.downloadDao().updateProgress(downloadId, Download.DownloadStatus.DOWNLOADING, progress, downloaded)
                    },
                    onComplete = { file ->
                        database.downloadDao().markAsCompleted(downloadId, Download.DownloadStatus.COMPLETED, file.absolutePath, System.currentTimeMillis())
                        database.episodeDao().markAsDownloaded(episode.id, file.absolutePath)
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, context.getString(R.string.download_completed, episode.title ?: "Episode ${episode.number}"), Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { error ->
                        database.downloadDao().markAsFailed(downloadId, Download.DownloadStatus.FAILED, error.message)
                        Handler(Looper.getMainLooper()).post {
                            Toast.makeText(context, context.getString(R.string.download_failed, episode.title ?: "Episode ${episode.number}", error.message), Toast.LENGTH_LONG).show()
                        }
                    },
                )
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(context, context.getString(R.string.download_failed, episode.title ?: "Episode ${episode.number}", e.message), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun displayContinueWatchingMobileItem(binding: ItemEpisodeContinueWatchingMobileBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    HomeMobileFragmentDirections.actionHomeToTvShow(
                        id = episode.tvShow?.id ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                    )
                )
                findNavController().navigate(
                    TvShowMobileFragmentDirections.actionTvShowToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsMobileDialog(context, episode)
                    .show()
                true
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork()
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
    }

    private fun displayContinueWatchingTvItem(binding: ItemEpisodeContinueWatchingTvBinding) {
        binding.root.apply {
            setOnClickListener {
                findNavController().navigate(
                    HomeTvFragmentDirections.actionHomeToTvShow(
                        id = episode.tvShow?.id ?: "",
                        poster = episode.tvShow?.poster,
                        banner = episode.tvShow?.banner,
                    )
                )
                findNavController().navigate(
                    TvShowTvFragmentDirections.actionTvShowToPlayer(
                        id = episode.id,
                        title = episode.tvShow?.title ?: "",
                        subtitle = episode.season?.takeIf { it.number != 0 }?.let { season ->
                            context.getString(
                                R.string.player_subtitle_tv_show,
                                season.number,
                                episode.number,
                                episode.title ?: context.getString(
                                    R.string.episode_number,
                                    episode.number
                                )
                            )
                        } ?: context.getString(
                            R.string.player_subtitle_tv_show_episode_only,
                            episode.number,
                            episode.title ?: context.getString(
                                R.string.episode_number,
                                episode.number
                            )
                        ),
                        videoType = Video.Type.Episode(
                            id = episode.id,
                            number = episode.number,
                            title = episode.title,
                            poster = episode.poster,
                            overview = episode.overview,
                            tvShow = Video.Type.Episode.TvShow(
                                id = episode.tvShow?.id ?: "",
                                title = episode.tvShow?.title ?: "",
                                poster = episode.tvShow?.poster,
                                banner = episode.tvShow?.banner,
                                releaseDate = episode.tvShow?.released?.format("yyyy-MM-dd"),
                                imdbId = episode.tvShow?.imdbId,
                            ),
                            season = Video.Type.Episode.Season(
                                number = episode.season?.number ?: 0,
                                title = episode.season?.title,
                            ),
                        ),
                    )
                )
            }
            setOnLongClickListener {
                ShowOptionsTvDialog(context, episode)
                    .show()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                val animation = when {
                    hasFocus -> AnimationUtils.loadAnimation(context, R.anim.zoom_in)
                    else -> AnimationUtils.loadAnimation(context, R.anim.zoom_out)
                }
                binding.root.startAnimation(animation)
                animation.fillAfter = true

                when (val fragment = context.toActivity()?.getCurrentFragment()) {
                    is HomeTvFragment -> {
                        if (hasFocus) {
                            fragment.pinBackground(episode.tvShow?.banner)
                        } else {
                            fragment.releasePinnedBackground()
                        }
                    }
                }
            }
        }

        binding.ivEpisodeTvShowPoster.apply {
            clipToOutline = true
            loadContinueWatchingArtwork(withFallback = true)
        }

        binding.pbEpisodeProgress.apply {
            val watchHistory = episode.watchHistory

            progress = when {
                watchHistory != null -> (watchHistory.lastPlaybackPositionMillis * 100 / watchHistory.durationMillis.toDouble()).toInt()
                episode.isWatched -> 100
                else -> 0
            }
            visibility = when {
                watchHistory != null -> View.VISIBLE
                episode.isWatched -> View.VISIBLE
                else -> View.GONE
            }
        }

        binding.tvEpisodeTvShowTitle.text = episode.tvShow?.title ?: ""

        binding.tvEpisodeInfo.text = episode.season?.takeIf { it.number != 0 }?.let { season ->
            context.getString(
                R.string.episode_item_info,
                season.number,
                episode.number,
                episode.title ?: context.getString(
                    R.string.episode_number,
                    episode.number
                )
            )
        } ?: context.getString(
            R.string.episode_item_info_episode_only,
            episode.number,
            episode.title ?: context.getString(
                R.string.episode_number,
                episode.number
            )
        )
    }

    private fun ImageView.loadContinueWatchingArtwork(withFallback: Boolean = false) {
        val tvShow = episode.tvShow
        if (tvShow == null) {
            Glide.with(context)
                .load(episode.poster)
                .error(R.drawable.glide_fallback_cover)
                .apply {
                    if (withFallback) fallback(R.drawable.glide_fallback_cover)
                }
                .centerCrop()
                .transition(DrawableTransitionOptions.withCrossFade())
                .into(this)
            return
        }

        loadTvShowCardArtwork(tvShow) {
            error(R.drawable.glide_fallback_cover)
            apply {
                if (withFallback) fallback(R.drawable.glide_fallback_cover)
            }
            centerCrop()
            transition(DrawableTransitionOptions.withCrossFade())
        }
    }
}
