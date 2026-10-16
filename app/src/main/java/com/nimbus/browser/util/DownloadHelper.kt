package com.nimbus.browser.util

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil

/**
 * Streams a WebView download through the system DownloadManager so it keeps
 * working while the browser is in the background and shows the normal system
 * notification.
 */
object DownloadHelper {

    /**
     * @return the download id, or null when the request could not be created.
     */
    fun enqueue(
        context: Context,
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
        contentLength: Long
    ): Long? = runCatching {
        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return null

        val request = DownloadManager.Request(Uri.parse(url))

        // Carry the session so authenticated downloads keep working.
        val cookies = CookieManager.getInstance().getCookie(url)
        if (!cookies.isNullOrEmpty()) {
            request.addRequestHeader("Cookie", cookies)
        }
        if (!userAgent.isNullOrEmpty()) {
            request.addRequestHeader("User-Agent", userAgent)
        }

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        request.setTitle(fileName)
        request.setDescription(url)
        request.setMimeType(mimeType ?: "application/octet-stream")
        request.setNotificationVisibility(
            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
        } else {
            @Suppress("DEPRECATION")
            request.setDestinationInExternalPublicDir(
                Environment.DIRECTORY_DOWNLOADS,
                fileName
            )
        }

        manager.enqueue(request)
    }.getOrNull()
}