package com.typesake.app

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * JNI 桥：优先调 Rust `libtypesake_core.so`，缺库时用 Kotlin 兜底。
 *
 * 协议：热路径返回分隔串（US='\u{1E}' 字段分隔，GS='\u{1F}' 列表分隔），
 * 冷路径（收藏/统计/初始化）返回 JSON。所有 JNI 在 Rust 侧都有 catch_unwind。
 */
object TypesakeCore {
    @Serializable
    data class SavedPhrase(
        val chinese: String = "",
        val english: String = "",
        val saved_at: Long = 0L,
    )

    /** 一次输入的分析结果。 */
    data class Match(
        val matched: String,
        val corrected: Boolean,
        val remembered: Boolean,
        val candidates: List<String>,
    )

    data class Stats(
        val words: Long = 0L,
        val days: List<String> = emptyList(),
        val saved: Int = 0,
        val lex: Long = 0L,
        val initials: Int = 0,
        val fuzzy: Boolean = true,
        val correction: Boolean = true,
    )

    private const val DELIM = '\u001F'
    private const val FIELD = '\u001E'
    private const val CACHE_MAX = 64

    private val json = Json { ignoreUnknownKeys = true }

    /** 访问序 LRU（不用 android.util.LruCache，便于 JVM 单测）。 */
    private val cache = object : LinkedHashMap<String, List<String>>(CACHE_MAX, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<String>>): Boolean =
            size > CACHE_MAX
    }

    var available: Boolean = false
        private set

    private var lexEntries: Long = 0L

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
    @JvmStatic private external fun analyzeInput(input: String): String
    @JvmStatic private external fun candidatesFor(input: String): String
    @JvmStatic private external fun pickCandidate(pinyin: String, word: String, corrected: Boolean): String
    @JvmStatic private external fun pinCandidate(pinyin: String, word: String): String
    @JvmStatic private external fun forgetCandidate(pinyin: String, word: String): String
    @JvmStatic private external fun predictNext(word: String): String
    @JvmStatic private external fun setEngineOptions(fuzzy: Boolean, correction: Boolean): String
    @JvmStatic private external fun bumpStats(today: String): String
    @JvmStatic private external fun statsInfo(): String
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

    internal fun longField(jsonish: String, key: String): Long =
        Regex("\"$key\":(\\d+)").find(jsonish)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

    internal fun boolField(jsonish: String, key: String): Boolean =
        Regex("\"$key\":(true|false)").find(jsonish)?.groupValues?.get(1) == "true"

    internal fun stringArrayField(jsonish: String, key: String): List<String> {
        val body = Regex("\"$key\":\\[(.*?)]").find(jsonish)?.groupValues?.get(1) ?: return emptyList()
        return Regex("\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
    }

    /** 解析 analyzeInput 的三段协议。 */
    internal fun parseMatch(raw: String, fallbackPinyin: String): Match {
        val parts = raw.split(FIELD)
        if (parts.size < 3) {
            return Match(fallbackPinyin, false, false, splitDelim(raw))
        }
        val flag = parts[0]
        return Match(
            matched = parts[1].ifEmpty { fallbackPinyin },
            corrected = flag == "1" || flag == "2",
            remembered = flag == "2",
            candidates = splitDelim(parts[2]),
        )
    }

    // ---- 公开 API ----

    /** 初始化存储目录，返回已载入收藏数。 */
    fun init(dir: String): Int {
        if (!available) return 0
        return try {
            val raw = initStorage(dir)
            lexEntries = longField(raw, "lex")
            intField(raw, "saved")
        } catch (_: Exception) {
            0
        }
    }

    fun lexiconEntries(): Long {
        if (!available) return 0L
        if (lexEntries > 0) return lexEntries
        lexEntries = try {
            lexiconSize().toLongOrNull() ?: 0L
        } catch (_: Exception) {
            0L
        }
        return lexEntries
    }

    fun cached(pinyin: String): List<String>? = synchronized(cache) { cache[pinyin] }

    /** 完整分析：候选 + 纠错信息（每键调用一次）。 */
    fun analyze(pinyin: String): Match {
        if (!available) {
            return Match(pinyin, false, false, fallbackCandidates(pinyin))
        }
        return runCatching { parseMatch(analyzeInput(pinyin), pinyin) }
            .getOrDefault(Match(pinyin, false, false, fallbackCandidates(pinyin)))
    }

    fun candidates(pinyin: String): List<String> =
        if (!available) fallbackCandidates(pinyin)
        else runCatching {
            val list = splitDelim(candidatesFor(pinyin))
            synchronized(cache) { cache[pinyin] = list }
            list
        }.getOrDefault(fallbackCandidates(pinyin))

    /** 上报选词（词频学习 + 纠错记忆），返回更新后的候选序。 */
    fun pick(pinyin: String, word: String, corrected: Boolean = false): List<String> {
        if (!available) return cached(pinyin) ?: fallbackCandidates(pinyin)
        return runCatching {
            val updated = splitDelim(pickCandidate(pinyin, word, corrected))
            synchronized(cache) { cache[pinyin] = updated }
            updated
        }.getOrDefault(emptyList())
    }

    /** 置顶（用户主动固定首位）。 */
    fun pin(pinyin: String, word: String): List<String> {
        if (!available) return cached(pinyin) ?: fallbackCandidates(pinyin)
        return runCatching {
            val updated = splitDelim(pinCandidate(pinyin, word))
            synchronized(cache) { cache[pinyin] = updated }
            updated
        }.getOrDefault(emptyList())
    }

    /** 删词：该拼音下不再推荐此词。 */
    fun forget(pinyin: String, word: String): List<String> {
        if (!available) return cached(pinyin) ?: fallbackCandidates(pinyin)
        return runCatching {
            val updated = splitDelim(forgetCandidate(pinyin, word))
            synchronized(cache) { cache[pinyin] = updated }
            updated
        }.getOrDefault(emptyList())
    }

    fun predict(word: String): List<String> =
        if (!available) emptyList()
        else runCatching { splitDelim(predictNext(word)) }.getOrDefault(emptyList())

    /** 写入模糊音/击键纠错开关（会落盘）。 */
    fun setOptions(fuzzy: Boolean, correction: Boolean) {
        if (!available) return
        runCatching { setEngineOptions(fuzzy, correction) }
        synchronized(cache) { cache.clear() }
    }

    /** 记一次上屏（词数 + 当天活跃）。 */
    fun bump(today: String) {
        if (!available) return
        runCatching { bumpStats(today) }
    }

    fun stats(): Stats {
        if (!available) {
            return Stats(saved = MemStore.list().size)
        }
        return runCatching {
            val raw = statsInfo()
            Stats(
                words = longField(raw, "words"),
                days = stringArrayField(raw, "days"),
                saved = intField(raw, "saved"),
                lex = longField(raw, "lex"),
                initials = intField(raw, "ini"),
                fuzzy = boolField(raw, "fuzzy"),
                correction = boolField(raw, "correction"),
            )
        }.getOrDefault(Stats(saved = MemStore.list().size))
    }

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

    // ---- 无 .so 时的最小兜底（演示/降级） ----
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
        if (k.length == 2 && k.all { it in "bcdfghjklmnpqrstvwxyz" }) {
            val expanded = k.map { ch ->
                pinMap.keys.firstOrNull { it.startsWith(ch) } ?: ""
            }
            val phrase = expanded.joinToString("")
            if (phrase.isNotEmpty()) {
                pinMap[phrase]?.let { return it }
            }
        }
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
