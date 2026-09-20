package com.typesake.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import com.typesake.app.kb.KbColors
import com.typesake.app.kb.KbKind
import com.typesake.app.kb.KbLayer
import com.typesake.app.kb.KbLayouts
import com.typesake.app.kb.KbThemes
import com.typesake.app.kb.KeyboardView
import com.typesake.app.kb.OneHand
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Typesake 输入法服务。
 *
 * 布局（对齐主流中文输入法）：**中文候选在上，英文伴学在下**，再往下是键盘。
 * 体验要点：
 * - 拼音走 composing region；候选来自 Rust 引擎（精确/整句/前缀/简拼/模糊音/击键纠错）
 * - 纠错命中时标签显示 `输入→纠正` 并在候选上打「纠」标；用户选中后记住该错拼
 * - 长按候选：置顶 / 删词；长按 ⌫ 或左滑 ⌫：删词块；空格左右滑：移光标
 * - 英文条点按直接上屏（中英混输），长按把刚上屏的中文改写成英文
 */
class TypesakeImeService : InputMethodService(), KeyboardView.Listener {

    private lateinit var prefs: TypesakePrefs
    private lateinit var root: FrameLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var candidateRow: LinearLayout
    private lateinit var englishRow: LinearLayout
    private lateinit var candidateScroll: HorizontalScrollView
    private lateinit var englishScroll: HorizontalScrollView

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lookupJob: Job? = null

    private val pinyin = StringBuilder()
    private val digitRun = StringBuilder()
    /** 九键缓冲（数字串，可混入长按插入的字母） */
    private val t9buf = StringBuilder()
    private var t9Mode = false
    private var engineReady = false
    private var englishMode = false
    private var pageStart = 0
    private var allCandidates: List<String> = emptyList()
    private var match: TypesakeCore.Match? = null
    private var candidates: List<String> = emptyList()
    private var predictions: List<String> = emptyList()
    private var englishChips: List<TypesakeCore.EnglishOption> = emptyList()
    private var lastCommittedChinese: String = ""
    private var phrases: List<Triple<String, String, Long>> = emptyList()

    private var shifted = false
    private var capsLock = false
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L
    private var selfEdit = false

    private var kind = KbKind.QWERTY
    private var layer = KbLayer.LETTERS
    private var privateField = false
    private var colors: KbColors = KbThemes.resolve(0, 0, false)

    private val clipItems = ArrayDeque<String>()
    private var clipRegistered = false
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    private var actionPopup: PopupWindow? = null
    private var popupPinyin: String = ""
    private var popupWord: String = ""

    // ---------------- 生命周期 ----------------

    override fun onCreate() {
        super.onCreate()
        prefs = TypesakePrefs(this)
        // C1：词典拷贝/JSON 载入/L0 导入全部移出主线程（首次约 50–150ms）
        io.launch {
            TypesakeAssets.ensureEnglishDict(this@TypesakeImeService)
            TypesakeCore.init(filesDir.absolutePath)
            TypesakeCore.setOptions(prefs.fuzzy, prefs.correction, prefs.shuangpin)
            withContext(Dispatchers.Main) {
                engineReady = true
                if (::keyboard.isInitialized) {
                    keyboard.render(kind, layer, colors, prefs.keyHeightDp, clipItems.toList(), pairList())
                    refreshBars()
                }
            }
        }
    }

    override fun onDestroy() {
        unregisterClipboard()
        io.cancel()
        super.onDestroy()
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        colors = resolveColors()
        applyGlassBlur()
        if (::keyboard.isInitialized) {
            keyboard.render(kind, layer, colors, prefs.keyHeightDp, clipItems.toList(), pairList(), prefs.customSymbols, englishMode)
            refreshBars()
        }
    }

