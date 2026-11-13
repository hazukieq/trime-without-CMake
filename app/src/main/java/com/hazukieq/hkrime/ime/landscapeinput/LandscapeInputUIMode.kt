package com.hazukieq.hkrime.ime.landscapeinput

import java.util.Locale

enum class LandscapeInputUIMode {
    AUTO_SHOW,
    ALWAYS_SHOW,
    NEVER_SHOW;

    companion object {
        /**
         * 舊版靠一個從未填充的 HashMap 做轉換，等於所有取值都落到 AUTO_SHOW。
         * 現在直接用 enum 名稱匹配（大小寫不敏感），未知值仍回落 AUTO_SHOW。
         */
        fun fromString(mode: String): LandscapeInputUIMode =
            values().firstOrNull { it.name == mode.uppercase(Locale.getDefault()) } ?: AUTO_SHOW
    }
}
