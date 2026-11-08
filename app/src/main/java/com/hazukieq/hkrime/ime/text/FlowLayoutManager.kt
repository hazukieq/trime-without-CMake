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
 * 12 鍵候選的長短差異主要來自字數與拼音長度，所以這裡要的是「每行高度一致、
 * 每個 item 寬度不同」的標籤流。
 *
 * 對齊：**每行整體水平居中**。候選只有 1~3 個時，行寬遠小於可用寬度，左對齊會在右側
 * 留下大片空白（用戶 2026-11-08 明確指出）；居中讓少數候選也落在視覺重心上，
 * 行排滿時則與左對齊無異。
 *
 * 面板一組候選通常只有十幾到幾十個（1224 條候選分散在 69 組裡），因此這裡
 * 一次量完並擺好全部 item，換來簡單且可預期的滾動；不做二級回收。
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

        // ③ 定位：左對齊（用戶 2026-11-08 否決了居中）；縱向從 -scrollOffset 開始
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
