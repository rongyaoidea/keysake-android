package com.typesake.app.kb

/**
 * 键盘配色（纯 int，便于单测）。
 *
 * 默认皮肤 = **珊瑚橙 + 奶油色 + 玻璃**（Claude 风格）：
 * 奶油底、珊瑚强调色、半透明玻璃键帽与带描边的浮起面板。
 * 其余 4 套为可选配色（翠绿/暖阳/玫瑰/海洋）。
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
    /** 玻璃皮肤的键帽/面板描边色（0 表示不使用） */
    val glassBorder: Int = 0,
    /** 是否玻璃皮肤（服务据此开启系统级背景模糊） */
    val glass: Boolean = false,
)

enum class KbPalette { CORAL, GREEN, SUNSET, ROSE, OCEAN }

object KbThemes {

    const val THEME_SYSTEM = 0
    const val THEME_LIGHT = 1
    const val THEME_DARK = 2

    /** Claude 珊瑚橙 */
    const val CORAL = 0xFFD97757.toInt()
    /** 奶油底 */
    const val CREAM = 0xFFFAF9F5.toInt()
    const val CREAM_DEEP = 0xFFF0EEE6.toInt()
    const val WARM_BORDER = 0xFFE5E1D8.toInt()

    fun paletteOf(id: Int): KbPalette = KbPalette.entries.getOrElse(id) { KbPalette.CORAL }

    /** 纯函数：从 Configuration.uiMode 判断夜间（UI_MODE_NIGHT_MASK=0x30, NIGHT_YES=0x20）。 */
    fun isNight(uiMode: Int): Boolean = (uiMode and 0x30) == 0x20

    // ---------------- 默认皮肤：珊瑚橙 + 奶油 + 玻璃 ----------------

    private fun coralLight(): KbColors = KbColors(
        bg = 0xB3F5F3EC.toInt(),          // 奶油玻璃
        key = 0xE6FFFFFF.toInt(),          // 半透明白键帽
        keyPressed = 0xCCF3E1D8.toInt(),   // 按下：珊瑚浅晕
        keyText = 0xFF2A2724.toInt(),
        actionKey = 0x99EDE9DF.toInt(),
        actionText = 0xFF3A3631.toInt(),
        accent = CORAL,
        accentText = 0xFFFFFFFF.toInt(),
        barBg = 0xCCFAF9F5.toInt(),
        barText = 0xFF3A2A22.toInt(),
        hint = 0xFF8A8377.toInt(),
        glassBorder = 0x66FFFFFF.toInt(),
        glass = true,
    )

    private fun coralDark(): KbColors = KbColors(
        bg = 0xB31F1D1A.toInt(),
        key = 0xE6332E29.toInt(),
        keyPressed = 0xCC4A4038.toInt(),
        keyText = 0xFFF5F1EC.toInt(),
        actionKey = 0x992A2622.toInt(),
        actionText = 0xFFE8E2DA.toInt(),
        accent = CORAL,
        accentText = 0xFFFFFFFF.toInt(),
        barBg = 0xCC26221F.toInt(),
        barText = 0xFFF1E4DA.toInt(),
        hint = 0xFF9C948A.toInt(),
        glassBorder = 0x33FFFFFF.toInt(),
        glass = true,
    )

    // ---------------- 可选配色 ----------------

    private fun lightBase(p: KbPalette): KbColors {
        val (accent, barBg, barText) = when (p) {
            KbPalette.CORAL -> Triple(CORAL, 0xFFF7EDE6.toInt(), 0xFF4A2C1E.toInt())
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

    private fun darkBase(p: KbPalette): KbColors {
        val (accent, barBg, barText) = when (p) {
            KbPalette.CORAL -> Triple(CORAL, 0xFF2A221E.toInt(), 0xFFF0DED2.toInt())
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
        return when (palette) {
            KbPalette.CORAL -> if (dark) coralDark() else coralLight()
            else -> if (dark) darkBase(palette) else lightBase(palette)
        }
    }

    fun resolve(paletteId: Int, themeMode: Int, night: Boolean): KbColors =
        resolve(paletteOf(paletteId), themeMode, night)

    /** 配色预览色（设置页色块 / Compose 主题用）。 */
    fun accentOf(palette: KbPalette, night: Boolean): Int =
        resolve(palette, THEME_SYSTEM, night).accent
}
