package com.typesake.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无 .so 环境下（JVM 单测）走兜底路径；同时验证分隔串解析等纯逻辑。
 */
class TypesakeCoreTest {

    @Test
    fun delimiterParsing() {
        assertEquals(listOf("你好", "Hello"), TypesakeCore.splitDelim("你好\u001FHello"))
        assertTrue(TypesakeCore.splitDelim("").isEmpty())
        assertEquals(listOf("a"), TypesakeCore.splitDelim("a"))
    }

    @Test
    fun jsonFieldParsing() {
        assertEquals(3, TypesakeCore.intField("{\"ok\":true,\"saved\":3}", "saved"))
        assertEquals(0, TypesakeCore.intField("{}", "saved"))
    }

    @Test
    fun fallbackCandidatesWhenLibraryMissing() {
        assertFalse(TypesakeCore.available)
        assertEquals("你好", TypesakeCore.candidates("nihao").first())
        assertTrue(TypesakeCore.candidates("ni").contains("你"))
        assertTrue(TypesakeCore.candidates("zzz").isEmpty())
    }

    @Test
    fun analyzeProtocolParsing() {
        val corrected = TypesakeCore.parseMatch("1\u001Enihao\u001E你好\u001F你号", "nihap")
        assertTrue(corrected.corrected)
        assertFalse(corrected.remembered)
        assertEquals("nihao", corrected.matched)
        assertEquals(listOf("你好", "你号"), corrected.candidates)

        val remembered = TypesakeCore.parseMatch("2\u001Enihap\u001E你好", "nihap")
        assertTrue(remembered.remembered)
        assertTrue(remembered.corrected)

        val direct = TypesakeCore.parseMatch("0\u001Enihao\u001E你好", "nihao")
        assertFalse(direct.corrected)
        assertEquals("nihao", direct.matched)
    }

    @Test
    fun analyzeWithoutLibraryFallsBack() {
        val m = TypesakeCore.analyze("nihao")
        assertFalse(m.corrected)
        assertEquals("你好", m.candidates.first())
        assertEquals("nihao", m.matched)
    }

    @Test
    fun statsJsonFieldParsing() {
        val raw = "{\"ok\":true,\"words\":42,\"days\":[\"2026-09-19\",\"2026-09-20\"],\"saved\":2,\"fuzzy\":true,\"correction\":false}"
        assertEquals(42, TypesakeCore.intField(raw, "words"))
        assertEquals(listOf("2026-09-19", "2026-09-20"), TypesakeCore.stringArrayField(raw, "days"))
        assertTrue(TypesakeCore.boolField(raw, "fuzzy"))
        assertFalse(TypesakeCore.boolField(raw, "correction"))
    }

    @Test
    fun statsFallbackIsEmpty() {
        val s = TypesakeCore.stats()
        assertEquals(0L, s.words)
        assertTrue(s.days.isEmpty())
    }

    @Test
    fun fallbackSuggestAndExplain() {
        assertEquals("Thank you!", TypesakeCore.suggest("谢谢"))
        assertEquals("", TypesakeCore.suggest("完全未知的句子"))
        assertTrue(TypesakeCore.explain("What's your name?").isNotEmpty())
    }

    @Test
    fun memoryStoreRoundTrip() {
        assertTrue(TypesakeCore.save("你好", "Hello!"))
        assertTrue(TypesakeCore.save("你好", "Hello!"))
        assertEquals(1, TypesakeCore.list().size)
        assertTrue(TypesakeCore.save("再见", "Goodbye! / See you!"))
        assertEquals(2, TypesakeCore.list().size)
        assertEquals(2, TypesakeCore.clear())
        assertTrue(TypesakeCore.list().isEmpty())
    }

    @Test
    fun saveRejectsBlankChinese() {
        assertFalse(TypesakeCore.save("   ", "Hello"))
    }
}
