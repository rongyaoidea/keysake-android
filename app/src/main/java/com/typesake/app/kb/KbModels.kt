package com.typesake.app.kb

/**
 * 键盘数据模型与布局表（纯 Kotlin，无 Android 依赖，便于 JVM 单测）。
 *
 * 布局原则：
 * - 字母页常驻数字行（输入中时数字键选候选，未输入时上屏数字）
 * - 符号页塞入 emoji / 剪贴板 / 单手 / 光标键等"低频但必需"入口
 * - 数字页、电话页按 inputType 自动切换
 */

enum class KbLayer { LETTERS, SYMBOLS, EMOJI, CLIPBOARD, PHRASES }

enum class KbKind { QWERTY, T9, NUMBER, PHONE, RAW }

enum class OneHand {
    NONE, LEFT, RIGHT;

    fun next(): OneHand = when (this) {
        NONE -> LEFT
        LEFT -> RIGHT
        RIGHT -> NONE
    }

    companion object {
        fun fromInt(v: Int): OneHand = entries.getOrElse(v) { NONE }
    }
}

sealed interface KbAction {
    data class Insert(val text: String) : KbAction
    data object Shift : KbAction
    data object Backspace : KbAction
    data object Enter : KbAction
    data object Space : KbAction
    data class ShowLayer(val layer: KbLayer) : KbAction
    data object Language : KbAction
    data object OpenHub : KbAction
    data object OneHandToggle : KbAction
    data object CursorLeft : KbAction
    data object CursorRight : KbAction
}

data class KbKey(
    val id: String,
    val label: String,
    val weight: Float = 1f,
    val action: KbAction,
    val isActionKey: Boolean = false,
    /** 长按可选内容（九键用：raw 数字/字母/标点）；为空则回退到全局变体表 */
    val longPress: List<String> = emptyList(),
)

data class KbRow(val keys: List<KbKey>)

object KbLayouts {

    // --- Android InputType 常量副本：避免 kb 包依赖 android.jar，保证单测可跑 ---
    const val TYPE_CLASS_TEXT = 0x00000001
    const val TYPE_CLASS_NUMBER = 0x00000002
    const val TYPE_CLASS_PHONE = 0x00000003
    const val TYPE_CLASS_DATETIME = 0x00000004
    const val TYPE_MASK_CLASS = 0x0000000f
    const val TYPE_MASK_VARIATION = 0x00000ff0
    const val TYPE_TEXT_VARIATION_PASSWORD = 0x00000010
    const val TYPE_TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    const val TYPE_TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    const val TYPE_NUMBER_VARIATION_PASSWORD = 0x00000010

    const val NUMBER_ROW = "1234567890"

    fun kindForInputType(inputType: Int): KbKind {
        val cls = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return when {
            cls == TYPE_CLASS_PHONE -> KbKind.PHONE
            cls == TYPE_CLASS_NUMBER || cls == TYPE_CLASS_DATETIME ->
                if (variation == TYPE_NUMBER_VARIATION_PASSWORD) KbKind.RAW else KbKind.NUMBER
            cls == TYPE_CLASS_TEXT && (variation == TYPE_TEXT_VARIATION_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == TYPE_TEXT_VARIATION_WEB_PASSWORD) -> KbKind.RAW
            else -> KbKind.QWERTY
        }
    }

    /** 密码/隐私字段：不做拼音转换、不做词频学习、不收藏。 */
    fun learningDisabled(inputType: Int): Boolean = kindForInputType(inputType) == KbKind.RAW

    private fun ins(text: String, weight: Float = 1f, label: String = text) =
        KbKey(id = "ins_$label", label = label, weight = weight, action = KbAction.Insert(text))

    private fun action(id: String, label: String, weight: Float, a: KbAction, wide: Boolean = true) =
        KbKey(id = id, label = label, weight = weight, action = a, isActionKey = wide)

    fun lettersRows(shifted: Boolean, capsLock: Boolean): List<KbRow> {
        val rows = mutableListOf<KbRow>()
        rows += KbRow(NUMBER_ROW.map { ins(it.toString()) })
        rows += KbRow("qwertyuiop".map { letter(it, shifted, capsLock) })
        rows += KbRow("asdfghjkl".map { letter(it, shifted, capsLock) })
        rows += KbRow(
            listOf(
                action("shift", if (capsLock) "⇪" else "⇧", 1.4f, KbAction.Shift),
                *"zxcvbnm".map { letter(it, shifted, capsLock) }.toTypedArray(),
                action("backspace", "⌫", 1.4f, KbAction.Backspace),
            )
        )
        rows += KbRow(
            listOf(
                action("symbols", "?123", 1.3f, KbAction.ShowLayer(KbLayer.SYMBOLS)),
                action("emoji", "☺", 1f, KbAction.ShowLayer(KbLayer.EMOJI)),
                action("lang", "🌐", 1f, KbAction.Language),
                ins(",", 1f),
                action("space", "空格", 3.2f, KbAction.Space, wide = false),
                ins(".", 1f),
                action("enter", "⏎", 1.5f, KbAction.Enter),
            )
        )
        return rows
    }

