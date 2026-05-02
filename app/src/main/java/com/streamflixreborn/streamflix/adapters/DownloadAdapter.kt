package com.streamflixreborn.streamflix.adapters

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewbinding.ViewBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ItemDownloadHeaderMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemDownloadHeaderTvBinding
import com.streamflixreborn.streamflix.databinding.ItemDownloadMobileBinding
import com.streamflixreborn.streamflix.databinding.ItemDownloadTvBinding
import com.streamflixreborn.streamflix.models.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class DownloadItem {
    data class Header(
        val tvShowId: String,
        val tvShowTitle: String,
        val seasonNumber: Int
    ) : DownloadItem()

    data class Download(val download: com.streamflixreborn.streamflix.models.Download) : DownloadItem()
}

class DownloadsAdapter(
    private val onPlayClick: (Download) -> Unit,
    private val onDeleteClick: (Download) -> Unit,
    private val onTitleClick: (Download) -> Unit,
    private val onNavigateUp: () -> Unit = {},
    private val onItemFocused: (Int) -> Unit = {},
    private val database: AppDatabase? = null,
    private val isTv: Boolean = true
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val TYPE_HEADER_TV = 0
        private const val TYPE_ITEM_TV = 1
        private const val TYPE_HEADER_MOBILE = 2
        private const val TYPE_ITEM_MOBILE = 3
    }

    private val items = mutableListOf<DownloadItem>()
    var progressMap: Map<String, DownloadProgress> = emptyMap()
    
    private val activeHolders = mutableMapOf<String, ItemViewHolder>()

    data class DownloadProgress(
        val progress: Int,
        val status: String,
        val speed: Long = 0,
        val etaSeconds: Long = -1
    )

    fun submitList(newItems: List<DownloadItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    fun getCurrentList(): List<DownloadItem> = items.toList()

    fun indexOfDownload(downloadId: String): Int {
        return items.indexOfFirst {
            it is DownloadItem.Download && it.download.id == downloadId
        }
    }

    override fun getItemViewType(position: Int): Int = when {
        isTv && items[position] is DownloadItem.Header -> TYPE_HEADER_TV
        isTv && items[position] is DownloadItem.Download -> TYPE_ITEM_TV
        !isTv && items[position] is DownloadItem.Header -> TYPE_HEADER_MOBILE
        !isTv && items[position] is DownloadItem.Download -> TYPE_ITEM_MOBILE
        else -> throw IllegalStateException("Unknown item type")
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)

        return when (viewType) {
            TYPE_HEADER_TV -> HeaderViewHolder(ItemDownloadHeaderTvBinding.inflate(inflater, parent, false))
            TYPE_ITEM_TV -> ItemViewHolder(ItemDownloadTvBinding.inflate(inflater, parent, false))
            TYPE_HEADER_MOBILE -> HeaderViewHolder(ItemDownloadHeaderMobileBinding.inflate(inflater, parent, false))
            TYPE_ITEM_MOBILE -> ItemViewHolder(ItemDownloadMobileBinding.inflate(inflater, parent, false))
            else -> throw IllegalStateException("Unknown view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> holder.bind(items[position] as DownloadItem.Header)
            is ItemViewHolder -> holder.bind((items[position] as DownloadItem.Download).download)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (holder is ItemViewHolder && payloads.isNotEmpty()) {
            holder.updateProgress((items[position] as DownloadItem.Download).download)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        super.onViewAttachedToWindow(holder)
        if (holder is ItemViewHolder) {
            val download = items.getOrNull(holder.bindingAdapterPosition) as? DownloadItem.Download
            download?.let { activeHolders[it.download.id] = holder }
        }
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        super.onViewDetachedFromWindow(holder)
        if (holder is ItemViewHolder) {
            val download = items.getOrNull(holder.bindingAdapterPosition) as? DownloadItem.Download
            download?.let { activeHolders.remove(it.download.id) }
        }
    }

    fun updateDownloadProgress(downloadId: String, progress: DownloadProgress) {
        val progressInfo = progressMap[downloadId] ?: progress
        activeHolders[downloadId]?.let { holder ->
            holder.updateProgressOnly(progressInfo)
        }
    }

    inner class HeaderViewHolder(binding: ViewBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private val tvShowTitle: TextView = binding.root.findViewById(R.id.tvShowTitle)
        private val seasonSubtitle: TextView = binding.root.findViewById(R.id.seasonSubtitle)

        fun bind(header: DownloadItem.Header) {
            tvShowTitle.text = header.tvShowTitle
            seasonSubtitle.text = itemView.context.getString(
                R.string.download_season_label,
                header.seasonNumber
            )
        }
    }

    inner class ItemViewHolder(
        private val binding: ViewBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val ivPoster: ImageView = binding.root.findViewById(R.id.ivPoster)
        private val tvTitle: TextView = binding.root.findViewById(R.id.tvTitle)
        private val tvSubtitle: TextView = binding.root.findViewById(R.id.tvSubtitle)
        private val tvStatus: TextView = binding.root.findViewById(R.id.tvStatus)
        private val progressBar: ProgressBar = binding.root.findViewById(R.id.progressBar)
        private val tvProgressPercent: TextView = binding.root.findViewById(R.id.tvProgressPercent)
        private val btnAction: ImageButton = binding.root.findViewById(R.id.btnAction)
        private val btnDelete: ImageButton = binding.root.findViewById(R.id.btnDelete)
        private val root: View = binding.root.findViewById(R.id.downloadItemRoot)

        private fun setupInnerControlKeyListener(view: View) {
            view.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_UP &&
                    (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
                ) {
                    return@setOnKeyListener false
                }
                false
            }
        }

        fun bind(download: Download) {
            tvTitle.text = download.title

            val subtitle = when {
                download.contentType == Download.ContentType.EPISODE &&
                    download.seasonNumber != null &&
                    download.episodeNumber != null -> {
                    download.subtitle ?: itemView.context.getString(
                        R.string.download_episode_info,
                        download.seasonNumber,
                        download.episodeNumber
                    )
                }
                download.subtitle != null -> download.subtitle!!
                else -> ""
            }

            tvSubtitle.text = subtitle
            tvSubtitle.visibility = if (subtitle.isNotEmpty()) View.VISIBLE else View.GONE

            val posterUrl = if (download.contentType == Download.ContentType.EPISODE) {
                database?.tvShowDao()?.getById(download.tvShowId ?: "")?.poster
                    ?: download.banner
            } else {
                download.poster ?: download.banner
            }

            if (!posterUrl.isNullOrEmpty()) {
                Glide.with(itemView.context)
                    .load(posterUrl)
                    .placeholder(R.drawable.glide_fallback_cover)
                    .error(R.drawable.glide_fallback_cover)
                    .transition(DrawableTransitionOptions.withCrossFade())
                    .into(ivPoster)
            } else {
                ivPoster.setImageResource(R.drawable.glide_fallback_cover)
            }

            val progressInfo = progressMap[download.id]
            
            when (download.status) {
                Download.DownloadStatus.DOWNLOADING,
                Download.DownloadStatus.QUEUED,
                Download.DownloadStatus.PAUSED -> {
                    progressBar.visibility = View.VISIBLE
                    tvProgressPercent.visibility = View.VISIBLE
                    progressBar.progress = progressInfo?.progress ?: download.progress
                    tvProgressPercent.text = "${progressBar.progress}%"
                    btnAction.visibility = View.GONE
                    tvStatus.text = progressInfo?.status.orEmpty()
                    tvStatus.visibility = if (tvStatus.text.isEmpty()) View.GONE else View.VISIBLE
                }

                Download.DownloadStatus.COMPLETED -> {
                    progressBar.visibility = View.GONE
                    tvProgressPercent.visibility = View.GONE
                    btnAction.visibility = View.VISIBLE
                    
                    val actualItemId = when (download.contentType) {
                        Download.ContentType.MOVIE -> download.id.removePrefix("movie_")
                        Download.ContentType.EPISODE -> download.id.removePrefix("episode_")
                        else -> download.id
                    }
                    
                    database?.let { db ->
                        val scope = CoroutineScope(Dispatchers.IO)
                        scope.launch {
                            val watchState = when (download.contentType) {
                                Download.ContentType.MOVIE -> {
                                    db.movieDao().getById(actualItemId)
                                }
                                Download.ContentType.EPISODE -> {
                                    db.episodeDao().getById(actualItemId)
                                }
                                else -> null
                            }
                            
                            val stateLabel = when {
                                watchState?.isWatched == true -> {
                                    itemView.context.getString(R.string.download_status_watched)
                                }
                                watchState?.watchHistory != null -> {
                                    itemView.context.getString(R.string.download_status_watching)
                                }
                                else -> {
                                    itemView.context.getString(R.string.download_status_downloaded)
                                }
                            }
                            
                            withContext(Dispatchers.Main) {
                                tvStatus.text = stateLabel
                                tvStatus.visibility = View.VISIBLE
                            }
                        }
                    } ?: run {
                        tvStatus.text = itemView.context.getString(R.string.download_status_downloaded)
                        tvStatus.visibility = View.VISIBLE
                    }
                }

                else -> {
                    progressBar.visibility = View.GONE
                    tvProgressPercent.visibility = View.GONE
                    btnAction.visibility = View.GONE
                    tvStatus.text = progressInfo?.status.orEmpty()
                    tvStatus.visibility = if (tvStatus.text.isEmpty()) View.GONE else View.VISIBLE
                }
            }

            btnAction.setOnClickListener { onPlayClick(download) }
            btnDelete.setOnClickListener { onDeleteClick(download) }
            itemView.setOnClickListener { onTitleClick(download) }

            setupInnerControlKeyListener(btnAction)
            setupInnerControlKeyListener(btnDelete)

            root.setOnFocusChangeListener { _, hasFocus ->
                root.isSelected = hasFocus
                if (hasFocus) {
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        onItemFocused(position)
                    }
                }
            }
        }

        fun updateProgressOnly(progressInfo: DownloadProgress) {
            progressBar.progress = progressInfo.progress
            tvProgressPercent.text = "${progressInfo.progress}%"
            tvStatus.text = progressInfo.status
            tvStatus.visibility = if (progressInfo.status.isEmpty()) View.GONE else View.VISIBLE
        }

        fun updateProgress(download: Download) {
            val progressInfo = progressMap[download.id]
            
            when (download.status) {
                Download.DownloadStatus.DOWNLOADING,
                Download.DownloadStatus.QUEUED,
                Download.DownloadStatus.PAUSED -> {
                    progressBar.progress = progressInfo?.progress ?: download.progress
                    tvProgressPercent.text = "${progressBar.progress}%"
                    tvStatus.text = progressInfo?.status.orEmpty()
                    tvStatus.visibility = if (tvStatus.text.isEmpty()) View.GONE else View.VISIBLE
                }
                
                Download.DownloadStatus.COMPLETED -> {
                    progressBar.visibility = View.GONE
                    tvProgressPercent.visibility = View.GONE
                    btnAction.visibility = View.VISIBLE
                    
                    val actualItemId = when (download.contentType) {
                        Download.ContentType.MOVIE -> download.id.removePrefix("movie_")
                        Download.ContentType.EPISODE -> download.id.removePrefix("episode_")
                        else -> download.id
                    }
                    
                    database?.let { db ->
                        val scope = CoroutineScope(Dispatchers.IO)
                        scope.launch {
                            val watchState = when (download.contentType) {
                                Download.ContentType.MOVIE -> db.movieDao().getById(actualItemId)
                                Download.ContentType.EPISODE -> db.episodeDao().getById(actualItemId)
                                else -> null
                            }
                            
                            val stateLabel = when {
                                watchState?.isWatched == true -> itemView.context.getString(R.string.download_status_watched)
                                watchState?.watchHistory != null -> itemView.context.getString(R.string.download_status_watching)
                                else -> itemView.context.getString(R.string.download_status_downloaded)
                            }
                            
                            withContext(Dispatchers.Main) {
                                tvStatus.text = stateLabel
                                tvStatus.visibility = View.VISIBLE
                            }
                        }
                    } ?: run {
                        tvStatus.text = itemView.context.getString(R.string.download_status_downloaded)
                        tvStatus.visibility = View.VISIBLE
                    }
                }
                
                else -> {
                    tvStatus.text = progressInfo?.status.orEmpty()
                    tvStatus.visibility = if (tvStatus.text.isEmpty()) View.GONE else View.VISIBLE
                }
            }
        }
    }
}
