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
import android.widget.TextView
import android.widget.Toast
import com.typesake.app.kb.KbColors
import com.typesake.app.kb.KbKind
import com.typesake.app.kb.KbLayer
import com.typesake.app.kb.KbLayouts
import com.typesake.app.kb.KbThemes
import com.typesake.app.kb.KeyboardView
import com.typesake.app.kb.OneHand
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
 * 体验要点（对齐 Mac 版，移动端适配）：
 * - 拼音走 composing region（可见、可退格、系统行为正确）
 * - 顶部英文条：点英文直接上屏（中英混输），长按英文 = 替换刚上屏的中文
 * - 上屏后展示"联想"（bigram 预测）；空格上屏首选，双击空格收藏整句
 * - 候选查询在后台线程 + 15ms 防抖 + LRU 缓存，主线程只渲染
 * - 密码等隐私字段关闭拼音转换/学习/收藏，只做原始输入
 */
class TypesakeImeService : InputMethodService(), KeyboardView.Listener {

    private lateinit var prefs: TypesakePrefs
    private lateinit var root: FrameLayout
    private lateinit var keyboard: KeyboardView
    private lateinit var englishRow: LinearLayout
    private lateinit var candidateRow: LinearLayout
    private lateinit var englishScroll: HorizontalScrollView
    private lateinit var candidateScroll: HorizontalScrollView

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lookupJob: Job? = null

    private val pinyin = StringBuilder()
    private var candidates: List<String> = emptyList()
    private var predictions: List<String> = emptyList()
    private var englishChips: List<String> = emptyList()
    private var lastCommittedChinese: String = ""

    private var shifted = false
    private var capsLock = false
    private var lastShiftTap = 0L
    private var lastSpaceTap = 0L
    private var selfEdit = false

    private var kind = KbKind.QWERTY
    private var layer = KbLayer.LETTERS
    private var privateField = false
    private var colors: KbColors = KbThemes.light

