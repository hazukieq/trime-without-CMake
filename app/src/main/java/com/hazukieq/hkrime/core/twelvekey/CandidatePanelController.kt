package com.hazukieq.hkrime.core.twelvekey

import android.content.Context
import android.view.View
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.hazukieq.hkrime.ime.keyboard.KeyboardView
import com.hazukieq.hkrime.ime.text.CandidateGroupAdapter
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

    var onConfirmPhonetic: ((String, Boolean, String) -> Unit)? = null

    /** 左列字母行：確認下一段首拼或細化最後一段（同一鍵的另一個字母）。 */
    var onInitialLetter: ((String) -> Unit)? = null

    private val groupList = groupListView
    private var snapshot: TwelveKeySnapshot = TwelveKeySnapshot.EMPTY
    private val navigation = PanelNavigation()

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
            // 官方 FlexboxLayoutManager：Row + Wrap 的流式候選，滾出屏幕的格子由 RecyclerView
            // 回收複用（Google 的 flexbox README 明確說明它會 recycle 離屏 view），不再自繪
            // 一個「實例化全部項目」的 LayoutManager。
            layoutManager = FlexboxLayoutManager(context).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
                justifyContent = JustifyContent.FLEX_START
                alignItems = AlignItems.FLEX_START
            }
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
        val hasSomething = snapshot.hasCandidates || snapshot.fallback != null || snapshot.phonetics.isNotEmpty()
        if (!hasSomething) {
            onHideKeyboard?.invoke()
            return
        }
        navigation.resetSelection()
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
        navigation.resetSelection()
    }

    /** 每次按鍵/上屏後由上層調用：整個面板只讀這一份快照。 */
    fun submit(next: TwelveKeySnapshot, preserveReading: Boolean = false) {
        val advanced = next.phonetics != snapshot.phonetics
        navigation.submit(next, preserveReading)
        snapshot = next
        if (!next.hasCandidates && next.fallback == null && next.phonetics.isEmpty()) {
            if (isShown) hide()
            return
        }
        if (!isShown) return
        // 右列內容變了就回頂；左列只在原碼真的變了才回頂，否則點底部字母會把字母行滾出視野。
        render(scrollGrid = advanced, scrollGroups = next.input != snapshot.input)
    }

    private fun pickGroup(group: CandidateGroupAdapter.GroupItem) {
        val target = navigation.groups.getOrNull(group.index) ?: return
        if (!CandidateSelection.isCurrent(group.contextKey, snapshot.contextKey)) {
            navigation.select(group.index, group.reading, group.contextKey)
            return
        }
        if (snapshot.phoneticNavigation) {
            // 12 鍵：左右徹底分離。左列只負責把模糊碼確認成確切音節/首字母，
            // 右列內容由快照的推薦組決定，不隨左列點擊切組。
            when {
                target.segment == INITIAL_LETTER_SEGMENT -> onInitialLetter?.invoke(target.reading)
                target.segment == RECOMMEND_SEGMENT -> Unit
                target.reading.isNotBlank() &&
                    com.hazukieq.hkrime.core.TwelveKeyPreedit.isKnownSyllable(target.reading) ->
                    onConfirmPhonetic?.invoke(target.reading, false, group.contextKey)
            }
            return
        }
        if (!navigation.select(group.index, group.reading, group.contextKey)) return
        // 切組：只把右格滾到頂，左列保持當前滾動位置。
        render(scrollGrid = true, scrollGroups = false)
    }

    private fun render(scrollGrid: Boolean, scrollGroups: Boolean) {
        val groups = snapshot.groups
        if (groups.isEmpty()) {
            val fallback = snapshot.fallback ?: run {
                groupAdapter.clear()
                gridAdapter.clear()
                return
            }
            groupAdapter.submit(
                listOf(CandidateGroupAdapter.GroupItem(0, "〔${fallback.rawCode}〕…", true)),
            )
            gridAdapter.submit(
                listOf(CandidatePanelAdapter.Item(fallback.letters, "本地原文", -1, contextKey = snapshot.contextKey)),
            )
            groupList?.scrollToPosition(0)
            grid?.scrollToPosition(0)
            return
        }
        val display = navigation.groups
        if (display.isEmpty()) return
        val recommendIndex = display.indexOfFirst { it.segment == RECOMMEND_SEGMENT }
        // 12 鍵右列固定是「當前確認序列的候選」（推薦組），與左列點擊解耦。
        val gridIndex = if (snapshot.phoneticNavigation && recommendIndex >= 0) recommendIndex
        else navigation.activeIndex
        groupAdapter.submit(
            display.mapIndexed { index, group ->
                CandidateGroupAdapter.GroupItem(
                    index = index,
                    reading = group.reading,
                    selected = if (snapshot.phoneticNavigation) {
                        group.segment == INITIAL_LETTER_SEGMENT &&
                            group.reading.firstOrNull() == snapshot.initial
                    } else {
                        index == navigation.activeIndex
                    },
                    count = group.items.size,
                    contextKey = snapshot.contextKey,
                )
            },
        )
        gridAdapter.setShowComment(display.size >= 2)
        gridAdapter.submit(
            display[gridIndex].items.map {
                CandidatePanelAdapter.Item(
                    text = it.text,
                    comment = it.comment,
                    index = it.index,
                    segment = it.segment,
                    restCode = it.restCode,
                    contextKey = snapshot.contextKey,
                    inputSpan = it.inputSpan,
                    engineSpan = it.engineSpan,
                )
            },
        )
        if (scrollGroups) groupList?.scrollToPosition(0)
        if (scrollGrid) grid?.scrollToPosition(0)
    }

    /**
     * 孤單聯想二級：右列原地換成某音節的字（items 首項是「← 返回」）。
     * 左列保持當前字母行，不切頁、不改快照。
     */
    fun showDrilldown(items: List<CandidatePanelAdapter.Item>) {
        gridAdapter.setShowComment(false)
        gridAdapter.submit(items.map { it.copy(contextKey = snapshot.contextKey) })
        grid?.scrollToPosition(0)
    }

    /** 從孤單聯想二級返回原快照網格。 */
    fun restoreGrid() {
        render(scrollGrid = true, scrollGroups = false)
    }
}