    private fun letter(c: Char, shifted: Boolean, capsLock: Boolean): KbKey {
        val up = shifted || capsLock
        val text = if (up) c.uppercaseChar().toString() else c.toString()
        return KbKey(id = "letter_$c", label = text, weight = 1f, action = KbAction.Insert(text))
    }

    fun symbolRows(): List<KbRow> = listOf(
        KbRow("-/：;()¥&@".map { ins(it.toString()) }),
        KbRow("。，？！'\"~\\_".map { ins(if (it == '。') "." else if (it == '，') "," else it.toString()) }),
        KbRow(
            listOf(
                action("abc", "ABC", 1.4f, KbAction.ShowLayer(KbLayer.LETTERS)),
                action("emoji2", "☺", 1f, KbAction.ShowLayer(KbLayer.EMOJI)),
                action("clip", "📋", 1f, KbAction.ShowLayer(KbLayer.CLIPBOARD)),
                action("phrases", "📝", 1f, KbAction.ShowLayer(KbLayer.PHRASES)),
                action("onehand", "⇤", 1.2f, KbAction.OneHandToggle),
                action("cursor_left", "←", 1f, KbAction.CursorLeft),
                action("cursor_right", "→", 1f, KbAction.CursorRight),
                action("backspace2", "⌫", 1.4f, KbAction.Backspace),
            )
        ),
    )

    /** 九键（T9）：1-9 输入拼音数字串，长按可插入原始数字/字母/标点。 */
    fun t9Rows(): List<KbRow> {
        fun digitKey(d: Char, letters: String): KbKey = KbKey(
            id = "t9_$d",
            label = if (letters.isEmpty()) "$d" else "$d $letters",
            weight = 1f,
            action = KbAction.Insert(d.toString()),
            longPress = listOf(d.toString()) + letters.map { it.toString() } + T9_PUNCT[d].orEmpty(),
        )
        val digits = mapOf(
            '2' to "abc", '3' to "def", '4' to "ghi", '5' to "jkl",
            '6' to "mno", '7' to "pqrs", '8' to "tuv", '9' to "wxyz",
        )
        return listOf(
            KbRow(listOf(digitKey('1', ""), digitKey('2', "abc"), digitKey('3', "def"))),
            KbRow(listOf(digitKey('4', "ghi"), digitKey('5', "jkl"), digitKey('6', "mno"))),
            KbRow(listOf(digitKey('7', "pqrs"), digitKey('8', "tuv"), digitKey('9', "wxyz"))),
            KbRow(
                listOf(
                    action("t9_symbols", "?123", 1f, KbAction.ShowLayer(KbLayer.SYMBOLS)),
                    KbKey(
                        id = "t9_0",
                        label = "0 ␣",
                        weight = 1f,
                        action = KbAction.Insert("0"),
                        longPress = listOf("0", " "),
                    ),
                    action("t9_backspace", "⌫", 1.4f, KbAction.Backspace),
                )
            ),
            KbRow(
                listOf(
                    action("t9_emoji", "☺", 1f, KbAction.ShowLayer(KbLayer.EMOJI)),
                    action("t9_space", "空格", 3f, KbAction.Space, wide = false),
                    action("t9_enter", "⏎", 1.5f, KbAction.Enter),
                )
            ),
        )
    }

    private val T9_PUNCT: Map<Char, List<String>> = mapOf(
        '1' to listOf("。", "，", "？", "！"),
        '2' to listOf("@"),
        '3' to listOf("#"),
    )

    fun numberRows(): List<KbRow> = listOf(
        KbRow(listOf("1", "2", "3").map { ins(it) }),
        KbRow(listOf("4", "5", "6").map { ins(it) }),
        KbRow(listOf("7", "8", "9").map { ins(it) }),
        KbRow(listOf(ins("."), ins(","), ins("-"), ins("+"), ins("0"))),
        KbRow(
            listOf(
                action("cursor_left_n", "←", 1f, KbAction.CursorLeft),
                action("cursor_right_n", "→", 1f, KbAction.CursorRight),
                action("backspace_n", "⌫", 1.4f, KbAction.Backspace),
                action("enter_n", "⏎", 1.4f, KbAction.Enter),
            )
        ),
    )

