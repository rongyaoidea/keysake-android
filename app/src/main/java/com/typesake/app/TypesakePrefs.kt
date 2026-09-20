package com.typesake.app

import android.content.Context

/** 输入法设置（SharedPreferences）。 */
class TypesakePrefs(context: Context) {

    private val sp = context.getSharedPreferences("typesake_prefs", Context.MODE_PRIVATE)

    /** 0=跟随系统 1=浅色 2=深色 */
    var themeMode: Int
        get() = sp.getInt(KEY_THEME, 0)
        set(v) = sp.edit().putInt(KEY_THEME, v.coerceIn(0, 2)).apply()

    /** 按键高度（dp），影响整个键盘高度 */
    var keyHeightDp: Int
        get() = sp.getInt(KEY_KEY_HEIGHT, 46)
        set(v) = sp.edit().putInt(KEY_KEY_HEIGHT, v.coerceIn(MIN_KEY_HEIGHT, MAX_KEY_HEIGHT)).apply()

    var haptics: Boolean
        get() = sp.getBoolean(KEY_HAPTICS, true)
        set(v) = sp.edit().putBoolean(KEY_HAPTICS, v).apply()

    var sound: Boolean
        get() = sp.getBoolean(KEY_SOUND, false)
        set(v) = sp.edit().putBoolean(KEY_SOUND, v).apply()

    var keyPreview: Boolean
        get() = sp.getBoolean(KEY_PREVIEW, true)
        set(v) = sp.edit().putBoolean(KEY_PREVIEW, v).apply()

    /** 单手模式 0=关 1=左手 2=右手 */
    var oneHand: Int
        get() = sp.getInt(KEY_ONE_HAND, 0)
        set(v) = sp.edit().putInt(KEY_ONE_HAND, v.coerceIn(0, 2)).apply()

    /** 配色（0 翠绿 1 暖阳 2 玫瑰 3 海洋） */
    var palette: Int
        get() = sp.getInt(KEY_PALETTE, 0)
        set(v) = sp.edit().putInt(KEY_PALETTE, v.coerceIn(0, 3)).apply()

    /** 模糊音（z/zh、n/l、an/ang…） */
    var fuzzy: Boolean
        get() = sp.getBoolean(KEY_FUZZY, true)
        set(v) = sp.edit().putBoolean(KEY_FUZZY, v).apply()

    /** 击键纠错（邻键/漏键/多键/换位） */
    var correction: Boolean
        get() = sp.getBoolean(KEY_CORRECTION, true)
        set(v) = sp.edit().putBoolean(KEY_CORRECTION, v).apply()

    /** 剪贴板历史（仅本次会话内存，不落盘） */
    var clipboardHistory: Boolean
        get() = sp.getBoolean(KEY_CLIPBOARD, true)
        set(v) = sp.edit().putBoolean(KEY_CLIPBOARD, v).apply()

    companion object {
        const val MIN_KEY_HEIGHT = 34
        const val MAX_KEY_HEIGHT = 62

        /** 旧版本可能存过越界值 -> 读取时兜底 */
        fun sanitizeKeyHeight(v: Int): Int = v.coerceIn(MIN_KEY_HEIGHT, MAX_KEY_HEIGHT)

        private const val KEY_THEME = "theme_mode"
        private const val KEY_KEY_HEIGHT = "key_height_dp"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_SOUND = "sound"
        private const val KEY_PREVIEW = "key_preview"
        private const val KEY_ONE_HAND = "one_hand"
        private const val KEY_CLIPBOARD = "clipboard_history"
        private const val KEY_PALETTE = "palette"
        private const val KEY_FUZZY = "fuzzy"
        private const val KEY_CORRECTION = "correction"
    }
}
