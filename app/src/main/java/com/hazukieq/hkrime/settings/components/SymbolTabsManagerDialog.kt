package com.hazukieq.hkrime.settings.components

import android.content.Context
import android.content.res.ColorStateList
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.ime.symbol.SymbolRepository

/**
 * 设置页「符号与表情分类」管理弹窗：
 * - 勾选/取消勾选控制底部标签显示与隐藏
 * - ↑ / ↓ 调整底部标签排列次序
 * - 新增自定义 `.txt` 分类 / 删除自定义 `.txt` 分类
 */
class SymbolTabsManagerDialog(
    private val context: Context,
    private val onSaved: (() -> Unit)? = null,
) {
    private data class RowState(
        val category: SymbolRepository.SymbolCategory,
        var enabled: Boolean,
    )

    private val prefs = AppPrefs.defaultInstance()
    private val userDir = prefs.conf.userDataDir
    private val rows = ArrayList<RowState>()
    private lateinit var adapter: TabRowAdapter

    fun show() {
        val hidden = prefs.looks.symbolTabHidden
        val all = SymbolRepository.loadCategories(
            context = context,
            userDataDir = userDir,
            order = prefs.looks.symbolTabOrder,
            hidden = hidden,
            includeHidden = true,
        )
        rows.clear()
        for (cat in all) {
            rows.add(RowState(cat, enabled = cat.title !in hidden))
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(4))
        }

        val hint = TextView(context).apply {
            text = context.getString(R.string.looks__symbol_tabs_dialog_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(resolveAttrColor(android.R.attr.textColorSecondary, 0xFF5F6368.toInt()))
            setPadding(dp(4), 0, dp(4), dp(8))
        }
        root.addView(hint)

        adapter = TabRowAdapter()
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SymbolTabsManagerDialog.adapter
            itemAnimator = null
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(360),
            )
        }
        root.addView(list)

        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.looks__symbol_tabs_title)
            .setView(root)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                saveChanges()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.looks__symbol_tabs_add, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                showAddCategoryDialog()
            }
        }
        dialog.show()
    }

    private fun saveChanges() {
        val order = rows.map { it.category.title }
        val hidden = rows.filter { !it.enabled }.map { it.category.title }.toSet()
        prefs.looks.symbolTabOrder = order
        prefs.looks.symbolTabHidden = hidden
        onSaved?.invoke()
    }

    private fun showAddCategoryDialog() {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }
        val titleInput = EditText(context).apply {
            hint = context.getString(R.string.looks__symbol_tabs_add_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        val contentInput = EditText(context).apply {
            hint = context.getString(R.string.looks__symbol_tabs_add_content_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            gravity = Gravity.TOP or Gravity.START
        }
        container.addView(titleInput)
        container.addView(contentInput)

        AlertDialog.Builder(context)
            .setTitle(R.string.looks__symbol_tabs_add)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val title = titleInput.text?.toString().orEmpty().trim()
                val content = contentInput.text?.toString().orEmpty().trim()
                val created = SymbolRepository.createCustomCategory(userDir, title, content)
                if (created == null) {
                    Toast.makeText(
                        context,
                        R.string.looks__symbol_tabs_add_invalid,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                val existingIdx = rows.indexOfFirst { it.category.title == created.title }
                if (existingIdx >= 0) {
                    rows[existingIdx] = RowState(created, enabled = true)
                } else {
                    rows.add(RowState(created, enabled = true))
                }
                adapter.notifyDataSetChanged()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class TabRowAdapter : RecyclerView.Adapter<TabRowAdapter.VH>() {
        private val inkPrimary = resolveAttrColor(android.R.attr.textColorPrimary, 0xFF202124.toInt())
        private val inkSecondary = resolveAttrColor(android.R.attr.textColorSecondary, 0xFF5F6368.toInt())

        inner class VH(
            val root: LinearLayout,
            val check: CheckBox,
            val title: TextView,
            val subtitle: TextView,
            val btnUp: ImageView,
            val btnDown: ImageView,
            val btnDelete: TextView,
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(50),
                )
                setPadding(dp(2), 0, dp(2), 0)
            }
            val check = CheckBox(parent.context)
            val textCol = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(4)
                }
            }
            val title = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(inkPrimary)
            }
            val subtitle = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(inkSecondary)
            }
            textCol.addView(title)
            textCol.addView(subtitle)

            fun iconBtn(resId: Int, desc: String): ImageView = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(36), dp(36))
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(resId)
                contentDescription = desc
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(inkPrimary))
                isClickable = true
                isFocusable = true
            }

            val btnUp = iconBtn(R.drawable.ic_symbol_up, "上移")
            val btnDown = iconBtn(R.drawable.ic_symbol_down, "下移")
            val btnDelete = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(34), dp(36))
                gravity = Gravity.CENTER
                text = "×"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(inkSecondary)
                isClickable = true
                isFocusable = true
            }

            row.addView(check)
            row.addView(textCol)
            row.addView(btnUp)
            row.addView(btnDown)
            row.addView(btnDelete)
            return VH(row, check, title, subtitle, btnUp, btnDown, btnDelete)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = rows[position]
            holder.check.setOnCheckedChangeListener(null)
            holder.check.isChecked = item.enabled
            holder.check.setOnCheckedChangeListener { button, checked ->
                if (!checked && rows.count { it.enabled } <= 1) {
                    button.isChecked = true
                    Toast.makeText(
                        context,
                        R.string.looks__symbol_tabs_keep_one,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnCheckedChangeListener
                }
                item.enabled = checked
            }

            holder.title.text = "${position + 1}. ${item.category.title}"
            val kind = if (item.category.isBundled) "内置" else "自定义"
            holder.subtitle.text = "$kind · ${item.category.items.size} 项"

            holder.btnUp.alpha = if (position > 0) 1f else 0.25f
            holder.btnUp.isEnabled = position > 0
            holder.btnUp.setOnClickListener {
                @Suppress("DEPRECATION")
                val idx = holder.adapterPosition
                if (idx > 0) {
                    val moved = rows.removeAt(idx)
                    rows.add(idx - 1, moved)
                    notifyDataSetChanged()
                }
            }

            holder.btnDown.alpha = if (position < rows.lastIndex) 1f else 0.25f
            holder.btnDown.isEnabled = position < rows.lastIndex
            holder.btnDown.setOnClickListener {
                @Suppress("DEPRECATION")
                val idx = holder.adapterPosition
                if (idx in 0 until rows.lastIndex) {
                    val moved = rows.removeAt(idx)
                    rows.add(idx + 1, moved)
                    notifyDataSetChanged()
                }
            }

            if (!item.category.isBundled) {
                holder.btnDelete.visibility = View.VISIBLE
                holder.btnDelete.setOnClickListener {
                    @Suppress("DEPRECATION")
                    val idx = holder.adapterPosition
                    if (idx in rows.indices) {
                        val target = rows[idx]
                        if (target.enabled && rows.count { it.enabled } <= 1) {
                            Toast.makeText(
                                context,
                                R.string.looks__symbol_tabs_keep_one,
                                Toast.LENGTH_SHORT,
                            ).show()
                            return@setOnClickListener
                        }
                        SymbolRepository.deleteCustomCategory(userDir, target.category.fileName)
                        rows.removeAt(idx)
                        notifyDataSetChanged()
                    }
                }
            } else {
                holder.btnDelete.visibility = View.GONE
            }
        }
    }

    private fun resolveAttrColor(attr: Int, fallback: Int): Int {
        val tv = TypedValue()
        return if (context.theme.resolveAttribute(attr, tv, true)) {
            if (tv.resourceId != 0) ContextCompat.getColor(context, tv.resourceId) else tv.data
        } else {
            fallback
        }
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
}
