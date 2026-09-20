package com.typesake.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** 首页：状态 + 启用指引 + 试打 + 设置 + 隐私说明。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TypesakeCore.init(filesDir.absolutePath)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val prefs = remember { TypesakePrefs(context) }

    var themeMode by remember { mutableIntStateOf(prefs.themeMode) }
    var keyHeight by remember { mutableFloatStateOf(prefs.keyHeightDp.toFloat()) }
    var haptics by remember { mutableStateOf(prefs.haptics) }
    var sound by remember { mutableStateOf(prefs.sound) }
    var preview by remember { mutableStateOf(prefs.keyPreview) }
    var clipboard by remember { mutableStateOf(prefs.clipboardHistory) }
    var palette by remember { mutableIntStateOf(prefs.palette) }
    var fuzzy by remember { mutableStateOf(prefs.fuzzy) }
    var correction by remember { mutableStateOf(prefs.correction) }
    var statTick by remember { mutableIntStateOf(0) }
    var trial by remember { mutableStateOf("") }
    var savedFlash by remember { mutableStateOf("") }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Typesake 输入法", style = MaterialTheme.typography.headlineMedium)
        Text(
            if (TypesakeCore.available) {
                "离线引擎已加载 · 词库 ${TypesakeCore.lexiconEntries()} 条"
            } else {
                "演示模式 · 安装包含 .so 后启用完整 Rust 引擎"
            },
            style = MaterialTheme.typography.bodySmall,
        )

        val stats = remember(statTick) { TypesakeCore.stats() }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("学习数据", style = MaterialTheme.typography.titleMedium)
                Text(
                    "已输入 ${stats.words} 词 · 连续 ${TextUtils.streak(stats.days, java.time.LocalDate.now().toString())} 天 · " +
                        "收藏 ${stats.saved} · 词库 ${stats.lex} 条（简拼索引 ${stats.initials}）",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("点此刷新", style = MaterialTheme.typography.bodySmall, modifier = Modifier.clickable { statTick++ })
            }
        }
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("启用三步走", style = MaterialTheme.typography.titleMedium)
                Text("1. 打开系统输入法设置，启用「Typesake 输入法」")
                Text("2. 切换到 Typesake（键盘左下角 🌐 可随时切换）")
                Text("3. 任意输入框打拼音：空格上屏首选，双击空格收藏整句")
            }
        }
        OutlinedButton(
            onClick = { context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("去开启输入法") }
        OutlinedButton(
            onClick = { context.startActivity(Intent(context, HubActivity::class.java)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("打开学习中心") }

        Spacer(Modifier.height(4.dp))
        Text("试打 & 收藏", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = trial,
            onValueChange = { trial = it },
            label = { Text("输入中文，如：谢谢 / 今天开会") },
            modifier = Modifier.fillMaxWidth(),
        )
        val english = remember(trial) { TypesakeCore.suggest(trial) }
        Card {
            Column(Modifier.padding(14.dp)) {
                Text("英文伴学", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (english.isBlank()) "（暂无对应表达，换个常用说法试试）" else english,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        Button(
            onClick = {
                val cn = trial.trim()
                if (cn.isNotEmpty()) {
                    val ok = TypesakeCore.save(cn, TypesakeCore.suggest(cn))
                    savedFlash = if (ok) "已收藏：$cn" else "收藏失败"
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("★ 收藏这句") }
        if (savedFlash.isNotEmpty()) {
            Text(savedFlash, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(4.dp))
        Text("键盘设置", style = MaterialTheme.typography.titleMedium)
        Text("主题", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("跟随系统" to 0, "浅色" to 1, "深色" to 2).forEach { (label, mode) ->
                val selected = themeMode == mode
                if (selected) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = {
                        themeMode = mode
                        prefs.themeMode = mode
                    }) { Text(label) }
                }
            }
        }
        Text("配色", style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("翠绿" to 0, "暖阳" to 1, "玫瑰" to 2, "海洋" to 3).forEach { (label, id) ->
                if (palette == id) {
                    Button(onClick = {}) { Text(label) }
                } else {
                    OutlinedButton(onClick = {
                        palette = id
                        prefs.palette = id
                    }) { Text(label) }
                }
            }
        }
        SwitchRow("模糊音（z/zh、n/l、an/ang…）", fuzzy) {
            fuzzy = it
            prefs.fuzzy = it
            TypesakeCore.setOptions(it, correction)
        }
        SwitchRow("击键纠错（邻键/漏键/多键/换位）", correction) {
            correction = it
            prefs.correction = it
            TypesakeCore.setOptions(fuzzy, it)
        }
        Text("按键高度 ${keyHeight.toInt()} dp", style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = keyHeight,
            onValueChange = { keyHeight = it },
            onValueChangeFinished = { prefs.keyHeightDp = keyHeight.toInt() },
            valueRange = TypesakePrefs.MIN_KEY_HEIGHT.toFloat()..TypesakePrefs.MAX_KEY_HEIGHT.toFloat(),
            steps = TypesakePrefs.MAX_KEY_HEIGHT - TypesakePrefs.MIN_KEY_HEIGHT - 1,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow("按键震动", haptics) { haptics = it; prefs.haptics = it }
        SwitchRow("按键声音", sound) { sound = it; prefs.sound = it }
        SwitchRow("按键预览气泡", preview) { preview = it; prefs.keyPreview = it }
        SwitchRow("剪贴板历史（仅本机内存）", clipboard) {
            clipboard = it
            prefs.clipboardHistory = it
        }
        Text(
            "单手模式在键盘 ?123 页的「⇤」键切换，自动记住选择。",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("隐私说明", style = MaterialTheme.typography.titleSmall)
                Text("· 本应用不申请网络权限，输入内容不出设备")
                Text("· 拼音转换、英文表达、词频学习全部本地完成")
                Text("· 密码等隐私输入框自动关闭转换、学习与收藏")
                Text("· 剪贴板历史仅保存在内存，退出即清空")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
