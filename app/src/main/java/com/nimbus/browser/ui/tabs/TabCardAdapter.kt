package com.nimbus.browser.ui.tabs

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nimbus.browser.R
import com.nimbus.browser.core.UrlUtils
import com.nimbus.browser.core.setVisible
import com.nimbus.browser.databinding.ItemTabCardBinding
import com.nimbus.browser.model.BrowserTab

class TabCardAdapter(
    private val selectedId: String?,
    private val onSelect: (BrowserTab) -> Unit,
    private val onClose: (BrowserTab) -> Unit
) : ListAdapter<BrowserTab, TabCardAdapter.ViewHolder>(DIFF) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemTabCardBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ViewHolder(private val binding: ItemTabCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(tab: BrowserTab) {
            val context = binding.root.context

            binding.txtGlyph.text = tab.glyph()
            binding.txtTitle.text = if (tab.isStartPage) {
                context.getString(R.string.start_page_title)
            } else {
                tab.displayTitle()
            }
            binding.txtDomain.text = if (tab.isStartPage) {
                context.getString(R.string.start_page_title)
            } else {
                UrlUtils.hostOf(tab.url).ifEmpty { UrlUtils.displayUrl(tab.url) }
            }
            binding.txtUrl.text = if (tab.isStartPage) "" else tab.displayUrl()
            binding.icPrivate.setVisible(tab.isPrivate)

            binding.tabCard.setBackgroundResource(
                when {
                    tab.id == selectedId -> R.drawable.bg_tab_card_active
                    tab.isPrivate -> R.drawable.bg_tab_card_private
                    else -> R.drawable.bg_tab_card
                }
            )

            // The stylised preview is only meaningful for real pages.
            binding.preview.setVisible(!tab.isStartPage)

            binding.tabCard.setOnClickListener { onSelect(tab) }
            binding.btnCloseTab.setOnClickListener { onClose(tab) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<BrowserTab>() {
            override fun areItemsTheSame(oldItem: BrowserTab, newItem: BrowserTab) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: BrowserTab, newItem: BrowserTab) =
                oldItem.url == newItem.url &&
                    oldItem.title == newItem.title &&
                    oldItem.revision == newItem.revision &&
                    oldItem.isStartPage == newItem.isStartPage &&
                    oldItem.isPrivate == newItem.isPrivate
        }
    }
}