    /**
     * 玻璃皮肤：API 31+ 打开系统级背景模糊（跨窗口模糊未开启时系统会忽略，不会报错）。
     * 低版本退化为半透明（alpha）玻璃观感。
     */
    private fun applyGlassBlur() {
        val dialog = window ?: return
        val attrs = dialog.window?.attributes ?: return
        if (colors.glass) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                attrs.flags = attrs.flags or android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                attrs.blurBehindRadius = dp(20)
            }
        } else {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                attrs.flags = attrs.flags and android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                attrs.blurBehindRadius = 0
            }
        }
        dialog.window?.attributes = attrs
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        val inputType = info?.inputType ?: 0
        englishMode = KbLayouts.prefersEnglish(inputType)
        kind = KbLayouts.kindForInputType(inputType)
        privateField = KbLayouts.learningDisabled(inputType)
        if (privateField) clearComposing()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (engineReady) {
            TypesakeCore.setOptions(prefs.fuzzy, prefs.correction, prefs.shuangpin)
        }
        englishMode = KbLayouts.prefersEnglish(inputType)
        t9Mode = prefs.t9Layout && (kind == KbKind.QWERTY || kind == KbKind.RAW)
        if (t9Mode) kind = KbKind.T9
        clearComposing()
        t9buf.setLength(0)
        digitRun.setLength(0)
        predictions = emptyList()
        englishChips = emptyList()
        shifted = false
        capsLock = false
        colors = resolveColors()
        phrases = loadPhrases()
        if (::keyboard.isInitialized) {
            pageStart = 0
        allCandidates = emptyList()
        keyboard.setOneHand(OneHand.fromInt(prefs.oneHand))
            keyboard.render(kind, layer, colors, prefs.keyHeightDp, clipItems.toList(), pairList(), prefs.customSymbols, englishMode)
            keyboard.setShift(false, false)
            refreshBars()
        }
        registerClipboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        unregisterClipboard()
        lookupJob?.cancel()
        dismissActionPopup()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        clearComposing()
        if (::keyboard.isInitialized) keyboard.setClipboardItems(clipItems.toList())
        super.onFinishInput()
    }

    override fun onCreateInputView(): View {
        root = FrameLayout(this)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 中文候选在上
        candidateScroll = HorizontalScrollView(this)
        candidateRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        candidateScroll.addView(candidateRow)
        column.addView(
            candidateScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )

        // 英文伴学在下
        englishScroll = HorizontalScrollView(this)
        englishRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        englishScroll.addView(englishRow)
        column.addView(
            englishScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )

        val keyboardHost = FrameLayout(this)
        keyboard = KeyboardView(this, prefs, this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        keyboardHost.addView(keyboard)
        column.addView(
            keyboardHost,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        )

        root.addView(column)
        colors = resolveColors()
        renderCandidates()
        renderEnglish()
        return root
    }

    // ---------------- 状态与渲染 ----------------

    private fun resolveColors(): KbColors =
        KbThemes.resolve(prefs.palette, prefs.themeMode, KbThemes.isNight(resources.configuration.uiMode))

    private fun pairList(): List<Pair<String, String>> =
        phrases.map { it.first to it.second }

    private fun loadPhrases(): List<Triple<String, String, Long>> =
        TypesakeCore.list().map { Triple(it.chinese, it.english, it.saved_at) }

    private fun clearComposing() {
        pinyin.setLength(0)
        t9buf.setLength(0)
        candidates = emptyList()
        match = null
        lastCommittedChinese = ""
    }

    private fun refreshBars() {
        renderCandidates()
        renderEnglish()
    }

    private fun renderCandidates() {
        if (!::candidateRow.isInitialized) return
        candidateRow.removeAllViews()
        applyBarBackground(candidateScroll)
        val vertical = prefs.verticalCandidates
        candidateRow.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        // 0) 九键输入中
        if (t9Mode && t9buf.isNotEmpty()) {
            candidateRow.addView(textLabel(t9buf.toString(), colors.accent, colors.barBg))
            val list = match?.candidates ?: candidates
            if (list.isEmpty()) {
                candidateRow.addView(textLabel("继续输入数字，或长按数字键插入原字", colors.hint, colors.barBg))
            } else {
                for ((i, word) in list.withIndex()) {
                    candidateRow.addView(candidateChip(if (i < 9) "${i + 1} $word" else word, word))
                }
            }
            return
        }

        // 1) 拼音输入中
        if (pinyin.isNotEmpty()) {
            val m = match
            val label = when {
                m != null && m.remembered -> "${pinyin}⇒${m.matched}"
                m != null && m.corrected -> "$pinyin→${m.matched}"
                else -> pinyin.toString()
            }
            candidateRow.addView(textLabel(label, colors.accent, colors.barBg))
            if (m != null && (m.corrected || m.remembered)) {
                candidateRow.addView(chip(if (m.remembered) "已记住" else "纠", colors.accentText, colors.accent))
            }
            val full = m?.candidates ?: candidates
            val paged = prefs.pageKeys == 1
            val perPage = if (vertical) 4 else 8
            if (paged) {
                allCandidates = if (allCandidates.isEmpty() || pageStart == 0) full else allCandidates
            }
            val list = if (paged) {
                allCandidates.drop(pageStart).take(perPage)
            } else {
                full.take(if (vertical) 4 else 8)
            }
            if (list.isEmpty()) {
                val hint = if (!TypesakeCore.available) {
                    "引擎未加载（演示模式）：请安装带 .so 的正式包"
                } else if (!engineReady) {
                    "引擎加载中…"
                } else {
                    "无候选：空格直接上屏（异常可到设置页跑「引擎自检」）"
                }
                candidateRow.addView(textLabel(hint, colors.hint, colors.barBg))
            } else {
                for ((i, word) in list.withIndex()) {
                    candidateRow.addView(
                        candidateChip(
                            label = if (i < 9 && !paged) "${i + 1} $word" else word,
                            word = word,
                        )
                    )
                }
            }
            if (paged && allCandidates.size > pageStart + perPage) {
                candidateRow.addView(chip("下页 ▸", colors.hint, colors.key) { flipPage(1) })
            }
            if (paged && pageStart > 0) {
                candidateRow.addView(chip("◂ 上页", colors.hint, colors.key) { flipPage(-1) })
            }
            // A3 误纠错一键还原：把原样输入也作为候选
            if (m != null && m.corrected) {
                val raw = pinyin.toString()
                if (raw.isNotEmpty() && !list.contains(raw)) {
                    candidateRow.addView(chip("原样 $raw", colors.hint, colors.key) { commitRaw(raw) })
                }
            }
            // A4 候选翻页
            candidateRow.addView(chip("更多 ▸", colors.hint, colors.key) { showMoreCandidates() })
            return
        }

        // 2) 数字智能候选（手机号/日期/时间）
        if (digitRun.isNotEmpty()) {
            val decorated = TextUtils.digitCandidates(digitRun.toString())
            if (decorated.isNotEmpty()) {
                candidateRow.addView(textLabel("数字", colors.hint, colors.barBg))
                for (text in decorated) {
                    candidateRow.addView(
                        chip(text, colors.keyText, colors.key, onClick = { commitDigitDecoration(text) })
                    )
                }
                return
            }
        }

        // 3) 联想
        if (predictions.isNotEmpty()) {
            candidateRow.addView(textLabel("联想", colors.hint, colors.barBg))
            for (word in predictions) {
                candidateRow.addView(
                    chip(word, colors.barText, colors.key, onClick = { insertPrediction(word) })
                )
            }
            return
        }

        val before = currentInputConnection?.getTextBeforeCursor(32, 0)?.toString().orEmpty()
        var shown = false
        TextUtils.mixedDigitSuggestion(before)?.let { (text, replaceLen) ->
            shown = true
            candidateRow.addView(textLabel("数字识别", colors.hint, colors.barBg))
            candidateRow.addView(chip("→ $text", colors.keyText, colors.key) {
                replaceTail(replaceLen, text)
            })
        }
        for ((domain, suffix) in TextUtils.emailDomainCandidates(before, TypesakeCore.learnedMailDomains())) {
            shown = true
            candidateRow.addView(chip("$domain", colors.keyText, colors.key) {
                TypesakeCore.recallMailDomain(domain)
                commitRaw(suffix)
            })
        }
        for ((suffix, _) in TextUtils.domainSuffixCandidates(before)) {
            shown = true
            candidateRow.addView(chip(".$suffix", colors.keyText, colors.key) { commitRaw(suffix) })
        }
        if (shown) return

        candidateRow.addView(
            textLabel(
                if (privateField) "密码/隐私输入模式" else "键入拼音，如 nihao；空格上屏，双击空格收藏",
                colors.hint,
                colors.barBg,
            )
        )
    }

    /** 翻页（逗号句号翻页模式） */
    private fun flipPage(delta: Int) {
        val size = allCandidates.size
        if (size == 0) return
        pageStart = (pageStart + delta * 8).coerceIn(0, ((size - 1) / 8) * 8)
        renderCandidates()
    }

    /** 替换光标前 N 个字符为 text（数字识别上屏用）。 */
    private fun replaceTail(len: Int, text: String) {
        val ic = currentInputConnection ?: return
        if (len > 0) ic.deleteSurroundingText(len, 0)
        ic.commitText(text, 1)
        digitRun.setLength(0)
        refreshBars()
    }

    private fun renderEnglish() {
        if (!::englishRow.isInitialized) return
        englishRow.removeAllViews()
        applyBarBackground(englishScroll)

        if (englishChips.isEmpty()) {
            englishRow.addView(
                chip(
                    if (privateField) "隐私输入：不转换、不学习、不收藏" else "英文表达显示在这里",
                    colors.hint,
                    colors.barBg,
                )
            )
        } else {
            for (option in englishChips) {
                val label = if (option.kindLabel.isEmpty()) {
                    option.text
                } else {
                    "${option.kindLabel}｜${option.text}"
                }
                englishRow.addView(
                    chip(
                        label,
                        colors.barText,
                        colors.key,
                        onClick = { insertEnglish(option.text) },
                        onLongClick = { replaceLastChineseWith(option.text) },
                    )
                )
            }
        }
        if (!privateField) {
            englishRow.addView(chip("★", colors.accentText, colors.accent, onClick = { saveCurrentSentence() }))
        }
        englishRow.addView(chip("学", colors.accentText, colors.accent, onClick = { onOpenHub() }))
    }

    /** 中文候选：字号更大（主视觉），支持长按置顶/删词。 */
    private fun candidateChip(label: String, word: String): TextView {
        // 注意：GradientDrawable 自己也有 colors 属性，apply 里必须显式取外层字段
        val keyColor = colors.key
        val textColor = colors.keyText
        return TextView(this).apply {
            text = label
            setTextColor(textColor)
            textSize = 17f
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                setColor(keyColor)
                cornerRadius = dp(9).toFloat()
            }
            isClickable = true
            setOnClickListener { commitCandidate(word) }
            setOnLongClickListener { showCandidateActions(word); true }
            layoutParams = chipParams()
        }
    }

    /** 英文/操作条：字号更小（次要信息）。 */
    private fun chip(
        label: String,
        textColor: Int,
        bg: Int,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(textColor)
        textSize = 13f
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = GradientDrawable().apply {
            setColor(bg)
            cornerRadius = dp(8).toFloat()
        }
        if (onClick != null) {
            isClickable = true
            setOnClickListener { onClick() }
        }
        if (onLongClick != null) {
            setOnLongClickListener {
                onLongClick()
                true
            }
        }
        layoutParams = chipParams()
    }

    /** 玻璃皮肤下面板用圆角+描边；普通皮肤仍用纯色。 */
    private fun applyBarBackground(view: android.view.View) {
        // 注意：GradientDrawable 自身也有 colors 属性，apply 里必须用提前取好的局部变量
        val barBg = colors.barBg
        val border = colors.glassBorder
        if (colors.glass) {
            view.background = GradientDrawable().apply {
                setColor(barBg)
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), border)
            }
        } else {
            view.setBackgroundColor(barBg)
        }
    }

    private fun chipParams(): LinearLayout.LayoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        marginStart = dp(6)
        topMargin = dp(4)
        bottomMargin = dp(4)
    }

    private fun textLabel(label: String, color: Int, bg: Int): TextView = TextView(this).apply {
        text = label
        setTextColor(color)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(4), dp(8))
        setBackgroundColor(bg)
    }

    // ---------------- 候选操作（长按） ----------------

    private fun showCandidateActions(word: String) {
        dismissActionPopup()
        popupPinyin = pinyin.toString()
        popupWord = word
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(colors.bg)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        // A6 以词定字：多字候选可只取其中一字
        if (word.length > 1) {
            for (ch in word.take(6)) {
                row.addView(
                    chip(ch.toString(), colors.keyText, colors.key) {
                        dismissActionPopup()
                        commitRaw(ch.toString())
                    }
                )
            }
        }
        row.addView(
            chip("置顶", colors.accentText, colors.accent, onClick = {
                TypesakeCore.pin(popupPinyin, popupWord)
                dismissActionPopup()
                candidates = TypesakeCore.cached(popupPinyin) ?: candidates
                match = match?.copy(candidates = candidates)
                renderCandidates()
                toast("已置顶：$popupWord")
            })
        )
        row.addView(
            chip("删词", colors.keyText, colors.key, onClick = {
                val updated = TypesakeCore.forget(popupPinyin, popupWord)
                dismissActionPopup()
                candidates = updated
                match = match?.copy(candidates = updated)
                renderCandidates()
                toast("已删除：$popupWord")
            })
        )
        val popup = PopupWindow(row, ViewGroup.LayoutParams.WRAP_CONTENT, dp(44), true).apply {
            isOutsideTouchable = true
        }
        val anchor = candidateRow
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, loc[0] + dp(8), loc[1] + dp(40))
        actionPopup = popup
    }

    private fun dismissActionPopup() {
        actionPopup?.dismiss()
        actionPopup = null
    }

    // ---------------- 输入逻辑 ----------------

    override fun onInsert(text: String) {
        val ic = currentInputConnection ?: return
        if (privateField) {
            ic.commitText(text, 1)
            return
        }

        // 英文模式：字母直接上屏（不转拼音）
        if (englishMode && text.length == 1 && text[0].isLetter()) {
            ic.commitText(text, 1)
            digitRun.setLength(0)
            refreshBars()
            return
        }
        // 九键：数字/字母进缓冲
        if (t9Mode && text.length == 1 && text[0].isLetterOrDigit()) {
            t9buf.append(text.lowercase())
            selfEdit = true
            ic.setComposingText(t9buf.toString(), 1)
            requestCandidates(t9buf.toString())
            return
        }
        if (t9Mode && (t9buf.isNotEmpty() || pinyin.isNotEmpty())) {
            commitTopCandidate()
        }
        // 拼音输入中：数字键选候选
        if (pinyin.isNotEmpty() && text.length == 1 && text[0].isDigit()) {
            val idx = if (text == "0") 9 else text[0].digitToInt() - 1
            (match?.candidates ?: candidates).getOrNull(idx)?.let {
                commitCandidate(it)
                return
            }
        }
        // 二三候选键：输入中 , 选第 2 个、. 选第 3 个
        if (pinyin.isNotEmpty() && prefs.pageKeys == 1 && (text == "," || text == ".")) {
            flipPage(if (text == ".") 1 else -1)
            return
        }
        if (pinyin.isNotEmpty() && (text == "," || text == ".")) {
            val idx = if (text == ",") 1 else 2
            (match?.candidates ?: candidates).getOrNull(idx)?.let {
                commitCandidate(it)
                return
            }
        }
        // 字母 -> 拼音
        if (text.length == 1 && text[0].isLetter()) {
            onLetter(text)
            return
        }

        if (pinyin.isNotEmpty()) commitTopCandidate()

        if (text.length == 1) {
            val ch = text[0]
            // 成对符号：自动补全并把光标放中间；若右侧已是闭合符则直接跳过
            TextUtils.insertPairFor(ch)?.let { (open, close) ->
                val after = ic.getTextAfterCursor(1, 0)?.toString().orEmpty()
                if (after.isNotEmpty() && after[0] == close[0]) {
                    moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT)
                } else {
                    ic.commitText(open + close, 1)
                    moveCursor(KeyEvent.KEYCODE_DPAD_LEFT)
                }
                digitRun.setLength(0)
                renderCandidates()
                return
            }
            // 智能标点
            if (ch in ",.;:?!") {
                val before = ic.getTextBeforeCursor(1, 0)?.toString()?.lastOrNull()
                ic.commitText(TextUtils.smartPunctuation(ch, before), 1)
                digitRun.setLength(0)
                renderCandidates()
                return
            }
        }

        ic.commitText(text, 1)
        if (text.length == 1 && text[0].isDigit()) {
            digitRun.append(text)
        } else {
            digitRun.setLength(0)
        }
        renderCandidates()
    }

    private fun onLetter(letterText: String) {
        val ic = currentInputConnection ?: return
        pinyin.append(letterText.lowercase())
        selfEdit = true
        digitRun.setLength(0)
        ic.setComposingText(pinyin.toString(), 1)
        val cached = TypesakeCore.cached(pinyin.toString())
        candidates = cached ?: emptyList()
        match = match?.copy(candidates = candidates)
        renderCandidates()
        requestCandidates(pinyin.toString())
        if (shifted && !capsLock) {
            shifted = false
            keyboard.setShift(false, false)
        }
    }

    private fun requestCandidates(p: String) {
        lookupJob?.cancel()
        lookupJob = io.launch {
            delay(15)
            if (t9Mode) {
                val list = TypesakeCore.t9(p)
                val cached = TypesakeCore.cached(p)
                withContext(Dispatchers.Main) {
                    if (t9buf.toString() == p) {
                        val hit = list.ifEmpty { cached ?: emptyList() }
                        match = TypesakeCore.Match(matched = p, corrected = false, remembered = false, candidates = hit)
                        candidates = hit
                        renderCandidates()
                        renderComposingEnglish(hit)
                    }
                }
                return@launch
            }
            val m = if (prefs.pageKeys == 1) {
                val direct = TypesakeCore.analyze(p)
                direct.copy(candidates = TypesakeCore.more(p))
            } else {
                TypesakeCore.analyze(p)
            }
            withContext(Dispatchers.Main) {
                if (pinyin.toString() == p) {
                    match = m
                    candidates = m.candidates
                    renderCandidates()
                    renderComposingEnglish(m.candidates)
                }
            }
        }
    }

    private fun renderComposingEnglish(list: List<String>) {
        if (englishChips.isNotEmpty()) return
        val target = list.firstOrNull() ?: return
        val list = TypesakeCore.englishList(target)
        if (list.isNotEmpty()) {
            englishChips = list
            renderEnglish()
        }
    }

    /** 简繁输出（设置里开"输出繁体"时生效）。 */
    private fun applyScript(text: String): String =
        if (prefs.script == 1 && text.any { it.code in 0x4E00..0x9FFF }) {
            TypesakeCore.convert(text, true)
        } else {
            text
        }

    private fun commitCandidate(word: String) {
        val ic = currentInputConnection ?: return
        val typed = if (t9Mode) t9buf.toString() else pinyin.toString()
        val corrected = match?.corrected == true || match?.remembered == true
        val out = applyScript(word)
        ic.commitText(out, 1)
        selfEdit = true
        pageStart = 0
        allCandidates = emptyList()
        pinyin.setLength(0)
        t9buf.setLength(0)
        candidates = emptyList()
        match = null
        lastCommittedChinese = word
        if (typed.isNotEmpty() && !t9Mode) {
            io.launch { TypesakeCore.pick(typed, word, corrected) }
        }
        TypesakeCore.context(word)
        afterCommit(word)
    }

    /** 原样上屏（不做纠错/不学习），并清空组合状态。 */
    private fun commitRaw(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(applyScript(text), 1)
        selfEdit = true
        pinyin.setLength(0)
        t9buf.setLength(0)
        candidates = emptyList()
        match = null
        refreshBars()
    }

    /** A4：候选翻页（最多 24 条） */
    private fun showMoreCandidates() {
        val typed = if (t9Mode) t9buf.toString() else pinyin.toString()
        if (typed.isEmpty()) return
        io.launch {
            val list = TypesakeCore.more(typed)
            withContext(Dispatchers.Main) { showListPopup(list) }
        }
    }

    private fun showListPopup(items: List<String>) {
        if (items.isEmpty()) {
            toast("没有更多候选")
            return
        }
        dismissActionPopup()
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = android.widget.ScrollView(this)
        for (w in items) {
            column.addView(
                chip(w, colors.keyText, colors.key) {
                    dismissActionPopup()
                    commitRaw(w)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        scroll.addView(column)
        val popup = PopupWindow(scroll, dp(240), dp(260), true).apply { isOutsideTouchable = true }
        val loc = IntArray(2)
        candidateRow.getLocationInWindow(loc)
        popup.showAtLocation(candidateRow, Gravity.NO_GRAVITY, loc[0] + dp(8), loc[1] + dp(44))
        actionPopup = popup
    }

    private fun commitTopCandidate() {
        val top = (match?.candidates ?: candidates).firstOrNull() ?: pinyin.toString()
        if (pinyin.isNotEmpty()) commitCandidate(top)
    }

    private fun afterCommit(word: String) {
        englishChips = TypesakeCore.englishList(word)
        predictions = TypesakeCore.predict(word)
        refreshBars()
        io.launch { TypesakeCore.bump(LocalDate.now().toString()) }
    }

    private fun commitDigitDecoration(text: String) {
        val ic = currentInputConnection ?: return
        if (digitRun.isNotEmpty()) {
            ic.deleteSurroundingText(digitRun.length, 0)
        }
        ic.commitText(text, 1)
        digitRun.setLength(0)
        renderCandidates()
    }

    private fun insertPrediction(word: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(applyScript(word), 1)
        lastCommittedChinese = word
        englishChips = TypesakeCore.englishList(word)
        predictions = TypesakeCore.predict(word)
        refreshBars()
        io.launch { TypesakeCore.bump(LocalDate.now().toString()) }
    }

    private fun insertEnglish(english: String) {
        val ic = currentInputConnection ?: return
        if (pinyin.isNotEmpty()) commitTopCandidate()
        ic.commitText(if (prefs.englishAutoSpace) "$english " else english, 1)
        digitRun.setLength(0)
        predictions = emptyList()
        englishChips = TypesakeCore.englishList(lastCommittedChinese)
        refreshBars()
    }

    /** 长按英文：把刚上屏的中文替换成英文（中英混输的"改写"用法）。 */
    private fun replaceLastChineseWith(english: String) {
        val ic = currentInputConnection ?: return
        val cn = lastCommittedChinese
        if (cn.isNotEmpty() && cn != english) {
            ic.deleteSurroundingText(cn.length, 0)
            ic.commitText(english, 1)
            lastCommittedChinese = english
            toast("已改写为：$english")
        } else {
            ic.commitText(english, 1)
        }
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        if (t9Mode && t9buf.isNotEmpty()) {
            commitTopCandidate()
            return
        }
        if (pinyin.isNotEmpty()) {
            commitTopCandidate()
        } else {
            ic.commitText(" ", 1)
            digitRun.setLength(0)
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastSpaceTap < DOUBLE_TAP_MS) {
            lastSpaceTap = 0L
            saveCurrentSentence()
        } else {
            lastSpaceTap = now
        }
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        if (t9Mode && t9buf.isNotEmpty()) {
            commitTopCandidate()
            return
        }
        if (pinyin.isNotEmpty()) {
            commitTopCandidate()
            return
        }
        val info = currentInputEditorInfo
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiline = info != null && (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (noEnterAction || multiline || action == EditorInfo.IME_ACTION_NONE) {
            ic.commitText("\n", 1)
        } else {
            ic.performEditorAction(action)
        }
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (t9Mode && t9buf.isNotEmpty()) {
            t9buf.deleteCharAt(t9buf.length - 1)
            selfEdit = true
            if (t9buf.isEmpty()) {
                ic.setComposingText("", 1)
                candidates = emptyList()
                match = null
                renderCandidates()
            } else {
                ic.setComposingText(t9buf.toString(), 1)
                requestCandidates(t9buf.toString())
            }
            return
        }
        if (pinyin.isNotEmpty()) {
            pinyin.deleteCharAt(pinyin.length - 1)
            selfEdit = true
            if (pinyin.isEmpty()) {
                ic.setComposingText("", 1)
                candidates = emptyList()
                match = null
                renderCandidates()
            } else {
                ic.setComposingText(pinyin.toString(), 1)
                val cached = TypesakeCore.cached(pinyin.toString())
                candidates = cached ?: emptyList()
                match = match?.copy(candidates = candidates)
                renderCandidates()
                requestCandidates(pinyin.toString())
            }
            return
        }
        if (digitRun.isNotEmpty()) {
            digitRun.deleteCharAt(digitRun.length - 1)
        }
        ic.deleteSurroundingText(1, 0)
        renderCandidates()
    }

    /** 滑动删除：一次删掉光标前的一个词块。 */
    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        if (t9Mode && t9buf.isNotEmpty()) {
            t9buf.setLength(0)
            ic.setComposingText("", 1)
            candidates = emptyList()
            match = null
            renderCandidates()
            return
        }
        if (pinyin.isNotEmpty()) {
            clearComposing()
            ic.setComposingText("", 1)
            renderCandidates()
            return
        }
        val before = ic.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        val n = TextUtils.deleteWordLength(before)
        if (n > 0) ic.deleteSurroundingText(n, 0)
        digitRun.setLength(0)
        renderCandidates()
    }

    override fun onShiftTap() {
        val now = SystemClock.uptimeMillis()
        if (capsLock) {
            capsLock = false
            shifted = false
        } else if (now - lastShiftTap < DOUBLE_TAP_MS) {
            capsLock = true
            shifted = true
        } else {
            shifted = !shifted
        }
        lastShiftTap = now
        keyboard.setShift(shifted, capsLock)
    }

    override fun onShiftLongPress() {
        capsLock = true
        shifted = true
        keyboard.setShift(shifted, capsLock)
    }

    override fun onShowLayer(l: KbLayer) {
        layer = l
        if (l == KbLayer.PHRASES) phrases = loadPhrases()
        keyboard.render(kind, layer, colors, prefs.keyHeightDp, clipItems.toList(), pairList())
    }

    override fun onLanguage() {
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    override fun onOpenHub() {
        startActivity(
            Intent(this, HubActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        )
    }

    override fun onOneHandToggle() {
        val next = OneHand.fromInt(prefs.oneHand).next()
        prefs.oneHand = next.ordinal
        keyboard.setOneHand(next)
        toast(
            when (next) {
                OneHand.NONE -> "单手模式已关闭"
                OneHand.LEFT -> "单手模式：左手"
                OneHand.RIGHT -> "单手模式：右手"
            }
        )
    }

    override fun onCursorLeft() = moveCursor(KeyEvent.KEYCODE_DPAD_LEFT)

    override fun onCursorRight() = moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT)

    override fun onToggleEnglish() {
        englishMode = !englishMode
        prefs.englishMode = englishMode
        if (englishMode && pinyin.isNotEmpty()) commitTopCandidate()
        toast(if (englishMode) "英文输入" else "中文输入")
        keyboard.render(kind, layer, colors, prefs.keyHeightDp, clipItems.toList(), pairList(), prefs.customSymbols, englishMode)
        refreshBars()
    }

    override fun onHideKeyboard() {
        requestHideSelf(0)
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    override fun onClipboardClear() {
        clipItems.clear()
        keyboard.setClipboardItems(emptyList())
        toast("剪贴板历史已清空")
    }

    private fun moveCursor(keyCode: Int) {
        val ic = currentInputConnection ?: return
        if (pinyin.isNotEmpty()) commitTopCandidate()
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    // ---------------- 收藏 ----------------

    private fun saveCurrentSentence() {
        if (privateField) {
            toast("隐私输入模式下不收藏")
            return
        }
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(240, 0)?.toString().orEmpty()
        val sentence = TextUtils.sentenceBefore(before)
        if (sentence.isBlank()) {
            toast("先输入一句中文，再点 ★ 或双击空格收藏")
            return
        }
        val english = TypesakeCore.suggest(sentence)
        val ok = TypesakeCore.save(sentence, english)
        if (ok) phrases = loadPhrases()
        toast(
            if (!ok) {
                "收藏失败"
            } else if (english.isEmpty()) {
                "已收藏 ★ $sentence（英文待补）"
            } else {
                "已收藏 ★ $sentence → $english"
            }
        )
    }

    // ---------------- 剪贴板 ----------------

    private fun registerClipboard() {
        if (!prefs.clipboardHistory || clipRegistered) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.addPrimaryClipChangedListener(clipListener)
        clipRegistered = true
        captureClipboard()
    }

    private fun unregisterClipboard() {
        if (!clipRegistered) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.removePrimaryClipChangedListener(clipListener)
        clipRegistered = false
    }

    private fun captureClipboard() {
        if (!prefs.clipboardHistory) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(this)
            ?.toString()
            ?.trim()
        if (text.isNullOrEmpty() || text.length > 1000) return
        clipItems.remove(text)
        clipItems.addFirst(text)
        while (clipItems.size > MAX_CLIP) clipItems.removeLast()
        if (::keyboard.isInitialized) keyboard.setClipboardItems(clipItems.toList())
    }

    // ---------------- 选区变化 ----------------

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd,
        )
        if (selfEdit) {
            selfEdit = false
            return
        }
        if (pinyin.isNotEmpty() && (newSelStart != oldSelStart || newSelEnd != oldSelEnd)) {
            pinyin.setLength(0)
            candidates = emptyList()
            match = null
            currentInputConnection?.finishComposingText()
            renderCandidates()
        }
    }

    // ---------------- 小工具 ----------------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val DOUBLE_TAP_MS = 420L
        const val MAX_CLIP = 20
    }
}
