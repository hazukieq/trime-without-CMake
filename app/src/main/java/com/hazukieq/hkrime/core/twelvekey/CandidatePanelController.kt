package com.hazukieq.hkrime.core.twelvekey

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.ime.keyboard.KeyboardView
import com.hazukieq.hkrime.ime.text.CandidateGroupAdapter
import com.hazukieq.hkrime.ime.text.FlowLayoutManager
import com.hazukieq.hkrime.ime.text.CandidatePanelAdapter

/**
 * 展開面板僅控制展示、活躍組與開合，候選點擊回調 TextInputManager，不訪問引擎。
 * 新輸入回第 0 組，連續選字盡量保留仍存在的活躍讀音組，不按候選條首選自動滾動。
 */
class CandidatePanelController(
    context: Context,
    private val keyboardView: KeyboardView?,
    private val inputArea: FrameLayout?,
    private val panelRoot: View?,
    groupListView: RecyclerView?,
    private val grid: RecyclerView?,
    backspaceView: View? = null,
) {

    val groupAdapter = CandidateGroupAdapter(context) { group -> pickGroup(group) }
    val gridAdapter = CandidatePanelAdapter(context) { item -> onPickCandidate?.invoke(item) }

    /** 點右格候選（index = 全局下標；fallback 時為 -1）。 */
    var onPickCandidate: ((CandidatePanelAdapter.Item) -> Unit)? = null

    /** 沒有任何候選時點 ▼：收起鍵盤。 */
    var onHideKeyboard: (() -> Unit)? = null

    /** 面板右下角的退格鍵（面板展開時鍵盤被蓋住，不收起面板也能刪）。 */
    var onBackspace: (() -> Unit)? = null

    private val groupList = groupListView
    private var snapshot: TwelveKeySnapshot = TwelveKeySnapshot.EMPTY
    private var activeGroup = 0
    private var lastInput: String? = null

    /**
     * 所有方案（12 鍵、全字母 26 鍵…）都只讀同一份 [snapshot]：
     * 非 12 鍵方案的快照由 TextInputManager 現算後 [submit]，分組/點擊語義與 12 鍵完全一致。
     */

    init {
        this.groupList?.apply {
            layoutManager = LinearLayoutManager(context)
            adapter = groupAdapter
            itemAnimator = null
        }
        grid?.apply {
            layoutManager = FlowLayoutManager(
                (8 * context.resources.displayMetrics.density).toInt(),
            )
            adapter = gridAdapter
            itemAnimator = null
        }
        backspaceView?.setOnClickListener { onBackspace?.invoke() }
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
        render(scrollGrid = true, scrollGroups = true)
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
        lastInput = null
    }

    /** 每次按鍵/上屏後由上層調用：整個面板只讀這一份快照。 */
    fun submit(next: TwelveKeySnapshot) {
        val previousReading = snapshot.groups.getOrNull(activeGroup)?.reading
        val inputChanged = next.input != lastInput
        if (inputChanged) lastInput = next.input

        if (inputChanged) {
            // 新輸入回第 0 組，不隨候選條首選自動滾動定位。
            activeGroup = 0
        } else {
            // 連續消費：input 不變、groups 變了；當前讀音還在就保持，否則回第 0 組。
            // 推薦組（reading 為空）不參與保持，否則逐段選字時會一直卡在推薦。
            val keep = if (!previousReading.isNullOrEmpty()) {
                next.groups.indexOfFirst {
                    it.reading == previousReading && it.segment != RECOMMEND_SEGMENT
                }
            } else {
                -1
            }
            activeGroup = if (keep >= 0) keep else 0
        }

        snapshot = next
        if (!next.hasCandidates && next.fallback == null) {
            if (isShown) hide()
            return
        }
        if (!isShown) return
        // 不 scroll：DiffUtil 原地更新，保持用戶當前滾動位置。
        render(scrollGrid = false, scrollGroups = false)
    }

    private fun pickGroup(group: CandidateGroupAdapter.GroupItem) {
        if (group.index == activeGroup) return
        activeGroup = group.index
        // 切組：只把右格滾到頂，左列保持當前滾動位置。
        render(scrollGrid = true, scrollGroups = false)
    }

    private fun render(scrollGrid: Boolean, scrollGroups: Boolean) {
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
        gridAdapter.setShowComment(groups.size >= 2)
        gridAdapter.submit(
            groups[activeGroup].items.map {
                CandidatePanelAdapter.Item(
                    text = it.text,
                    comment = it.comment,
                    index = it.index,
                    segment = it.segment,
                    restCode = it.restCode,
                )
            },
        )
        if (scrollGroups) groupList?.scrollToPosition(0)
        if (scrollGrid) grid?.scrollToPosition(0)
    }

}
