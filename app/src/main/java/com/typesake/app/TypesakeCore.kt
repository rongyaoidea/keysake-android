package com.typesake.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JNI 桥：优先调 Rust `libtypesake_core.so`，缺库时用 Kotlin 兜底，保证 App 可装可用。 */
object TypesakeCore {
    @Serializable
    data class SavedPhrase(
        val chinese: String = "",
        val english: String = "",
        val saved_at: Long = 0L,
    )

    private val json = Json { ignoreUnknownKeys = true }
    var available: Boolean = false
        private set

    init {
        available = try {
            System.loadLibrary("typesake_core")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    // ---- JNI（Rust 侧 #[no_mangle] 实现） ----
    @JvmStatic private external fun initStorage(dir: String): String
    @JvmStatic private external fun suggestEnglish(chinese: String): String
    @JvmStatic private external fun candidatesFor(pinyin: String): String
    @JvmStatic private external fun englishCandidates(chinese: String): String
    @JvmStatic private external fun grammarExplain(english: String): String
    @JvmStatic private external fun savePhrase(chinese: String, english: String): String
    @JvmStatic private external fun listSaved(): String
    @JvmStatic private external fun clearSaved(): String

    fun init(dir: String): Int {
        if (!available) return 0
        return try {
            val raw = initStorage(dir)
            Regex(""""count":(\d+)""").find(raw)?.groupValues?.get(1)?.toInt() ?: 0
        } catch (_: Exception) { 0 }
    }

    fun suggest(chinese: String): String =
        if (available) runCatching { suggestEnglish(chinese) }.getOrDefault(fallbackSuggest(chinese))
        else fallbackSuggest(chinese)

    fun candidates(pinyin: String): List<String> =
        if (available) runCatching {
            json.decodeFromString<List<String>>(candidatesFor(pinyin))
        }.getOrDefault(fallbackCandidates(pinyin)) else fallbackCandidates(pinyin)

    fun englishList(chinese: String): List<String> =
        if (available) runCatching {
            json.decodeFromString<List<String>>(englishCandidates(chinese))
        }.getOrDefault(listOf(fallbackSuggest(chinese))) else listOf(fallbackSuggest(chinese))

    fun explain(english: String): String =
        if (available) runCatching { grammarExplain(english) }.getOrDefault(fallbackExplain(english))
        else fallbackExplain(english)

    fun save(chinese: String, english: String): Boolean {
        if (chinese.isBlank() || english.isBlank()) return false
        if (!available) return MemStore.save(chinese, english)
        return runCatching { savePhrase(chinese, english); true }.getOrDefault(false)
    }

    fun list(): List<SavedPhrase> =
        if (available) runCatching {
            json.decodeFromString<List<SavedPhrase>>(listSaved())
        }.getOrDefault(MemStore.list()) else MemStore.list()

    fun clear(): Int =
        if (available) runCatching {
            val raw = clearSaved()
            Regex(""""cleared":(\d+)""").find(raw)?.groupValues?.get(1)?.toInt() ?: 0
        }.getOrDefault(MemStore.clear()) else MemStore.clear()

    // ---- 无 .so 时的最小兜底（与 Rust 内置表子集一致） ----
    private val pinMap = mapOf(
        "nihao" to listOf("你好"), "ni" to listOf("你", "泥", "尼"),
        "hao" to listOf("好", "号"), "xiexie" to listOf("谢谢"),
        "zaijian" to listOf("再见"), "zhongguo" to listOf("中国"),
        "yingyu" to listOf("英语"), "gongzuo" to listOf("工作"),
        "huiyi" to listOf("会议"), "pengyou" to listOf("朋友"),
    )
    private val enMap = mapOf(
        "你好" to "Hello!", "谢谢" to "Thank you!", "再见" to "Goodbye! / See you!",
        "中国" to "China", "英语" to "English", "工作" to "work / job",
        "会议" to "meeting", "朋友" to "friend", "学习英语" to "Learn English",
    )
    private fun fallbackCandidates(p: String): List<String> {
        val k = p.lowercase().replace(" ", "")
        pinMap[k]?.let { return it }
        return pinMap.entries.firstOrNull { it.key.startsWith(k) || k.startsWith(it.key) }?.value ?: emptyList()
    }
    private fun fallbackSuggest(c: String): String =
        enMap[c.trim()] ?: enMap.entries.firstOrNull { c.contains(it.key) }?.value ?: ""
    private fun fallbackExplain(e: String): String =
        "句子：$e\n句式：${if (e.trimEnd().endsWith("?")) "疑问句" else "陈述句"}\n提示：接上 Rust .so 后可看完整时态与成分分析。"

    /** 进程内兜底收藏（仅无 .so 时用）。 */
    private object MemStore {
        private val items = mutableListOf<SavedPhrase>()
        @Synchronized fun save(c: String, e: String): Boolean {
            items.removeAll { it.chinese == c && it.english == e }
            items.add(0, SavedPhrase(c, e, System.currentTimeMillis() / 1000))
            return true
        }
        @Synchronized fun list(): List<SavedPhrase> = items.toList()
        @Synchronized fun clear(): Int = items.size.also { items.clear() }
    }
}
