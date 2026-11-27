package com.hazukieq.hkrime.settings.components

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.ImageViewCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.ime.symbol.SymbolRepository

/**
 * 设置页「符号与表情分类」管理弹窗（Apple / iOS 风格卡片设计）：
 * - 圆形蓝勾切换底部标签显示与隐藏
 * - 轻质圆角胶囊按钮 ↑ / ↓ 调整排列次序
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

    private val night: Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    private val surface: Int get() = if (night) 0xFF1C1C1E.toInt() else Color.WHITE
    private val groupedFill: Int get() = if (night) 0xFF2C2C2E.toInt() else 0xFFF2F2F7.toInt()
    private val inkPrimary: Int get() = if (night) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
    private val inkSecondary: Int get() = if (night) 0xFF8E8E93.toInt() else 0xFF8E8E93.toInt()
    private val hairline: Int get() = if (night) 0xFF38383A.toInt() else 0xFFE5E5EA.toInt()
    private val ringStroke: Int get() = if (night) 0xFF48484A.toInt() else 0xFFC7C7CC.toInt()
    private val accent: Int get() = if (night) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
    private val accentSoft: Int get() = if (night) 0x260A84FF else 0x1A007AFF
    private val danger: Int get() = if (night) 0xFFFF453A.toInt() else 0xFFFF3B30.toInt()
    private val dangerSoft: Int get() = if (night) 0x26FF453A else 0x18FF3B30
    private val rippleTint: Int get() = if (night) 0x22FFFFFF else 0x14000000

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

        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val root = FrameLayout(context).apply {
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = 0f
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(surface)
            }
        }

        // Header: centered Apple title + subtle secondary hint
        card.addView(TextView(context).apply {
            text = context.getString(R.string.looks__symbol_tabs_title)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(inkPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(4))
        })
        card.addView(TextView(context).apply {
            text = context.getString(R.string.looks__symbol_tabs_dialog_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(inkSecondary)
            gravity = Gravity.CENTER
            setPadding(dp(20), 0, dp(20), dp(14))
        })
        card.addView(makeHairline(horizontal = true))

        // List body
        adapter = TabRowAdapter()
        val maxListHeight = (context.resources.displayMetrics.heightPixels * 0.48f)
            .toInt()
            .coerceIn(dp(240), dp(380))
        val list = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            adapter = this@SymbolTabsManagerDialog.adapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                maxListHeight,
            )
        }
        card.addView(list)

        // Footer: iOS hairline + 3 equal actions (取消 | 新增分类 | 确定)
        card.addView(makeHairline(horizontal = true))
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(48)
        }
        footer.addView(
            makeFooterButton(context.getString(android.R.string.cancel), inkSecondary, bold = false) {
                dialog.dismiss()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        footer.addView(makeHairline(horizontal = false))
        footer.addView(
            makeFooterButton(context.getString(R.string.looks__symbol_tabs_add), accent, bold = false) {
                showAddCategoryDialog()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        footer.addView(makeHairline(horizontal = false))
        footer.addView(
            makeFooterButton(context.getString(android.R.string.ok), accent, bold = true) {
                saveChanges()
                dialog.dismiss()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        card.addView(footer)

        val cardWidth = minOf(
            context.resources.displayMetrics.widthPixels - dp(48),
            dp(440),
        ).coerceAtLeast(dp(280))
        root.addView(
            card,
            FrameLayout.LayoutParams(
                cardWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                topMargin = dp(24)
                bottomMargin = dp(24)
            },
        )
        dialog.setContentView(root)
        configureAppleWindow(dialog)
        dialog.show()
        applyCenteredFullscreenLayout(dialog)
    }

    private fun saveChanges() {
        val order = rows.map { it.category.title }
        val hidden = rows.filter { !it.enabled }.map { it.category.title }.toSet()
        prefs.looks.symbolTabOrder = order
        prefs.looks.symbolTabHidden = hidden
        onSaved?.invoke()
    }

    private fun showAddCategoryDialog() {
        val dialog = Dialog(context).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
        }
        val root = FrameLayout(context).apply {
            setOnClickListener { dialog.dismiss() }
        }
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            elevation = 0f
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(surface)
            }
        }

        card.addView(TextView(context).apply {
            text = context.getString(R.string.looks__symbol_tabs_add)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            setTextColor(inkPrimary)
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(14))
        })

        fun styleField(et: EditText) {
            et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            et.setTextColor(inkPrimary)
            et.setHintTextColor(inkSecondary)
            et.background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(groupedFill)
            }
            et.setPadding(dp(14), dp(11), dp(14), dp(11))
        }

        val titleInput = EditText(context).apply {
            hint = context.getString(R.string.looks__symbol_tabs_add_name_hint)
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            styleField(this)
        }
        val contentInput = EditText(context).apply {
            hint = context.getString(R.string.looks__symbol_tabs_add_content_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            maxLines = 6
            gravity = Gravity.TOP or Gravity.START
            styleField(this)
        }
        card.addView(
            titleInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
                bottomMargin = dp(10)
            },
        )
        card.addView(
            contentInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(20)
                rightMargin = dp(20)
                bottomMargin = dp(18)
            },
        )

        card.addView(makeHairline(horizontal = true))
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            minimumHeight = dp(48)
        }
        footer.addView(
            makeFooterButton(context.getString(android.R.string.cancel), inkSecondary, bold = false) {
                dialog.dismiss()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        footer.addView(makeHairline(horizontal = false))
        footer.addView(
            makeFooterButton(context.getString(android.R.string.ok), accent, bold = true) {
                val title = titleInput.text?.toString().orEmpty().trim()
                val content = contentInput.text?.toString().orEmpty().trim()
                val created = SymbolRepository.createCustomCategory(userDir, title, content)
                if (created == null) {
                    Toast.makeText(
                        context,
                        R.string.looks__symbol_tabs_add_invalid,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@makeFooterButton
                }
                val existingIdx = rows.indexOfFirst { it.category.title == created.title }
                if (existingIdx >= 0) {
                    rows[existingIdx] = RowState(created, enabled = true)
                } else {
                    rows.add(RowState(created, enabled = true))
                }
                adapter.notifyDataSetChanged()
                dialog.dismiss()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f),
        )
        card.addView(footer)

        val subCardWidth = minOf(
            context.resources.displayMetrics.widthPixels - dp(56),
            dp(400),
        ).coerceAtLeast(dp(260))
        root.addView(
            card,
            FrameLayout.LayoutParams(
                subCardWidth,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                gravity = Gravity.CENTER
                topMargin = dp(24)
                bottomMargin = dp(24)
            },
        )
        dialog.setContentView(root)
        configureAppleWindow(dialog)
        dialog.show()
        applyCenteredFullscreenLayout(dialog)
    }

    private fun configureAppleWindow(dialog: Dialog) {
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.4f)
            setElevation(0f)
            setGravity(Gravity.CENTER)
        }
        dialog.setCanceledOnTouchOutside(true)
    }

    private fun applyCenteredFullscreenLayout(dialog: Dialog) {
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog.window?.setGravity(Gravity.CENTER)
    }

    private fun makeHairline(horizontal: Boolean): View = View(context).apply {
        setBackgroundColor(hairline)
        layoutParams = if (horizontal) {
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
        } else {
            LinearLayout.LayoutParams(1, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun makeFooterButton(
        label: String,
        color: Int,
        bold: Boolean,
        onClick: () -> Unit,
    ): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(color)
        gravity = Gravity.CENTER
        typeface = Typeface.create(
            Typeface.DEFAULT,
            if (bold) Typeface.BOLD else Typeface.NORMAL,
        )
        background = RippleDrawable(ColorStateList.valueOf(rippleTint), null, ColorDrawable(Color.WHITE))
        setOnClickListener { onClick() }
    }

    private inner class TabRowAdapter : RecyclerView.Adapter<TabRowAdapter.VH>() {

        inner class VH(
            val container: LinearLayout,
            val toggleArea: LinearLayout,
            val checkCircle: TextView,
            val title: TextView,
            val badge: TextView,
            val countText: TextView,
            val btnUp: ImageView,
            val btnDown: ImageView,
            val btnDelete: TextView,
            val divider: View,
        ) : RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            val row = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(54)
                setPadding(dp(16), dp(8), dp(14), dp(8))
            }

            // Left clickable toggle area (Apple circular checkmark + title/meta)
            val toggleArea = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val checkCircle = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).apply {
                    marginEnd = dp(12)
                }
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(Color.WHITE)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val textCol = LinearLayout(parent.context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val title = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setTextColor(inkPrimary)
                maxLines = 1
            }

            val metaRow = LinearLayout(parent.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(2) }
            }

            val badge = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10.5f)
                setPadding(dp(6), dp(1), dp(6), dp(1))
            }

            val countText = TextView(parent.context).apply {
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(inkSecondary)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(6) }
            }

            metaRow.addView(badge)
            metaRow.addView(countText)
            textCol.addView(title)
            textCol.addView(metaRow)
            toggleArea.addView(checkCircle)
            toggleArea.addView(textCol)

            fun pillIconBtn(resId: Int, desc: String): ImageView = ImageView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginStart = dp(6)
                }
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(6), dp(6), dp(6), dp(6))
                setImageResource(resId)
                contentDescription = desc
                ImageViewCompat.setImageTintList(this, ColorStateList.valueOf(inkPrimary))
                background = RippleDrawable(
                    ColorStateList.valueOf(rippleTint),
                    GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(groupedFill)
                    },
                    null,
                )
                isClickable = true
                isFocusable = true
            }

            val btnUp = pillIconBtn(R.drawable.ic_symbol_up, "上移")
            val btnDown = pillIconBtn(R.drawable.ic_symbol_down, "下移")
            val btnDelete = TextView(parent.context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).apply {
                    marginStart = dp(6)
                }
                gravity = Gravity.CENTER
                text = "×"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setTextColor(danger)
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                background = RippleDrawable(
                    ColorStateList.valueOf(rippleTint),
                    GradientDrawable().apply {
                        cornerRadius = dp(8).toFloat()
                        setColor(dangerSoft)
                    },
                    null,
                )
                isClickable = true
                isFocusable = true
            }

            row.addView(toggleArea)
            row.addView(btnUp)
            row.addView(btnDown)
            row.addView(btnDelete)

            val divider = View(parent.context).apply {
                setBackgroundColor(hairline)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    marginStart = dp(50)
                }
            }

            container.addView(row)
            container.addView(divider)
            return VH(container, toggleArea, checkCircle, title, badge, countText, btnUp, btnDown, btnDelete, divider)
        }

        override fun getItemCount(): Int = rows.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = rows[position]
            bindCheckState(holder, item.enabled)

            holder.toggleArea.setOnClickListener {
                @Suppress("DEPRECATION")
                val idx = holder.adapterPosition
                if (idx !in rows.indices) return@setOnClickListener
                val target = rows[idx]
                if (target.enabled && rows.count { it.enabled } <= 1) {
                    Toast.makeText(
                        context,
                        R.string.looks__symbol_tabs_keep_one,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                target.enabled = !target.enabled
                bindCheckState(holder, target.enabled)
            }

            holder.title.text = item.category.title
            if (item.category.isBundled) {
                holder.badge.text = "内置"
                holder.badge.setTextColor(inkSecondary)
                holder.badge.background = GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor(groupedFill)
                }
            } else {
                holder.badge.text = "自定义"
                holder.badge.setTextColor(accent)
                holder.badge.background = GradientDrawable().apply {
                    cornerRadius = dp(5).toFloat()
                    setColor(accentSoft)
                }
            }
            holder.countText.text = "${item.category.items.size} 项"

            holder.btnUp.alpha = if (position > 0) 1f else 0.28f
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

            holder.btnDown.alpha = if (position < rows.lastIndex) 1f else 0.28f
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

            holder.divider.visibility = if (position == rows.lastIndex) View.GONE else View.VISIBLE
        }

        private fun bindCheckState(holder: VH, enabled: Boolean) {
            if (enabled) {
                holder.checkCircle.text = "✓"
                holder.checkCircle.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
                holder.title.alpha = 1f
            } else {
                holder.checkCircle.text = ""
                holder.checkCircle.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.TRANSPARENT)
                    setStroke(dp(1.5f), ringStroke)
                }
                holder.title.alpha = 0.45f
            }
        }
    }

    private fun dp(value: Number): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
}
