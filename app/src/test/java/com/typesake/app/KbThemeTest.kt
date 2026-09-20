package com.typesake.app

import com.typesake.app.kb.KbPalette
import com.typesake.app.kb.KbThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KbThemeTest {

    @Test
    fun followsSystemWhenModeIsSystem() {
        assertEquals(
            KbThemes.resolve(KbPalette.GREEN, KbThemes.THEME_DARK, false),
            KbThemes.resolve(KbPalette.GREEN, KbThemes.THEME_SYSTEM, true),
        )
        assertEquals(
            KbThemes.resolve(KbPalette.GREEN, KbThemes.THEME_LIGHT, true),
            KbThemes.resolve(KbPalette.GREEN, KbThemes.THEME_SYSTEM, false),
        )
    }

    @Test
    fun explicitModeOverridesSystem() {
        assertNotEquals(
            KbThemes.resolve(KbPalette.ROSE, KbThemes.THEME_LIGHT, true),
            KbThemes.resolve(KbPalette.ROSE, KbThemes.THEME_DARK, true),
        )
    }

    @Test
    fun nightDetection() {
        // UI_MODE_NIGHT_YES = 0x20, UI_MODE_NIGHT_NO = 0x10, TYPE_NORMAL = 0x01
        assertTrue(KbThemes.isNight(0x21))
        assertFalse(KbThemes.isNight(0x11))
    }

    @Test
    fun palettesAreVisuallyDistinct() {
        val accents = KbPalette.entries.map { KbThemes.accentOf(it, night = false) }
        assertEquals(accents.size, accents.toSet().size)
        val darkAccents = KbPalette.entries.map { KbThemes.accentOf(it, night = true) }
        assertEquals(darkAccents.size, darkAccents.toSet().size)
    }

    @Test
    fun paletteLookupIsBoundsSafe() {
        assertEquals(KbPalette.GREEN, KbThemes.paletteOf(0))
        assertEquals(KbPalette.OCEAN, KbThemes.paletteOf(3))
        assertEquals(KbPalette.GREEN, KbThemes.paletteOf(99))
        assertEquals(KbPalette.GREEN, KbThemes.paletteOf(-1))
    }
}
