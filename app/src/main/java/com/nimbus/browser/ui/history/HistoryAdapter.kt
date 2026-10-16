package com.nimbus.browser.ui.history

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nimbus.browser.core.UrlUtils
import com.nimbus.browser.core.setIconTint
import com.nimbus.browser.core.setVisible
import com.nimbus.browser.data.HistoryEntry
import com.nimbus.browser.databinding.ItemHistoryBinding
import com.nimbus.browser.ui.common.bindAvatar
import com.nimbus.browser.util.relativeTime
import com.nimbus.browser.R

/**
 * Rows of visited pages.
 *
 * [ListAdapter] handles the diffing for us, which matters here because the
 * cache in `HistoryRepository` is replaced wholesale after every mutation.
 */
class HistoryAdapter(
    private val onClick: (HistoryEntry) -> Unit,
    private val onLongClick: (HistoryEntry) -> Unit,
    private val onDelete: (HistoryEntry) -> Unit
) : ListAdapter<HistoryEntry, HistoryAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemHistoryBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    /** The row at a RecyclerView position, or null once it is being animated away. */
    fun itemAt(position: Int): HistoryEntry? =
        if (position in 0 until itemCount) getItem(position) else null

    inner class ViewHolder(
        private val binding: ItemHistoryBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.btnDelete.setIconTint(R.color.text_tertiary)
        }

        fun bind(item: HistoryEntry) {
            binding.txtTitle.text = item.title
            binding.txtUrl.text = UrlUtils.displayUrl(item.url)
            binding.txtMeta.text = metaLine(item)
            binding.faviconHolder.bindAvatar(binding.txtGlyph, item.url)

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
            binding.btnDelete.setOnClickListener { onDelete(item) }

            // No reason to offer "delete" on a row the swipe already removed.
            binding.btnDelete.setVisible(item.url.isNotEmpty())
            binding.root.isClickable = item.url.isNotEmpty()
        }

        private fun metaLine(item: HistoryEntry): String {
            val context = binding.root.context
            val whenText = relativeTime(context, item.lastVisit)
            val visits = if (item.visitCount <= 1) {
                context.getString(R.string.history_meta_one_visit)
            } else {
                context.getString(R.string.history_meta_visits, item.visitCount)
            }
            return if (whenText.isEmpty()) visits else context.getString(
                R.string.history_meta_recent,
                whenText,
                visits
            )
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<HistoryEntry>() {
            override fun areItemsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
                oldItem.url == newItem.url

            override fun areContentsTheSame(oldItem: HistoryEntry, newItem: HistoryEntry): Boolean =
                oldItem == newItem
        }
    }
}