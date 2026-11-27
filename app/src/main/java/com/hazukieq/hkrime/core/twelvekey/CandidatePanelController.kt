package com.hazukieq.hkrime.core.twelvekey

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.AlignItems
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.JustifyContent
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.keyboard.KeyboardView
import com.hazukieq.hkrime.ime.text.CandidateGroupAdapter
import com.hazukieq.hkrime.ime.text.CandidatePanelAdapter

/**
 * 展開面板僅控制展示、活躍組與開合，候選點擊回調 TextInputManager，不訪問引擎。
 * 新輸入回第 0 組，連續選字盡量保留仍存在的活躍讀音組，不按候選條首選自動滾動。
 */
class CandidatePanelController(
    private val context: Context,
    private val keyboardView: KeyboardView?,
    private val inputArea: FrameLayout?,
    private val panelRoot: View?,
    groupListView: RecyclerView?,
    private val grid: RecyclerView?,
    private val backspaceView: View? = null,
    private val topDivider: View? = null,
    private val railDivider: View? = null,
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

    /** 右格滾動到底部後點擊「全部」按鈕：按需懶加載至 [TwelveKeyTuning.CONFIRM_ALL_LIMIT]。 */
    var onLoadAll: (() -> Unit)? = null

    private val groupList = groupListView
    private var snapshot: TwelveKeySnapshot = TwelveKeySnapshot.EMPTY
    private val navigation = PanelNavigation()
    private var loadingAll = false

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
            // 官方 FlexboxLayoutManager：Row + Wrap 的流式候選；覆寫 onItemsChanged 清理舊 flexLine 緩存，
            // 避免數據集驟縮（如 60 項 → 1 項）時 FlexboxLayoutManager 觸發 IndexOutOfBoundsException。
            layoutManager = object : FlexboxLayoutManager(context) {
                override fun onItemsChanged(recyclerView: RecyclerView) {
                    removeAllViews()
                    super.onItemsChanged(recyclerView)
                }

                override fun onLayoutChildren(
                    recycler: RecyclerView.Recycler,
                    state: RecyclerView.State,
                ) {
                    try {
                        super.onLayoutChildren(recycler, state)
                    } catch (_: IndexOutOfBoundsException) {
                        removeAllViews()
                    }
                }
            }.apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
                justifyContent = JustifyContent.FLEX_START
                alignItems = AlignItems.FLEX_START
            }
            adapter = gridAdapter
            itemAnimator = null
        }
        gridAdapter.onLoadAll = { triggerLoadAll() }
        backspaceView?.setOnClickListener { onBackspace?.invoke() }
        applyTheme()
    }

    /** 切換配色/主題/深淺模式時即時同步展開面板全部區塊（底色、左列、右格、退格鍵）。 */
    fun applyTheme() {
        val cfg = runCatching { Config.get(context) }.getOrNull()
        val fallbackSurface = ContextCompat.getColor(context, R.color.panel_surface_fallback)
        val fallbackGroup = ContextCompat.getColor(context, R.color.panel_group_bg_fallback)
        val fallbackInk = ContextCompat.getColor(context, R.color.panel_backspace_ink_fallback)

        val canvas = cfg?.getColor("keyboard_back_color") ?: fallbackSurface
        val ink = cfg?.getColor("candidate_text_color")
            ?: cfg?.getColor("key_text_color")
            ?: fallbackInk
        val offKey = cfg?.getColor("off_key_back_color")
        val actionBg = cfg?.getColor("bbs") ?: cfg?.getColor("benter")
        val railBg = when {
            offKey != null && offKey != canvas -> offKey
            actionBg != null && actionBg != canvas -> actionBg
            cfg != null -> ColorUtils.blendARGB(canvas, ink, 0.05f)
            else -> fallbackGroup
        }

        val dividerColor = cfg?.getColor("key_border_color")
            ?.let { ColorUtils.setAlphaComponent(it, 175) }
            ?: ColorUtils.setAlphaComponent(ink, 28)

        panelRoot?.setBackgroundColor(canvas)
        groupList?.setBackgroundColor(railBg)
        topDivider?.setBackgroundColor(dividerColor)
        railDivider?.setBackgroundColor(dividerColor)

        backspaceView?.let { bs ->
            val bsBg = cfg?.getColor("bbs")
                ?: cfg?.getColor("benter")
                ?: cfg?.getColor("off_key_back_color")
                ?: cfg?.getColor("key_back_color")
                ?: railBg
            val bsPressed = ColorUtils.blendARGB(bsBg, ink, 0.14f)
            val bsBorder = cfg?.getColor("key_border_color")
                ?: ColorUtils.setAlphaComponent(ink, 40)
            val bsInk = cfg?.getColor("tbs")
                ?: cfg?.getColor("key_text_color")
                ?: fallbackInk
            bs.background = makeBackspaceBackground(bsBg, bsPressed, bsBorder)
            (bs as? TextView)?.setTextColor(bsInk)
        }

        groupList?.recycledViewPool?.clear()
        grid?.recycledViewPool?.clear()
        groupAdapter.applyTheme()
        gridAdapter.applyTheme()
    }

    private fun makeBackspaceBackground(normalColor: Int, pressedColor: Int, borderColor: Int): StateListDrawable {
        val density = context.resources.displayMetrics.density
        val corner = 10f * density
        val stroke = (1f * density).toInt().coerceAtLeast(1)
        fun shape(fill: Int) = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = corner
            setColor(fill)
            setStroke(stroke, borderColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressedColor))
            addState(IntArray(0), shape(normalColor))
        }
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
        loadingAll = false
    }

    private fun triggerLoadAll() {
        if (loadingAll || !snapshot.hasMore) return
        loadingAll = true
        render(scrollGrid = false, scrollGroups = false)
        onLoadAll?.invoke()
    }

    /** 每次按鍵/上屏後由上層調用：整個面板只讀這一份快照。 */
    fun submit(next: TwelveKeySnapshot, preserveReading: Boolean = false) {
        val prevInput = snapshot.input
        val prevDepth = snapshot.phonetics.size
        val advanced = next.phonetics != snapshot.phonetics
        loadingAll = false
        navigation.submit(next, preserveReading)
        snapshot = next
        if (!next.hasCandidates && next.fallback == null && next.phonetics.isEmpty()) {
            if (isShown) hide()
            return
        }
        if (!isShown) return
        // 右列內容變了就回頂；左列僅在原碼或確認層級變化時回頂，同層切換音節（如 pau ↔ sau）保持滾動位置。
        render(scrollGrid = advanced, scrollGroups = next.input != prevInput || next.phonetics.size != prevDepth)
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
                    MultiKeyProfile.active.isKnownSyllable(target.reading) ->
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
        // 12 鍵右列固定是「當前確認序列的候選」，左列不再顯示冗餘的「推荐」行。
        val gridIndex = if (snapshot.phoneticNavigation && recommendIndex >= 0) recommendIndex
        else navigation.activeIndex
        val lastPhonetic = snapshot.phonetics.lastOrNull()
        val atTail = lastPhonetic != null &&
            PhoneticSelection.pending(snapshot.remaining, snapshot.phonetics).isEmpty()
        val leftItems = display.mapIndexedNotNull { index, group ->
            if (group.segment == RECOMMEND_SEGMENT || group.reading.isBlank()) return@mapIndexedNotNull null
            val isInitialRow = group.segment == INITIAL_LETTER_SEGMENT
            val isSelected = if (snapshot.phoneticNavigation) {
                atTail && (
                    (lastPhonetic!!.initialOnly && isInitialRow && group.reading == lastPhonetic.reading) ||
                        (!lastPhonetic.initialOnly && !isInitialRow && group.reading == lastPhonetic.reading)
                    )
            } else {
                index == navigation.activeIndex
            }
            CandidateGroupAdapter.GroupItem(
                index = index,
                reading = group.reading,
                selected = isSelected,
                count = group.items.size,
                contextKey = snapshot.contextKey,
            )
        }
        val showRail = leftItems.isNotEmpty()
        groupList?.visibility = if (showRail) View.VISIBLE else View.GONE
        railDivider?.visibility = if (showRail) View.VISIBLE else View.GONE
        groupAdapter.submit(leftItems)
        gridAdapter.setShowComment(true)
        val savedGridAnchor = if (!scrollGrid) {
            (grid?.layoutManager as? FlexboxLayoutManager)?.findFirstVisibleItemPosition()
                ?: RecyclerView.NO_POSITION
        } else {
            RecyclerView.NO_POSITION
        }
        val currentItems = display[gridIndex].items
        val showLoadAll = snapshot.hasMore &&
            (gridIndex == recommendIndex || !snapshot.phoneticNavigation) &&
            currentItems.size >= TwelveKeyTuning.CONFIRM_RECOMMEND_LIMIT
        val gridItems = ArrayList<CandidatePanelAdapter.Item>(
            currentItems.size + (if (showLoadAll) 1 else 0),
        )
        currentItems.forEach {
            gridItems.add(
                CandidatePanelAdapter.Item(
                    text = it.text,
                    comment = it.comment,
                    index = it.index,
                    segment = it.segment,
                    restCode = it.restCode,
                    contextKey = snapshot.contextKey,
                    inputSpan = it.inputSpan,
                    engineSpan = it.engineSpan,
                ),
            )
        }
        if (showLoadAll) {
            gridItems.add(
                CandidatePanelAdapter.Item(
                    text = if (loadingAll) "加载中…" else "全部",
                    comment = "",
                    index = -1,
                    contextKey = snapshot.contextKey,
                    isLoadAll = true,
                    isLoading = loadingAll,
                ),
            )
        }
        gridAdapter.submit(gridItems)
        if (scrollGroups) groupList?.scrollToPosition(0)
        if (scrollGrid) {
            grid?.scrollToPosition(0)
        } else if (savedGridAnchor > 0 && savedGridAnchor < gridItems.size) {
            grid?.scrollToPosition(savedGridAnchor)
        }
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