    fun phoneRows(): List<KbRow> = listOf(
        KbRow(listOf("1", "2", "3").map { ins(it) }),
        KbRow(listOf("4", "5", "6").map { ins(it) }),
        KbRow(listOf("7", "8", "9").map { ins(it) }),
        KbRow(listOf(ins("*"), ins("+"), ins("#"), ins("0"))),
        KbRow(
            listOf(
                action("cursor_left_p", "←", 1f, KbAction.CursorLeft),
                action("cursor_right_p", "→", 1f, KbAction.CursorRight),
                action("backspace_p", "⌫", 1.4f, KbAction.Backspace),
                action("enter_p", "⏎", 1.4f, KbAction.Enter),
            )
        ),
    )

    fun rowsFor(kind: KbKind, layer: KbLayer, shifted: Boolean, capsLock: Boolean): List<KbRow> =
        when (kind) {
            KbKind.NUMBER -> numberRows()
            KbKind.PHONE -> phoneRows()
            KbKind.T9 -> if (layer == KbLayer.SYMBOLS) symbolRows() else t9Rows()
            KbKind.RAW, KbKind.QWERTY -> when (layer) {
                KbLayer.SYMBOLS -> symbolRows()
                else -> lettersRows(shifted, capsLock)
            }
        }

    /** 长按变体（重音字母 / 常用符号），空表示无变体。 */
    private val ALTERNATES: Map<String, List<String>> = mapOf(
        "a" to listOf("á", "à", "ä", "â"),
        "e" to listOf("é", "è", "ë", "ê"),
        "i" to listOf("í", "ì", "ï", "î"),
        "o" to listOf("ó", "ò", "ö", "ô"),
        "u" to listOf("ú", "ù", "ü", "û"),
        "n" to listOf("ñ"),
        "c" to listOf("ç"),
        "s" to listOf("ß"),
        "." to listOf(",", "…", "。"),
        "," to listOf(".", "、", "，"),
        "-" to listOf("—", "_", "~"),
        "!" to listOf("?", "！", "？"),
        "?" to listOf("!", "？", "！"),
        "0" to listOf("°"),
        "'" to listOf("\""),
    )

    fun alternatesFor(label: String): List<String> = ALTERNATES[label.lowercase()] ?: emptyList()

    /** 上滑（手势）插入的字符：数字键 -> 符号，字母 -> 对应数字/重音。 */
    private val SWIPE_UP_DIGIT: Map<String, String> = mapOf(
        "1" to "!", "2" to "@", "3" to "#", "4" to "$", "5" to "%",
        "6" to "^", "7" to "&", "8" to "*", "9" to "(", "0" to ")",
    )
    private val SWIPE_UP_LETTER: Map<String, String> = buildMap {
        // 上排字母上滑 -> 数字（q=1 … p=0），中/下排 -> 常用符号
        "qwertyuiop".forEachIndexed { i, c -> put(c.toString(), ((i + 1) % 10).toString()) }
        "asdfghjkl".forEachIndexed { i, c ->
            put(c.toString(), listOf("@", "#", "-", "_", "+", "=", ";", ":", "/")[i])
        }
        "zxcvbnm".forEachIndexed { i, c ->
            put(c.toString(), listOf("*", "#", "(", ")", "\"", "'", ",")[i])
        }
    }

    fun swipeUpFor(label: String): String? {
        val key = label.lowercase()
        val digits = SWIPE_UP_DIGIT[key]
        if (digits != null) return digits
        val sym = SWIPE_UP_LETTER[key]
        if (sym != null) return sym
        return alternatesFor(key).firstOrNull()
    }

    /** 常用 emoji（覆盖表情/手势/生活/办公）。 */
    val EMOJI: List<String> = listOf(
        "😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "🙃",
        "😉", "😊", "😇", "🥰", "😍", "😘", "😋", "🤤", "🤗", "🤔",
        "🤩", "😎", "🥳", "😌", "😴", "😪", "😢", "😭", "😤", "😡",
        "🥺", "😱", "🫡", "🤝", "👍", "👎", "👏", "🙏", "💪", "✌️",
        "👌", "🤞", "❤️", "💔", "💕", "💯", "⭐", "🔥", "✨", "🎉",
        "🎊", "🎁", "🎂", "🎈", "🌸", "🌹", "🍀", "🌈", "☀️", "🌙",
        "⚡", "❄️", "☕", "🍜", "🍚", "🍕", "🍎", "🍺", "🍻", "🥂",
        "🎵", "🎶", "📱", "💻", "⌨️", "🖥️", "📝", "📌", "📅", "⏰",
        "💰", "💳", "📈", "📉", "📊", "🏆", "🚀", "🎯", "🤖", "💡",
        "✅", "❌", "⚠️", "❓", "❗", "🔔", "🔍", "🔗", "📎", "📦",
        "🐱", "🐶", "🐼", "🦄", "🐟", "🌻", "🌴", "🍀", "🌏", "🏠",
        "😷", "🤒", "🚗", "✈️", "🚄", "🚇", "🗺️", "🧳", "🛒", "🧠",
    )
}
