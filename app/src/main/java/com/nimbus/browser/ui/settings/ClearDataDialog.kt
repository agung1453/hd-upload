package com.nimbus.browser.ui.settings

import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nimbus.browser.R
import com.nimbus.browser.core.setVisible
import com.nimbus.browser.core.toast
import com.nimbus.browser.data.HistoryRepository
import com.nimbus.browser.databinding.DialogClearDataBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Clear browsing data" checklist.
 *
 * Every item is cleared independently and each call is guarded with `runCatching`
 * so a single misbehaving provider can never abort the whole cleanup. All the
 * genuinely blocking work (DB, ContentResolver, file tree) runs on
 * [Dispatchers.IO]; only the WebView touch happens back on the main thread
 * because `WebView` may not be driven from a worker thread.
 */
class ClearDataDialog : DialogFragment() {

    private var _binding: DialogClearDataBinding? = null
    private val binding get() = requireNotNull(_binding)

    /** Ids of the downloads the user can see; empty hides the download row. */
    private var downloadIds: LongArray = LongArray(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Queried once here so the checkbox row and the removal list stay in sync.
        downloadIds = queryVisibleDownloadIds()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogClearDataBinding.inflate(layoutInflater)

        // Only offer "download history" when there is something to remove.
        binding.chkDownloads.setVisible(downloadIds.isNotEmpty())

        return MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.dialog_clear_data_title)
            .setMessage(R.string.clear_data_message)
            .setView(binding.rootDialog)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_clear) { _, _ -> onClearClicked() }
            .create()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    // ------------------------------------------------------------------ actions

    private fun onClearClicked() {
        val context = context ?: return
        val clearHistory = binding.chkHistory.isChecked
        val clearCookies = binding.chkCookies.isChecked
        val clearCache = binding.chkCache.isChecked
        val clearFormData = binding.chkFormData.isChecked
        val clearDownloads = binding.chkDownloads.isChecked && downloadIds.isNotEmpty()
        val ids = downloadIds

        if (!clearHistory && !clearCookies && !clearCache && !clearFormData && !clearDownloads) {
            context.toast(R.string.toast_data_none_selected)
            dismiss()
            return
        }

        // A DialogFragment is not a LifecycleOwner, so borrow the host activity's
        // scope: the job is cancelled if the user leaves the screen meanwhile.
        val scope = (activity as? LifecycleOwner)?.lifecycleScope
        if (scope == null) {
            dismiss()
            return
        }

        scope.launch {
            withContext(Dispatchers.IO) {
                if (clearHistory) {
                    runCatching { HistoryRepository.clearHistory() }
                }
                if (clearCookies) {
                    runCatching { CookieManager.getInstance().removeAllCookies(null) }
                    runCatching { WebStorage.getInstance().deleteAllData() }
                }
                if (clearCache) {
                    clearCacheDirs(context)
                }
                if (clearDownloads) {
                    runCatching { DownloadManager(context).remove(ids) }
                }
            }

            // WebView insists on being created/touched from the main thread.
            if (clearFormData) {
                runCatching {
                    WebView(context).apply {
                        clearFormData()
                        destroy()
                    }
                }
            }

            if (isAdded) {
                context.toast(R.string.toast_data_cleared)
                dismiss()
            }
        }
    }

    /** Wipes the app cache plus the (usually separate) WebView cache directory. */
    private fun clearCacheDirs(context: Context) {
        runCatching { context.cacheDir.deleteRecursively() }
        runCatching {
            val webCache = context.webViewCacheDir ?: return@runCatching
            if (webCache.absolutePath != context.cacheDir.absolutePath) {
                webCache.deleteRecursively()
            }
        }
    }

    // ------------------------------------------------------------------ downloads

    /** Ids of every download visible to the user, or an empty array on any failure. */
    private fun queryVisibleDownloadIds(): LongArray = runCatching {
        val manager = requireContext()
            .getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: return@runCatching LongArray(0)
        val ids = mutableListOf<Long>()
        manager.query(DownloadManager.Query().setFilterOnlyVisibleDownloads()).use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)
            while (cursor.moveToNext()) {
                ids += cursor.getLong(idColumn)
            }
        }
        ids.toLongArray()
    }.getOrDefault(LongArray(0))

    companion object {
        const val TAG = "ClearDataDialog"
    }
}