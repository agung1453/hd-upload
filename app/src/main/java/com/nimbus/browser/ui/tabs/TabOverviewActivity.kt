package com.nimbus.browser.ui.tabs

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nimbus.browser.R
import com.nimbus.browser.core.ThemeHelper
import com.nimbus.browser.core.applyBottomInset
import com.nimbus.browser.core.applyTopInset
import com.nimbus.browser.core.setVisible
import com.nimbus.browser.databinding.ActivityTabOverviewBinding
import com.nimbus.browser.model.TabManager

/**
 * Safari-style tab overview: a scrollable grid of tab cards plus the two
 * "new tab" affordances. Selecting a card returns to the browser with that tab.
 */
class TabOverviewActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTabOverviewBinding
    private lateinit var adapter: TabCardAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeHelper.apply()
        super.onCreate(savedInstanceState)
        binding = ActivityTabOverviewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.topBar.applyTopInset()
        binding.bottomActions.applyBottomInset()

        setupRecycler()
        setupActions()
        refresh()
    }

    private fun setupRecycler() {
        adapter = TabCardAdapter(
            selectedId = TabManager.currentTab?.id,
            onSelect = { tab ->
                setResult(
                    Activity.RESULT_OK,
                    Intent().putExtra(RESULT_SELECTED_TAB, tab.id)
                )
                finish()
            },
            onClose = { tab ->
                TabManager.closeTab(tab)
                refresh()
            }
        )

        binding.rvTabs.layoutManager = GridLayoutManager(this, SPAN_COUNT)
        binding.rvTabs.adapter = adapter
        binding.rvTabs.addItemDecoration(
            GridSpacingDecoration(resources.getDimensionPixelSize(R.dimen.tab_grid_spacing))
        )
        binding.rvTabs.setHasFixedSize(true)
    }

    private fun setupActions() {
        binding.btnDone.setOnClickListener { finish() }

        binding.btnCloseAll.setOnClickListener {
            if (TabManager.count() == 0) {
                finish()
                return@setOnClickListener
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dialog_close_all_title)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_close_all_tabs) { _, _ ->
                    TabManager.closeAllTabs()
                    refresh()
                }
                .show()
        }

        binding.btnNewTab.setOnClickListener {
            selectNewTab(isPrivate = TabManager.privateMode)
        }

        binding.btnNewPrivate.setOnClickListener {
            selectNewTab(isPrivate = true)
        }
    }

    private fun selectNewTab(isPrivate: Boolean) {
        val tab = TabManager.newTab(isPrivate = isPrivate)
        setResult(
            Activity.RESULT_OK,
            Intent().putExtra(RESULT_SELECTED_TAB, tab.id)
        )
        finish()
    }

    private fun refresh() {
        val tabs = TabManager.tabs()
        adapter.submitList(tabs.toList())
        binding.emptyState.setVisible(tabs.isEmpty())
        binding.rvTabs.setVisible(tabs.isNotEmpty())
        binding.txtTitle.text = resources.getQuantityString(
            R.plurals.tabs_open_count,
            tabs.size,
            tabs.size
        )
        binding.btnCloseAll.setVisible(tabs.isNotEmpty())
    }

    companion object {
        const val RESULT_SELECTED_TAB = "nimbus.tabs.selected"
        const val RESULT_NEW_TAB = "nimbus.tabs.newTab"
        private const val SPAN_COUNT = 2
    }
}