    private val clipItems = ArrayDeque<String>()
    private var clipRegistered = false
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClipboard() }

    // ---------------- 生命周期 ----------------

    override fun onCreate() {
        super.onCreate()
        prefs = TypesakePrefs(this)
        TypesakeCore.init(filesDir.absolutePath)
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
        if (::keyboard.isInitialized) {
            keyboard.render(kind, layer, colors, prefs.keyHeightDp, clipItems.toList())
            refreshBars()
        }
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        val inputType = info?.inputType ?: 0
        kind = KbLayouts.kindForInputType(inputType)
        privateField = KbLayouts.learningDisabled(inputType)
        if (privateField) clearComposing()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        TypesakeCore.init(filesDir.absolutePath)
        clearComposing()
        predictions = emptyList()
        englishChips = emptyList()
        shifted = false
        capsLock = false
        colors = resolveColors()
        if (::keyboard.isInitialized) {
            keyboard.setOneHand(OneHand.fromInt(prefs.oneHand))
            keyboard.render(kind, layer, colors, prefs.keyHeightDp, clipItems.toList())
            keyboard.setShift(false, false)
            refreshBars()
        }
        registerClipboard()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        unregisterClipboard()
        lookupJob?.cancel()
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

        // 键盘放在 FrameLayout 里，单手模式才能靠 gravity 左右贴边
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
        renderEnglish()
        renderCandidates()
        return root
    }

    // ---------------- 状态与渲染 ----------------

    private fun resolveColors(): KbColors =
        KbThemes.resolve(prefs.themeMode, KbThemes.isNight(resources.configuration.uiMode))

    private fun clearComposing() {
        pinyin.setLength(0)
        candidates = emptyList()
        lastCommittedChinese = ""
    }

    private fun refreshBars() {
        renderEnglish()
        renderCandidates()
    }

    private fun renderEnglish() {
        if (!::englishRow.isInitialized) return
        englishRow.removeAllViews()
        englishScroll.setBackgroundColor(colors.barBg)

        if (englishChips.isEmpty()) {
            englishRow.addView(
                chip(
                    label = if (privateField) "隐私输入：不转换、不学习、不收藏" else "英文表达显示在这里",
                    textColor = colors.hint,
                    bg = colors.barBg,
                )
            )
        } else {
            for (text in englishChips) {
                englishRow.addView(
                    chip(
                        label = text,
                        textColor = colors.barText,
                        bg = colors.key,
                        onClick = { insertEnglish(text) },
                        onLongClick = { replaceLastChineseWith(text) },
                    )
                )
            }
        }
        if (!privateField) {
            englishRow.addView(
                chip("★", colors.accentText, colors.accent, onClick = { saveCurrentSentence() })
            )
        }
        englishRow.addView(chip("学", colors.accentText, colors.accent, onClick = { onOpenHub() }))
    }

    private fun renderCandidates() {
        if (!::candidateRow.isInitialized) return
        candidateRow.removeAllViews()
        candidateScroll.setBackgroundColor(colors.barBg)

        if (pinyin.isNotEmpty()) {
            candidateRow.addView(textLabel(pinyin.toString(), colors.accent, colors.barBg))
            if (candidates.isEmpty()) {
                candidateRow.addView(textLabel("空格直接上屏", colors.hint, colors.barBg))
            } else {
                for ((i, word) in candidates.withIndex()) {
                    candidateRow.addView(
                        chip(
                            label = if (i < 9) "${i + 1} $word" else word,
                            textColor = colors.keyText,
                            bg = colors.key,
                            onClick = { commitCandidate(word) },
                        )
                    )
                }
            }
            return
        }

        if (predictions.isNotEmpty()) {
            candidateRow.addView(textLabel("联想", colors.hint, colors.barBg))
            for (word in predictions) {
                candidateRow.addView(
                    chip(
                        label = word,
                        textColor = colors.barText,
                        bg = colors.key,
                        onClick = { insertPrediction(word) },
                    )
                )
            }
            return
        }

        candidateRow.addView(
            textLabel(
                if (privateField) "密码/隐私输入模式" else "键入拼音，如 nihao；空格上屏，双击空格收藏",
                colors.hint,
                colors.barBg,
            )
        )
    }

    private fun chip(
        label: String,
        textColor: Int,
        bg: Int,
        onClick: (() -> Unit)? = null,
        onLongClick: (() -> Unit)? = null,
    ): TextView = TextView(this).apply {
        text = label
        setTextColor(textColor)
        textSize = 14f
        maxLines = 1
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
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
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply {
            marginStart = dp(6)
            topMargin = dp(4)
            bottomMargin = dp(4)
        }
    }

    private fun textLabel(label: String, color: Int, bg: Int): TextView = TextView(this).apply {
        text = label
        setTextColor(color)
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(4), dp(8))
        setBackgroundColor(bg)
    }

    // ---------------- 输入逻辑 ----------------

    override fun onInsert(text: String) {
        val ic = currentInputConnection ?: return
        if (privateField) {
            ic.commitText(text, 1)
            return
        }
        // 拼音输入中：数字键选候选
        if (pinyin.isNotEmpty() && text.length == 1 && text[0].isDigit()) {
            val idx = if (text == "0") 9 else text[0].digitToInt() - 1
            candidates.getOrNull(idx)?.let {
                commitCandidate(it)
                return
            }
        }
        if (text.length == 1 && text[0].isLetter()) {
            onLetter(text)
            return
        }
        if (pinyin.isNotEmpty()) commitTopCandidate()
        ic.commitText(text, 1)
        refreshBars()
    }

    private fun onLetter(letterText: String) {
        val ic = currentInputConnection ?: return
        pinyin.append(letterText.lowercase())
        selfEdit = true
        ic.setComposingText(pinyin.toString(), 1)
        candidates = TypesakeCore.cached(pinyin.toString()) ?: emptyList()
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
            val list = TypesakeCore.candidates(p)
            withContext(Dispatchers.Main) {
                if (pinyin.toString() == p) {
                    candidates = list
                    renderCandidates()
                    renderComposingEnglish(list)
                }
            }
        }
    }

    private fun renderComposingEnglish(list: List<String>) {
        if (englishChips.isNotEmpty()) return
        val target = list.firstOrNull() ?: return
        val en = TypesakeCore.suggest(target)
        if (en.isNotEmpty()) {
            englishChips = listOf(en)
            renderEnglish()
        }
    }

    private fun commitCandidate(word: String) {
        val ic = currentInputConnection ?: return
        val pin = pinyin.toString()
        ic.commitText(word, 1)
        selfEdit = true
        pinyin.setLength(0)
        candidates = emptyList()
        lastCommittedChinese = word
        if (pin.isNotEmpty()) {
            io.launch { TypesakeCore.pick(pin, word) }
        }
        afterCommit(word)
    }

    private fun commitTopCandidate() {
        val top = candidates.firstOrNull() ?: pinyin.toString()
        if (pinyin.isNotEmpty()) commitCandidate(top)
    }

    private fun afterCommit(word: String) {
        englishChips = TypesakeCore.englishList(word)
        predictions = TypesakeCore.predict(word)
        refreshBars()
    }

    private fun insertPrediction(word: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(word, 1)
        lastCommittedChinese = word
        englishChips = TypesakeCore.englishList(word)
        predictions = TypesakeCore.predict(word)
        refreshBars()
    }

    private fun insertEnglish(english: String) {
        val ic = currentInputConnection ?: return
        if (pinyin.isNotEmpty()) commitTopCandidate()
        ic.commitText(english, 1)
        predictions = emptyList()
        englishChips = TypesakeCore.englishList(lastCommittedChinese)
        refreshBars()
    }

    /** 长按英文条：把刚上屏的中文替换成英文（中英混输的"改写"用法）。 */
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
        if (pinyin.isNotEmpty()) {
            commitTopCandidate()
        } else {
            ic.commitText(" ", 1)
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
        if (pinyin.isNotEmpty()) {
            commitTopCandidate()
            return
        }
        val info = currentInputEditorInfo
        val noEnterAction = info != null && (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        val multiline = info != null &&
            (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (noEnterAction || multiline || action == EditorInfo.IME_ACTION_NONE) {
            ic.commitText("\n", 1)
        } else {
            ic.performEditorAction(action)
        }
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (pinyin.isNotEmpty()) {
            pinyin.deleteCharAt(pinyin.length - 1)
            selfEdit = true
            if (pinyin.isEmpty()) {
                ic.setComposingText("", 1)
                candidates = emptyList()
                renderCandidates()
            } else {
                ic.setComposingText(pinyin.toString(), 1)
                candidates = TypesakeCore.cached(pinyin.toString()) ?: emptyList()
                renderCandidates()
                requestCandidates(pinyin.toString())
            }
        } else {
            ic.deleteSurroundingText(1, 0)
        }
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
        keyboard.render(kind, layer, colors, prefs.keyHeightDp, clipItems.toList())
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
