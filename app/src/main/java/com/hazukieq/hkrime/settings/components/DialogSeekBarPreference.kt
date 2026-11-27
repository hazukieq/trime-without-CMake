package com.hazukieq.hkrime.settings.components

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceManager
import com.hazukieq.hkrime.R
import com.hazukieq.hkrime.databinding.SeekBarDialogBinding

/**
 * Custom preference which represents a seek bar which shows the current value in the summary. The
 * value can be changed by clicking on the preference, which brings up a dialog which a seek bar.
 * This implementation also allows for a min / max step value, while being backwards compatible.
 *
 * @see R.styleable.DialogSeekBarPreferenceAttrs for which xml attributes this preference accepts
 *  besides the default Preference attributes.
 *
 * @property defaultValue The default value of this preference.
 * @property min The minimum value of the seek bar. Must not be greater or equal than [max].
 * @property max The maximum value of the seek bar. Must not be lesser or equal than [min].
 * @property step The step in which the seek bar increases per move. If the provided value is less
 *  than 1, 1 will be used as step. Note that the xml attribute's name for this property is
 *  [R.styleable.DialogSeekBarPreferenceAttrs_seekBarIncrement].
 * @property unit The unit to show after the value. Set to an empty string to disable this feature.
 */
class DialogSeekBarPreference : Preference {
    private var defaultValue: Int = 0
    private var systemDefaultValue: Int = -1
    private var systemDefaultValueText: String? = null
    private var min: Int = 0
    private var max: Int = 100
    private var step: Int = 1
    private var unit: String = ""

    private val currentValue: Int
        get() = sharedPreferences!!.getInt(key, defaultValue)

