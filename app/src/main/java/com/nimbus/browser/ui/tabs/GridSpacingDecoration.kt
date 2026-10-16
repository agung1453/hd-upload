package com.nimbus.browser.ui.tabs

import android.graphics.Rect
import android.view.View
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** Uniform gutters between the cards of the tab grid. */
class GridSpacingDecoration(private val spacing: Int) : RecyclerView.ItemDecoration() {

    override fun getItemOffsets(
        outRect: Rect,
        view: View,
        parent: RecyclerView,
        state: RecyclerView.State
    ) {
        val params = view.layoutParams as? GridLayoutManager.LayoutParams ?: return
        if (view.parent == null) return
        val spanCount = (parent.layoutManager as? GridLayoutManager)?.spanCount ?: 2
        val half = spacing / 2
        val column = params.spanIndex

        outRect.left = if (column == 0) spacing else half
        outRect.right = if (column == spanCount - 1) spacing else half
        outRect.top = if (view.top == 0) spacing else half
        outRect.bottom = half
    }
}