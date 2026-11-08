package com.hazukieq.hkrime.settings

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * 設置類列表的統一收尾：
 * - 去掉 RecyclerView 的分割線（行本身是白卡片，灰底已能區分）；
 * - 底部留出空白，最後一行不貼屏幕底。
 */
fun View.tunePreferenceList() {
    // PreferenceFragmentCompat 給到 onViewCreated 的是外層容器，RecyclerView 在裡面。
    val rv = when (this) {
        is RecyclerView -> this
        is ViewGroup -> findViewById<RecyclerView>(androidx.preference.R.id.recycler_view)
            ?: (0 until childCount).asSequence()
                .map { getChildAt(it) }
                .filterIsInstance<ViewGroup>()
                .mapNotNull { it.findViewById<RecyclerView>(androidx.preference.R.id.recycler_view) }
                .firstOrNull()
        else -> null
    } ?: return
    while (rv.itemDecorationCount > 0) rv.removeItemDecorationAt(0)
    rv.clipToPadding = false
    val bottom = (24 * rv.resources.displayMetrics.density + 0.5f).toInt()
    rv.setPadding(rv.paddingLeft, rv.paddingTop, rv.paddingRight, bottom)
}
