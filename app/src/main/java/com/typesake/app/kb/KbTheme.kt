package com.typesake.app.kb

/**
 * 键盘配色（纯 int，便于单测）。
 * 4 套配色（翠绿/暖阳/玫瑰/海洋）× 浅色/深色，中性键帽共用，强调色与顶部条随主题变化。
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

enum class KbPalette { GREEN, SUNSET, ROSE, OCEAN }

object KbThemes {

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    fun paletteOf(id: Int): KbPalette = KbPalette.entries.getOrElse(id) { KbPalette.GREEN }

    /** 纯函数：从 Configuration.uiMode 判断夜间（UI_MODE_NIGHT_MASK=0x30, NIGHT_YES=0x20）。 */
    fun isNight(uiMode: Int): Boolean = (uiMode and 0x30) == 0x20

    /** 浅色底（中性色共用）。 */
    private fun lightBase(p: KbPalette): KbColors {
        val (accent, barBg, barText) = when (p) {
            KbPalette.GREEN -> Triple(0xFF1B7A5A.toInt(), 0xFFE7F4EC.toInt(), 0xFF14432F.toInt())
            KbPalette.SUNSET -> Triple(0xFFB4560E.toInt(), 0xFFFDEEE0.toInt(), 0xFF5A2A06.toInt())
            KbPalette.ROSE -> Triple(0xFFB03060.toInt(), 0xFFFDECF2.toInt(), 0xFF55142E.toInt())
            KbPalette.OCEAN -> Triple(0xFF1668A8.toInt(), 0xFFE6F0FA.toInt(), 0xFF0E3C63.toInt())
        }
        return KbColors(
            bg = 0xFFD9DEE8.toInt(),
            key = 0xFFFFFFFF.toInt(),
            keyPressed = 0xFFC6CFDD.toInt(),
            keyText = 0xFF1B2430.toInt(),
            actionKey = 0xFFB9C2D0.toInt(),
            actionText = 0xFF1B2430.toInt(),
            accent = accent,
            accentText = 0xFFFFFFFF.toInt(),
            barBg = barBg,
            barText = barText,
            hint = 0xFF6B7887.toInt(),
        )
    }

    /** 深色底。 */
    private fun darkBase(p: KbPalette): KbColors {
        val (accent, barBg, barText) = when (p) {
            KbPalette.GREEN -> Triple(0xFF2FBF87.toInt(), 0xFF17251F.toInt(), 0xFFBDE9D5.toInt())
            KbPalette.SUNSET -> Triple(0xFFE08A45.toInt(), 0xFF2A1F14.toInt(), 0xFFF2D6B8.toInt())
            KbPalette.ROSE -> Triple(0xFFE06A96.toInt(), 0xFF2A1620.toInt(), 0xFFF5CBDA.toInt())
            KbPalette.OCEAN -> Triple(0xFF4FA3DC.toInt(), 0xFF14202B.toInt(), 0xFFC6DFF2.toInt())
        }
        return KbColors(
            bg = 0xFF12181F.toInt(),
            key = 0xFF2A333F.toInt(),
            keyPressed = 0xFF3B4756.toInt(),
            keyText = 0xFFF2F5F8.toInt(),
            actionKey = 0xFF1D2530.toInt(),
            actionText = 0xFFD7DEE7.toInt(),
            accent = accent,
            accentText = 0xFF05221A.toInt(),
            barBg = barBg,
            barText = barText,
            hint = 0xFF8A97A6.toInt(),
        )
    }

    fun resolve(palette: KbPalette, themeMode: Int, night: Boolean): KbColors {
        val dark = when (themeMode) {
            THEME_LIGHT -> false
            THEME_DARK -> true
            else -> night
        }
        return if (dark) darkBase(palette) else lightBase(palette)
    }

    fun resolve(paletteId: Int, themeMode: Int, night: Boolean): KbColors =
        resolve(paletteOf(paletteId), themeMode, night)

    /** 4 套配色预览色（设置页色块）。 */
    fun accentOf(palette: KbPalette, night: Boolean): Int =
        if (night) darkBase(palette).accent else lightBase(palette).accent
}
