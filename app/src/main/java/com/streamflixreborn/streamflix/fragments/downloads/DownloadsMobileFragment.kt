package com.streamflixreborn.streamflix.fragments.downloads

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.DownloadItem
import com.streamflixreborn.streamflix.adapters.DownloadsAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentDownloadsMobileBinding
import com.streamflixreborn.streamflix.models.Download
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadsMobileFragment : Fragment() {

    private var _binding: FragmentDownloadsMobileBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val downloadManager by lazy { DownloadManager.getInstance(requireContext()) }
    private val adapter by lazy {
        DownloadsAdapter(
            onPlayClick = { download -> playDownload(download) },
            onDeleteClick = { download -> showDeleteConfirmation(download) },
            onTitleClick = { download -> navigateToContent(download) },
            database = database,
            isTv = false,
        )
    }

    private var currentTab = Tab.ALL
    private var observeDownloadsJob: Job? = null

    enum class Tab {
        ALL, MOVIES, EPISODES, DOWNLOADING
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsMobileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupTabs()
        observeDownloads()
        observeDownloadProgress()
        updateStorageInfo()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        observeDownloadsJob?.cancel()
        _binding = null
    }

    private fun setupRecyclerView() {
        binding.rvDownloads.adapter = adapter
    }

    private fun setupTabs() {
        binding.tabLayout.addOnTabSelectedListener(object :
            com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: com.google.android.material.tabs.TabLayout.Tab?) {
                currentTab = when (tab?.position) {
                    0 -> Tab.ALL
                    1 -> Tab.MOVIES
                    2 -> Tab.EPISODES
                    3 -> Tab.DOWNLOADING
                    else -> Tab.ALL
                }
                observeDownloads()
            }

            override fun onTabUnselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
            override fun onTabReselected(tab: com.google.android.material.tabs.TabLayout.Tab?) {}
        })
    }

    private fun observeDownloads() {
        observeDownloadsJob?.cancel()
        observeDownloadsJob = viewLifecycleOwner.lifecycleScope.launch {
            val flow = when (currentTab) {
                Tab.ALL -> database.downloadDao().getAllDownloads()
                Tab.MOVIES -> database.downloadDao().getCompletedMovies()
                Tab.EPISODES -> database.downloadDao().getCompletedEpisodes()
                Tab.DOWNLOADING -> database.downloadDao().getActiveDownloads()
            }

            flow.collectLatest { downloads ->
                withContext(Dispatchers.Main) {
                    val groupedItems = groupDownloads(downloads)
                    adapter.submitList(groupedItems)
                    binding.llEmpty.visibility = if (groupedItems.isEmpty()) View.VISIBLE else View.GONE
                }
            }
        }
    }

    private fun groupDownloads(downloads: List<Download>): List<DownloadItem> {
        val result = mutableListOf<DownloadItem>()

        val (episodes, nonEpisodes) = downloads.partition { it.contentType == Download.ContentType.EPISODE }

        val groupedEpisodes = episodes
            .groupBy { it.tvShowTitle ?: "Unknown" }
            .toSortedMap()
            .mapValues { (_, showDownloads) ->
                showDownloads.groupBy { it.seasonNumber ?: 0 }
                    .toSortedMap()
                    .flatMap { (season, seasonDownloads) ->
                        listOf(
                            DownloadItem.Header(
                                tvShowId = seasonDownloads.firstOrNull()?.tvShowId ?: "",
                                tvShowTitle = seasonDownloads.firstOrNull()?.tvShowTitle ?: "Unknown",
                                seasonNumber = season,
                            )
                        ) + seasonDownloads.sortedBy { it.episodeNumber ?: 0 }
                            .map { DownloadItem.Download(it) }
                    }
            }
            .values
            .flatten()

        result.addAll(groupedEpisodes)

        if (currentTab == Tab.ALL || currentTab == Tab.MOVIES) {
            val movies = nonEpisodes.filter { it.contentType == Download.ContentType.MOVIE }
                .sortedByDescending { it.completedAt ?: 0L }
            movies.forEach { result.add(DownloadItem.Download(it)) }
        }

        if (currentTab == Tab.DOWNLOADING) {
            nonEpisodes.filter { it.contentType == Download.ContentType.MOVIE }
                .forEach { result.add(DownloadItem.Download(it)) }
        }

        return result
    }

    private var lastProgressMap: Map<String, DownloadsAdapter.DownloadProgress> = emptyMap()

    private fun observeDownloadProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            downloadManager.downloadProgress.collectLatest { progressMap ->
                val adapterProgress = progressMap.mapValues { (_, info) ->
                    DownloadsAdapter.DownloadProgress(
                        progress = info.progress,
                        status = when (info.status) {
                            DownloadManager.DownloadStatus.DOWNLOADING -> getString(R.string.download_status_downloading)
                            DownloadManager.DownloadStatus.PAUSED -> getString(R.string.download_status_paused)
                            DownloadManager.DownloadStatus.COMPLETED -> getString(R.string.download_completed)
                            DownloadManager.DownloadStatus.FAILED -> getString(R.string.download_status_failed)
                            DownloadManager.DownloadStatus.CANCELLED -> getString(R.string.download_status_cancelled)
                        },
                        speed = info.speed,
                        etaSeconds = info.etaSeconds,
                    )
                }

                for ((id, newProgress) in adapterProgress) {
                    val oldProgress = lastProgressMap[id]
                    if (newProgress != oldProgress) {
                        val position = adapter.indexOfDownload(id)
                        if (position >= 0) {
                            adapter.notifyItemChanged(position, Any())
                        }
                    }
                }

                adapter.progressMap = adapterProgress
                lastProgressMap = adapterProgress
            }
        }
    }

    private fun updateStorageInfo() {
        viewLifecycleOwner.lifecycleScope.launch {
            val used = downloadManager.getTotalDownloadedSize()
            val available = downloadManager.getAvailableSpace()
            binding.tvStorageInfo.text = getString(
                R.string.download_storage_info,
                downloadManager.formatFileSize(used),
                downloadManager.formatFileSize(available)
            )
        }
    }

    private fun playDownload(download: Download) {
        if (download.status != Download.DownloadStatus.COMPLETED) return

        val localFilePath = download.localFilePath
        if (localFilePath == null || !File(localFilePath).exists()) {
            Toast.makeText(requireContext(), getString(R.string.download_file_not_found), Toast.LENGTH_SHORT).show()
            return
        }

        val videoType = when (download.contentType) {
            Download.ContentType.MOVIE -> {
                Video.Type.Movie(
                    id = download.id,
                    title = download.title,
                    releaseDate = "",
                    poster = download.poster ?: "",
                    imdbId = null,
                )
            }
            Download.ContentType.EPISODE -> {
                Video.Type.Episode(
                    id = download.id,
                    number = download.episodeNumber ?: 0,
                    title = download.subtitle,
                    poster = download.poster,
                    overview = null,
                    tvShow = Video.Type.Episode.TvShow(
                        id = download.tvShowId ?: "",
                        title = download.tvShowTitle ?: download.title,
                        poster = download.poster,
                        banner = download.banner,
                        releaseDate = null,
                        imdbId = null,
                    ),
                    season = Video.Type.Episode.Season(
                        number = download.seasonNumber ?: 0,
                        title = null,
                    ),
                )
            }
        }

        val subtitle = when (download.contentType) {
            Download.ContentType.MOVIE -> download.title
            Download.ContentType.EPISODE -> {
                if (download.seasonNumber != null && download.episodeNumber != null) {
                    "S${download.seasonNumber} E${download.episodeNumber}" + if (download.subtitle != null) " - ${download.subtitle}" else ""
                } else {
                    download.subtitle ?: download.title
                }
            }
        }

        val bundle = android.os.Bundle().apply {
            putString("id", download.id)
            putString("title", download.title)
            putString("subtitle", subtitle ?: "")
            putParcelable("videoType", videoType)
            putString("localFilePath", localFilePath)
            putBoolean("isLocalFile", true)
        }

        findNavController().navigate(R.id.player, bundle)
    }

    private fun navigateToContent(download: Download) {
        when (download.contentType) {
            Download.ContentType.MOVIE -> {
                try {
                    val action = DownloadsMobileFragmentDirections.actionDownloadsToMovie(download.id)
                    findNavController().navigate(action)
                } catch (e: Exception) {
                }
            }
            Download.ContentType.EPISODE -> {
                try {
                    val action = DownloadsMobileFragmentDirections.actionDownloadsToTvShow(
                        download.tvShowId ?: download.id,
                        download.poster,
                        download.banner,
                    )
                    findNavController().navigate(action)
                } catch (e: Exception) {
                }
            }
        }
    }

    private fun showDeleteConfirmation(download: Download) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.download_delete_confirm)
            .setMessage(R.string.download_delete_message)
            .setPositiveButton(R.string.download_delete) { _, _ ->
                deleteDownload(download)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteDownload(download: Download) {
        viewLifecycleOwner.lifecycleScope.launch {
            val localFilePath = download.localFilePath
            if (localFilePath != null) {
                val file = File(localFilePath)
                if (file.exists()) {
                    file.delete()
                }
                val dir = file.parentFile
                if (dir != null && dir.exists() && dir.listFiles()?.isEmpty() == true) {
                    dir.delete()
                }
            }

            withContext(Dispatchers.IO) {
                database.downloadDao().deleteById(download.id)
                if (download.contentType == Download.ContentType.EPISODE) {
                    val actualEpisodeId = download.id.removePrefix("episode_")
                    database.episodeDao().markAsNotDownloaded(actualEpisodeId)
                }
            }

            updateStorageInfo()
            Toast.makeText(requireContext(), getString(R.string.download_deleted), Toast.LENGTH_SHORT).show()
        }
    }
}