    @Suppress("unused")
    constructor(context: Context) : this(context, null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, R.attr.preferenceStyle)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        context.obtainStyledAttributes(attrs, R.styleable.DialogSeekBarPreferenceAttrs).apply {
            min = getInt(R.styleable.DialogSeekBarPreferenceAttrs_min, min)
            max = getInt(R.styleable.DialogSeekBarPreferenceAttrs_max, max)
            step = getInt(R.styleable.DialogSeekBarPreferenceAttrs_seekBarIncrement, step)
            if (step < 1) {
                step = 1
            }
            defaultValue = getInt(R.styleable.DialogSeekBarPreferenceAttrs_android_defaultValue, defaultValue)
            systemDefaultValue = getInt(R.styleable.DialogSeekBarPreferenceAttrs_systemDefaultValue, min - 1)
            systemDefaultValueText = getString(R.styleable.DialogSeekBarPreferenceAttrs_systemDefaultValueText)
            unit = getString(R.styleable.DialogSeekBarPreferenceAttrs_unit) ?: unit
            recycle()
        }
    }

    override fun onClick() {
        showSeekBarDialog()
    }

    override fun onAttachedToHierarchy(preferenceManager: PreferenceManager) {
        super.onAttachedToHierarchy(preferenceManager)
        summary = getTextForValue(currentValue)
    }

    /**
     * Generates the text for the given [value] and adds the defined [unit] at the end.
     * If [systemDefaultValueText] is not null this method tries to match the given [value] with
     * [systemDefaultValue] and returns [systemDefaultValueText] upon matching.
     */
    private fun getTextForValue(value: Int): String {
        val systemDefValText = systemDefaultValueText
        return if (value == systemDefaultValue && systemDefValText != null) {
            systemDefValText
        } else {
            value.toString() + unit
        }
    }

    /**
     * Shows the seek bar dialog.
     */
    private fun showSeekBarDialog() {
        val ctx = context
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Number): Int = (v.toFloat() * density + 0.5f).toInt()
        val night = (ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val surface = if (night) 0xFF1C1C1E.toInt() else android.graphics.Color.WHITE
        val ink = if (night) 0xFFF2F2F7.toInt() else 0xFF1C1C1E.toInt()
        val ink2 = 0xFF8E8E93.toInt()
        val accent = if (night) 0xFF0A84FF.toInt() else 0xFF007AFF.toInt()
        val hairline = if (night) 0xFF38383A.toInt() else 0xFFE5E5EA.toInt()

        val dialog = android.app.Dialog(ctx)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root = android.widget.FrameLayout(ctx).apply {
            setOnClickListener { dialog.dismiss() }
        }
        val card = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            isClickable = true
            elevation = 0f
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = dp(22).toFloat(); setColor(surface)
            }
        }
        card.addView(android.widget.TextView(ctx).apply {
            text = title; textSize = 17f; setTextColor(ink)
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            setPadding(0, dp(22), 0, dp(6))
        })
        val valueView = android.widget.TextView(ctx).apply {
            textSize = 26f; setTextColor(ink); gravity = android.view.Gravity.CENTER
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
            text = getTextForValue(currentValue)
        }
        card.addView(valueView)
        val seek = androidx.appcompat.widget.AppCompatSeekBar(ctx).apply {
            max = actualValueToSeekBarProgress(max)
            progress = actualValueToSeekBarProgress(currentValue)
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(accent)
            setPadding(dp(24), dp(12), dp(24), dp(12))
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                valueView.text = getTextForValue(seekBarProgressToActualValue(progress))
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        card.addView(seek)
        card.addView(android.view.View(ctx).apply { setBackgroundColor(hairline) },
            android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1))
        val buttons = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            minimumHeight = dp(48)
        }
        fun button(label: String, bold: Boolean, onClick: () -> Unit) =
            android.widget.TextView(ctx).apply {
                text = label; textSize = 17f; setTextColor(accent)
                gravity = android.view.Gravity.CENTER
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setOnClickListener { onClick() }
            }
        fun vDivider() = android.view.View(ctx).apply { setBackgroundColor(hairline) }
        buttons.addView(button(ctx.getString(android.R.string.cancel), false) { dialog.dismiss() },
            android.widget.LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(vDivider(), android.widget.LinearLayout.LayoutParams(1, dp(48)))
        buttons.addView(button(ctx.getString(R.string.pref__default), false) {
            sharedPreferences?.edit()?.putInt(key, defaultValue)?.apply()
            summary = getTextForValue(currentValue)
            dialog.dismiss()
        }, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f))
        buttons.addView(vDivider(), android.widget.LinearLayout.LayoutParams(1, dp(48)))
        buttons.addView(button(ctx.getString(android.R.string.ok), true) {
            val actualValue = seekBarProgressToActualValue(seek.progress)
            if (callChangeListener(actualValue)) {
                sharedPreferences?.edit()?.putInt(key, actualValue)?.apply()
                summary = getTextForValue(currentValue)
            }
            dialog.dismiss()
        }, android.widget.LinearLayout.LayoutParams(0, dp(48), 1f))
        card.addView(buttons)

        val cardWidth = minOf(
            ctx.resources.displayMetrics.widthPixels - dp(64),
            dp(400),
        ).coerceAtLeast(dp(260))
        root.addView(card, android.widget.FrameLayout.LayoutParams(
            cardWidth,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            gravity = android.view.Gravity.CENTER
            topMargin = dp(24); bottomMargin = dp(24)
        })
        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.4f)
        dialog.window?.setElevation(0f)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
        )
        dialog.window?.setGravity(android.view.Gravity.CENTER)
    }

    /**
     * Converts the actual value to a progress value which the Android SeekBar implementation can
     * handle. (Android's SeekBar step is fixed at 1 and min at 0)
     *
     * @param actual The actual value.
     * @return the internal value which is used to allow different min and step values.
     */
    private fun actualValueToSeekBarProgress(actual: Int): Int {
        return (actual - min) / step
    }

    /**
     * Converts the Android SeekBar value to the actual value.
     *
     * @param progress The progress value of the SeekBar.
     * @return the actual value which is ready to use.
     */
    private fun seekBarProgressToActualValue(progress: Int): Int {
        return (progress * step) + min
    }
}
