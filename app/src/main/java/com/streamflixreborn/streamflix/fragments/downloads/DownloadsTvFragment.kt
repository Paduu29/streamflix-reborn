package com.streamflixreborn.streamflix.fragments.downloads

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.DownloadItem
import com.streamflixreborn.streamflix.adapters.DownloadsAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.FragmentDownloadsTvBinding
import com.streamflixreborn.streamflix.databinding.ItemDownloadHeaderTvBinding
import com.streamflixreborn.streamflix.models.Download
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.DownloadManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class DownloadsTvFragment : Fragment() {

    private var _binding: FragmentDownloadsTvBinding? = null
    private val binding get() = _binding!!

    private val database by lazy { AppDatabase.getInstance(requireContext()) }
    private val downloadManager by lazy { DownloadManager.getInstance(requireContext()) }
    private val adapter by lazy {
        DownloadsAdapter(
            onPlayClick = { download -> playDownload(download) },
            onDeleteClick = { download -> showDeleteConfirmation(download) },
            onTitleClick = { download -> navigateToContent(download) },
            onNavigateUp = { navigateUpToTabs() },
            onItemFocused = { position -> ensureHeaderVisibleForFocusedItem(position) },
            database = database,
            isTv = true,
        )
    }

    private var currentTab = Tab.ALL
    private var observeDownloadsJob: Job? = null
    private var cachedHeaderHeight = 0
    private var pendingScrollToTop = false

    enum class Tab {
        ALL, MOVIES, EPISODES, DOWNLOADING
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDownloadsTvBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupRecyclerView()
        setupTabs()
        observeDownloads()
        observeDownloadProgress()
        updateStorageInfo()

        binding.tabAll.requestFocus()

        binding.tabLayout.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                val selectedButton = when (currentTab) {
                    Tab.ALL -> binding.tabAll
                    Tab.MOVIES -> binding.tabMovies
                    Tab.EPISODES -> binding.tabEpisodes
                    Tab.DOWNLOADING -> binding.tabDownloading
                }
                selectedButton.requestFocus()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        observeDownloadsJob?.cancel()
        _binding = null
    }

    private fun setupRecyclerView() {
        binding.rvDownloads.adapter = adapter

        binding.rvDownloads.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false

            val focusedChild = binding.rvDownloads.findFocus()
            val viewHolder = focusedChild?.let {
                binding.rvDownloads.findContainingViewHolder(it)
            }
            val position = viewHolder?.bindingAdapterPosition ?: -1
            val items = adapter.getCurrentList()

            when (keyCode) {

                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    val sideNav = activity?.findViewById<View>(R.id.nav_main)
                    if (sideNav != null) {
                        sideNav.requestFocus()
                        return@setOnKeyListener true
                    }
                }

                KeyEvent.KEYCODE_DPAD_UP -> {
                    val previousDownloadPosition = findPreviousDownloadPosition(position, items)
                    if (previousDownloadPosition == null) {
                        navigateUpToTabs()
                        return@setOnKeyListener true
                    }
                    if (hasHeaderAbove(previousDownloadPosition, items)) {
                        focusDownloadPosition(previousDownloadPosition, items)
                        return@setOnKeyListener true
                    }
                    return@setOnKeyListener false
                }

                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (items.isEmpty()) return@setOnKeyListener true

                    val nextDownloadPosition = findNextDownloadPosition(position, items)
                    if (nextDownloadPosition == null) {
                        return@setOnKeyListener true
                    }
                }
            }

            false
        }
    }

    private fun navigateUpToTabs() {
        val selectedButton = when (currentTab) {
            Tab.ALL -> binding.tabAll
            Tab.MOVIES -> binding.tabMovies
            Tab.EPISODES -> binding.tabEpisodes
            Tab.DOWNLOADING -> binding.tabDownloading
        }
        selectedButton.requestFocus()
    }

    private fun findPreviousDownloadPosition(position: Int, items: List<DownloadItem>): Int? {
        for (index in position - 1 downTo 0) {
            if (items.getOrNull(index) is DownloadItem.Download) return index
        }
        return null
    }

    private fun findNextDownloadPosition(position: Int, items: List<DownloadItem>): Int? {
        for (index in position + 1 until items.size) {
            if (items[index] is DownloadItem.Download) return index
        }
        return null
    }

    private fun hasHeaderAbove(position: Int, items: List<DownloadItem>): Boolean {
        return position > 0 && items.getOrNull(position - 1) is DownloadItem.Header
    }

    private fun focusDownloadPosition(position: Int, items: List<DownloadItem>) {
        val layoutManager = binding.rvDownloads.layoutManager as? LinearLayoutManager ?: return
        val offset = if (hasHeaderAbove(position, items)) resolveHeaderHeight() else 0
        layoutManager.scrollToPositionWithOffset(position, offset)
        binding.rvDownloads.post {
            val target = binding.rvDownloads.findViewHolderForAdapterPosition(position)?.itemView
                ?: layoutManager.findViewByPosition(position)
            target?.requestFocus()
        }
    }

    private fun ensureHeaderVisibleForFocusedItem(position: Int) {
        val items = adapter.getCurrentList()
        if (!hasHeaderAbove(position, items)) return

        binding.rvDownloads.post {
            val layoutManager = binding.rvDownloads.layoutManager as? LinearLayoutManager ?: return@post
            val targetView = binding.rvDownloads.findViewHolderForAdapterPosition(position)?.itemView
                ?: layoutManager.findViewByPosition(position)
            val headerHeight = resolveHeaderHeight()
            if (targetView != null && targetView.top < headerHeight) {
                layoutManager.scrollToPositionWithOffset(position, resolveHeaderHeight())
            }
        }
    }

    private fun resolveHeaderHeight(): Int {
        if (cachedHeaderHeight > 0) return cachedHeaderHeight

        val headerBinding = ItemDownloadHeaderTvBinding.inflate(layoutInflater, binding.rvDownloads, false)
        val parentWidth = binding.rvDownloads.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.AT_MOST)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        headerBinding.root.measure(widthSpec, heightSpec)

        cachedHeaderHeight = headerBinding.root.measuredHeight
        return cachedHeaderHeight
    }

    private fun setupTabs() {
        val sideNavId = R.id.nav_main
        binding.tabAll.nextFocusLeftId = sideNavId
        binding.tabMovies.nextFocusLeftId = R.id.tabAll
        binding.tabEpisodes.nextFocusLeftId = R.id.tabMovies
        binding.tabDownloading.nextFocusLeftId = R.id.tabEpisodes

        val tabs = listOf(
            binding.tabAll to Tab.ALL,
            binding.tabMovies to Tab.MOVIES,
            binding.tabEpisodes to Tab.EPISODES,
            binding.tabDownloading to Tab.DOWNLOADING,
        )
        tabs.forEach { (button, tab) ->
            button.setOnClickListener {
                selectTab(tab, button)
            }
            button.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                    val items = adapter.getCurrentList()
                    val firstDownloadPosition = items.indexOfFirst { it is DownloadItem.Download }
                    if (firstDownloadPosition >= 0) {
                        focusDownloadPosition(firstDownloadPosition, items)
                        return@setOnKeyListener true
                    }
                }
                false
            }
        }
        selectTab(Tab.ALL, binding.tabAll)
    }

    private fun selectTab(tab: Tab, selectedButton: Button) {
        currentTab = tab
        pendingScrollToTop = true
        binding.tabAll.isSelected = selectedButton == binding.tabAll
        binding.tabMovies.isSelected = selectedButton == binding.tabMovies
        binding.tabEpisodes.isSelected = selectedButton == binding.tabEpisodes
        binding.tabDownloading.isSelected = selectedButton == binding.tabDownloading
        observeDownloads()
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
                    if (pendingScrollToTop) {
                        pendingScrollToTop = false
                        if (groupedItems.isNotEmpty()) {
                            binding.rvDownloads.scrollToPosition(0)
                        }
                    }
                    binding.llEmpty.visibility = if (groupedItems.isEmpty()) View.VISIBLE else View.GONE
                    binding.rvDownloads.isFocusable = groupedItems.isNotEmpty()
                    binding.rvDownloads.isFocusableInTouchMode = groupedItems.isNotEmpty()
                    if (groupedItems.isEmpty() && binding.rvDownloads.hasFocus()) {
                        binding.tabAll.requestFocus()
                    }
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
            Toast.makeText(requireContext(), "Downloaded file not found", Toast.LENGTH_SHORT).show()
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
                    val action = DownloadsTvFragmentDirections.actionDownloadsToMovie(download.id)
                    findNavController().navigate(action)
                } catch (e: Exception) {
                }
            }
            Download.ContentType.EPISODE -> {
                try {
                    val action = DownloadsTvFragmentDirections.actionDownloadsToTvShow(
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
            Toast.makeText(requireContext(), "Download deleted", Toast.LENGTH_SHORT).show()
        }
    }
}
