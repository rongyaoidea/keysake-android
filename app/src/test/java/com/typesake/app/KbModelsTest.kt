package com.typesake.app

import com.typesake.app.kb.KbAction
import com.typesake.app.kb.KbKind
import com.typesake.app.kb.KbLayer
import com.typesake.app.kb.KbLayouts
import com.typesake.app.kb.OneHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KbModelsTest {

    @Test
    fun lettersLayerHasFullAlphabetAndNumberRow() {
        val rows = KbLayouts.lettersRows(shifted = false, capsLock = false)
        val labels = rows.flatMap { row -> row.keys.map { it.label } }
        for (c in 'a'..'z') {
            assertTrue("missing letter $c", labels.contains(c.toString()))
        }
        assertEquals(10, rows[0].keys.size)
        assertEquals(
            listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
            rows[0].keys.map { it.label },
        )
        assertTrue(labels.contains("空格"))
        assertTrue(labels.contains("⏎"))
        assertTrue(labels.contains("⌫"))
        assertTrue(labels.contains("🌐"))
    }

    @Test
    fun shiftAndCapsProduceUppercase() {
        val lower = KbLayouts.lettersRows(false, false).flatMap { it.keys }.map { it.label }
        val shift = KbLayouts.lettersRows(true, false).flatMap { it.keys }.map { it.label }
        val caps = KbLayouts.lettersRows(false, true).flatMap { it.keys }.map { it.label }
        assertTrue(lower.contains("a"))
        assertTrue(shift.contains("A"))
        assertFalse(shift.contains("a"))
        assertTrue(caps.contains("Z"))
    }

    @Test
    fun numberAndPhoneKindsHaveOwnLayouts() {
        assertEquals(5, KbLayouts.numberRows().size)
        assertEquals(5, KbLayouts.phoneRows().size)
        val numbers = KbLayouts.numberRows().flatMap { it.keys }.map { it.label }
        for (d in '0'..'9') assertTrue(numbers.contains(d.toString()))
        val phone = KbLayouts.phoneRows().flatMap { it.keys }.map { it.label }
        assertTrue(phone.contains("*"))
        assertTrue(phone.contains("#"))
    }

    @Test
    fun symbolLayerOffersEmojiClipboardAndOneHand() {
        val labels = KbLayouts.symbolRows().flatMap { it.keys }.map { it.label }
        assertTrue(labels.contains("ABC"))
        assertTrue(labels.contains("📋"))
        assertTrue(labels.contains("⇤"))
        assertTrue(labels.contains("←"))
        assertTrue(labels.contains("→"))
    }

    @Test
    fun rowsForKindRespectsLayer() {
        assertEquals(
            KbLayouts.numberRows().size,
            KbLayouts.rowsFor(KbKind.NUMBER, KbLayer.SYMBOLS, false, false).size,
        )
        val symbolLabels =
            KbLayouts.rowsFor(KbKind.QWERTY, KbLayer.SYMBOLS, false, false).flatMap { it.keys }.map { it.label }
        assertTrue(symbolLabels.contains("ABC"))
    }

    @Test
    fun inputTypeMapping() {
        assertEquals(KbKind.QWERTY, KbLayouts.kindForInputType(KbLayouts.TYPE_CLASS_TEXT))
        assertEquals(KbKind.NUMBER, KbLayouts.kindForInputType(KbLayouts.TYPE_CLASS_NUMBER))
        assertEquals(KbKind.PHONE, KbLayouts.kindForInputType(KbLayouts.TYPE_CLASS_PHONE))
        assertEquals(KbKind.NUMBER, KbLayouts.kindForInputType(KbLayouts.TYPE_CLASS_DATETIME))
        assertEquals(
            KbKind.RAW,
            KbLayouts.kindForInputType(
                KbLayouts.TYPE_CLASS_TEXT or KbLayouts.TYPE_TEXT_VARIATION_PASSWORD
            ),
        )
        assertEquals(
            KbKind.RAW,
            KbLayouts.kindForInputType(
                KbLayouts.TYPE_CLASS_NUMBER or KbLayouts.TYPE_NUMBER_VARIATION_PASSWORD
            ),
        )
        // 邮箱/普通文本仍走完整键盘
        assertEquals(
            KbKind.QWERTY,
            KbLayouts.kindForInputType(KbLayouts.TYPE_CLASS_TEXT or 0x20),
        )
    }

    @Test
    fun privateFieldsDisableLearning() {
        assertTrue(
            KbLayouts.learningDisabled(
                KbLayouts.TYPE_CLASS_TEXT or KbLayouts.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            )
        )
        assertFalse(KbLayouts.learningDisabled(KbLayouts.TYPE_CLASS_TEXT))
    }

    @Test
    fun alternatesAndEmoji() {
        assertTrue(KbLayouts.alternatesFor("a").isNotEmpty())
        assertTrue(KbLayouts.alternatesFor("A").isNotEmpty())
        assertTrue(KbLayouts.alternatesFor("z").isEmpty())
        assertTrue(KbLayouts.EMOJI.size >= 100)
        assertTrue(KbLayouts.EMOJI.all { it.isNotBlank() })
    }

    @Test
    fun oneHandCycles() {
        assertEquals(OneHand.LEFT, OneHand.NONE.next())
        assertEquals(OneHand.RIGHT, OneHand.LEFT.next())
        assertEquals(OneHand.NONE, OneHand.RIGHT.next())
        assertEquals(OneHand.RIGHT, OneHand.fromInt(2))
        assertEquals(OneHand.NONE, OneHand.fromInt(99))
    }

    @Test
    fun spaceAndBackspaceAreActions() {
        val keys = KbLayouts.lettersRows(false, false).flatMap { it.keys }
        val space = keys.first { it.label == "空格" }
        assertTrue(space.action is KbAction.Space)
        val backspace = keys.first { it.label == "⌫" }
        assertTrue(backspace.action is KbAction.Backspace)
    }
}
