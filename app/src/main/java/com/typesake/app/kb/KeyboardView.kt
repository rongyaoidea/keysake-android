package com.typesake.app.kb

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import com.typesake.app.TypesakePrefs

/**
 * 自绘键盘：圆角键帽、按下态、震动/声音、按键预览、长按变体、
 * 单手模式、emoji / 剪贴板面板。对外只通过 [Listener] 与输入法服务通信。
 */
class KeyboardView(
    context: Context,
    private val prefs: TypesakePrefs,
    private val listener: Listener? = null,
) : LinearLayout(context) {

    interface Listener {
        fun onInsert(text: String)
        fun onBackspace()
        fun onEnter()
        fun onSpace()
        fun onShiftTap()
        fun onShiftLongPress()
        fun onShowLayer(layer: KbLayer)
        fun onLanguage()
        fun onOpenHub()
        fun onOneHandToggle()
        fun onCursorLeft()
        fun onCursorRight()
        fun onClipboardClear()
        fun onDeleteWord()
    }

    var kind: KbKind = KbKind.QWERTY
        private set
    var layer: KbLayer = KbLayer.LETTERS
        private set
    var oneHand: OneHand = OneHand.NONE
        private set

    private var shifted = false
    private var capsLock = false
    private var colors: KbColors = KbThemes.resolve(KbPalette.GREEN, KbThemes.THEME_LIGHT, false)
    private var rowHeightPx: Int = dp(46)
    private var clipItems: List<String> = emptyList()
    private var clipPhrases: List<Pair<String, String>> = emptyList()

    private val rows = LinearLayout(context).apply { orientation = VERTICAL }
    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var preview: PopupWindow? = null
    private var alternates: PopupWindow? = null
    private var longPress: Runnable? = null

    init {
        orientation = VERTICAL
        addView(rows, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    // ---------------- 对外 API ----------------

    fun render(
        kind: KbKind,
        layer: KbLayer,
        colors: KbColors,
        keyHeightDp: Int,
        clipboardItems: List<String> = clipItems,
        phrases: List<Pair<String, String>> = clipPhrases,
    ) {
        this.kind = kind
        this.layer = if (kind == KbKind.QWERTY || kind == KbKind.RAW) layer else KbLayer.LETTERS
        this.colors = colors
        this.rowHeightPx = dp(TypesakePrefs.sanitizeKeyHeight(keyHeightDp))
        this.clipItems = clipboardItems
        this.clipPhrases = phrases
        dismissPopups()
        rows.removeAllViews()
        setBackgroundColor(colors.bg)
        when (this.layer) {
            KbLayer.EMOJI -> buildEmojiPanel()
            KbLayer.CLIPBOARD -> buildClipboardPanel()
            KbLayer.PHRASES -> buildPhrasesPanel()
            else -> buildKeys()
        }
    }

    fun setShift(shifted: Boolean, capsLock: Boolean) {
        if (this.shifted == shifted && this.capsLock == capsLock) return
        this.shifted = shifted
        this.capsLock = capsLock
        if (layer == KbLayer.LETTERS) {
            rows.removeAllViews()
            buildKeys()
        }
    }

    fun setClipboardItems(items: List<String>) {
        clipItems = items
        if (layer == KbLayer.CLIPBOARD) {
            rows.removeAllViews()
            buildClipboardPanel()
        }
    }

    fun setPhrases(phrases: List<Pair<String, String>>) {
        clipPhrases = phrases
        if (layer == KbLayer.PHRASES) {
            rows.removeAllViews()
            buildPhrasesPanel()
        }
    }

    fun setOneHand(mode: OneHand) {
        oneHand = mode
        val lp = layoutParams
        if (lp != null) {
            lp.width = if (mode == OneHand.NONE) {
                ViewGroup.LayoutParams.MATCH_PARENT
            } else {
                (resources.displayMetrics.widthPixels * 0.8f).toInt()
            }
            if (lp is FrameLayout.LayoutParams) {
                lp.gravity = when (mode) {
                    OneHand.NONE -> Gravity.NO_GRAVITY
                    OneHand.LEFT -> Gravity.START
                    OneHand.RIGHT -> Gravity.END
                }
            }
            layoutParams = lp
        }
    }

    override fun onDetachedFromWindow() {
        dismissPopups()
        super.onDetachedFromWindow()
    }

    // ---------------- 键盘构建 ----------------

    private fun buildKeys() {
        val layout = KbLayouts.rowsFor(kind, layer, shifted, capsLock)
        for (row in layout) {
            val rowView = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (key in row.keys) {
                rowView.addView(
                    KeyButton(key).apply {
                        layoutParams = LayoutParams(0, rowHeightPx, key.weight)
                    }
                )
            }
            rows.addView(
                rowView,
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(4)
                }
            )
        }
    }

    private fun buildEmojiPanel() {
        val scroll = ScrollView(context)
        val grid = LinearLayout(context).apply { orientation = VERTICAL }
        val perRow = 8
        for (chunk in KbLayouts.EMOJI.chunked(perRow)) {
            val rowView = LinearLayout(context).apply { orientation = HORIZONTAL }
            for (emoji in chunk) {
                rowView.addView(
                    KeyButton(
                        KbKey("emoji_$emoji", emoji, 1f, KbAction.Insert(emoji))
                    ).apply { layoutParams = LayoutParams(0, rowHeightPx, 1f) }
                )
            }
            // 补足空位，保持网格对齐
            repeat(perRow - chunk.size) {
                rowView.addView(View(context), LayoutParams(0, rowHeightPx, 1f))
            }
            grid.addView(rowView)
        }
        scroll.addView(grid)
        rows.addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx * 4).apply { topMargin = dp(4) }
        )
        rows.addView(
            actionBar(
                listOf(
                    BarAction("ABC", onClick = { listener?.onShowLayer(KbLayer.LETTERS) }),
                )
            ),
            LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx).apply { topMargin = dp(4) }
        )
    }

    private fun buildClipboardPanel() {
        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        if (clipItems.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = "剪贴板暂无内容（仅记录本次会话，不落盘）"
                    setTextColor(colors.hint)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
            )
        } else {
            for ((i, item) in clipItems.withIndex()) {
                val oneLine = item.replace('\n', ' ').take(60)
                list.addView(
                    TextView(context).apply {
                        text = oneLine
                        setTextColor(colors.keyText)
                        gravity = Gravity.CENTER_VERTICAL
                        maxLines = 1
                        setPadding(dp(12), 0, dp(12), 0)
                        background = rounded(colors.key, dp(6))
                        setOnClickListener { listener?.onInsert(item) }
                    },
                    LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx).apply {
                        bottomMargin = dp(4)
                    }
                )
                if (i == 19) break
            }
        }
        scroll.addView(list)
        rows.addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx * 4).apply { topMargin = dp(4) }
        )
        rows.addView(
            actionBar(
                listOf(
                    BarAction("ABC", onClick = { listener?.onShowLayer(KbLayer.LETTERS) }),
                    BarAction("清空", onClick = { listener?.onClipboardClear() }),
                )
            ),
            LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx).apply { topMargin = dp(4) }
        )
    }

    private fun buildPhrasesPanel() {
        val scroll = ScrollView(context)
        val list = LinearLayout(context).apply { orientation = VERTICAL }
        if (clipPhrases.isEmpty()) {
            list.addView(
                TextView(context).apply {
                    text = "还没有收藏。打拼音后点 ★ 或双击空格收藏整句。"
                    setTextColor(colors.hint)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                }
            )
        } else {
            for ((cn, en) in clipPhrases.take(20)) {
                val title = if (en.isBlank()) cn else "$cn  ·  $en"
                list.addView(
                    TextView(context).apply {
                        text = title
                        setTextColor(colors.keyText)
                        gravity = Gravity.CENTER_VERTICAL
                        maxLines = 1
                        setPadding(dp(12), 0, dp(12), 0)
                        background = rounded(colors.key, dp(6))
                        setOnClickListener { listener?.onInsert(cn) }
                        setOnLongClickListener {
                            if (en.isNotBlank()) listener?.onInsert(en)
                            true
                        }
                    },
                    LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx).apply {
                        bottomMargin = dp(4)
                    }
                )
            }
        }
        scroll.addView(list)
        rows.addView(
            scroll,
            LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx * 4).apply { topMargin = dp(4) }
        )
        rows.addView(
            actionBar(
                listOf(
                    BarAction("ABC", onClick = { listener?.onShowLayer(KbLayer.LETTERS) }),
                    BarAction("提示：长按插入英文", onClick = {}),
                )
            ),
            LayoutParams(LayoutParams.MATCH_PARENT, rowHeightPx).apply { topMargin = dp(4) }
        )
    }

    private class BarAction(val label: String, val onClick: () -> Unit)

    private fun actionBar(items: List<BarAction>): LinearLayout {
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        for (item in items) {
            val v = TextView(context).apply {
                text = item.label
                gravity = Gravity.CENTER
                setTextColor(colors.actionText)
                background = rounded(colors.actionKey, dp(6))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setOnClickListener { item.onClick() }
            }
            row.addView(v, LayoutParams(0, rowHeightPx, 1f).apply { marginStart = dp(4) })
        }
        return row
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun keyBackground(pressed: GradientDrawable): StateListDrawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
    }

    // ---------------- 按键 ----------------

    private inner class KeyButton(val key: KbKey) : TextView(context) {

        init {
            text = key.label
            gravity = Gravity.CENTER
            maxLines = 1
            isClickable = true
            setTextColor(if (key.isActionKey) colors.actionText else colors.keyText)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (key.isActionKey) 13f else 18f)
            val idle = rounded(if (key.isActionKey) colors.actionKey else colors.key, dp(7))
            val down = rounded(
                if (key.isActionKey) colors.actionKey else colors.accent,
                dp(7)
            )
            background = keyBackground(down).apply {
                addState(intArrayOf(), idle)
            }
            setPadding(dp(2), 0, dp(2), 0)
            setOnTouchListener { v, e -> handleTouch(this, e) }
        }
    }

    private fun handleTouch(v: View, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.isPressed = true
                downX = e.x
                downY = e.y
                feedback(v)
                if (!(v as KeyButton).key.isActionKey) showPreview(v)
                scheduleLongPress(v)
                return true
            }
            MotionEvent.ACTION_UP -> {
                cancelPendingLongPress()
                v.isPressed = false
                dismissPreview()
                if (alternates == null && inside(v, e)) {
                    val key = (v as KeyButton).key
                    val dx = e.x - downX
                    val dy = e.y - downY
                    when {
                        dy < -SWIPE_THRESHOLD && dx < SWIPE_THRESHOLD_ORTHO ->
                            swipeUp(key)
                        key.action is KbAction.Backspace && dx < -SWIPE_THRESHOLD ->
                            listener?.onDeleteWord()
                        key.action is KbAction.Space && dx > SWIPE_THRESHOLD ->
                            listener?.onCursorRight()
                        key.action is KbAction.Space && dx < -SWIPE_THRESHOLD ->
                            listener?.onCursorLeft()
                        else -> perform(key)
                    }
                }
                dismissAlternates()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPendingLongPress()
                v.isPressed = false
                dismissPreview()
                dismissAlternates()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!inside(v, e)) {
                    v.isPressed = false
                    dismissPreview()
                    cancelPendingLongPress()
                }
            }
        }
        return false
    }

    /** 上滑：字母/符号键插入备选字符（搜狗"上滑符号"的等价实现）。 */
    private fun swipeUp(key: KbKey) {
        val alt = KbLayouts.swipeUpFor(key.label)
        if (alt != null) {
            listener?.onInsert(alt)
        } else {
            perform(key)
        }
    }

    private fun inside(v: View, e: MotionEvent): Boolean =
        e.x >= 0 && e.y >= 0 && e.x <= v.width && e.y <= v.height

    private fun feedback(v: View) {
        if (prefs.haptics) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        if (prefs.sound) v.playSoundEffect(SoundEffectConstants.CLICK)
    }

    private fun perform(key: KbKey) {
        val l = listener ?: return
        when (val a = key.action) {
            is KbAction.Insert -> l.onInsert(a.text)
            KbAction.Shift -> l.onShiftTap()
            KbAction.Backspace -> l.onBackspace()
            KbAction.Enter -> l.onEnter()
            KbAction.Space -> l.onSpace()
            is KbAction.ShowLayer -> l.onShowLayer(a.layer)
            KbAction.Language -> l.onLanguage()
            KbAction.OpenHub -> l.onOpenHub()
            KbAction.OneHandToggle -> l.onOneHandToggle()
            KbAction.CursorLeft -> l.onCursorLeft()
            KbAction.CursorRight -> l.onCursorRight()
        }
    }

    // ---------------- 长按与预览 ----------------

    private fun scheduleLongPress(v: View) {
        val r = Runnable {
            val key = (v as? KeyButton)?.key ?: return@Runnable
            if (key.action is KbAction.Shift) {
                listener?.onShiftLongPress()
                return@Runnable
            }
            val alts = KbLayouts.alternatesFor(key.label)
            if (alts.isNotEmpty()) {
                dismissPreview()
                showAlternates(v, alts)
            }
        }
        longPress = r
        handler.postDelayed(r, LONG_PRESS_MS)
    }

    private fun cancelPendingLongPress() {
        longPress?.let { handler.removeCallbacks(it) }
        longPress = null
    }

    private fun showPreview(anchor: View) {
        if (!prefs.keyPreview) return
        val text = (anchor as? KeyButton)?.key?.label ?: return
        val popup = PopupWindow(
            TextView(context).apply {
                this.text = text
                gravity = Gravity.CENTER
                setTextColor(colors.keyText)
                background = rounded(colors.key, dp(7))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            },
            dp(40),
            dp(52),
            false,
        ).apply {
            isOutsideTouchable = false
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        positionAbove(popup, anchor, dp(40), dp(52))
        preview = popup
    }

    private fun showAlternates(anchor: View, items: List<String>) {
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(colors.bg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for (item in items) {
            row.addView(
                TextView(context).apply {
                    text = item
                    gravity = Gravity.CENTER
                    setTextColor(colors.keyText)
                    background = rounded(colors.key, dp(6))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    setOnClickListener {
                        listener?.onInsert(item)
                        dismissAlternates()
                    }
                },
                LayoutParams(dp(44), dp(48)).apply { marginStart = dp(4) }
            )
        }
        val width = dp(52) * items.size + dp(8)
        val popup = PopupWindow(row, width, dp(56), false).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        positionAbove(popup, anchor, width, dp(56))
        alternates = popup
    }

    private fun positionAbove(popup: PopupWindow, anchor: View, w: Int, h: Int) {
        val loc = IntArray(2)
        anchor.getLocationInWindow(loc)
        val x = loc[0] + anchor.width / 2 - w / 2
        val y = loc[1] - h - dp(4)
        try {
            popup.showAtLocation(this, Gravity.NO_GRAVITY, x.coerceAtLeast(0), y.coerceAtLeast(0))
        } catch (_: Exception) {
            // 窗口 token 失效（切换输入框瞬间）时静默忽略
        }
    }

    private fun dismissPreview() {
        preview?.dismiss()
        preview = null
    }

    private fun dismissAlternates() {
        alternates?.dismiss()
        alternates = null
    }

    private fun dismissPopups() {
        dismissPreview()
        dismissAlternates()
        cancelPendingLongPress()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val LONG_PRESS_MS = 420L
        const val SWIPE_THRESHOLD = 60f
        const val SWIPE_THRESHOLD_ORTHO = 90f
    }
}
