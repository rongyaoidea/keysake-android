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
            KbThemes.resolve(KbPalette.CORAL, KbThemes.THEME_DARK, false),
            KbThemes.resolve(KbPalette.CORAL, KbThemes.THEME_SYSTEM, true),
        )
        assertEquals(
            KbThemes.resolve(KbPalette.CORAL, KbThemes.THEME_LIGHT, true),
            KbThemes.resolve(KbPalette.CORAL, KbThemes.THEME_SYSTEM, false),
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
    fun defaultPaletteIsCoralGlass() {
        assertEquals(KbPalette.CORAL, KbThemes.paletteOf(0))
        assertEquals(KbPalette.CORAL, KbThemes.paletteOf(99))
        val light = KbThemes.resolve(KbPalette.CORAL, KbThemes.THEME_LIGHT, false)
        assertTrue("default skin must be glass", light.glass)
        assertNotEquals(0, light.glassBorder)
        assertEquals(KbThemes.CORAL, light.accent)
        val dark = KbThemes.resolve(KbPalette.CORAL, KbThemes.THEME_DARK, true)
        assertTrue(dark.glass)
        assertEquals(KbThemes.CORAL, dark.accent)
    }

    @Test
    fun nonGlassPalettesStayFlat() {
        for (p in listOf(KbPalette.GREEN, KbPalette.SUNSET, KbPalette.ROSE, KbPalette.OCEAN)) {
            assertFalse(p.name, KbThemes.resolve(p, KbThemes.THEME_LIGHT, false).glass)
        }
    }

    @Test
    fun paletteLookupIsBoundsSafe() {
        assertEquals(KbPalette.CORAL, KbThemes.paletteOf(0))
        assertEquals(KbPalette.OCEAN, KbThemes.paletteOf(4))
        assertEquals(KbPalette.CORAL, KbThemes.paletteOf(-1))
    }
}
