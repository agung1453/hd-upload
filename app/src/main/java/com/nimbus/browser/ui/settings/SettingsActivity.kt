package com.nimbus.browser.ui.settings

import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.nimbus.browser.R
import com.nimbus.browser.core.Prefs
import com.nimbus.browser.core.ThemeHelper
import com.nimbus.browser.databinding.ActivitySettingsBinding

/**
 * Hosts [SettingsFragment].
 *
 * The theme is set in code (before `super.onCreate`) so the Material preference
 * overlay declared by `Theme.Nimbus.Settings` is guaranteed to be in place even
 * if the activity is recreated for a theme change, where the manifest theme is
 * only re-resolved lazily.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    /** Last theme id handed to [ThemeHelper]; guards against redundant recreates. */
    private var appliedTheme: String = ""

    private val themeListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != Prefs.KEY_THEME) return@OnSharedPreferenceChangeListener
            val next = Prefs.getString(Prefs.KEY_THEME, appliedTheme)
            if (next == appliedTheme) return@OnSharedPreferenceChangeListener
            appliedTheme = next
            ThemeHelper.apply()
            recreate()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Nimbus_Settings)
        super.onCreate(savedInstanceState)

        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // First launch of the app is also the cheapest moment to seed the start
        // page shortcuts; the guard inside makes this idempotent.
        DefaultShortcuts.ensureSeeded()

        binding.btnBack.setOnClickListener { finish() }

        applyWindowInsets()

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }

        // Seeds the guard with the currently *effective* theme, so writing the
        // untouched default from the preference XML is not seen as a change.
        appliedTheme = Prefs.themeMode.id
        Prefs.registerChangeListener(themeListener)
    }

    override fun onDestroy() {
        Prefs.unregisterChangeListener(themeListener)
        super.onDestroy()
    }

    /** Pad the toolbar by the status bar and the root by the navigation bar. */
    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.topBar) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, view.paddingBottom)
            insets
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom)
            insets
        }
    }
}