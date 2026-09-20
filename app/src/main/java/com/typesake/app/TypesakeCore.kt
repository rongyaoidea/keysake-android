package com.typesake.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JNI 桥：优先调 Rust `libtypesake_core.so`，缺库时用 Kotlin 兜底，保证可装可用。
 *
 * 热路径约定：候选用 `\u{1F}` 分隔串返回（避免每键 JSON 解析），并带 LRU 缓存；
 * 收藏/初始化等冷路径仍用 JSON。所有 JNI 调用在 Rust 侧都有 catch_unwind。
 */
object TypesakeCore {
    @Serializable
    data class SavedPhrase(
        val chinese: String = "",
        val english: String = "",
        val saved_at: Long = 0L,
    )

    private const val DELIM = '\u001F'
    private const val CACHE_MAX = 64

    private val json = Json { ignoreUnknownKeys = true }

    /** 访问序 LRU（不用 android.util.LruCache，便于 JVM 单测）。 */
    private val cache = object : LinkedHashMap<String, List<String>>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
            size > CACHE_MAX
    }

    var available: Boolean = false
        private set

    private var lexicon: Long = 0L

    init {
        available = try {
            System.loadLibrary("typesake_core")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    // ---- JNI（Rust #[no_mangle] 实现） ----
    @JvmStatic private external fun initStorage(dir: String): String
    @JvmStatic private external fun lexiconSize(): String
    @JvmStatic private external fun candidatesFor(pinyin: String): String
    @JvmStatic private external fun pickCandidate(pinyin: String, word: String): String
    @JvmStatic private external fun predictNext(word: String): String
    @JvmStatic private external fun suggestEnglish(chinese: String): String
    @JvmStatic private external fun englishCandidates(chinese: String): String
    @JvmStatic private external fun grammarExplain(english: String): String
    @JvmStatic private external fun savePhrase(chinese: String, english: String): String
    @JvmStatic private external fun listSaved(): String
    @JvmStatic private external fun clearSaved(): String

    // ---- 解析工具（internal 供单测） ----

    internal fun splitDelim(s: String): List<String> =
        if (s.isEmpty()) emptyList() else s.split(DELIM).filter { it.isNotEmpty() }

    internal fun intField(jsonish: String, key: String): Int =
        Regex("\"$key\":(\\d+)").find(jsonish)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // ---- 公开 API ----

    /** 初始化存储目录，返回已载入收藏数。 */
    fun init(dir: String): Int {
        if (!available) return 0
        return try {
            val raw = initStorage(dir)
            lexicon = intField(raw, "lex").toLong()
            intField(raw, "saved")
        } catch (_: Exception) {
            0
        }
    }

    /** 词库条目数（0 表示引擎未加载）。 */
    fun lexiconEntries(): Long {
        if (!available) return 0L
        if (lexicon > 0) return lexicon
        lexicon = try {
            lexiconSize().toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
        return lexicon
    }

    /** 同步缓存命中（用于即时渲染），未命中返回 null。 */
    fun cached(pinyin: String): List<String>? = synchronized(cache) { cache[pinyin] }

    fun candidates(pinyin: String): List<String> =
        if (!available) fallbackCandidates(pinyin)
        else runCatching {
            val list = splitDelim(candidatesFor(pinyin))
            synchronized(cache) { cache[pinyin] = list }
            list
        }.getOrDefault(fallbackCandidates(pinyin))

    /** 上报选词（词频学习），返回更新后的候选序。 */
    fun pick(pinyin: String, word: String): List<String> {
        if (!available) return cached(pinyin) ?: fallbackCandidates(pinyin)
        return runCatching {
            val updated = splitDelim(pickCandidate(pinyin, word))
            synchronized(cache) { cache[pinyin] = updated }
            updated
        }.getOrDefault(emptyList())
    }

    /** 联想：上一个词的后续词预测。 */
    fun predict(word: String): List<String> =
        if (!available) emptyList()
        else runCatching { splitDelim(predictNext(word)) }.getOrDefault(emptyList())

    fun suggest(chinese: String): String =
        if (!available) fallbackSuggest(chinese)
        else runCatching { suggestEnglish(chinese) }.getOrDefault(fallbackSuggest(chinese))

    fun englishList(chinese: String): List<String> =
        if (!available) fallbackSuggest(chinese).takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
        else runCatching { splitDelim(englishCandidates(chinese)) }.getOrDefault(emptyList())

    fun explain(english: String): String =
        if (!available) fallbackExplain(english)
        else runCatching { grammarExplain(english) }.getOrDefault(fallbackExplain(english))

    /** 收藏（中文必填，英文可留空待补）。 */
    fun save(chinese: String, english: String): Boolean {
        if (chinese.isBlank()) return false
        if (!available) return MemStore.save(chinese.trim(), english.trim())
        return runCatching { savePhrase(chinese, english); true }.getOrDefault(false)
    }

    fun list(): List<SavedPhrase> =
        if (!available) MemStore.list()
        else runCatching { json.decodeFromString<List<SavedPhrase>>(listSaved()) }
            .getOrDefault(MemStore.list())

    fun clear(): Int =
        if (!available) MemStore.clear()
        else runCatching { intField(clearSaved(), "cleared") }.getOrDefault(MemStore.clear())

    // ---- 无 .so 时的最小兜底（演示/降级，不影响生产路径） ----
    private val pinMap = mapOf(
        "nihao" to listOf("你好"), "ni" to listOf("你", "泥", "尼", "呢"),
        "hao" to listOf("好", "号"), "xiexie" to listOf("谢谢"),
        "zaijian" to listOf("再见"), "zhongguo" to listOf("中国"),
        "yingyu" to listOf("英语"), "gongzuo" to listOf("工作"),
        "huiyi" to listOf("会议"), "pengyou" to listOf("朋友"),
        "jintian" to listOf("今天"), "mingtian" to listOf("明天"),
    )
    private val enMap = mapOf(
        "你好" to "Hello!", "谢谢" to "Thank you!", "再见" to "Goodbye! / See you!",
        "中国" to "China", "英语" to "English", "工作" to "work / job",
        "会议" to "meeting", "朋友" to "friend", "学习英语" to "Learn English",
        "今天" to "today", "明天" to "tomorrow",
    )

    private fun fallbackCandidates(p: String): List<String> {
        val k = p.lowercase().replace(" ", "")
        if (k.isEmpty()) return emptyList()
        pinMap[k]?.let { return it }
        return pinMap.entries.firstOrNull { it.key.startsWith(k) || k.startsWith(it.key) }?.value
            ?: emptyList()
    }

    private fun fallbackSuggest(c: String): String =
        enMap[c.trim()] ?: enMap.entries.firstOrNull { c.contains(it.key) }?.value ?: ""

    private fun fallbackExplain(e: String): String =
        "句子：$e\n句式：${if (e.trimEnd().endsWith("?")) "疑问句" else "陈述句"}\n提示：接上 Rust .so 后可看完整时态与成分分析。"

    /** 进程内兜底收藏（仅无 .so 时用）。 */
    private object MemStore {
        private val items = mutableListOf<SavedPhrase>()

        @Synchronized
        fun save(c: String, e: String): Boolean {
            items.removeAll { it.chinese == c && it.english == e }
            items.add(0, SavedPhrase(c, e, System.currentTimeMillis() / 1000))
            return true
        }

        @Synchronized
        fun list(): List<SavedPhrase> = items.toList()

        @Synchronized
        fun clear(): Int = items.size.also { items.clear() }
    }
}
