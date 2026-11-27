package com.hazukieq.hkrime.ime.keyboard

import android.content.res.Configuration
import com.hazukieq.hkrime.data.AppPrefs
import com.hazukieq.hkrime.data.Config
import com.hazukieq.hkrime.ime.core.Trime
import com.hazukieq.hkrime.util.appContext
import timber.log.Timber

/**
 * Manages [Keyboard]s and their status with lazy instantiation so utility keyboards
 * (numbers, symbols, emoji, kaomoji) do not slow down IME cold start.
 */
class KeyboardSwitcher {
    var currentId: Int = -1
    var lastId: Int = 0
    var lastLockId: Int = 0

    private var currentDisplayWidth: Int = 0

    private var keyboardCache: Array<Keyboard?> = emptyArray()
    lateinit var keyboardNames: List<String>

    /** To get current keyboard instance (lazily instantiated on first access). **/
    val currentKeyboard: Keyboard get() = keyboardAt(currentId)

    /** To get [currentKeyboard]'s ascii mode. **/
    val asciiMode: Boolean get() = currentKeyboard.asciiMode

    init {
        newOrReset()
    }

    private fun keyboardAt(id: Int): Keyboard {
        val idx = if (id.isValidId()) id else 0
        return keyboardCache[idx] ?: Keyboard(Trime.getService(), keyboardNames[idx]).also {
            keyboardCache[idx] = it
        }
    }

    fun newOrReset() {
        val methodName = "\t<TrimeInit>\t" + Thread.currentThread().stackTrace[2].methodName + "\t"
        Timber.d(methodName)
        val ims = Trime.getService()
        Timber.d(methodName + "getConfig")
        keyboardNames = Config.get(ims).keyboardNames
        Timber.d(methodName + "land")
        val land = (
            ims.resources.configuration.orientation
                == Configuration.ORIENTATION_LANDSCAPE
            )
        Timber.d(methodName + "getConfig")
        Config.get(ims).getKeyboardPadding(land)
        Timber.d("update KeyboardPadding: KeyboardSwitcher.init")

        Timber.d(methodName + "initLazyCache(size=%d)", keyboardNames.size)
        keyboardCache = arrayOfNulls(keyboardNames.size)
        // Eagerly instantiate only the default keyboard; all other keyboards are built lazily.
        if (keyboardNames.isNotEmpty()) {
            keyboardCache[0] = Keyboard(ims, keyboardNames[0])
        }
        Timber.d(methodName + "setKeyboard")
        setKeyboard(0)
        Timber.d(methodName + "finish")
    }

    /**
     * Switch to a certain keyboard by given [name].
     */
    fun switchToKeyboard(name: String?) {
        val i = resolveTargetId(name)
        setKeyboard(i)
    }

    /**
     * Switch to a certain keyboard by given [name].
     */
    fun startKeyboard(name: String?) {
        var i = resolveTargetId(name)

        if (i == 0 && keyboardNames.contains("mini")) {
            if (AppPrefs.defaultInstance().looks.useMiniKeyboard) {
                val realkeyboard = appContext.resources.configuration.keyboard
                if (realkeyboard != Configuration.KEYBOARD_NOKEYS) {
                    Timber.i("onStartInputView() configuration.keyboard=" + realkeyboard + ", keyboardType=" + i)
                    i = keyboardNames.indexOf("mini")
                }
            }
        }

        setKeyboard(i)
    }

    private fun resolveTargetId(name: String?): Int {
        val cur = if (currentId.isValidId()) currentId else 0
        return when {
            name.isNullOrEmpty() -> if (keyboardAt(cur).isLock) cur else lastLockId
            name.contentEquals(".default") -> 0
            name.contentEquals(".prior") -> currentId - 1
            name.contentEquals(".next") -> currentId + 1
            name.contentEquals(".last") -> lastId
            name.contentEquals(".last_lock") -> lastLockId
            name.contentEquals(".ascii") -> {
                val asciiKeyboard = keyboardAt(cur).asciiKeyboard
                val target = if (asciiKeyboard.isNullOrEmpty()) "english" else BuiltinKeyboards.canonicalName(asciiKeyboard)
                val idx = keyboardNames.indexOf(target)
                if (idx >= 0) idx else cur
            }
            else -> keyboardNames.indexOf(BuiltinKeyboards.canonicalName(name))
        }
    }

    /**
     * Change current display width when e.g. rotate the screen.
     */
    fun resize(displayWidth: Int) {
        if (currentId >= 0 && (displayWidth == currentDisplayWidth)) return

        currentDisplayWidth = displayWidth
        newOrReset()
    }

    private fun setKeyboard(id: Int) {
        Timber.d("\t<TrimeInit>\tsetKeyboard()\t" + currentId + "->" + id)
        lastId = currentId
        if (lastId.isValidId() && keyboardAt(lastId).isLock) {
            lastLockId = lastId
        }
        currentId = if (id.isValidId()) id else 0
    }

    fun getCurrentKeyboardName(): String {
        return keyboardNames[currentId]
    }

    private fun Int.isValidId() = this in keyboardNames.indices
}
