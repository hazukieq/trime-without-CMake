package com.hazukieq.hkrime.ime.hardware

import android.content.Context
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.Config

/**
 * 物理鍵盤接手模式下的「跟隨光標方案切換 / 啟停 & 開關懸浮窗」（快捷鍵 `Ctrl+Shift+M`）：
 * - 風格與 [HardwareClipboardPopup] 一致，直接渲染於 `InputMethod` 透明畫布內（免權限、全場景可見）；
 * - 頂部雙標籤：
 *   - `方案`：僅列出物理鍵盤支援的 26 鍵方案（含已啟用與未啟用），
 *     `Enter`/`1-9`/點擊可切換（未啟用則自動啟用並部署後切換），`Space` 或點右側狀態膠囊可開啟/關閉方案；
 *   - `開關`：切換當前方案的 Rime switches（簡繁、標點、顏文字、表情等），支援連續切換不關閉；
 * - 全鍵盤操作：`Tab`/`←`/`→` 切頁、`↑`/`↓` 選中、`Enter`/`1-9` 確認、`Space` 啟停方案或切開關、`Esc` 關閉。
 */
class HardwareMenuPopup(
    private val context: Context,
    private val isSchemaSupported: (String?) -> Boolean,
) {
    /** 切換到已啟用的方案（參數為目標 schemaId）。 */
    var onSwitchSchema: ((String) -> Unit)? = null

    /**
     * 開啟或關閉某個支援的 26 鍵方案：
     * - `schemaId`：目標方案 ID；
     * - `enable`：`true` 啟用，`false` 停用；
     * - `switchAfterEnable`：啟用後是否立即切換過去；
     * - `onDone`：背景部署完成後的回調（用於刷新彈窗狀態）。
     */
    var onToggleSchemaEnabled: ((schemaId: String, enable: Boolean, switchAfterEnable: Boolean, onDone: () -> Unit) -> Unit)? = null

    /** 切換當前方案第 switchIndex 個 Rime 開關時回調。 */
    var onToggleSwitch: ((Int) -> Unit)? = null

    /** 切換主題配色方案時回調（參數為 colorKey）。 */
    var onSelectColor: ((String) -> Unit)? = null

    var onDismissed: (() -> Unit)? = null

    private val overlay = HardwareOverlayHost(context, touchable = true, dismissOnOutsideTouch = true).apply {
        onOutsideTouch = { dismiss() }
    }

    private var shadowWrapper: FrameLayout? = null
    private var rootCard: LinearLayout? = null
    private var titleView: TextView? = null
    private var tabSchemaView: TextView? = null
    private var tabSwitchView: TextView? = null
    private var tabThemeView: TextView? = null
    private var dividerView: View? = null
    private var listView: RecyclerView? = null
    private var emptyView: TextView? = null
    private var hintView: TextView? = null

    private var activeTab: Int = TAB_SCHEMA
    private var selectedIndex: Int = 0
    private var busyDeploying: Boolean = false
    private var rows: List<MenuRow> = emptyList()
    private var lastAnchorView: View? = null
    private val lastCursorRect = RectF()
    private var lastColorSchemeKey: String? = null

    // Theme tokens
    private var cardBgColor: Int = 0xFFFFFFFF.toInt()
    private var textColor: Int = 0xFF1C1C1E.toInt()
    private var subTextColor: Int = 0xFF8E8E93.toInt()
    private var borderColor: Int = 0xFFDADCE0.toInt()
    private var accentBgColor: Int = 0xFF007AFF.toInt()
    private var accentTextColor: Int = Color.WHITE

    private data class MenuRow(
        val index: Int,
        val schemaId: String?,
        val title: String,
        val badge: String,
        val isCurrent: Boolean,
        val isEnabled: Boolean,
    )

    private val adapter = MenuAdapter()

    val isShowing: Boolean get() = overlay.isShowing

    fun copyTouchableRect(out: android.graphics.Rect): Boolean = overlay.copyTouchableRect(out)

    fun setOnBoundsChanged(listener: (() -> Unit)?) {
        overlay.onBoundsChanged = listener
    }

    fun applyTheme() {
        val cfg = runCatching { Config.get(context) }.getOrNull()
        val selectedColor = runCatching { AppPrefs.defaultInstance().looks.selectedColor }.getOrNull()
        if (cfg != null && (selectedColor != lastColorSchemeKey || cfg.getCurrentColor_("back_color") == null)) {
            runCatching { cfg.initCurrentColors() }
            lastColorSchemeKey = selectedColor
        }
        val bg = cfg?.getColor("back_color")
            ?: cfg?.getColor("keyboard_back_color")
            ?: cfg?.getColor("key_back_color")
            ?: 0xFFFFFFFF.toInt()
        cardBgColor = ColorUtils.setAlphaComponent(bg, 255)
        textColor = cfg?.getColor("candidate_text_color")
            ?: cfg?.getColor("text_color")
            ?: cfg?.getColor("key_text_color")
            ?: if (ColorUtils.calculateLuminance(cardBgColor) < 0.5) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
        val commentColor = cfg?.getColor("comment_text_color")
        subTextColor = if (commentColor != null && commentColor != cardBgColor) {
            commentColor
        } else {
            ColorUtils.setAlphaComponent(textColor, 150)
        }
        val rawBorder = cfg?.getColor("border_color") ?: cfg?.getColor("key_border_color")
        borderColor = if (rawBorder != null && rawBorder != cardBgColor) {
            rawBorder
        } else {
            ColorUtils.setAlphaComponent(textColor, 44)
        }
        val candHiliteBg = cfg?.getColor("hilited_candidate_back_color")
        val keyHiliteBg = cfg?.getColor("hilited_key_back_color")
        accentBgColor = when {
            candHiliteBg != null && candHiliteBg != cardBgColor -> candHiliteBg
            keyHiliteBg != null && keyHiliteBg != cardBgColor -> keyHiliteBg
            else -> 0xFF007AFF.toInt()
        }
        val rawAccentText = cfg?.getColor("hilited_candidate_text_color")
            ?: cfg?.getColor("hilited_key_text_color")
        accentTextColor = if (rawAccentText != null &&
            ColorUtils.calculateContrast(
                ColorUtils.setAlphaComponent(rawAccentText, 255),
                ColorUtils.setAlphaComponent(accentBgColor, 255),
            ) >= 2.2
        ) {
            rawAccentText
        } else if (ColorUtils.calculateLuminance(accentBgColor) < 0.5) {
            Color.WHITE
        } else {
            0xFF1C1C1E.toInt()
        }

        rootCard?.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(12).toFloat()
            setColor(cardBgColor)
            setStroke(dp(1).coerceAtLeast(1), borderColor)
        }
        titleView?.setTextColor(textColor)
        dividerView?.setBackgroundColor(ColorUtils.setAlphaComponent(textColor, 26))
        emptyView?.setTextColor(subTextColor)
        hintView?.setTextColor(subTextColor)
        updateTabPills()
        if (isShowing) {
            adapter.notifyDataSetChanged()
        }
    }

    fun show(anchorView: View?, cursorRect: RectF, initialTab: Int = activeTab) {
        activeTab = initialTab.coerceIn(TAB_SCHEMA, TAB_THEME)
        busyDeploying = false
        lastAnchorView = anchorView
        lastCursorRect.set(cursorRect)

        ensurePopup()
        applyTheme()
        refreshData(resetSelection = true)
        positionPopup(anchorView, lastCursorRect)
    }

    fun updateCursorPosition(anchorView: View?, cursorRect: RectF) {
        lastCursorRect.set(cursorRect)
        if (anchorView != null) lastAnchorView = anchorView
        if (!isShowing) return
        positionPopup(lastAnchorView, lastCursorRect)
    }

    fun dismiss() {
        val wasShowing = isShowing
        busyDeploying = false
        overlay.dismiss()
        if (wasShowing) {
            onDismissed?.invoke()
        }
    }

    fun handleKeyDown(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        if (keyCode == KeyEvent.KEYCODE_ESCAPE || keyCode == KeyEvent.KEYCODE_BACK) {
            dismiss()
            return true
        }
        if (busyDeploying) return true
        when {
            keyCode == KeyEvent.KEYCODE_TAB -> {
                val delta = if (event.isShiftPressed) -1 else 1
                switchTab((activeTab + delta + TAB_COUNT) % TAB_COUNT)
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> {
                switchTab((activeTab + 1) % TAB_COUNT)
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> {
                switchTab((activeTab - 1 + TAB_COUNT) % TAB_COUNT)
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                moveSelection(1)
                return true
            }
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                moveSelection(-1)
                return true
            }
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                activateRow(selectedIndex)
                return true
            }
            keyCode == KeyEvent.KEYCODE_SPACE -> {
                if (activeTab == TAB_SCHEMA) {
                    toggleSchemaEnableAt(selectedIndex)
                } else {
                    activateRow(selectedIndex)
                }
                return true
            }
            keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9 && !event.isCtrlPressed && !event.isAltPressed -> {
                val idx = keyCode - KeyEvent.KEYCODE_1
                if (idx in rows.indices) {
                    activateRow(idx)
                }
                return true
            }
            else -> return true
        }
    }

    private fun switchTab(tab: Int) {
        if (tab == activeTab || busyDeploying) return
        activeTab = tab
        updateTabPills()
        refreshData(resetSelection = true)
        positionPopup(lastAnchorView, lastCursorRect)
    }

    private fun moveSelection(delta: Int) {
        if (rows.isEmpty()) return
        val next = (selectedIndex + delta).coerceIn(0, rows.lastIndex)
        if (next == selectedIndex) return
        val old = selectedIndex
        selectedIndex = next
        adapter.notifyItemChanged(old)
        adapter.notifyItemChanged(next)
        listView?.scrollToPosition(next)
    }

    private fun activateRow(position: Int) {
        if (busyDeploying) return
        val row = rows.getOrNull(position) ?: return
        if (activeTab == TAB_SCHEMA) {
            val sid = row.schemaId ?: return
            if (row.isEnabled) {
                dismiss()
                onSwitchSchema?.invoke(sid)
            } else {
                // 未啟用的 26 鍵方案：先啟用並部署，完成後自動切換
                startSchemaToggle(sid, enable = true, switchAfterEnable = true)
            }
        } else if (activeTab == TAB_SWITCH) {
            onToggleSwitch?.invoke(row.index)
            refreshData(resetSelection = false)
        } else {
            val colorKey = row.schemaId ?: return
            onSelectColor?.invoke(colorKey)
            applyTheme()
            refreshData(resetSelection = false)
        }
    }

    private fun toggleSchemaEnableAt(position: Int) {
        if (busyDeploying || activeTab != TAB_SCHEMA) return
        val row = rows.getOrNull(position) ?: return
        val sid = row.schemaId ?: return
        val enabledSupportedCount = rows.count { it.isEnabled }
        if (row.isEnabled && enabledSupportedCount <= 1) {
            hintView?.text = "至少保留一个已启用的外设方案"
            return
        }
        startSchemaToggle(sid, enable = !row.isEnabled, switchAfterEnable = false)
    }

    private fun startSchemaToggle(schemaId: String, enable: Boolean, switchAfterEnable: Boolean) {
        val handler = onToggleSchemaEnabled ?: return
        busyDeploying = true
        hintView?.text = if (enable) "正在启用并部署方案…" else "正在停用并部署方案…"
        handler.invoke(schemaId, enable, switchAfterEnable) {
            busyDeploying = false
            if (!isShowing) return@invoke
            if (switchAfterEnable) {
                dismiss()
            } else {
                refreshData(resetSelection = false)
                positionPopup(lastAnchorView, lastCursorRect)
            }
        }
    }

    private fun refreshData(resetSelection: Boolean) {
        val built = ArrayList<MenuRow>()
        if (activeTab == TAB_SCHEMA) {
            val currentId = synchronized(Rime.ENGINE_LOCK) { Rime.getSchemaId().orEmpty() }
            val available = runCatching {
                synchronized(Rime.ENGINE_LOCK) { Rime.get_available_schema_list() }
            }.getOrNull().orEmpty()
            val selected = runCatching {
                synchronized(Rime.ENGINE_LOCK) { Rime.get_selected_schema_list() }
            }.getOrNull().orEmpty()
            val selectedIds = LinkedHashSet<String>()
            for (m in selected) {
                val id = m["schema_id"]?.toString()
                if (!id.isNullOrBlank()) selectedIds.add(id)
            }

            // 僅列出物理鍵盤接手模式支援的 26 鍵方案（已啟用在先、未啟用在後）
            val seen = HashSet<String>()
            val supportedEntries = ArrayList<Triple<String, String, Boolean>>()
            for (m in available) {
                val id = m["schema_id"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(id)) continue
                if (!isSchemaSupported(id)) continue
                val name = m["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id
                supportedEntries.add(Triple(id, name, selectedIds.contains(id) || id == currentId))
            }
            // 若當前啟用的方案未在 available 列表裡，也兜底補進來
            for (m in selected) {
                val id = m["schema_id"]?.toString()?.takeIf { it.isNotBlank() } ?: continue
                if (!seen.add(id)) continue
                if (!isSchemaSupported(id)) continue
                val name = m["name"]?.toString()?.takeIf { it.isNotBlank() } ?: id
                supportedEntries.add(Triple(id, name, true))
            }
            supportedEntries.sortWith(
                compareByDescending<Triple<String, String, Boolean>> { it.first == currentId }
                    .thenByDescending { it.third }
                    .thenBy { it.first },
            )

            var currentRowPos = 0
            supportedEntries.forEachIndexed { idx, (id, name, enabled) ->
                val isCur = id == currentId
                if (isCur) currentRowPos = idx
                val badge = when {
                    isCur -> "✓ 当前"
                    enabled -> "已启用"
                    else -> "未启用"
                }
                built.add(
                    MenuRow(
                        index = idx,
                        schemaId = id,
                        title = name,
                        badge = badge,
                        isCurrent = isCur,
                        isEnabled = enabled,
                    ),
                )
            }
            selectedIndex = if (resetSelection) {
                currentRowPos.coerceIn(0, (built.size - 1).coerceAtLeast(0))
            } else {
                selectedIndex.coerceIn(0, (built.size - 1).coerceAtLeast(0))
            }
            hintView?.text = "Tab 切页 · Enter/1-9 切换 · Space 启停 · Esc 关闭"
        } else if (activeTab == TAB_SWITCH) {
            val labels = synchronized(Rime.ENGINE_LOCK) {
                Rime.getContexts()
                Rime.getSchemaSwitchDialogLabels()
            }
            for (i in labels.indices) {
                built.add(
                    MenuRow(
                        index = i,
                        schemaId = null,
                        title = labels[i].orEmpty(),
                        badge = "切换",
                        isCurrent = false,
                        isEnabled = true,
                    ),
                )
            }
            selectedIndex = if (resetSelection) {
                0
            } else {
                selectedIndex.coerceIn(0, (built.size - 1).coerceAtLeast(0))
            }
            hintView?.text = "Tab 切页 · ↑↓ 选择 · Enter/Space/1-9 切换 · Esc 关闭"
        } else {
            val cfg = runCatching { Config.get(context) }.getOrNull()
            val currentColor = runCatching { AppPrefs.defaultInstance().looks.selectedColor }.getOrNull().orEmpty()
            val keys = runCatching { cfg?.colorKeys }.getOrNull()?.also { it.sort() } ?: emptyArray()
            val names = runCatching { cfg?.getColorNames(keys) }.getOrNull() ?: arrayOfNulls(keys.size)
            var currentPos = 0
            for (i in keys.indices) {
                val key = keys[i]
                val label = names.getOrNull(i)?.takeIf { it.isNotBlank() } ?: key
                val isCur = key == currentColor
                if (isCur) currentPos = i
                built.add(
                    MenuRow(
                        index = i,
                        schemaId = key,
                        title = label,
                        badge = if (isCur) "✓ 当前" else "",
                        isCurrent = isCur,
                        isEnabled = true,
                    ),
                )
            }
            selectedIndex = if (resetSelection) {
                currentPos.coerceIn(0, (built.size - 1).coerceAtLeast(0))
            } else {
                selectedIndex.coerceIn(0, (built.size - 1).coerceAtLeast(0))
            }
            hintView?.text = "Tab 切页 · ↑↓ 选择 · Enter/Space/1-9 即时换色 · Esc 关闭"
        }

        rows = built
        if (rows.isEmpty()) {
            listView?.visibility = View.GONE
            emptyView?.text = when (activeTab) {
                TAB_SCHEMA -> "暂无支持的外设方案"
                TAB_SWITCH -> "当前方案无可用开关"
                else -> "暂无可用主题配色"
            }
            emptyView?.visibility = View.VISIBLE
        } else {
            listView?.visibility = View.VISIBLE
            emptyView?.visibility = View.GONE
        }
        adapter.submit(rows)
        if (rows.isNotEmpty()) {
            listView?.scrollToPosition(selectedIndex)
        }
    }

    private fun updateTabPills() {
        styleTabPill(tabSchemaView, activeTab == TAB_SCHEMA)
        styleTabPill(tabSwitchView, activeTab == TAB_SWITCH)
        styleTabPill(tabThemeView, activeTab == TAB_THEME)
    }

    private fun styleTabPill(tv: TextView?, active: Boolean) {
        if (tv == null) return
        tv.setTextColor(if (active) accentTextColor else subTextColor)
        tv.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(7).toFloat()
            setColor(if (active) accentBgColor else ColorUtils.setAlphaComponent(textColor, 14))
        }
    }

    private fun ensurePopup() {
        if (rootCard != null && shadowWrapper != null) return
        val pad = dp(SHADOW_PAD_DP)
        val wrapper = FrameLayout(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
            clipChildren = false
            clipToPadding = false
            setPadding(pad, pad, pad, pad)
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(6).toFloat()
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
            setPadding(dp(10), dp(8), dp(10), dp(8))
            layoutParams = FrameLayout.LayoutParams(dp(CARD_WIDTH_DP), ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val titleTv = TextView(context).apply {
            text = "设置菜单"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val schemaTab = TextView(context).apply {
            text = "方案"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            isClickable = true
            setOnClickListener { switchTab(TAB_SCHEMA) }
        }
        val switchTab = TextView(context).apply {
            text = "开关"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(4) }
            setOnClickListener { switchTab(TAB_SWITCH) }
        }
        val themeTab = TextView(context).apply {
            text = "主题"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            setPadding(dp(8), dp(3), dp(8), dp(3))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(4) }
            setOnClickListener { switchTab(TAB_THEME) }
        }
        header.addView(titleTv)
        header.addView(schemaTab)
        header.addView(switchTab)
        header.addView(themeTab)

        val divider = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }

        val rv = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@HardwareMenuPopup.adapter
            overScrollMode = View.OVER_SCROLL_NEVER
            itemAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val emptyTv = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            gravity = Gravity.CENTER
            setPadding(0, dp(22), 0, dp(22))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        val hintTv = TextView(context).apply {
            text = "Tab 切页 · Enter/1-9 切换 · Space 启停 · Esc 关闭"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(2), dp(6), dp(2), 0)
        }

        card.addView(header)
        card.addView(divider)
        card.addView(rv)
        card.addView(emptyTv)
        card.addView(hintTv)
        wrapper.addView(card)

        shadowWrapper = wrapper
        rootCard = card
        titleView = titleTv
        tabSchemaView = schemaTab
        tabSwitchView = switchTab
        tabThemeView = themeTab
        dividerView = divider
        listView = rv
        emptyView = emptyTv
        hintView = hintTv
    }

    private fun positionPopup(anchorView: View?, cursorRect: RectF) {
        val wrapper = shadowWrapper ?: return

        val dm = context.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        val margin = dp(8)
        val pad = dp(SHADOW_PAD_DP)

        val cardW = dp(CARD_WIDTH_DP).coerceAtMost((screenW - margin * 2).coerceAtLeast(dp(240)))
        rootCard?.layoutParams = FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT)
        val maxListH = dp(MAX_LIST_HEIGHT_DP).coerceAtMost((screenH * 0.42f).toInt())
        val desiredListH = if (rows.isEmpty()) 0 else (rows.size * dp(44)).coerceAtMost(maxListH)
        listView?.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            desiredListH,
        )

        wrapper.measure(
            View.MeasureSpec.makeMeasureSpec(cardW + pad * 2, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((screenH * 0.7f).toInt(), View.MeasureSpec.AT_MOST),
        )
        val popW = wrapper.measuredWidth
        val popH = wrapper.measuredHeight
        val actualCardW = (popW - pad * 2).coerceAtLeast(0)
        val actualCardH = (popH - pad * 2).coerceAtLeast(0)

        val hasValidCursor = cursorRect.left.isFinite() && cursorRect.top.isFinite() && cursorRect.bottom.isFinite() &&
            cursorRect.bottom >= cursorRect.top &&
            !(cursorRect.left == 0f && cursorRect.top == 0f && cursorRect.bottom == 0f) &&
            cursorRect.left >= -20f && cursorRect.left <= screenW + 20f &&
            cursorRect.bottom > 0f && cursorRect.top < screenH
        val cardX: Int
        val cardY: Int
        if (hasValidCursor) {
            cardX = cursorRect.left.toInt().coerceIn(margin, (screenW - actualCardW - margin).coerceAtLeast(margin))
            val belowY = cursorRect.bottom.toInt() + dp(6)
            cardY = if (belowY + actualCardH <= screenH - dp(16)) {
                belowY
            } else {
                (cursorRect.top.toInt() - actualCardH - dp(6)).coerceAtLeast(dp(16))
            }
        } else {
            cardX = dp(32).coerceIn(margin, (screenW - actualCardW - margin).coerceAtLeast(margin))
            cardY = (screenH - actualCardH - dp(84)).coerceAtLeast(dp(24))
        }

        overlay.showOrUpdate(wrapper, anchorView, cardX - pad, cardY - pad, popW, popH)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()

    private inner class MenuAdapter : RecyclerView.Adapter<MenuAdapter.Holder>() {
        private var data: List<MenuRow> = emptyList()

        fun submit(list: List<MenuRow>) {
            data = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(7), dp(8), dp(7))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(2) }
            }
            val numTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setPadding(0, 0, dp(6), 0)
            }
            val titleTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val badgeTv = TextView(context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                isClickable = true
            }
            row.addView(numTv)
            row.addView(titleTv)
            row.addView(badgeTv)
            return Holder(row, numTv, titleTv, badgeTv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val item = data[position]
            val selected = position == selectedIndex
            holder.row.background = if (selected) {
                GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(8).toFloat()
                    setColor(accentBgColor)
                }
            } else {
                null
            }
            val primaryInk = when {
                selected -> accentTextColor
                !item.isEnabled -> subTextColor
                else -> textColor
            }
            val badgeInk = when {
                selected -> accentTextColor
                item.isCurrent -> accentBgColor
                item.isEnabled -> textColor
                else -> subTextColor
            }
            holder.numTv.text = if (position < 9) "${position + 1}." else "·"
            holder.numTv.setTextColor(if (selected) ColorUtils.setAlphaComponent(accentTextColor, 200) else subTextColor)
            holder.titleTv.text = item.title
            holder.titleTv.setTextColor(primaryInk)
            holder.badgeTv.text = item.badge
            holder.badgeTv.setTextColor(badgeInk)
            if (activeTab == TAB_SCHEMA) {
                holder.badgeTv.background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(6).toFloat()
                    setColor(
                        if (selected) {
                            ColorUtils.setAlphaComponent(accentTextColor, 34)
                        } else if (item.isEnabled) {
                            ColorUtils.setAlphaComponent(accentBgColor, 24)
                        } else {
                            ColorUtils.setAlphaComponent(textColor, 14)
                        },
                    )
                }
                holder.badgeTv.setOnClickListener { toggleSchemaEnableAt(position) }
            } else {
                holder.badgeTv.background = null
                holder.badgeTv.setOnClickListener { activateRow(position) }
            }
            holder.row.setOnClickListener {
                activateRow(position)
            }
        }

        override fun getItemCount(): Int = data.size

        inner class Holder(
            val row: LinearLayout,
            val numTv: TextView,
            val titleTv: TextView,
            val badgeTv: TextView,
        ) : RecyclerView.ViewHolder(row)
    }

    companion object {
        const val TAB_SCHEMA = 0
        const val TAB_SWITCH = 1
        const val TAB_THEME = 2
        private const val TAB_COUNT = 3
        private const val CARD_WIDTH_DP = 320
        private const val MAX_LIST_HEIGHT_DP = 250
        private const val SHADOW_PAD_DP = 10
    }
}
