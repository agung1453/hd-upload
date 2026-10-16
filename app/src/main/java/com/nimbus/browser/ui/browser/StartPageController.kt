package com.nimbus.browser.ui.browser

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import com.nimbus.browser.R
import com.nimbus.browser.core.Prefs
import com.nimbus.browser.core.Shortcut
import com.nimbus.browser.core.UrlUtils
import com.nimbus.browser.core.setVisible
import com.nimbus.browser.data.HistoryEntry
import com.nimbus.browser.data.HistoryRepository
import com.nimbus.browser.databinding.ViewStartPageBinding
import com.nimbus.browser.databinding.ViewStartShortcutBinding
import com.nimbus.browser.databinding.ViewStartSiteBinding
import java.util.Calendar

interface StartPageHost {
    fun onStartPageNavigate(input: String)
    fun onStartPageShortcutLongPressed(shortcut: Shortcut, anchor: View)
}

/**
 * The native start page: a prominent search column, pinned shortcuts and the
 * recently visited pages. Implemented with plain views (not a WebView and not
 * a RecyclerView inside a ScrollView) so it stays instant and light.
 */
class StartPageController(
    private val context: Context,
    private val container: ViewGroup,
    private val host: StartPageHost
) {

    private val inflater = LayoutInflater.from(context)
    private var binding: ViewStartPageBinding? = null

    fun show() {
        val b = binding ?: ViewStartPageBinding.inflate(inflater, container, true).also {
            binding = it
            wireSearch(it)
        }
        container.setVisible(true)
        populate(b)
    }

    fun hide() {
        binding?.let { it.etSearch.clearFocus() }
        container.setVisible(false)
    }

    val isVisible: Boolean get() = container.visibility == View.VISIBLE

    /** Called whenever the page should re-read history (tab switch, resume). */
    fun refresh() {
        binding?.let { if (container.visibility == View.VISIBLE) populate(it) }
    }

    // ----------------------------------------------------------------- //

    private fun wireSearch(b: ViewStartPageBinding) {
        b.etSearch.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_GO) {
                val text = view.text?.toString().orEmpty()
                hideKeyboard(view)
                view.clearFocus()
                if (text.isNotBlank()) host.onStartPageNavigate(text.trim())
                true
            } else {
                false
            }
        }
        b.searchRow.setOnClickListener {
            b.etSearch.requestFocus()
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(b.etSearch, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun populate(b: ViewStartPageBinding) {
        b.txtGreeting.text = greeting()

        // ---- Shortcuts (4 per row) ----
        val shortcuts = Prefs.shortcuts()
        b.headerShortcuts.setVisible(Prefs.showShortcuts)
        b.gridShortcuts.setVisible(Prefs.showShortcuts)
        if (Prefs.showShortcuts) buildShortcutGrid(b.gridShortcuts, shortcuts)

        // ---- Frequently visited ----
        val frequent = HistoryRepository.frequent(8)
        b.headerFrequent.setVisible(Prefs.showFrequent && frequent.isNotEmpty())
        b.listFrequent.setVisible(Prefs.showFrequent && frequent.isNotEmpty())
        if (Prefs.showFrequent) buildSiteList(b.listFrequent, frequent)

        // ---- Recent ----
        val recent = HistoryRepository.recent(12)
        b.headerRecent.setVisible(Prefs.showRecent && recent.isNotEmpty())
        b.listRecent.setVisible(Prefs.showRecent && recent.isNotEmpty())
        if (Prefs.showRecent) buildSiteList(b.listRecent, recent)

        val empty = Prefs.showRecent && recent.isEmpty() && frequent.isEmpty()
        b.txtEmpty.setVisible(empty)
    }

    private fun buildShortcutGrid(parent: LinearLayout, shortcuts: List<Shortcut>) {
        parent.removeAllViews()
        if (shortcuts.isEmpty()) {
            val hint = inflater.inflate(R.layout.view_start_site, parent, false)
            val empty = ViewStartSiteBinding.bind(hint)
            empty.txtTitle.setText(R.string.start_empty_shortcuts)
            empty.txtUrl.text = ""
            empty.txtGlyph.text = "+"
            empty.favHolder.background = null
            empty.root.setOnClickListener { host.onStartPageNavigate("") }
            parent.addView(hint)
            return
        }

        var row: LinearLayout? = null
        shortcuts.forEachIndexed { index, shortcut ->
            if (index % COLUMNS == 0) {
                row = LinearLayout(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                    orientation = LinearLayout.HORIZONTAL
                }
                parent.addView(row)
            }
            // Non-null local: `inflate` needs a real parent, not a nullable one.
            val currentRow = requireNotNull(row)
            val item = ViewStartShortcutBinding.inflate(inflater, currentRow, false)
            item.txtGlyph.text = UrlUtils.glyphFor(shortcut.url)
            item.txtLabel.text = shortcut.title.ifBlank {
                UrlUtils.domainLabel(shortcut.url)
            }
            item.root.setOnClickListener { host.onStartPageNavigate(shortcut.url) }
            item.root.setOnLongClickListener {
                host.onStartPageShortcutLongPressed(shortcut, item.root)
                true
            }
            currentRow.addView(item.root)
        }
    }

    private fun buildSiteList(parent: LinearLayout, entries: List<HistoryEntry>) {
        parent.removeAllViews()
        entries.forEach { entry ->
            val item = ViewStartSiteBinding.inflate(inflater, parent, false)
            item.txtGlyph.text = UrlUtils.glyphFor(entry.url)
            item.txtTitle.text = entry.title.ifBlank {
                UrlUtils.hostOf(entry.url)
            }
            item.txtUrl.text = UrlUtils.displayUrl(entry.url)
            item.root.setOnClickListener { host.onStartPageNavigate(entry.url) }
            parent.addView(item.root)
        }
    }

    private fun hideKeyboard(view: View) {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun greeting(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val res = when (hour) {
            in 5..11 -> R.string.greeting_morning
            in 12..17 -> R.string.greeting_afternoon
            else -> R.string.greeting_evening
        }
        return context.getString(res)
    }

    private companion object {
        const val COLUMNS = 4
    }
}