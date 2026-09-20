# Typesake Android（Rust 引擎 + 安卓输入法）

打中文拼音 → 出中文候选 → 同步给出英文表达 → 收藏 → 学习中心复习 + 语法讲解。
全离线、**零网络权限**：拼音转换、英文表达、词频学习全部在设备本地完成。

## 架构

```
rust-core/                Rust 离线语言引擎（cdylib，经 JNI 供 Kotlin 调用）
  engine.rs               拼音->候选（精确 / 整句 Viterbi / 前缀补全）+ 联想 + 词频学习(L0)
  english.rs              中文->英文（词典贪心 + 覆盖度阈值，不吐垃圾）
  store.rs                收藏与学习快照：原子写（tmp + rename）
  ffi.rs                  JNI 边界，全部 catch_unwind（panic 不跨 FFI）
app/                      Kotlin 壳（IME 只能是 Kotlin/Java）+ Compose
  kb/KbModels.kt          纯数据布局：字母/数字/电话/符号页、emoji、长按变体、inputType 映射
  kb/KeyboardView.kt      自绘键盘：键帽/按下态/震动/按键预览/长按变体/单手/emoji/剪贴板
  TypesakeImeService.kt   输入逻辑：composing、候选择、联想、双击空格收藏、隐私字段保护
  MainActivity.kt         启用指引 + 试打 + 键盘设置（主题/高度/震动/声音/预览/剪贴板）
  HubActivity.kt          学习中心：收藏列表 + 语法讲解
```

词库用 [`inputx-pinyin`](https://crates.io/crates/inputx-pinyin)（165k 条 FST 词典 + DP 切分 +
Viterbi 整句组合 + bigram 联想 + L0 用户学习层）；`trigrams` 特性关闭以控制体积。

## 已实现的能力

**输入体验**
- 拼音走 composing region（可见、可退格、光标移走自动结束组合）
- 候选：精确词 → 整句组合（`jintiankaihui` → 今天开会）→ 前缀补全（`nih` → 你好…）
- 空格上屏首选；数字键 1-9/0 选候选；回车按输入框类型换行或执行动作
- 上屏后展示 bigram **联想**；候选查询在后台线程 + 15ms 防抖 + LRU 缓存
- 英文条点按直接上屏（中英混输），长按把刚上屏的中文**改写**成英文
- 双击空格 / ★ 收藏**整句**（从光标前取当前句）

**键盘**
- 常驻数字行、符号页、emoji 面板、剪贴板历史（仅本机内存，最多 20 条）
- 长按字母出重音变体；⇧ 双击/长按 = 大小写锁定
- 震动、按键声音、按键预览气泡（均可关）
- 主题跟随系统/浅色/深色；按键高度 34–62dp 可调；单手模式（左/右）
- inputType 分支：数字键盘、电话键盘；密码/隐私字段关闭转换、学习与收藏

**学习闭环入口**
- 词频学习：连选同一候选 3 次自动置顶（L0 自动 pin），跨重启保留
- 收藏与学习数据原子落盘；文件损坏时安全降级为空

## 本地开发

```sh
# Rust 引擎：格式 / lint / 单测（不需要 Android SDK）
cargo fmt --manifest-path rust-core/Cargo.toml -- --check
cargo clippy --manifest-path rust-core/Cargo.toml --all-targets -- -D warnings
cargo test --manifest-path rust-core/Cargo.toml --release
```

完整 APK 走 GitHub Actions（本机 PRoot 无 Android SDK platform）。

## CI / 发布

- `.github/workflows/android.yml`：`cargo fmt/clippy/test` → 3 ABI `.so` → `testDebugUnitTest` +
  `lintDebug` + `assembleDebug` → 上传 `typesake-debug-apk` 与报告
- `.github/workflows/release.yml`：打 tag（`v*`）触发，`assembleRelease` 并创建 GitHub Release。
  每次发布会用 `apksigner` 打印签名证书指纹，日志可审计

### 签名（固定同一签名）

签名密钥存在仓库 secrets（write-only，无法读回，**务必保留本地备份**）：

| Secret | 内容 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | keystore 的 base64 |
| `ANDROID_KEYSTORE_PASSWORD` | store 密码 |
| `ANDROID_KEY_ALIAS` | 别名（`typesake`） |
| `ANDROID_KEY_PASSWORD` | key 密码 |

发布证书（SHA-256，用于核对历次发布是否同一签名）：

```
22:DF:C8:B0:F2:2F:9B:BC:A3:32:16:39:F6:AC:96:A7:39:50:74:D7:23:35:E6:A3:4A:BE:6E:34:37:A7:4F:50
```

本地备份：`/workspace/typesake-release-backup.jks` + `/workspace/typesake-release-backup.pwd.txt`
（仅本机，不入库）。没配 secrets 时 workflow 仍可通过，只是产出未签名 APK。
注意：debug 包与 release 包签名不同，换装前先卸载。

安装：下载 APK → 安装 → 系统设置里启用「Typesake 输入法」→ 切换 → 输入框打 `nihao`。

## 已知边界

- 英文表达是离线词典法（约 200 条常用句 + 贪心组合），不是机器翻译；未命中时留空并可后补
  （AI 增强：本地小模型 / 云端改写为后续工作）
- 整句组合（Viterbi）走的是无 trigram 的 bigram 排序，长句优先级以词频为主
- 剪贴板历史不落盘，退出即清空（隐私优先）
