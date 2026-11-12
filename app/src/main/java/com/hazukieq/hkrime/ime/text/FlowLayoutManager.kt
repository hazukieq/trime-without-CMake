package com.hazukieq.hkrime.ime.text

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView

/**
 * 不等寬候選流式佈局：按內容測寬，行內左對齊，寬度不足時換行。
 * 按行高度計算滾動範圍，維持垂直 scrollOffset。
 */
class FlowLayoutManager(private val gap: Int) : RecyclerView.LayoutManager() {

    private var totalHeight = 0
    private var scrollOffset = 0

    private class Tile(val view: View, val width: Int, val height: Int)

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

        // ① 先量：每個 item 的自然寬高
        val tiles = ArrayList<Tile>(state.itemCount)
        for (i in 0 until state.itemCount) {
            val view = recycler.getViewForPosition(i)
            addView(view)
            measureChildWithMargins(view, 0, 0)
            tiles.add(
                Tile(
                    view,
                    getDecoratedMeasuredWidth(view).coerceAtMost(avail),
                    getDecoratedMeasuredHeight(view),
                ),
            )
        }

        // ② 分行：行內放不下就換行（行首不換）
        data class Row(val items: MutableList<Tile> = mutableListOf(), var width: Int = 0, var height: Int = 0)

        val rows = ArrayList<Row>()
        var row = Row()
        for (tile in tiles) {
            if (row.items.isNotEmpty() && row.width + gap + tile.width > avail) {
                rows.add(row)
                row = Row()
            }
            if (row.items.isNotEmpty()) row.width += gap
            row.items.add(tile)
            row.width += tile.width
            if (tile.height > row.height) row.height = tile.height
        }
        if (row.items.isNotEmpty()) rows.add(row)

        // 行內左對齊，縱向從 -scrollOffset 開始。
        var y = paddingTop - scrollOffset
        for (r in rows) {
            var x = paddingLeft
            for (tile in r.items) {
                layoutDecoratedWithMargins(tile.view, x, y, x + tile.width, y + tile.height)
                x += tile.width + gap
            }
            y += r.height + gap
        }

        totalHeight = (y - gap) + paddingBottom
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
