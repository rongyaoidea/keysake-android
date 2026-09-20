package com.typesake.app

import org.junit.Assert.assertEquals
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
        assertEquals("", TextUtils.sentenceBefore("end."))
    }

    @Test
    fun truncatesLongSentencesFromTheLeft() {
        val long = "a".repeat(400)
        val out = TextUtils.sentenceBefore(long, max = 120)
        assertEquals(120, out.length)
        assertEquals(long.takeLast(120), out)
    }

    @Test
    fun asciiPeriodActsAsTerminator() {
        assertEquals("This is a test", TextUtils.sentenceBefore("Hi. This is a test"))
        assertTrue(TextUtils.sentenceBefore("no terminator here").isNotEmpty())
    }
}
