package com.typesake.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextUtilsTest {

    @Test
    fun takesTextAfterLastTerminator() {
        assertEquals("你好", TextUtils.sentenceBefore("今天天气不错。你好"))
        assertEquals("How are you", TextUtils.sentenceBefore("Hello! How are you"))
        assertEquals("第二句", TextUtils.sentenceBefore("第一句？第二句"))
        assertEquals("新行", TextUtils.sentenceBefore("旧的\n新行"))
    }

    @Test
    fun trimsAndHandlesEmpty() {
        assertEquals("", TextUtils.sentenceBefore(""))
        assertEquals("", TextUtils.sentenceBefore("   "))
        assertEquals("你好", TextUtils.sentenceBefore("  你好  "))
        assertEquals("", TextUtils.sentenceBefore("结束。"))
        assertTrue(TextUtils.sentenceBefore("no terminator here").isNotEmpty())
    }

    @Test
    fun truncatesLongSentencesFromTheLeft() {
        val long = "a".repeat(400)
        val out = TextUtils.sentenceBefore(long, max = 120)
        assertEquals(120, out.length)
        assertEquals(long.takeLast(120), out)
    }

    @Test
    fun deleteWordLengthStopsAtBoundaries() {
        assertEquals(0, TextUtils.deleteWordLength(""))
        assertEquals(2, TextUtils.deleteWordLength("你好"))
        assertEquals(3, TextUtils.deleteWordLength("hello world"))
        assertEquals(2, TextUtils.deleteWordLength("你好，"))
        assertEquals(3, TextUtils.deleteWordLength("hello   "))
    }

    @Test
    fun smartPunctuationFollowsContext() {
        assertEquals(".", TextUtils.smartPunctuation('.', '3'))
        assertEquals(",", TextUtils.smartPunctuation(',', 'a'))
        assertEquals("。", TextUtils.smartPunctuation('.', '好'))
        assertEquals("，", TextUtils.smartPunctuation(',', null))
        assertEquals("？", TextUtils.smartPunctuation('?', '好'))
        assertEquals("？", TextUtils.smartPunctuation('?', null))
    }

    @Test
    fun pairsAndQuotes() {
        assertEquals("(" to ")", TextUtils.insertPairFor('('))
        assertEquals("《" to "》", TextUtils.insertPairFor('《'))
        assertEquals("\u201C" to "\u201D", TextUtils.insertPairFor('"'))
        assertNull(TextUtils.insertPairFor('a'))
        assertTrue(TextUtils.isPairClose(')'))
        assertTrue(TextUtils.isPairClose('》'))
    }

    @Test
    fun digitDecorations() {
        assertEquals(emptyList<String>(), TextUtils.digitCandidates(""))
        assertEquals(emptyList<String>(), TextUtils.digitCandidates("12ab"))
        val phone = TextUtils.digitCandidates("13812345678")
        assertTrue(phone.any { it == "138 1234 5678" })
        val date = TextUtils.digitCandidates("20260920")
        assertTrue(date.any { it == "2026年9月20日" })
        val time = TextUtils.digitCandidates("0930")
        assertTrue(time.any { it == "09:30" })
        assertEquals(emptyList<String>(), TextUtils.digitCandidates("9999"))
        assertEquals(emptyList<String>(), TextUtils.digitCandidates("20261340"))
    }

    @Test
    fun streakCountsConsecutiveDays() {
        assertEquals(0, TextUtils.streak(emptyList(), "2026-09-20"))
        assertEquals(
            3,
            TextUtils.streak(listOf("2026-09-18", "2026-09-19", "2026-09-20"), "2026-09-20"),
        )
        // 今天还没输入：从昨天往前算
        assertEquals(
            2,
            TextUtils.streak(listOf("2026-09-18", "2026-09-19"), "2026-09-20"),
        )
        // 断档
        assertEquals(
            1,
            TextUtils.streak(listOf("2026-09-10", "2026-09-20"), "2026-09-20"),
        )
        assertEquals(0, TextUtils.streak(listOf("not-a-date"), "2026-09-20"))
    }
}
