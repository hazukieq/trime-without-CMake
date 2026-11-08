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
 * 展開面板的視圖控制：只負責「把快照畫出來 / 收放面板」，不碰 librime。
 *
 * 交互（設計 §5）：
 * - 左列 = 快照的 comment 分組；點組只切右格視圖，不調引擎、不動 caret。
 * - 右格 = 當前組的全部候選；點候選回調給上層，由上層 Rime.select_candidate(全局下標)。
 * - 面板保持打開，直到 ▼/⌄/退格清空輸入。
 *
 * 連續消費手感（P1）：
 * - 上屏後 input 可能不變、groups 變了：若當前 reading 仍在新組列表裡就保持該組，
 *   否則回第 0 組；
 * - 不主動 scrollToPosition(0)，靠 ListAdapter + DiffUtil 原地更新，避免跳頂/閃爍；
 * - 只有「點左列切組」才把右格滾到頂，左列自己的滾動位置不動。
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
    private var lastInput: String? = null

    /**
     * 非 12 鍵方案（客語 26 鍵等「全字母」方案）走舊路徑：面板內容由外部準備好後交給
     * [showLegacy] / [updateLegacy]，不讀 [snapshot]。
     *
     * 之前 show() 直接 `if (snapshot.isEmpty) return`，而舊路徑永遠不會有快照，
     * 導致這些方案按 ▼ 完全打不開面板（用戶 2026-11-09 反饋）。
     */
    private var legacyMode = false

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
        showFollowingBar()
    }

    /** 開面板時跟隨候選條第一條的讀音（而不是永遠第 0 組）。 */
    private var followBarOnNextSubmit = false

    fun showFollowingBar() {
        followBarOnNextSubmit = true
        show()
    }

    fun show() {
        if (!legacyMode && snapshot.isEmpty) return
        val height = keyboardView?.let { if (it.height > 0) it.height else it.measuredHeight } ?: 0
        if (height > 0) {
            inputArea?.layoutParams?.let {
                it.height = height
                inputArea.requestLayout()
            }
        }
        keyboardView?.visibility = View.GONE
        panelRoot?.visibility = View.VISIBLE
        if (!legacyMode) render(scrollGrid = true, scrollGroups = true)
    }

    /**
     * 非 12 鍵方案：候選由外部一次性算好（全局下標），這裡只負責貼上面板。
     *
     * 這些方案沒有「按 comment 分組」的讀音資訊，左列音節索引沒有意義，直接隱藏，
     * 寬度全部留給候選格。
     */
    fun showLegacy(items: List<CandidatePanelAdapter.Item>) {
        legacyMode = true
        groupList?.visibility = View.GONE
        groupAdapter.clear()
        gridAdapter.submit(items)
        show()
        grid?.scrollToPosition(0)
    }

    /** 面板已顯示時按鍵/上屏後刷新右格（只在舊路徑用）。 */
    fun updateLegacy(items: List<CandidatePanelAdapter.Item>) {
        if (!legacyMode || !isShown) return
        gridAdapter.submit(items)
    }

    fun hide() {
        legacyMode = false
        panelRoot?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
        groupList?.visibility = View.VISIBLE
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
        // 一旦走快照路徑，就恢復 12 鍵的「左列音節索引 + 右格」佈局。
        legacyMode = false
        groupList?.visibility = View.VISIBLE
        val previousReading = snapshot.groups.getOrNull(activeGroup)?.reading
        val inputChanged = next.input != lastInput
        if (inputChanged) lastInput = next.input

        if (followBarOnNextSubmit) {
            // 剛打開面板：與候選條第一條對齐（避免「條上是 nzin、面板開在 nzi」）。
            followBarOnNextSubmit = false
            activeGroup = preferredGroup(next)
        } else if (inputChanged) {
            // 新按鍵/新輸入：預設跟隨候選條第一條所在的組。
            activeGroup = preferredGroup(next)
        } else {
            // 連續消費：input 不變、groups 變了；當前讀音還在就保持，否則回第 0 組。
            val keep = if (previousReading != null) {
                next.groups.indexOfFirst { it.reading == previousReading }
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

    /** 候選條第一條所屬的組；找不到就退回第 0 組。 */
    private fun preferredGroup(next: TwelveKeySnapshot): Int {
        val first = next.barEntries.firstOrNull() ?: return 0
        val idx = next.groups.indexOfFirst { group ->
            group.items.any { it.index == first.index && it.text == first.text }
        }
        return if (idx >= 0) idx else 0
    }

    private fun pickGroup(group: CandidateGroupAdapter.GroupItem) {
        // 舊路徑沒有分組資料（左列已隱藏），點組不該把面板重繪成空。
        if (legacyMode) return
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
                CandidatePanelAdapter.Item(text = it.text, comment = it.comment, index = it.index)
            },
        )
        if (scrollGroups) groupList?.scrollToPosition(0)
        if (scrollGrid) grid?.scrollToPosition(0)
    }

}
