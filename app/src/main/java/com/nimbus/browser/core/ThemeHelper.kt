package com.nimbus.browser.core

import androidx.appcompat.app.AppCompatDelegate

object ThemeHelper {

    /** Applies the stored light/dark preference. Safe to call repeatedly. */
    fun apply() {
        AppCompatDelegate.setDefaultNightMode(
            when (Prefs.themeMode) {
                ThemeMode.SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                ThemeMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                ThemeMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }
}