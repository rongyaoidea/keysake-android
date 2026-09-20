package com.typesake.app

/** 纯文本工具（无 Android 依赖，便于 JVM 单测）。 */
object TextUtils {

    private val TERMINATORS = charArrayOf(
        '。', '！', '？', '!', '?', ';', '；', '\n', '.',
    )

    /**
     * 取光标前文本里的“当前句”：最后一个句末符号之后的内容。
     * 主要用于双击空格 / ★ 收藏整句。
     */
    fun sentenceBefore(text: String, max: Int = 120): String {
        if (text.isBlank()) return ""
        var start = 0
        for (i in text.indices) {
            if (text[i] in TERMINATORS) start = i + 1
        }
        var out = text.substring(start).trim()
        if (out.length > max) out = out.takeLast(max)
        return out
    }

    /** 收藏用：去掉句尾多余空白，保留标点。 */
    fun normalizeForSave(text: String): String = text.trim()
}
