package com.nimbus.browser.ui.history

import android.content.Intent
import android.graphics.Canvas
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nimbus.browser.R
import com.nimbus.browser.core.setVisible
import com.nimbus.browser.core.toast
import com.nimbus.browser.data.HistoryEntry
import com.nimbus.browser.data.HistoryRepository
import com.nimbus.browser.databinding.ActivityListScreenBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Flat, searchable list of everything the browser has visited.
 *
 * Rows can be removed three ways - the trailing cross, a long press or a
 * swipe - and all three funnel through [deleteWithUndo] so the snackbar is the
 * single place undo lives.
 */
class HistoryActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListScreenBinding

    private val adapter by lazy {
        HistoryAdapter(
            onClick = ::open,
            onLongClick = ::deleteWithUndo,
            onDelete = ::deleteWithUndo
        )
    }

    private var searchJob: Job? = null
    private var searchVisible = false

    /**
     * Designed toolbar height captured once. The inset listener runs again on
     * every inset dispatch (rotation, keyboard, gesture changes), so the
     * addition has to be made against the original value, never against the
     * already-adjusted one.
     */
    private var baseToolbarHeight: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityListScreenBinding.inflate(layoutInflater)
        setContentView(binding.root)

        HistoryRepository.init(this)

        binding.txtTitle.setText(R.string.action_history)
        binding.etSearch.setHint(R.string.history_search_hint)
        binding.btnAction.setImageResource(R.drawable.ic_trash)
        binding.btnAction.contentDescription = getString(R.string.cd_history_clear)
        binding.btnAction.setVisible(true)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnAction.setOnClickListener { confirmClearAll() }
        binding.btnSearch.setOnClickListener { toggleSearch() }

        setUpList()
        applyWindowInsets()

        binding.etSearch.doAfterTextChanged { runSearch(it?.toString().orEmpty()) }

        showEntries(HistoryRepository.recent(LIST_LIMIT))
        if (savedInstanceState?.getBoolean(STATE_SEARCH_VISIBLE) == true) {
            setSearchVisible(true)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SEARCH_VISIBLE, searchVisible)
    }

    // ------------------------------------------------------------------- setup

    private fun setUpList() {
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.setHasFixedSize(true)
        attachSwipeToDelete()
    }

    /**
     * Edge-to-edge: the toolbar grows by the status-bar inset (so its content
     * stays optically centred) and the root picks up the navigation-bar inset.
     * Done by hand rather than through `fitsSystemWindows` because the toolbar
     * needs height + padding while the root only needs padding.
     */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (baseToolbarHeight == 0) {
                val params = view.layoutParams
                baseToolbarHeight = if (params.height > 0) params.height else view.height
            }
            val params = view.layoutParams
            params.height = baseToolbarHeight + bars.top
            view.layoutParams = params
            view.updatePadding(top = bars.top)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootList) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom)
            insets
        }
    }

    // ------------------------------------------------------------------ search

    private fun toggleSearch() = setSearchVisible(!searchVisible)

    private fun setSearchVisible(visible: Boolean) {
        searchVisible = visible
        binding.etSearch.setVisible(visible)
        binding.btnSearch.setVisible(!visible)
        if (!visible) {
            binding.etSearch.setText("")
            runSearch("")
        } else {
            binding.etSearch.requestFocus()
            binding.etSearch.setSelection(binding.etSearch.text.length)
        }
    }

    /** Debounced through coroutine cancellation rather than a Handler. */
    private fun runSearch(query: String) {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val trimmed = query.trim()
            val results = if (trimmed.isEmpty()) {
                HistoryRepository.recentAsync(LIST_LIMIT)
            } else {
                HistoryRepository.searchAsync(trimmed)
            }
            showEntries(results)
        }
    }

    // -------------------------------------------------------------- list state

    private fun showEntries(entries: List<HistoryEntry>) {
        adapter.submitList(entries)
        val isEmpty = entries.isEmpty()
        binding.emptyState.setVisible(isEmpty)
        binding.list.setVisible(!isEmpty)

        if (!isEmpty) return

        val query = binding.etSearch.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            binding.imgEmpty.setImageResource(R.drawable.ic_history)
            binding.txtEmptyTitle.setText(R.string.history_empty_title)
            binding.txtEmptyBody.setText(R.string.history_empty_body)
        } else {
            binding.imgEmpty.setImageResource(R.drawable.ic_search)
            binding.txtEmptyTitle.setText(R.string.history_search_empty_title)
            binding.txtEmptyBody.text = getString(R.string.history_search_empty_body, query)
        }
    }

    // ----------------------------------------------------------------- actions

    private fun open(entry: HistoryEntry) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)))
        }.onFailure { toast(R.string.toast_no_app_found) }
    }

    private fun deleteWithUndo(entry: HistoryEntry) {
        HistoryRepository.deleteVisit(entry.url)
        showEntries(currentFiltered())
        Snackbar
            .make(binding.rootList, R.string.history_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) { HistoryRepository.recordVisit(entry.url, entry.title) }
            .show()
    }

    private fun confirmClearAll() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.history_clear_title)
            .setMessage(R.string.history_clear_message)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ ->
                HistoryRepository.clearHistory()
                toast(R.string.toast_cleared)
                showEntries(emptyList())
            }
            .show()
    }

    /**
     * Re-reads whatever the search box currently asks for, so a delete made
     * from a filtered list does not snap the UI back to unfiltered results.
     */
    private fun currentFiltered(): List<HistoryEntry> {
        val query = binding.etSearch.text?.toString()?.trim().orEmpty()
        return if (query.isEmpty()) {
            HistoryRepository.recent(LIST_LIMIT)
        } else {
            HistoryRepository.search(query)
        }
    }

    // ------------------------------------------------------------ swipe to go

    private fun attachSwipeToDelete() {
        val callback = object : ItemTouchHelper.SimpleCallback(
            0,
            ItemTouchHelper.START or ItemTouchHelper.END
        ) {
            private val background = ColorDrawable(
                ContextCompat.getColor(this@HistoryActivity, R.color.danger)
            )
            private val icon: Drawable? =
                ContextCompat.getDrawable(this@HistoryActivity, R.drawable.ic_trash)

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val entry = adapter.itemAt(position)
                if (entry == null) {
                    adapter.notifyItemChanged(position)
                    return
                }
                deleteWithUndo(entry)
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean
            ) {
                val view = viewHolder.itemView
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
                    val swipingRight = dX > 0
                    if (swipingRight) {
                        background.setBounds(
                            view.left,
                            view.top,
                            view.left + dX.toInt(),
                            view.bottom
                        )
                    } else {
                        background.setBounds(
                            view.right + dX.toInt(),
                            view.top,
                            view.right,
                            view.bottom
                        )
                    }
                    background.draw(c)

                    // Keep the trash glyph pinned to the edge being pulled so it
                    // stays visible at full swipe distance and never clips.
                    icon?.let { trash ->
                        val size = view.height / 3
                        val inset = (view.height - size) / 2
                        val left = if (swipingRight) {
                            view.left + inset
                        } else {
                            view.right - inset - size
                        }
                        trash.setBounds(left, view.top + inset, left + size, view.top + inset + size)
                        trash.setTint(ContextCompat.getColor(this@HistoryActivity, android.R.color.white))
                        trash.draw(c)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }
        }
        ItemTouchHelper(callback).attachToRecyclerView(binding.list)
    }

    private companion object {
        const val LIST_LIMIT = 200
        const val STATE_SEARCH_VISIBLE = "history.searchVisible"
    }
}