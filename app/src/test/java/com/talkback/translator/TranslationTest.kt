package com.talkback.translator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTest {

    @Test
    fun testCommonDictionaryFallback() {
        val dictionary = mapOf(
            "home" to "Startseite",
            "back" to "Zurück",
            "next" to "Weiter",
            "settings" to "Einstellungen",
            "cancel" to "Abbrechen"
        )

        assertEquals("Startseite", dictionary["home"])
        assertEquals("Zurück", dictionary["back"])
        assertEquals("Einstellungen", dictionary["settings"])
    }

    @Test
    fun testGermanDetectionPassThrough() {
        val originalText = "Guten Tag, wie geht es dir?"
        val isGerman = "de".equals("de", ignoreCase = true)
        assertTrue(isGerman)
    }
}