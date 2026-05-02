package com.streamflixreborn.streamflix.fragments.season

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentSeasonTvBinding
import com.streamflixreborn.streamflix.models.Download
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.DownloadManager
import com.streamflixreborn.streamflix.utils.LoggingUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.format
import com.streamflixreborn.streamflix.utils.viewModelsFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SeasonTvFragment : Fragment() {

    private var hasAutoCleared409: Boolean = false

    private var _binding: FragmentSeasonTvBinding? = null
    private val binding get() = _binding!!

    private val args by navArgs<SeasonTvFragmentArgs>()
    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val viewModel by viewModelsFactory {
        SeasonViewModel(
            args.seasonId,
            args.tvShowId,
            database,
        )
    }

    private val appAdapter = AppAdapter()
    private var currentEpisodes: List<Episode> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSeasonTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initializeSeason()
        setupDownloadAllButton()
        observeDownloadChanges()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    SeasonViewModel.State.LoadingEpisodes -> binding.isLoading.apply {
                        root.visibility = View.VISIBLE
                        pbIsLoading.visibility = View.VISIBLE
                        gIsLoadingRetry.visibility = View.GONE
                    }

                    is SeasonViewModel.State.SuccessLoadingEpisodes -> {
                        displaySeason(state.episodes)
                        binding.isLoading.root.visibility = View.GONE
                    }

                    is SeasonViewModel.State.FailedLoadingEpisodes -> {
                        val code = (state.error as? retrofit2.HttpException)?.code()
                        if (code == 409 && !hasAutoCleared409) {
                            hasAutoCleared409 = true
                            CacheUtils.clearAppCache(requireContext())
                            android.widget.Toast.makeText(requireContext(), getString(R.string.clear_cache_done_409), android.widget.Toast.LENGTH_SHORT).show()
                            viewModel.getSeasonEpisodes(args.seasonId)
                            return@collect
                        }
                        Toast.makeText(
                            requireContext(),
                            state.error.message ?: "",
                            Toast.LENGTH_SHORT
                        ).show()
                        binding.isLoading.apply {
                            pbIsLoading.visibility = View.GONE
                            gIsLoadingRetry.visibility = View.VISIBLE
                            btnIsLoadingRetry.setOnClickListener { viewModel.getSeasonEpisodes(args.seasonId) }
                            btnIsLoadingClearCache.setOnClickListener {
                                CacheUtils.clearAppCache(requireContext())
                                android.widget.Toast.makeText(requireContext(), getString(R.string.clear_cache_done), android.widget.Toast.LENGTH_SHORT).show()
                                viewModel.getSeasonEpisodes(args.seasonId)
                            }
                            btnIsLoadingErrorDetails.setOnClickListener {
                                LoggingUtils.showErrorDialog(requireContext(), state.error)
                            }
                            btnIsLoadingRetry.requestFocus()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun initializeSeason() {
        binding.tvSeasonTitle.text = args.seasonTitle

        binding.hgvEpisodes.apply {
            adapter = appAdapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setItemSpacing(resources.getDimension(R.dimen.season_episodes_spacing).toInt())
        }
    }

    private fun setupDownloadAllButton() {
        binding.btnSeasonDownloadAll.setOnClickListener {
            downloadAllEpisodes()
        }
    }

    private var lastDownloadStatuses: Map<String, Download.DownloadStatus?> = emptyMap()

    private fun observeDownloadChanges() {
        viewLifecycleOwner.lifecycleScope.launch {
            database.downloadDao().getAllDownloads().flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { downloads ->
                for (download in downloads) {
                    if (download.status != lastDownloadStatuses[download.id]) {
                        lastDownloadStatuses = lastDownloadStatuses + (download.id to download.status)
                        refreshEpisodeStates()
                        break
                    }
                }
            }
        }
    }

    private fun refreshEpisodeStates() {
        if (currentEpisodes.isEmpty()) return
        for (i in currentEpisodes.indices) {
            val episode = currentEpisodes[i]
            val downloadId = "episode_${episode.id}"
            val currentStatus = database.downloadDao().getDownloadById(downloadId)?.status
            if (currentStatus != lastDownloadStatuses[downloadId]) {
                appAdapter.notifyItemChanged(i, "download_state")
                lastDownloadStatuses = lastDownloadStatuses + (downloadId to currentStatus)
            }
        }
    }

    private var focusedEpisodeIndex: Int? = null

    private fun displaySeason(episodes: List<Episode>) {
        currentEpisodes = episodes
        val preparedEpisodes = episodes.onEach { episode ->
            episode.itemType = AppAdapter.Type.EPISODE_TV_ITEM
        }

        binding.btnSeasonDownloadAll.visibility = if (episodes.isNotEmpty()) View.VISIBLE else View.GONE

        val lastWatchedIndex = episodes
            .filter { it.watchHistory != null }
            .sortedByDescending { it.watchHistory?.lastEngagementTimeUtcMillis }
            .firstOrNull()
            ?.let { episodes.indexOf(it) }
            ?: episodes.indexOfLast { it.isWatched }

        appAdapter.submitList(preparedEpisodes)

        if (focusedEpisodeIndex == null) {
            val scrollIndex = when {
                lastWatchedIndex == -1 -> 0
                lastWatchedIndex < episodes.lastIndex -> lastWatchedIndex + 1
                else -> lastWatchedIndex
            }
            binding.hgvEpisodes.scrollAndFocus(scrollIndex)
            focusedEpisodeIndex = scrollIndex
        }
    }

    private fun RecyclerView.scrollAndFocus(position: Int) {
        scrollToPosition(position)
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                viewTreeObserver.removeOnGlobalLayoutListener(this)
                findViewHolderForAdapterPosition(position)?.itemView?.requestFocus()
            }
        })
    }

    private fun downloadAllEpisodes() {
        val provider = UserPreferences.currentProvider
        if (provider == null) {
            Toast.makeText(requireContext(), "No provider selected", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentEpisodes.isEmpty()) {
            Toast.makeText(requireContext(), "No episodes available", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(requireContext(), "Starting downloads...", Toast.LENGTH_SHORT).show()

        val downloadManager = DownloadManager.getInstance(requireContext())
        val tvShowId = args.tvShowId
        val seasonNumber = args.seasonNumber
        val tvShowTitle = args.tvShowTitle

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            var queuedCount = 0
            var skippedCount = 0
            var failedCount = 0

            for (episode in currentEpisodes) {
                val downloadId = "episode_${episode.id}"
                val existingDownload = database.downloadDao().getDownloadById(downloadId)

                if (existingDownload?.status == Download.DownloadStatus.COMPLETED ||
                    existingDownload?.status == Download.DownloadStatus.DOWNLOADING ||
                    existingDownload?.status == Download.DownloadStatus.QUEUED) {
                    skippedCount++
                    continue
                }

                try {
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
                        failedCount++
                        continue
                    }

                    val video = provider.getVideo(servers.first())
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
                        },
                        onError = { error ->
                            database.downloadDao().markAsFailed(downloadId, Download.DownloadStatus.FAILED, error.message)
                        },
                    )
                    queuedCount++

                    kotlinx.coroutines.delay(500)
                } catch (e: Exception) {
                    Log.e("SeasonTvFragment", "Failed to queue download for episode ${episode.id}", e)
                    failedCount++
                }
            }

            withContext(Dispatchers.Main) {
                val message = when {
                    queuedCount > 0 && skippedCount > 0 -> "Queued $queuedCount downloads, $skippedCount already in progress"
                    queuedCount > 0 -> "Queued $queuedCount downloads"
                    skippedCount > 0 -> "All episodes already downloaded or in progress"
                    else -> "No episodes to download"
                }
                Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show()
            }
        }
    }
}
