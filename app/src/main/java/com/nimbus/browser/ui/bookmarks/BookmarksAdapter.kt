package com.nimbus.browser.ui.bookmarks

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nimbus.browser.core.UrlUtils
import com.nimbus.browser.core.setIconTint
import com.nimbus.browser.data.BookmarkEntry
import com.nimbus.browser.databinding.ItemBookmarkBinding
import com.nimbus.browser.ui.common.bindAvatar
import com.nimbus.browser.util.relativeTime
import com.nimbus.browser.R

/** Saved pages, newest first. */
class BookmarksAdapter(
    private val onClick: (BookmarkEntry) -> Unit,
    private val onLongClick: (BookmarkEntry) -> Unit
) : ListAdapter<BookmarkEntry, BookmarksAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemBookmarkBinding.inflate(
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
    fun itemAt(position: Int): BookmarkEntry? =
        if (position in 0 until itemCount) getItem(position) else null

    inner class ViewHolder(
        private val binding: ItemBookmarkBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        init {
            binding.imgOpen.setIconTint(R.color.text_tertiary)
        }

        fun bind(item: BookmarkEntry) {
            val context = binding.root.context

            binding.txtTitle.text = item.title
            binding.txtUrl.text = UrlUtils.displayUrl(item.url)
            binding.txtMeta.text = context.getString(
                R.string.bookmark_meta,
                relativeTime(context, item.createdAt)
            )
            binding.faviconHolder.bindAvatar(binding.txtGlyph, item.url)

            binding.root.setOnClickListener { onClick(item) }
            binding.root.setOnLongClickListener {
                onLongClick(item)
                true
            }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BookmarkEntry>() {
            override fun areItemsTheSame(oldItem: BookmarkEntry, newItem: BookmarkEntry): Boolean =
                oldItem.url == newItem.url

            override fun areContentsTheSame(oldItem: BookmarkEntry, newItem: BookmarkEntry): Boolean =
                oldItem == newItem
        }
    }
}