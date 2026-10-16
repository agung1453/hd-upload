package com.nimbus.browser.ui.bookmarks

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nimbus.browser.R
import com.nimbus.browser.core.UrlUtils
import com.nimbus.browser.core.setVisible
import com.nimbus.browser.core.toast
import com.nimbus.browser.data.BookmarkEntry
import com.nimbus.browser.data.BookmarkRepository
import com.nimbus.browser.databinding.ActivityListScreenBinding
import com.nimbus.browser.databinding.DialogNewBookmarkBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Saved pages, newest first.
 *
 * Unlike history there is no swipe gesture here: bookmarks are a short list of
 * things the user deliberately kept, so removal is a deliberate long press (or
 * the confirmation dialog), not an accidental flick.
 */
class BookmarksActivity : AppCompatActivity() {

    private lateinit var binding: ActivityListScreenBinding

    private val adapter by lazy {
        BookmarksAdapter(onClick = ::open, onLongClick = ::confirmDelete)
    }

    private var searchJob: Job? = null
    private var searchVisible = false

    /** Set while a live search is running so add/remove know what to redraw. */
    private var activeQuery: String = ""

    /**
     * Held in a field (not a lambda captured by the closure it registers) so
     * [onDestroy] can pass the *same* instance back to `removeChangeListener`.
     */
    private var changeListener: (() -> Unit)? = null

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

        BookmarkRepository.init(this)

        binding.txtTitle.setText(R.string.action_bookmarks)
        binding.etSearch.setHint(R.string.bookmarks_search_hint)
        binding.btnAction.setImageResource(R.drawable.ic_add)
        binding.btnAction.contentDescription = getString(R.string.cd_bookmarks_add)
        binding.btnAction.setVisible(true)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnSearch.setOnClickListener { toggleSearch() }
        binding.btnAction.setOnClickListener { showAddDialog() }
        binding.etSearch.doAfterTextChanged {
            activeQuery = it?.toString()?.trim().orEmpty()
            runSearch()
        }

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.list.setHasFixedSize(true)

        applyWindowInsets()

        if (savedInstanceState?.getBoolean(STATE_SEARCH_VISIBLE) == true) {
            setSearchVisible(true)
        }

        // Any change made from elsewhere in the app (the toolbar star, the menu)
        // refreshes this list without the screen having to be told.
        changeListener = { reload() }
        BookmarkRepository.addChangeListener(changeListener)

        reload()
    }

    override fun onDestroy() {
        changeListener?.let { BookmarkRepository.removeChangeListener(it) }
        changeListener = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_SEARCH_VISIBLE, searchVisible)
    }

    // ------------------------------------------------------------------- setup

    /**
     * Same hand-rolled inset split as the history screen: the toolbar grows by
     * the status-bar inset, the root takes the navigation-bar inset as padding.
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
        if (visible) {
            binding.etSearch.requestFocus()
            binding.etSearch.setSelection(binding.etSearch.text.length)
        } else {
            binding.etSearch.setText("")
        }
    }

    private fun runSearch() {
        searchJob?.cancel()
        searchJob = lifecycleScope.launch {
            val results = if (activeQuery.isEmpty()) {
                BookmarkRepository.bookmarksAsync()
            } else {
                BookmarkRepository.searchAsync(activeQuery)
            }
            showEntries(results)
        }
    }

    private fun reload() {
        if (activeQuery.isEmpty()) {
            showEntries(BookmarkRepository.bookmarks())
        } else {
            runSearch()
        }
    }

    // -------------------------------------------------------------- list state

    private fun showEntries(entries: List<BookmarkEntry>) {
        adapter.submitList(entries)
        val isEmpty = entries.isEmpty()
        binding.emptyState.setVisible(isEmpty)
        binding.list.setVisible(!isEmpty)
        if (!isEmpty) return

        binding.imgEmpty.setImageResource(
            if (activeQuery.isEmpty()) R.drawable.ic_bookmark else R.drawable.ic_search
        )
        if (activeQuery.isEmpty()) {
            binding.txtEmptyTitle.setText(R.string.bookmarks_empty_title)
            binding.txtEmptyBody.setText(R.string.bookmarks_empty_body)
        } else {
            binding.txtEmptyTitle.setText(R.string.bookmarks_search_empty_title)
            binding.txtEmptyBody.text =
                getString(R.string.bookmarks_search_empty_body, activeQuery)
        }
    }

    // ----------------------------------------------------------------- actions

    private fun open(entry: BookmarkEntry) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(entry.url)))
        }.onFailure { toast(R.string.toast_no_app_found) }
    }

    private fun confirmDelete(entry: BookmarkEntry) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_delete_bookmark_title)
            .setMessage(entry.title)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_delete) { _, _ -> deleteWithUndo(entry) }
            .show()
    }

    private fun deleteWithUndo(entry: BookmarkEntry) {
        BookmarkRepository.removeBookmark(entry.url)
        reload()
        Snackbar
            .make(binding.rootList, R.string.bookmarks_deleted, Snackbar.LENGTH_LONG)
            .setAction(R.string.action_undo) {
                BookmarkRepository.addBookmark(entry.url, entry.title)
            }
            .show()
    }

    /** Small title + url form; validation happens before the dialog closes. */
    private fun showAddDialog() {
        val form = DialogNewBookmarkBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.bookmarks_add_title)
            .setView(form.root)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save, null)
            .create()

        dialog.setOnShowListener {
            val save = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val rawUrl = form.etBookmarkUrl.text?.toString()?.trim().orEmpty()
                if (rawUrl.isEmpty() || !UrlUtils.isProbablyUrl(rawUrl)) {
                    form.etBookmarkUrl.error = getString(R.string.bookmarks_add_error_url)
                    return@setOnClickListener
                }
                val url = UrlUtils.ensureScheme(rawUrl)
                val title = form.etBookmarkTitle.text?.toString()?.trim()
                    .orEmpty()
                    .ifEmpty { UrlUtils.displayUrl(url) }
                BookmarkRepository.addBookmark(url, title)
                dialog.dismiss()
                toast(R.string.toast_bookmark_added)
            }
        }
        dialog.show()
    }

    private companion object {
        const val STATE_SEARCH_VISIBLE = "bookmarks.searchVisible"
    }
}