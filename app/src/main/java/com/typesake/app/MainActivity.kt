package com.typesake.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 首页：启用教程 + 试打区（Rust suggest 实时预览）+ 学习中心入口。 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TypesakeCore.init(filesDir.absolutePath)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        Modifier.fillMaxSize().padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Typesake 输入法", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            if (TypesakeCore.available) "Rust 引擎已加载 · 离线伴学中"
                            else "演示模式 · 安装包带 .so 后启用完整 Rust 引擎",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Card(colors = CardDefaults.cardColors()) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("启用三步走", style = MaterialTheme.typography.titleMedium)
                                Text("1. 点「去开启」→ 打开 Typesake 输入法开关")
                                Text("2. 点「去切换」→ 当前输入法选 Typesake")
                                Text("3. 回到任意输入框试打拼音，如 nihao")
                            }
                        }
                        var trial by remember { mutableStateOf("") }
                        OutlinedTextField(
                            value = trial,
                            onValueChange = { trial = it },
                            label = { Text("试打中文，如：谢谢 / 今天开会") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        val en = remember(trial) { TypesakeCore.suggest(trial) }
                        Card {
                            Column(Modifier.padding(16.dp)) {
                                Text("英文伴学", style = MaterialTheme.typography.titleSmall)
                                Text(if (en.isBlank()) "（输入后这里显示英文）" else en)
                            }
                        }
                        Button(onClick = {
                            trial.takeIf { it.isNotBlank() }?.let {
                                val e = TypesakeCore.suggest(it)
                                if (e.isNotBlank()) TypesakeCore.save(it, e)
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("★ 收藏这句") }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("去开启输入法") }
                        OutlinedButton(
                            onClick = { startActivity(Intent(this@MainActivity, HubActivity::class.java)) },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("打开学习中心") }
                    }
                }
            }
        }
    }
}
