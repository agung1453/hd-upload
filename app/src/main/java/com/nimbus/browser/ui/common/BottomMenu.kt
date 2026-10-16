package com.nimbus.browser.ui.common

import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.annotation.DrawableRes
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.nimbus.browser.R
import com.nimbus.browser.core.setVisible
import com.nimbus.browser.databinding.ViewBottomSheetBinding
import com.nimbus.browser.databinding.ViewMenuRowBinding

/** One tappable line in a [BottomMenu]. */
data class MenuEntry(
    val id: String,
    val title: String,
    @DrawableRes val icon: Int,
    val subtitle: String? = null,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val danger: Boolean = false
)

/**
 * The app-wide action sheet. A bottom sheet (rather than a popup menu) matches
 * the iOS-style interaction model the rest of the UI follows.
 */
object BottomMenu {

    fun show(
        context: Context,
        title: String? = null,
        entries: List<MenuEntry>,
        onPick: (MenuEntry) -> Unit
    ) {
        val dialog = BottomSheetDialog(context)
        val sheet = ViewBottomSheetBinding.inflate(LayoutInflater.from(context))
        dialog.setContentView(sheet.root)

        sheet.sheetTitle.text = title.orEmpty()
        sheet.sheetTitle.setVisible(!title.isNullOrEmpty())

        val inflater = LayoutInflater.from(context)
        entries.forEach { entry ->
            val row = ViewMenuRowBinding.inflate(inflater, sheet.sheetContent, false)
            row.tvTitle.text = entry.title
            row.ivIcon.setImageResource(entry.icon)

            if (!entry.subtitle.isNullOrEmpty()) {
                row.tvSubtitle.text = entry.subtitle
                row.tvSubtitle.setVisible(true)
            }
            row.ivCheck.setVisible(entry.checked)
            row.root.isEnabled = entry.enabled
            row.root.alpha = if (entry.enabled) 1f else 0.4f
            if (entry.danger) {
                row.tvTitle.setTextColor(context.getColor(R.color.danger))
                row.ivIcon.setColorFilter(context.getColor(R.color.danger))
            }
            row.root.setOnClickListener {
                if (!entry.enabled) return@setOnClickListener
                dialog.dismiss()
                onPick(entry)
            }
            sheet.sheetContent.addView(row.root)
        }

        // Keep the last row reachable above the gesture bar.
        ViewCompat.setOnApplyWindowInsetsListener(sheet.root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bars.bottom)
            insets
        }

        // Round the sheet to match the app's corner language.
        val sheetView = sheet.root.parent?.let { it as? ViewGroup }?.getChildAt(0)
        sheetView?.setBackgroundResource(R.drawable.bg_sheet_top)

        dialog.show()
    }
}