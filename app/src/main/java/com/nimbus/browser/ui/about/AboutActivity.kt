package com.nimbus.browser.ui.about

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import com.nimbus.browser.BuildConfig
import com.nimbus.browser.R
import com.nimbus.browser.databinding.ActivityAboutBinding

/**
 * Static "about" screen: app name, version, rendering engine and privacy blurb.
 *
 * Launched from the Settings screen (Settings -> About). It is a plain screen so
 * it deliberately hosts no preferences and keeps no state beyond the back stack.
 */
class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.txtVersion.text = getString(R.string.about_version, BuildConfig.VERSION_NAME)

        // The WebView provider package is only present on devices that actually
        // ship a WebView, so the row degrades gracefully to "Not available".
        binding.txtWebViewValue.text = WebViewCompat.getCurrentWebViewPackage(this)
            ?.versionName
            ?.takeIf { it.isNotBlank() }
            ?.let { version -> getString(R.string.about_webview_value, version) }
            ?: getString(R.string.about_webview_unknown)

        applyWindowInsets()
    }

    /** Pad the toolbar by the status bar and the content by the navigation bar. */
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