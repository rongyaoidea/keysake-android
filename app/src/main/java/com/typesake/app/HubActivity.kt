package com.typesake.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 学习中心：收藏列表 + 点击看 Rust 语法讲解 + 清空。 */
class HubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        TypesakeCore.init(filesDir.absolutePath)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    var items by remember { mutableStateOf(TypesakeCore.list()) }
                    var expanded by remember { mutableStateOf<TypesakeCore.SavedPhrase?>(null) }
                    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("学习中心（${items.size}）", style = MaterialTheme.typography.headlineSmall)
                            OutlinedButton(onClick = { TypesakeCore.clear(); items = TypesakeCore.list() }) {
                                Text("清空")
                            }
                        }
                        if (items.isEmpty()) {
                            Card(Modifier.fillMaxWidth()) {
                                Text(
                                    "还没有收藏。去键盘打字，点 ★ 或双击空格收藏，\n如：nihao → 你好 → Hello!",
                                    Modifier.padding(16.dp),
                                )
                            }
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(items) { p ->
                                Card(Modifier.fillMaxWidth().clickable {
                                    expanded = if (expanded == p) null else p
                                }) {
                                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(p.chinese, style = MaterialTheme.typography.titleMedium)
                                        Text(p.english, style = MaterialTheme.typography.bodyLarge)
                                        if (expanded == p) {
                                            Text(
                                                TypesakeCore.explain(p.english),
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        } else {
                                            Text("点我看语法讲解", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                        Button(onClick = { items = TypesakeCore.list() }, Modifier.fillMaxWidth()) {
                            Text("刷新")
                        }
                    }
                }
            }
        }
    }
}
