# Typesake Android（Rust 核心 + 安卓输入法壳）

对标 typesake.ai Mac 版的安卓输入法 MVP：打中文拼音出中文候选，同步显示英文表达，一键/双击空格收藏，学习中心复习 + 语法讲解。离线优先，无网络请求。

## 架构（我定的 UIUX 决策）

```
rust-core/   Rust 离线语言引擎（cdylib，经 JNI 供 Kotlin 调用）
  拼音->中文候选 / 中文->英文 / 收藏JSON持久化 / 语法讲解
app/         Kotlin 壳（必须：InputMethodService 只能是 Java/Kotlin）
  TypesakeImeService  键盘 + 候选条 + 英文条（翡翠绿伴学条）
  MainActivity         启用三步走 + 试打区 + 收藏
  HubActivity          收藏列表 + 点击看语法讲解
```

UX 关键决策：
- 键盘顶部常驻英文条（emerald），★ 一键收藏，「学」直达学习中心。
- 空格单击上屏首选，**双击空格收藏**（对齐 Mac 版双击空格保存）。
- 无 `.so` 时 Kotlin 有最小兜底词库，App 照样能装能演示；CI 打出的包带完整 Rust `.so`。

## JNI 契约

Kotlin `com.typesake.app.TypesakeCore` ↔ Rust `Java_com_typesake_app_TypesakeCore_*`：
`initStorage / suggestEnglish / candidatesFor / englishCandidates / grammarExplain / savePhrase / listSaved / clearSaved`

## 本地开发

```sh
# Rust 单测（不需要 Android SDK）
cargo test --manifest-path rust-core/Cargo.toml
# 完整 APK 走 GitHub Actions（本机 PRoot 无 SDK platform）
```

## CI

`.github/workflows/android.yml`：Rust test → cargo-ndk 编 3 ABI `.so` → Gradle assembleDebug → 上传 APK artifact。
在 Actions 页下载 `typesake-debug-apk`，装到真机后：设置 → 系统 → 语言和输入法 → 启用 Typesake → 切换到它试打 `nihao`。
