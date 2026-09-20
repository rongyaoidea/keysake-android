package com.typesake.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.typesake.app.kb.KbThemes

/** 奶油底 */
private val Cream = Color(0xFFFAF9F5)
private val WarmInk = Color(0xFF2A2724)

/**
 * 默认主题：珊瑚橙（Claude）+ 奶油色 + 玻璃质感。
 * 强调色跟随用户在设置里选的配色；底色保持奶油暖调。
 */
@Composable
fun TypesakeTheme(paletteId: Int, content: @Composable () -> Unit) {
    val night = isSystemInDarkTheme()
    val accent = Color(KbThemes.accentOf(KbThemes.paletteOf(paletteId), night))
    val scheme = if (night) {
        darkColorScheme(
            primary = accent,
            background = Color(0xFF1B1917),
            surface = Color(0xFF252220),
            surfaceVariant = Color(0xFF2E2A27),
            onBackground = Color(0xFFF5F1EC),
            onSurface = Color(0xFFF5F1EC),
        )
    } else {
        lightColorScheme(
            primary = accent,
            background = Cream,
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF0EEE6),
            onBackground = WarmInk,
            onSurface = WarmInk,
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 玻璃卡片：半透明 + 描边（API < 31 没有真模糊，用层次感模拟磨砂玻璃）。 */
@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val night = isSystemInDarkTheme()
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (night) Color(0xCC2A2724) else Color(0xCCFFFFFF),
        ),
        border = BorderStroke(1.dp, if (night) Color(0x33FFFFFF) else Color(0xB3FFFFFF)),
        content = content,
    )
}
