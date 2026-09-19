package com.typesake.app

import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Typesake 安卓输入法（MVP）。
 *
 * UX（对标 Mac 版，移动端适配）：
 * 顶部英文条（Rust suggest）+ ★ 一键收藏 + 「学」进学习中心；
 * 中间中文候选条（Rust candidates）；下面 QWERTY；
 * 空格单击上屏首选，双击空格收藏当前句（Mac 版双击空格保存的移动版）。
 */
class TypesakeImeService : InputMethodService() {

    private val pinyin = StringBuilder()
    private var caps = false
    private var lastSpaceMs = 0L
    private var lastChinese = ""
    private var lastEnglish = ""

    private lateinit var englishText: TextView
    private lateinit var candidatesRow: LinearLayout

    override fun onCreate() {
        super.onCreate()
        TypesakeCore.init(filesDir.absolutePath)
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        TypesakeCore.init(filesDir.absolutePath)
        pinyin.clear()
        renderCandidates(emptyList())
        setEnglishBar("", hintNoEnglish())
    }

    override fun onCreateInputView(): View {
        val ctx = this
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF7F5EF.toInt())
        }

        // ---- 1. 英文伴学条 ----
        val engBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFFE7F4EC.toInt())
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        englishText = TextView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 14f
            setTextColor(0xFF1B4332.toInt())
        }
        val saveBtn = Button(ctx).apply {
            text = "★"
            setOnClickListener { saveCurrent() }
        }
        val hubBtn = Button(ctx).apply {
            text = "学"
            setOnClickListener {
                startActivity(Intent(ctx, HubActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            }
        }
        engBar.addView(englishText)
        engBar.addView(saveBtn)
        engBar.addView(hubBtn)
        root.addView(engBar)

        // ---- 2. 中文候选条 ----
        val scroll = HorizontalScrollView(ctx).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
        }
        candidatesRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        scroll.addView(candidatesRow)
        root.addView(scroll)

        // ---- 3. 键盘区 ----
        val kb = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        for (row in listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")) {
            kb.addView(makeLetterRow(row))
        }
        kb.addView(makeBottomRow())
        root.addView(kb)
        return root
    }

    // ---------- 键盘构建 ----------

    private fun makeLetterRow(letters: String): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (letters == "zxcvbnm") {
            row.addView(makeKey("⇧", 1.2f) { toggleCaps(it as Button) })
        }
        for (ch in letters) {
            row.addView(makeKey(ch.toString(), 1f) { onLetter(ch.toString()) })
        }
        if (letters == "zxcvbnm") {
            row.addView(makeKey("⌫", 1.2f) { onDelete() })
        }
        return row
    }

    private fun makeBottomRow(): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(makeKey("🌐", 1f) {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                .showInputMethodPicker()
        })
        row.addView(makeKey("空格·双击★", 3f) { onSpace() })
        row.addView(makeKey("⏎", 1f) { onEnter() })
        row.addView(makeKey("学", 1f) {
            startActivity(Intent(this, HubActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        })
        return row
    }

    private fun makeKey(label: String, weight: Float, onClick: (View) -> Unit): Button =
        Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(0, dp(48), weight)
            setOnClickListener(onClick)
        }

    // ---------- 输入逻辑 ----------

    private fun onLetter(raw: String) {
        val ch = if (caps) raw.uppercase() else raw
        caps = false
        // 拼音缓冲一律小写查表
        pinyin.append(ch.lowercase())
        val cands = TypesakeCore.candidates(pinyin.toString())
        if (cands.isEmpty() && pinyin.length >= 24) {
            // 过长无候选：直接上屏，避免卡死
            currentInputConnection?.commitText(pinyin.toString(), 1)
            pinyin.clear()
            renderCandidates(emptyList())
        } else {
            renderCandidates(cands)
        }
    }

    private fun onDelete() {
        if (pinyin.isNotEmpty()) {
            pinyin.deleteCharAt(pinyin.length - 1)
            renderCandidates(TypesakeCore.candidates(pinyin.toString()))
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun onSpace() {
        val now = System.currentTimeMillis()
        if (pinyin.isNotEmpty()) {
            val cands = TypesakeCore.candidates(pinyin.toString())
            commitCandidate(cands.firstOrNull() ?: pinyin.toString())
        } else {
            currentInputConnection?.commitText(" ", 1)
        }
        if (now - lastSpaceMs < 500) {
            saveCurrent(doubleTap = true)
            lastSpaceMs = 0L
        } else {
            lastSpaceMs = now
        }
    }

    private fun onEnter() {
        if (pinyin.isNotEmpty()) {
            val cands = TypesakeCore.candidates(pinyin.toString())
            commitCandidate(cands.firstOrNull() ?: pinyin.toString())
        }
        currentInputConnection?.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            ?: currentInputConnection?.commitText("\n", 1)
    }

    private fun commitCandidate(word: String) {
        currentInputConnection?.commitText(word, 1)
        pinyin.clear()
        renderCandidates(emptyList())
        val en = TypesakeCore.suggest(word)
        lastChinese = word
        lastEnglish = en
        setEnglishBar(en, hintNoEnglish())
    }

    private fun saveCurrent(doubleTap: Boolean = false) {
        if (lastChinese.isBlank() || lastEnglish.isBlank()) {
            Toast.makeText(this, "先选个中文候选，再收藏", Toast.LENGTH_SHORT).show()
            return
        }
        val ok = TypesakeCore.save(lastChinese, lastEnglish)
        Toast.makeText(
            this,
            if (ok) "已收藏 ★ $lastChinese → $lastEnglish" else "收藏失败",
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun renderCandidates(list: List<String>) {
        candidatesRow.removeAllViews()
        if (list.isEmpty()) {
            candidatesRow.addView(TextView(this).apply {
                text = if (pinyin.isEmpty()) "键入拼音，如 nihao" else "无候选，回车/空格直输"
                setPadding(dp(12), dp(10), dp(12), dp(10))
            })
            return
        }
        for (w in list) {
            candidatesRow.addView(Button(this).apply {
                text = w
                setOnClickListener { commitCandidate(w) }
            })
        }
    }

    private fun setEnglishBar(en: String, hint: String) {
        englishText.text = if (en.isBlank()) hint else "EN  $en"
    }

    private fun hintNoEnglish(): String =
        if (TypesakeCore.available) "中文候选上屏后，这里显示英文表达" else "演示模式：接 Rust .so 后显示完整英文（CI 打包带 .so）"

    private fun toggleCaps(btn: Button) {
        caps = !caps
        btn.text = if (caps) "⇪" else "⇧"
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
