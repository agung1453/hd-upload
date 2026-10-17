package com.nimbus.browser.ui.settings

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.nimbus.browser.BuildConfig
import com.nimbus.browser.R
import com.nimbus.browser.core.Prefs
import com.nimbus.browser.core.WebCacheMode
import com.nimbus.browser.core.toast
import com.nimbus.browser.ui.about.AboutActivity

/**
 * The preference list itself.
 *
 * Two of the entries are *index based*:
 *
 *  * `pref_text_zoom` — `Prefs` stores an Int index into [Prefs.TEXT_ZOOM_STEPS]
 *  * `pref_web_cache`  — `Prefs` stores an Int index into [WebCacheMode]
 *
 * A `ListPreference`, however, can only ever persist a String. To stop the two
 * worlds from fighting over the same key (a String left behind would make
 * `Prefs.getInt()` throw a `ClassCastException`, and the widget would throw on
 * `getPersistedString()` when reading the Int back), those two preferences are
 * declared `android:persistent="false"` in res/xml/preferences.xml. The widget
 * therefore keeps its selection in memory only, and this fragment is the single
 * writer: it parses the selected entry value back into an Int and stores it via
 * [Prefs.edit]. [readInt] also repairs a stale String if one ever slips in.
 */
class SettingsFragment : PreferenceFragmentCompat() {

    /**
     * The exact store [Prefs] uses, so preference widgets and app code never
     * drift onto two different SharedPreferences files.
     */
    private val store: SharedPreferences
        get() = requireContext().getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.sharedPreferencesName = Prefs.FILE
        preferenceManager.sharedPreferencesMode = Context.MODE_PRIVATE

        setPreferencesFromResource(R.xml.preferences, rootKey)

        bindIndexPreference(Prefs.KEY_TEXT_ZOOM, Prefs.DEFAULT_TEXT_ZOOM_INDEX)
        bindIndexPreference(Prefs.KEY_WEB_CACHE, WebCacheMode.AUTO.ordinal)

        bindAction(getString(R.string.PREF_KEY_CLEAR_DATA)) {
            ClearDataDialog().show(parentFragmentManager, ClearDataDialog.TAG)
        }
        bindAction(getString(R.string.PREF_KEY_OPEN_DOWNLOADS)) { openDownloads() }
        bindAction(getString(R.string.PREF_KEY_RESET_SHORTCUTS)) {
            DefaultShortcuts.restoreDefaults(requireContext())
        }
        bindAction(getString(R.string.PREF_KEY_ABOUT)) {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
        }

        findPreference<Preference>(getString(R.string.PREF_KEY_CLEAR_DATA))?.summary =
            getString(R.string.pref_clear_data_summary, BuildConfig.VERSION_NAME)

        syncAllSummaries()
    }

    override fun onResume() {
        super.onResume()
        // The index preferences are non-persistent, so re-apply the stored value
        // every time the screen comes back (they may have been edited elsewhere).
        bindIndexPreference(Prefs.KEY_TEXT_ZOOM, Prefs.DEFAULT_TEXT_ZOOM_INDEX)
        bindIndexPreference(Prefs.KEY_WEB_CACHE, WebCacheMode.AUTO.ordinal)
        syncAllSummaries()
    }

    // ------------------------------------------------------------------ index prefs

    /**
     * Wires a `ListPreference` whose entry values are "0", "1", "2"... to the Int
     * stored under the very same key. See the class doc for why this is needed.
     */
    private fun bindIndexPreference(key: String, default: Int) {
        val preference = findPreference<ListPreference>(key) ?: return

        preference.setOnPreferenceChangeListener { _, newValue ->
            val index = newValue?.toString()?.toIntOrNull() ?: default
            Prefs.edit { putInt(key, index) }
            // Keep the listener: it is what mirrors the selection into the store.
            true
        }

        // Non-persistent widgets start from the XML default; show the real value.
        preference.value = readInt(key, default).toString()
        syncSummary(preference)
    }

    /**
     * Reads [key] as an Int, repairing the store if something stored a String
     * (e.g. an older build where the widget was persistent).
     */
    private fun readInt(key: String, default: Int): Int {
        val raw = store.all[key]
        val value = when (raw) {
            is Int -> raw
            is String -> raw.toIntOrNull() ?: default
            else -> default
        }
        if (raw !is Int) {
            Prefs.edit {
                remove(key)
                putInt(key, value)
            }
        }
        return value
    }

    /**
     * Shows the selected entry of every list preference as its summary.
     *
     * Done in code instead of relying on the `%s` marker in the XML: androidx
     * still formats it but logs a deprecation warning on every rebind.
     */
    private fun syncAllSummaries() {
        LIST_KEYS.forEach { key -> syncSummary(getString(key)) }
    }

    /** Shows the selected list entry as the preference summary. */
    private fun syncSummary(key: String) {
        val preference = findPreference<ListPreference>(key) ?: return
        syncSummary(preference)
    }

    private fun syncSummary(preference: ListPreference) {
        // ListPreference.getEntryAt() does not exist in androidx.preference
        // 1.2.1; getEntries() is the supported accessor.
        val index = preference.findIndexOfValue(preference.value)
        val entries = preference.entries
        val entry = if (index in entries.indices) entries[index].toString() else null
        preference.summary = entry ?: preference.title
    }

    // ------------------------------------------------------------------ actions

    private fun bindAction(key: String, action: () -> Unit) {
        findPreference<Preference>(key)?.setOnPreferenceClickListener {
            action()
            true
        }
    }

    /**
     * Hands off to the system download screen. Package visibility rules (API 30+)
     * mean `resolveActivity` can legitimately return null here.
     */
    private fun openDownloads() {
        val context = context ?: return
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        if (intent.resolveActivity(context.packageManager) != null) {
            startActivity(intent)
        } else {
            context.toast(R.string.toast_no_app_found)
        }
    }

    companion object {
        /** Every list backed preference, so their summaries can be kept in sync. */
        private val LIST_KEYS = intArrayOf(
            R.string.PREF_KEY_THEME,
            R.string.PREF_KEY_SEARCH_ENGINE,
            R.string.PREF_KEY_TEXT_ZOOM,
            R.string.PREF_KEY_WEB_CACHE
        )
    }
}