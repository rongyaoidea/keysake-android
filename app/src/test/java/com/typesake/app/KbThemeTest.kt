package com.typesake.app

import com.typesake.app.kb.KbThemes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KbThemeTest {

    @Test
    fun followsSystemWhenModeIsSystem() {
        assertEquals(KbThemes.dark, KbThemes.resolve(KbThemes.THEME_SYSTEM, night = true))
        assertEquals(KbThemes.light, KbThemes.resolve(KbThemes.THEME_SYSTEM, night = false))
    }

    @Test
    fun explicitModeOverridesSystem() {
        assertEquals(KbThemes.light, KbThemes.resolve(KbThemes.THEME_LIGHT, night = true))
        assertEquals(KbThemes.dark, KbThemes.resolve(KbThemes.THEME_DARK, night = false))
    }

    @Test
    fun nightDetection() {
        // UI_MODE_NIGHT_YES = 0x20, UI_MODE_NIGHT_NO = 0x10, TYPE_NORMAL = 0x01
        assertTrue(KbThemes.isNight(0x21))
        assertFalse(KbThemes.isNight(0x11))
    }

    @Test
    fun themesAreVisuallyDistinct() {
        assertTrue(KbThemes.light.key != KbThemes.dark.key)
        assertTrue(KbThemes.light.barBg != KbThemes.dark.barBg)
        assertTrue(KbThemes.light.keyText != KbThemes.dark.keyText)
    }
}
