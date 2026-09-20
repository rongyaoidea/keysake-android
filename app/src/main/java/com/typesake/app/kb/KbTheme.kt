package com.typesake.app.kb

/**
 * 键盘配色（纯 int，便于单测）。亮/暗两套，中性色 + 翠绿强调色，
 * 与 App 主色一致（Typesake = "打字学英语"）。
 */
data class KbColors(
    val bg: Int,
    val key: Int,
    val keyPressed: Int,
    val keyText: Int,
    val actionKey: Int,
    val actionText: Int,
    val accent: Int,
    val accentText: Int,
    val barBg: Int,
    val barText: Int,
    val hint: Int,
)

object KbThemes {

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    val light = KbColors(
        bg = 0xFFD9DEE8.toInt(),
        key = 0xFFFFFFFF.toInt(),
        keyPressed = 0xFFC6CFDD.toInt(),
        keyText = 0xFF1B2430.toInt(),
        actionKey = 0xFFB9C2D0.toInt(),
        actionText = 0xFF1B2430.toInt(),
        accent = 0xFF1B7A5A.toInt(),
        accentText = 0xFFFFFFFF.toInt(),
        barBg = 0xFFE7F4EC.toInt(),
        barText = 0xFF14432F.toInt(),
        hint = 0xFF6B7887.toInt(),
    )

    val dark = KbColors(
        bg = 0xFF12181F.toInt(),
        key = 0xFF2A333F.toInt(),
        keyPressed = 0xFF3B4756.toInt(),
        keyText = 0xFFF2F5F8.toInt(),
        actionKey = 0xFF1D2530.toInt(),
        actionText = 0xFFD7DEE7.toInt(),
        accent = 0xFF2FBF87.toInt(),
        accentText = 0xFF05221A.toInt(),
        barBg = 0xFF17251F.toInt(),
        barText = 0xFFBDE9D5.toInt(),
        hint = 0xFF8A97A6.toInt(),
    )

    fun resolve(themeMode: Int, night: Boolean): KbColors = when (themeMode) {
        THEME_LIGHT -> light
        THEME_DARK -> dark
        else -> if (night) dark else light
    }

    /** 纯函数：从 Configuration.uiMode 判断夜间（UI_MODE_NIGHT_MASK=0x30, NIGHT_YES=0x20）。 */
    fun isNight(uiMode: Int): Boolean = (uiMode and 0x30) == 0x20
}
