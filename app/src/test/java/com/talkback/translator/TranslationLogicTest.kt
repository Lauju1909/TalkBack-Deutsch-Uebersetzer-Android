package com.talkback.translator

import com.talkback.translator.engine.HistoryItem
import com.talkback.translator.engine.TranslationManager
import com.talkback.translator.service.TalkBackTranslationService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLogicTest {

    // =========================================================================
    // 1. Tests für isLikelyGerman (Zero-Defect Sprach-Klassifizierung)
    // =========================================================================

    @Test
    fun testShortEnglishPhrasesAreNeverClassifiedAsGerman() {
        // Frühere Fehlerfälle: "Come in", "Log in", "Sign in" etc.
        assertFalse("Come in must be recognized as English", TranslationManager.isLikelyGerman("Come in"))
        assertFalse("Log in must be recognized as English", TranslationManager.isLikelyGerman("Log in"))
        assertFalse("Sign in must be recognized as English", TranslationManager.isLikelyGerman("Sign in"))
        assertFalse("I am here must be recognized as English", TranslationManager.isLikelyGerman("I am here"))
        assertFalse("Never die must be recognized as English", TranslationManager.isLikelyGerman("Never die"))
        assertFalse("Nice hat must be recognized as English", TranslationManager.isLikelyGerman("Nice hat"))
        assertFalse("Drop by tonight must be recognized as English", TranslationManager.isLikelyGerman("Drop by tonight"))
        assertFalse("Check this out must be recognized as English", TranslationManager.isLikelyGerman("Check this out"))
    }

    @Test
    fun testCommonEnglishSentencesAreNotGerman() {
        assertFalse(TranslationManager.isLikelyGerman("Hello, how are you today?"))
        assertFalse(TranslationManager.isLikelyGerman("Can you please help me with this Discord channel?"))
        assertFalse(TranslationManager.isLikelyGerman("Good game everyone, see you tomorrow!"))
        assertFalse(TranslationManager.isLikelyGerman("Welcome to the community, please read the rules."))
        assertFalse(TranslationManager.isLikelyGerman("What is your favorite game?"))
        assertFalse(TranslationManager.isLikelyGerman("Let's go into voice chat!"))
        assertFalse(TranslationManager.isLikelyGerman("Who is streaming tonight?"))
    }

    @Test
    fun testRealGermanSentencesAreRecognizedAsGerman() {
        assertTrue(TranslationManager.isLikelyGerman("Hallo, wie geht es dir heute?"))
        assertTrue(TranslationManager.isLikelyGerman("Das ist ein wirklich schöner Tag."))
        assertTrue(TranslationManager.isLikelyGerman("Wir müssen jetzt losgehen."))
        assertTrue(TranslationManager.isLikelyGerman("Bitte die Einstellungen nicht ändern."))
        assertTrue(TranslationManager.isLikelyGerman("Zurück zur Startseite."))
        assertTrue(TranslationManager.isLikelyGerman("Ich habe eine neue Nachricht erhalten."))
    }

    @Test
    fun testGermanUmlauteAlwaysTriggerGerman() {
        assertTrue(TranslationManager.isLikelyGerman("Übertragung gestartet"))
        assertTrue(TranslationManager.isLikelyGerman("Schöne Grüße"))
        assertTrue(TranslationManager.isLikelyGerman("Für mich bitte"))
        assertTrue(TranslationManager.isLikelyGerman("Änderung gespeichert"))
    }

    @Test
    fun testEmptyOrBlankIsHandledSafely() {
        assertTrue(TranslationManager.isLikelyGerman(""))
        assertTrue(TranslationManager.isLikelyGerman("   "))
    }

    // =========================================================================
    // 2. Tests für cleanChatText (Discord-Filter)
    // =========================================================================

    @Test
    fun testDiscordTimestampRemoval() {
        val input1 = "Today at 14:30 Hello world"
        assertEquals("Hello world", TranslationManager.cleanChatText(input1))

        val input2 = "Yesterday at 9:15 PM How are you?"
        assertEquals("How are you?", TranslationManager.cleanChatText(input2))

        val input3 = "Heute um 18:00 Uhr Treffen im Sprachkanal"
        assertEquals("Treffen im Sprachkanal", TranslationManager.cleanChatText(input3))

        val input4 = "[15:45] What's up guys"
        assertEquals("What's up guys", TranslationManager.cleanChatText(input4))
    }

    @Test
    fun testDiscordMentionsAndReactionsRemoval() {
        val input1 = "<@!1234567890> can you help me?"
        assertEquals("can you help me?", TranslationManager.cleanChatText(input1))

        val input2 = "@everyone meeting starts now"
        assertEquals("meeting starts now", TranslationManager.cleanChatText(input2))

        val input3 = "@here voice channel is open"
        assertEquals("voice channel is open", TranslationManager.cleanChatText(input3))
    }

    @Test
    fun testDiscordMarkdownAndSpoilersRemoval() {
        val input1 = "> Quoted message from earlier"
        assertEquals("Quoted message from earlier", TranslationManager.cleanChatText(input1))

        val input2 = "Check out this ||secret plot|| in the movie"
        assertEquals("Check out this secret plot in the movie", TranslationManager.cleanChatText(input2))

        val input3 = "**Important** message with *italics* and `code`"
        assertEquals("Important message with italics and code", TranslationManager.cleanChatText(input3))
    }

    // =========================================================================
    // 3. Tests für expandSlangTerms (Internet- & Gamer-Slang)
    // =========================================================================

    @Test
    fun testGamerSlangExpansions() {
        assertEquals("I don't know to be honest", TranslationManager.expandSlangTerms("idk tbh"))
        assertEquals("be right back away from keyboard", TranslationManager.expandSlangTerms("brb afk"))
        assertEquals("good game no problem", TranslationManager.expandSlangTerms("gg np"))
        assertEquals("thank you so much", TranslationManager.expandSlangTerms("thx so much"))
        assertEquals("in my opinion this is great", TranslationManager.expandSlangTerms("imo this is great"))
        assertEquals("by the way check this out", TranslationManager.expandSlangTerms("btw check this out"))
        assertEquals("never mind I don't care", TranslationManager.expandSlangTerms("nvm idc"))
    }

    @Test
    fun testSlangWordBoundarySafety() {
        // "egg" enthält "gg", darf aber nicht ersetzt werden!
        assertEquals("eat an egg", TranslationManager.expandSlangTerms("eat an egg"))
        // "kidk" enthält "idk", darf nicht ersetzt werden!
        assertEquals("kidk", TranslationManager.expandSlangTerms("kidk"))
    }

    // =========================================================================
    // 4. Tests für HistoryItem
    // =========================================================================

    @Test
    fun testHistoryItemFormatting() {
        val item = HistoryItem("Hello world", "Hallo Welt", System.currentTimeMillis())
        assertEquals("Hello world", item.originalText)
        assertEquals("Hallo Welt", item.germanText)
        val formatted = item.formatTimestamp()
        assertNotNull(formatted)
        assertTrue(formatted.isNotBlank())
    }

    // =========================================================================
    // 5. Tests für assembleChatMessage (Discord-Einzelnachrichten-Auflösung)
    // =========================================================================

    @Test
    fun testAssembleDiscordMessageWithAuthorAndTimestampAndBody() {
        val pieces = listOf("JohnDoe", "Today at 14:30", "Can you help me with this quest?")
        val assembled = TalkBackTranslationService.assembleChatMessage(pieces)
        assertEquals("JohnDoe: Can you help me with this quest?", assembled)
    }

    @Test
    fun testAssembleDiscordMessageWithoutAuthor() {
        val pieces = listOf("14:30", "I will be there in 5 minutes")
        val assembled = TalkBackTranslationService.assembleChatMessage(pieces)
        assertEquals("I will be there in 5 minutes", assembled)
    }

    @Test
    fun testAssembleDiscordMessageWithMultiPartBody() {
        val pieces = listOf("Alex", "Yesterday at 9:00 PM", "Hey everyone,", "let's join voice!")
        val assembled = TalkBackTranslationService.assembleChatMessage(pieces)
        assertEquals("Alex: Hey everyone, let's join voice!", assembled)
    }

    @Test
    fun testAssembleDiscordMessageAvoidDuplicateAuthor() {
        val pieces = listOf("Laurin", "Laurin: Welcome to the server!")
        val assembled = TalkBackTranslationService.assembleChatMessage(pieces)
        assertEquals("Laurin: Welcome to the server!", assembled)
    }

    @Test
    fun testAssembleDiscordMessageWithMarkdownAndReactions() {
        val pieces = listOf("Gamer99", "Today at 12:00", "**Important announcement** ||spoilers||")
        val assembled = TalkBackTranslationService.assembleChatMessage(pieces)
        assertEquals("Gamer99: Important announcement spoilers", assembled)
    }

    @Test
    fun testAssembleDiscordMessagePureAuthorOnly() {
        val pieces = listOf("JohnDoe")
        val assembled = TalkBackTranslationService.assembleChatMessage(pieces)
        assertEquals("JohnDoe", assembled)
    }

    @Test
    fun testAssembleDiscordEmptyPieces() {
        val pieces = emptyList<String>()
        val assembled = TalkBackTranslationService.assembleChatMessage(pieces)
        assertEquals("", assembled)
    }
}
