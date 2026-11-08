package com.hazukieq.hkrime.ime.text

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * 不等寬的流式佈局（標籤流）：每個 item 按自身內容測寬，寬度不夠就換行。
 *
 * 為甚麼不用 GridLayoutManager / StaggeredGridLayoutManager：
 * - GridLayoutManager 的單元格等寬，長詞會被截斷；
 * - StaggeredGridLayoutManager 只支持「同列寬、不同高」，不支持「不同寬」。
 * 12 鍵候選的長短差異主要來自字數與拼音長度，所以這裡要的是「同一行高度一致、
 * 每個 item 寬度不同」的標籤流。
 *
 * 面板一組候選通常只有十幾到幾十個（1224 條候選分散在 69 組裡），因此這裡
 * 一次量完並擺好全部 item，換來簡單且可預期的滾動；不做二級回收。
 */
class FlowLayoutManager(private val gap: Int) : RecyclerView.LayoutManager() {

    private var totalHeight = 0
    private var scrollOffset = 0

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams =
        RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

    override fun canScrollVertically(): Boolean = true

    override fun scrollToPosition(position: Int) {
        scrollOffset = 0
        requestLayout()
    }

    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (state.itemCount == 0) {
            detachAndScrapAttachedViews(recycler)
            totalHeight = 0
            scrollOffset = 0
            return
        }
        detachAndScrapAttachedViews(recycler)

        val avail = width - paddingLeft - paddingRight
        if (avail <= 0) return

        var x = paddingLeft
        var y = paddingTop - scrollOffset
        var rowHeight = 0
        var lastRowY = y

        for (i in 0 until state.itemCount) {
            val view = recycler.getViewForPosition(i)
            addView(view)
            measureChildWithMargins(view, 0, 0)

            val w = getDecoratedMeasuredWidth(view).coerceAtMost(avail)
            val h = getDecoratedMeasuredHeight(view)
            // 一行放不下就換行（行首不換）
            if (x > paddingLeft && x + w > paddingLeft + avail) {
                x = paddingLeft
                y += rowHeight + gap
                rowHeight = 0
            }
            layoutDecoratedWithMargins(view, x, y, x + w, y + h)
            x += w + gap
            if (h > rowHeight) rowHeight = h
            lastRowY = y
        }

        totalHeight = lastRowY + rowHeight + paddingBottom
        // 內容變短時把滾動位置收回來，避免停在空白處
        val max = maxOf(0, totalHeight - height)
        if (scrollOffset > max) {
            offsetChildrenVertical(scrollOffset - max)
            scrollOffset = max
        }
    }

    override fun scrollVerticallyBy(
        dy: Int,
        recycler: RecyclerView.Recycler,
        state: RecyclerView.State,
    ): Int {
        if (childCount == 0) return 0
        val max = maxOf(0, totalHeight - height)
        val target = (scrollOffset + dy).coerceIn(0, max)
        val consumed = target - scrollOffset
        if (consumed != 0) {
            offsetChildrenVertical(-consumed)
            scrollOffset = target
        }
        return consumed
    }

    override fun computeVerticalScrollOffset(state: RecyclerView.State): Int = scrollOffset

    override fun computeVerticalScrollExtent(state: RecyclerView.State): Int =
        maxOf(0, height - paddingTop - paddingBottom)

    override fun computeVerticalScrollRange(state: RecyclerView.State): Int = totalHeight

    /** 供邊界情況的測試/調試使用。 */
    internal fun contentHeight(): Int = totalHeight
}
