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

**P0/P1 新增（对齐搜狗输入法的离线能力）**
- 纠错兜底三层：模糊音（z/zh、c/ch、s/sh、n/l、r/l、f/h、an/ang、en/eng、in/ing）、
  击键纠错（邻键/漏键/多键/换位）、纠错习惯记忆（选中后记住「错拼→正确词」）
- 纠错可视化：候选条显示 `输入→纠正` 并打「纠 / 已记住」标；设置页可关模糊音与击键纠错
- 简拼：`bjdx` → 北京大学（惰性声母串索引，约 5k 键 / 一次构建）
- 删词与置顶：长按候选 → 置顶 / 删词（删词进黑名单，永久不再推荐，可持久化）
- 二三候选键：输入中 `,` 选第 2 个候选、`.` 选第 3 个
- 手势：上滑字母/数字出符号或数字、左滑 ⌫ 删词块、空格左右滑移光标
- 智能标点（数字/英文上下文自动用半角）与成对符号（`(`→`()` 且光标居中，右侧已存在则跳过）
- 数字智能候选：手机号 / 日期 / 时间自动格式化（138 1234 5678、2026年9月20日、09:30）
- 快捷短语面板：收藏的句子一键上屏（长按插英文）
- 输入统计：累计词数 / 连续天数 / 收藏数 / 词库规模（首页与学习中心展示）
- **默认皮肤：珊瑚橙 + 奶油色 + 玻璃**（Claude 风格）——奶油玻璃底、半透明白键帽、1dp
  高光描边、圆角浮起面板；API 31+ 打开系统级背景模糊（`FLAG_BLUR_BEHIND` + `blurBehindRadius`），
  低版本退化为半透明玻璃观感；Compose 侧配套 `TypesakeTheme` + `GlassCard`
- 其它可选配色：翠绿 / 暖阳 / 玫瑰 / 海洋 × 浅色·深色·跟随系统

**输入体验**
- 拼音走 composing region（可见、可退格、光标移走自动结束组合）
- 版面：**中文候选在上、英文伴学在下**（中文大字主视觉，英文小字次要信息）
- 候选：精确词 → 整句组合（`jintiankaihui` → 今天开会）→ 前缀补全（`nih` → 你好…）→ 简拼 → 纠错
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

## 英文表达组合（v2）

优先级从高到低（全部离线）：

| 层 | 来源 | 例子 |
|---|---|---|
| 我的 | 收藏句子（翻译记忆，精确/包含命中） | 收藏过「今天天气很好」→ 直接给你自己存过的英文 |
| 地道 | 整句短语库精确命中 | 请发送报告 → *Please send the report.* |
| 结构 | 句式模板 + 槽位翻译 + 词形变化 | 我要买咖啡 → *I'd like to buy coffee.* / 太贵了 → *That's too expensive.* |
| 直译 | 词级贪心 + 词序调整（时间后置）+ 冠词/单复数 | 我今天很忙 → *I'm busy today.* |

- 词形变化：-ing / 过去式（含 60+ 不规则动词）/ 三单 / 复数 / a-an / 首字母大写
- 每一层都过覆盖率闸门：命中率不足时宁可不给，不输出 `… …` 噪音
- 候选带来源标签（我的｜地道｜词典｜结构｜直译），英文条按优先级最多给 3 条
- **词汇量**：`app/src/main/assets/en_dict.tsv` 打包 CC-CEDICT 生成的 10.3 万条中英词条
  （assets 只存一份、压缩进 APK；运行时拷到 filesDir 由 Rust 载入，缺失则自动退回内建小词表）
- 测试里有 30 句日常语料覆盖率断言（≥90%）与形态学单测

## A 级输入增强 / B 级学习闭环（本轮）

- **双拼**：小鹤方案（`rust-core/src/shuangpin.rs`，纯数据表 + 400 音节往返自检），
  设置里可切「全拼 / 小鹤双拼」；显示"击键→全拼"
- **上下文重排**：每次上屏把词喂给引擎，用 bigram 对前 5 个候选重排（`setContext`）
- **误纠错一键还原**：纠错命中时候选条多一个「原样 xxx」，可原样上屏
- **候选翻页**：候选条末尾「更多 ▸」弹层，最多 24 条
- **九键（T9）**：数字串按"词典前缀剪枝"展开成拼音再查词（不是盲笛卡尔积），
  长按数字键可插原始数字/字母/标点；设置里一键切换全键盘/九键
- **以词定字**：长按多字候选弹出单字行，可只取其中一字
- **trigram 联想**：CI 只对 arm64 编 `--features trigrams`（体积 +13.5MB），其余 ABI 用 bigram
- **B2 TTS 朗读**：学习中心每条收藏可朗读（系统离线 TTS）
- **B4 收藏可编辑**：学习中心可编辑英文（反哺翻译记忆，以后同类句子优先用你自己的表达）
- **B5 统计可视化**：首页 12 周输入热力图 + 连续天数
- **C1 启动移出主线程**：词典拷贝/JSON 载入/L0 导入全在 IO 协程，完成后自动刷新界面

## 图标

手绘线条稿（键帽 = 打字，A = 英语，笔迹 + 落点 = 书写/学习）：

- `app/src/main/res/drawable/ic_typesake_line.xml` — 应用内线条图标（纯描边，可 tint）
- `app/src/main/res/drawable/ic_launcher_foreground.xml` + `ic_launcher_background.xml`
  + `ic_launcher_monochrome.xml` — 自适应图标三层（含 Android 13 主题化单色层）
- `app/src/main/res/mipmap-anydpi-v26/{ic_launcher,ic_launcher_round}.xml`
- `docs/icon/typesake-line.svg` — 同一套路径的 SVG 源文件

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

## 许可

- 代码：MIT
- 词典数据 `app/src/main/assets/en_dict.tsv`：CC-CEDICT，**CC BY-SA 4.0**（可能修改过），
  署名与再分发要求见 `THIRD_PARTY_NOTICES.md`

## 已知边界

- 英文表达是离线词典法（约 200 条常用句 + 贪心组合），不是机器翻译；未命中时留空并可后补
  （AI 增强：本地小模型 / 云端改写为后续工作）
- 整句组合（Viterbi）走的是无 trigram 的 bigram 排序，长句优先级以词频为主
- 剪贴板历史不落盘，退出即清空（隐私优先）
