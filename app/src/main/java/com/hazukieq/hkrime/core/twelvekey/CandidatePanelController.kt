package com.hazukieq.hkrime.core.twelvekey

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.ime.keyboard.KeyboardView
import com.hazukieq.hkrime.ime.text.CandidateGroupAdapter
import com.hazukieq.hkrime.ime.text.CandidatePanelAdapter

/**
 * 展開面板的視圖控制：只負責「把快照畫出來 / 收放面板」，不碰 librime。
 *
 * 交互（設計 §5）：
 * - 左列 = 快照的 comment 分組；點組只切右格視圖，不調引擎、不動 caret。
 * - 右格 = 當前組的全部候選；點候選回調給上層，由上層 Rime.select_candidate(全局下標)。
 * - 面板保持打開，直到 ▼/⌄/退格清空輸入。
 */
class CandidatePanelController(
    context: Context,
    private val keyboardView: KeyboardView?,
    private val inputArea: FrameLayout?,
    private val panelRoot: View?,
    groupListView: RecyclerView?,
    private val grid: RecyclerView?,
) {

    val groupAdapter = CandidateGroupAdapter(context) { group -> pickGroup(group) }
    val gridAdapter = CandidatePanelAdapter(context) { item -> onPickCandidate?.invoke(item) }

    /** 點右格候選（index = 全局下標；fallback 時為 -1）。 */
    var onPickCandidate: ((CandidatePanelAdapter.Item) -> Unit)? = null

    /** 沒有任何候選時點 ▼：收起鍵盤。 */
    var onHideKeyboard: (() -> Unit)? = null

    private val groupList = groupListView
    private var snapshot: TwelveKeySnapshot = TwelveKeySnapshot.EMPTY
    private var activeGroup = 0
    private var renderedKey: String? = null
    private var lastInput: String? = null

    init {
        this.groupList?.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = groupAdapter
            itemAnimator = null
        }
        grid?.apply {
            layoutManager = GridLayoutManager(context, GRID_COLUMNS)
            adapter = gridAdapter
            itemAnimator = null
        }
    }

    val isShown: Boolean get() = panelRoot?.visibility == View.VISIBLE

    fun toggle() {
        if (isShown) {
            hide()
            return
        }
        val hasSomething = snapshot.hasCandidates || snapshot.fallback != null
        if (!hasSomething) {
            onHideKeyboard?.invoke()
            return
        }
        activeGroup = 0
        show()
    }

    fun show() {
        if (snapshot.isEmpty) return
        val height = keyboardView?.let { if (it.height > 0) it.height else it.measuredHeight } ?: 0
        if (height > 0) {
            inputArea?.layoutParams?.let {
                it.height = height
                inputArea.requestLayout()
            }
        }
        keyboardView?.visibility = View.GONE
        panelRoot?.visibility = View.VISIBLE
        render(forceScroll = true)
    }

    fun hide() {
        panelRoot?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
        inputArea?.layoutParams?.let {
            it.height = FrameLayout.LayoutParams.WRAP_CONTENT
            inputArea.requestLayout()
        }
        groupAdapter.clear()
        gridAdapter.clear()
        renderedKey = null
        lastInput = null
    }

    /** 每次按鍵/上屏後由上層調用：整個面板只讀這一份快照。 */
    fun submit(next: TwelveKeySnapshot) {
        if (next.input != lastInput) {
            // 輸入變了（新按鍵/上屏）：默認組回到第 0 組，右格跟隨新快照
            activeGroup = 0
            lastInput = next.input
        }
        snapshot = next
        if (!next.hasCandidates && next.fallback == null) {
            if (isShown) hide()
            return
        }
        if (!isShown) return
        render(forceScroll = false)
    }

    /** 舊路徑（非 12 鍵）兼容：直接給已算好的左列/右格。 */
    fun submitLegacy(groups: List<CandidateGroupAdapter.GroupItem>, items: List<CandidatePanelAdapter.Item>) {
        if (isShown) {
            groupAdapter.submit(groups)
            gridAdapter.submit(items)
            groupList?.scrollToPosition(0)
            grid?.scrollToPosition(0)
        }
    }

    private fun pickGroup(group: CandidateGroupAdapter.GroupItem) {
        if (group.index == activeGroup) return
        activeGroup = group.index
        render(forceScroll = true)
    }

    private fun render(forceScroll: Boolean) {
        val groups = snapshot.groups
        if (groups.isEmpty()) {
            val fallback = snapshot.fallback ?: return
            groupAdapter.submit(
                listOf(CandidateGroupAdapter.GroupItem(0, "〔${fallback.rawCode}〕…", true)),
            )
            gridAdapter.submit(
                listOf(CandidatePanelAdapter.Item(fallback.letters, "本地原文", -1)),
            )
            groupList?.scrollToPosition(0)
            grid?.scrollToPosition(0)
            renderedKey = "fallback:${fallback.rawCode}"
            return
        }
        if (activeGroup !in groups.indices) activeGroup = 0
        groupAdapter.submit(
            groups.mapIndexed { index, group ->
                CandidateGroupAdapter.GroupItem(
                    index = index,
                    reading = group.reading,
                    selected = index == activeGroup,
                    count = group.size,
                )
            },
        )
        gridAdapter.submit(
            groups[activeGroup].items.map {
                CandidatePanelAdapter.Item(text = it.text, comment = it.comment, index = it.index)
            },
        )
        val key = "${snapshot.input}|$activeGroup"
        if (forceScroll || key != renderedKey) {
            groupList?.scrollToPosition(0)
            grid?.scrollToPosition(0)
        }
        renderedKey = key
    }

    companion object {
        private const val GRID_COLUMNS = 4
    }
}
