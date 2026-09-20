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
