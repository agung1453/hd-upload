package com.nimbus.browser.data

import android.content.Context

/**
 * Holds the application [Context] so the repositories can open the database
 * lazily without every call site having to thread a context through.
 *
 * [NimbusApp] calls [install] during `onCreate`, which is the supported path.
 * [require] additionally falls back to the running process's `Application`
 * instance so a call made before `onCreate` still works instead of throwing.
 */
object AppContext {

    @Volatile
    private var stored: Context? = null

    /** Safe to call with any context: only the application context is kept. */
    fun install(context: Context) {
        if (stored != null) return
        synchronized(this) {
            if (stored == null) stored = context.applicationContext
        }
    }

    fun peek(): Context? = stored

    fun require(): Context {
        stored?.let { return it }
        val fallback = currentProcessApplication()
        if (fallback != null) {
            install(fallback)
            return fallback
        }
        error("No Context available. Call AppContext.install(context) from Application.onCreate().")
    }

    @Suppress("PrivateApi")
    private fun currentProcessApplication(): Context? = runCatching {
        Class.forName("android.app.ActivityThread")
            .getDeclaredMethod("currentApplication")
            .apply { isAccessible = true }
            .invoke(null) as? Context
    }.getOrNull()
}