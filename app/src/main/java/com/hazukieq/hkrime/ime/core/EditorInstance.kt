package com.hazukieq.hkrime.ime.core

import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.hazukieq.hkrime.core.Rime
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.ime.enums.InlineModeType
import com.hazukieq.hkrime.ime.text.TextInputManager
import timber.log.Timber

class EditorInstance(private val ims: InputMethodService) {

    val prefs get() = AppPrefs.defaultInstance()
    val inputConnection: InputConnection?
        get() = ims.currentInputConnection
    val editorInfo: EditorInfo?
        get() = ims.currentInputEditorInfo
    val cursorCapsMode: Int
        get() {
            val ic = inputConnection ?: return 0
            val ei = editorInfo ?: return 0
            return if (ei.inputType != EditorInfo.TYPE_NULL) {
                ic.getCursorCapsMode(ei.inputType)
            } else 0
        }
    val textInputManager: TextInputManager
        get() = (ims as Trime).textInputManager

    var lastCommittedText: CharSequence = ""

    fun commitText(text: CharSequence, dispatchToRime: Boolean = true): Boolean = commitToEditor(text)

    fun commitText(text: CharSequence): Boolean = commitToEditor(text)

    private fun commitToEditor(text: CharSequence): Boolean {
        val ic = inputConnection ?: return false
        if (!textInputManager.commitEditorText(text.toString(),
                com.hazukieq.hkrime.core.twelvekey.CompositionSession.EditorIdentity(editorInfo, ic)) { ic.commitText(text, 1) }) return false
        lastCommittedText = text
        runCatching { com.hazukieq.hkrime.core.twelvekey.SegmentMemory.bump(text) }
        runCatching { ic.clearMetaKeyStates(KeyEvent.getModifierMetaStateMask()) }
        return true
    }

    /**
     * Commits the text got from Rime.
     */
    fun commitRimeText(): Boolean {
        if ((textInputManager.isTwelveKeyMode() && !Rime.isAsciiMode()) || textInputManager.hasTwelveKeySession()) {
            return textInputManager.consumeTwelveKeyEngineCommit()
        }
        val ret = Rime.getCommit()
        if (ret) {
            commitText(Rime.getCommitText())
        }
        Timber.i("\t<TrimeInput>\tcommitRimeText()\tupdateComposing")
        (ims as Trime).updateComposing()
        return ret
    }

    fun updateComposingText() {
        // 切換焦點可由宿主自行結束 composing，不能靠事後取消阻止未選內容上屏。
        // 十二鍵的待選狀態只畫在輸入法 preedit，全部選完才經提交許可寫入宿主。
        if (textInputManager.isTwelveKeyMode() || textInputManager.hasTwelveKeySession()) return
        val ic = inputConnection ?: return
        val composingText = when (prefs.keyboard.inlinePreedit) {
            InlineModeType.INLINE_PREVIEW ->
                Rime.getComposingText()
            InlineModeType.INLINE_COMPOSITION -> Rime.getCompositionText()
            InlineModeType.INLINE_INPUT -> Rime.RimeGetInput()
            else -> ""
        }
        if (ic.getSelectedText(0).isNullOrEmpty() || !composingText.isNullOrEmpty()) {
            ic.setComposingText(composingText, 1)
        }
    }

    /**
     * Gets [n] characters after the cursor's current position. The resulting string may be any
     * length ranging from 0 to n.
     *
     * @param n The number of characters to get after the cursor. Must be greater than 0 or this
     *  method will fail.
     * @return [n] or less characters after the cursor.
     */
    fun getTextAfterCursor(n: Int): String {
        val ic = inputConnection
        if (ic == null || n < 1) {
            return ""
        }
        return ic.getTextAfterCursor(n, 0)?.toString() ?: ""
    }

    /**
     * Gets [n] characters before the cursor's current position. The resulting string may be any
     * length ranging from 0 to n.
     *
     * @param n The number of characters to get before the cursor. Must be greater than 0 or this
     *  method will fail.
     * @return [n] or less characters before the cursor.
     */
    fun getTextBeforeCursor(n: Int): String {
        val ic = inputConnection
        if (ic == null || n < 1) {
            return ""
        }
        return ic.getTextBeforeCursor(n.coerceAtMost(1024), 0)?.toString() ?: ""
    }

    /**
     * Constructs a meta state integer flag which can be used for setting the `metaState` field when sending a KeyEvent
     * to the input connection. If this method is called without a meta modifier set to true, the default value `0` is
     * returned.
     *
     * @param ctrl Set to true to enable the CTRL meta modifier. Defaults to false.
     * @param alt Set to true to enable the ALT meta modifier. Defaults to false.
     * @param shift Set to true to enable the SHIFT meta modifier. Defaults to false.
     *
     * @return An integer containing all meta flags passed and formatted for use in a [KeyEvent].
     */
    fun meta(
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        meta: Boolean = false,
        sym: Boolean = false,
    ): Int {
        var metaState = 0
        if (ctrl) {
            metaState = metaState or KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        }
        if (alt) {
            metaState = metaState or KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        }
        if (shift) {
            metaState = metaState or KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        }
        if (meta) {
            metaState = metaState or KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        }
        if (sym) {
            metaState = metaState or KeyEvent.META_SYM_ON
        }

        return metaState
    }

    private fun sendDownKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int): Boolean {
        val ic = inputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                eventTime,
                KeyEvent.ACTION_DOWN,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD
            )
        )
    }

    private fun sendUpKeyEvent(eventTime: Long, keyEventCode: Int, metaState: Int): Boolean {
        val ic = inputConnection ?: return false
        return ic.sendKeyEvent(
            KeyEvent(
                eventTime,
                SystemClock.uptimeMillis(),
                KeyEvent.ACTION_UP,
                keyEventCode,
                0,
                metaState,
                KeyCharacterMap.VIRTUAL_KEYBOARD,
                0,
                KeyEvent.FLAG_SOFT_KEYBOARD or KeyEvent.FLAG_KEEP_TOUCH_MODE,
                InputDevice.SOURCE_KEYBOARD
            )
        )
    }

    /**
     * Same as [InputMethodService.sendDownUpKeyEvents] but also allows to set meta state.
     *
     * @param keyEventCode The key code to send, use a key code defined in Android's [KeyEvent].
     * @param metaState Flags indicating which meta keys are currently pressed.
     * @param count How often the key is pressed while the meta keys passed are down. Must be greater than or equal to
     *  `1`, else this method will immediately return false.
     *
     * @return True on success, false if an error occurred or the input connection is invalid.
     */
    fun sendDownUpKeyEvent(keyEventCode: Int, metaState: Int = meta(), count: Int = 1): Boolean {
        if (count < 1) return false
        val ic = inputConnection ?: return false
        ic.clearMetaKeyStates(
            KeyEvent.META_FUNCTION_ON
                or KeyEvent.META_SHIFT_MASK
                or KeyEvent.META_ALT_MASK
                or KeyEvent.META_CTRL_MASK
                or KeyEvent.META_META_MASK
                or KeyEvent.META_SYM_ON
        )
        ic.beginBatchEdit()
        val eventTime = SystemClock.uptimeMillis()
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT, 0)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT, 0)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT, 0)
        }
        if (metaState and KeyEvent.META_META_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT, 0)
        }

        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendDownKeyEvent(eventTime, KeyEvent.KEYCODE_SYM, 0)
        }

        for (n in 0 until count) {
            sendDownKeyEvent(eventTime, keyEventCode, metaState)
            sendUpKeyEvent(eventTime, keyEventCode, metaState)
        }
        if (metaState and KeyEvent.META_SHIFT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SHIFT_LEFT, 0)
        }
        if (metaState and KeyEvent.META_ALT_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_ALT_LEFT, 0)
        }
        if (metaState and KeyEvent.META_CTRL_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_CTRL_LEFT, 0)
        }

        if (metaState and KeyEvent.META_META_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_META_LEFT, 0)
        }

        if (metaState and KeyEvent.META_SYM_ON != 0) {
            sendUpKeyEvent(eventTime, KeyEvent.KEYCODE_SYM, 0)
        }

        ic.endBatchEdit()
        return true
    }
}
