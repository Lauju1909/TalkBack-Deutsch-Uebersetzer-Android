package com.talkback.translator.engine

import android.content.Context
import android.util.Log
import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class TranslationManager(private val context: Context) {

    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()
    private val translatorPool = ConcurrentHashMap<String, Translator>()
    private val translationCache = LruCache<String, String>(2000)

    var isModelReady: Boolean = false
        private set

    init {
        preloadEnglishGermanModel()
    }

    fun preloadEnglishGermanModel(onComplete: ((Boolean) -> Unit)? = null) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val translator = getOrCreateTranslator(TranslateLanguage.ENGLISH)
                val conditions = DownloadConditions.Builder().build()
                translator.downloadModelIfNeeded(conditions).await()
                isModelReady = true
                Log.i("TranslationManager", "ML Kit English-German translation model is downloaded and ready!")
                onComplete?.invoke(true)
            } catch (e: Exception) {
                Log.w("TranslationManager", "Preload model failed: ${e.message}")
                onComplete?.invoke(false)
            }
        }
    }

    fun cleanChatText(text: String): String = Companion.cleanChatText(text)

    fun expandSlangTerms(text: String): String = Companion.expandSlangTerms(text)

    suspend fun translateToGerman(
        text: String,
        useChatFilter: Boolean = false,
        useSlangExpansion: Boolean = false,
        preferNeuralOnline: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        var processedText = text.trim()
        if (processedText.isEmpty()) return@withContext ""

        if (useChatFilter) {
            processedText = cleanChatText(processedText)
        }
        if (useSlangExpansion) {
            processedText = expandSlangTerms(processedText)
        }
        val trimmed = processedText.trim()
        if (trimmed.isEmpty()) return@withContext ""

        // 1. Ist der Text bereits Deutsch?
        if (isLikelyGerman(trimmed)) {
            return@withContext trimmed
        }

        // 2. Cache-Lookup
        synchronized(translationCache) {
            val cached = translationCache.get(trimmed)
            if (cached != null) return@withContext cached
        }

        // 3. Wenn die Nachricht einen Autoren enthält ("JohnDoe: Message text"):
        // Übersetze nur den eigentlichen Inhalt, damit der Name niemals verfälscht wird!
        val authorPrefix = extractAuthorPrefix(trimmed)
        if (authorPrefix != null) {
            val (author, body) = authorPrefix
            val translatedBody = translateRawText(body, preferNeuralOnline)
            val fullResult = "$author: $translatedBody"
            synchronized(translationCache) {
                translationCache.put(trimmed, fullResult)
            }
            return@withContext fullResult
        }

        val result = translateRawText(trimmed, preferNeuralOnline)
        synchronized(translationCache) {
            translationCache.put(trimmed, result)
        }
        result
    }

    private suspend fun translateRawText(text: String, preferNeuralOnline: Boolean): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        // 1. Häufige Umgangssprache / Redewendungen direkt abgleichen (100% natürliches Deutsch)
        val idiomResult = getIdiomTranslation(trimmed)
        if (idiomResult != null) {
            return idiomResult
        }

        // 2. Quellsprache bestimmen
        val langCode = detectLanguage(trimmed)
        if (langCode.equals("de", ignoreCase = true)) {
            return trimmed
        }
        val sourceLangTag = TranslateLanguage.fromLanguageTag(langCode) ?: TranslateLanguage.ENGLISH

        // 3. Hohe Qualität bevorzugt (Google Neural Online Translate - natürlichstes Deutsch)
        if (preferNeuralOnline) {
            try {
                val onlineResult = translateOnline(trimmed)
                if (onlineResult.isNotBlank() && onlineResult != trimmed) {
                    return onlineResult
                }
            } catch (e: Exception) {
                Log.w("TranslationManager", "Online Neural translation failed: ${e.message}, falling back to ML Kit")
            }
        }

        // 4. ML Kit On-Device (Offline oder Fallback)
        try {
            val translator = getOrCreateTranslator(sourceLangTag)
            val conditions = DownloadConditions.Builder().build()
            translator.downloadModelIfNeeded(conditions).await()
            val result = translator.translate(trimmed).await()
            if (result.isNotBlank() && result != trimmed) {
                return result
            }
        } catch (e: Exception) {
            Log.w("TranslationManager", "ML Kit translation failed: ${e.message}, falling back...")
        }

        // 5. Falls Offline-Modus gewählt war, aber ML Kit fehlschlug: Online Notfall-Versuch
        if (!preferNeuralOnline) {
            try {
                val onlineResult = translateOnline(trimmed)
                if (onlineResult.isNotBlank() && onlineResult != trimmed) {
                    return onlineResult
                }
            } catch (_: Exception) {}
        }

        // 6. Lokales Wörterbuch
        return fallbackDictionary(trimmed)
    }

    private suspend fun detectLanguage(text: String): String {
        if (text.length < 4) return "en"
        return try {
            val code = languageIdentifier.identifyLanguage(text).await()
            if (code == "und") "en" else code
        } catch (_: Exception) {
            "en"
        }
    }

    fun isLikelyGerman(text: String): Boolean = Companion.isLikelyGerman(text)

    private fun translateOnline(text: String): String {
        val encoded = URLEncoder.encode(text, "UTF-8")
        val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=de&dt=t&q=$encoded"
        val url = URL(urlString)
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 3000
        conn.readTimeout = 3000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")

        if (conn.responseCode == 200) {
            val reader = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8))
            val sb = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                sb.append(line)
            }
            reader.close()

            val json = JSONArray(sb.toString())
            val sentences = json.getJSONArray(0)
            val resultBuilder = StringBuilder()
            for (i in 0 until sentences.length()) {
                val piece = sentences.getJSONArray(i).getString(0)
                resultBuilder.append(piece)
            }
            val result = resultBuilder.toString().trim()
            if (result.isNotBlank()) return result
        }
        return ""
    }

    private fun getOrCreateTranslator(sourceLanguage: String): Translator {
        return translatorPool.computeIfAbsent(sourceLanguage) { lang ->
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(lang)
                .setTargetLanguage(TranslateLanguage.GERMAN)
                .build()
            Translation.getClient(options)
        }
    }

    private fun fallbackDictionary(text: String): String {
        val dict = mapOf(
            "settings" to "Einstellungen",
            "home" to "Startseite",
            "back" to "Zurück",
            "next" to "Weiter",
            "search" to "Suchen",
            "cancel" to "Abbrechen",
            "ok" to "OK",
            "yes" to "Ja",
            "no" to "Nein",
            "delete" to "Löschen",
            "edit" to "Bearbeiten",
            "save" to "Speichern",
            "open" to "Öffnen",
            "close" to "Schließen",
            "share" to "Teilen",
            "notifications" to "Benachrichtigungen",
            "notification" to "Benachrichtigung",
            "account" to "Konto",
            "profile" to "Profil",
            "help" to "Hilfe",
            "double tap to activate" to "Doppeltippen zum Aktivieren",
            "tap to activate" to "Tippen zum Aktivieren",
            "double tap to open" to "Doppeltippen zum Öffnen",
            "button" to "Schaltfläche",
            "switch" to "Schalter",
            "on" to "An",
            "off" to "Aus",
            "selected" to "Ausgewählt",
            "not selected" to "Nicht ausgewählt",
            "checked" to "Aktiviert",
            "not checked" to "Nicht aktiviert",
            "more options" to "Weitere Optionen",
            "menu" to "Menü",
            "volume" to "Lautstärke",
            "install" to "Installieren",
            "update" to "Aktualisieren",
            "download" to "Herunterladen",
            "loading" to "Wird geladen",
            "error" to "Fehler",
            "network error" to "Netzwerkfehler",
            "try again" to "Erneut versuchen",
            "allow" to "Zulassen",
            "deny" to "Ablehnen",
            "done" to "Fertig",
            // Discord & Messaging
            "message" to "Nachricht",
            "messages" to "Nachrichten",
            "send" to "Senden",
            "reply" to "Antworten",
            "replied" to "Hat geantwortet",
            "react" to "Reagieren",
            "reactions" to "Reaktionen",
            "server" to "Server",
            "channel" to "Kanal",
            "channels" to "Kanäle",
            "general" to "Allgemein",
            "voice" to "Sprachkanal",
            "voice connected" to "Sprachkanal verbunden",
            "disconnect" to "Trennen",
            "mute" to "Stummschalten",
            "unmute" to "Stummschaltung aufheben",
            "deafen" to "Kopfhörer stummschalten",
            "undeafen" to "Kopfhörer-Stummschaltung aufheben",
            "members" to "Mitglieder",
            "online" to "Online",
            "offline" to "Offline",
            "friends" to "Freunde",
            "direct messages" to "Direktnachrichten",
            "pinned" to "Angepinnt",
            "pinned messages" to "Angepinnte Nachrichten",
            "search in conversation" to "In Unterhaltung suchen"
        )
        val lower = text.lowercase().trim()
        return dict[lower] ?: text
    }

    fun close() {
        languageIdentifier.close()
        translatorPool.values.forEach { it.close() }
        translatorPool.clear()
    }

    companion object {
        fun isChatMetadataOrTimestamp(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return true
            // Reiner Zeitstempel: z. B. "14:30", "14:30 Uhr", "2:30 PM", "Today at 14:30", "Yesterday at 9:00 PM", "Heute um 18:00 Uhr"
            val timestampRegex = Regex("""^(?i)(Today|Yesterday|Gestern|Heute)?\s*(at|um)?\s*\[?\d{1,2}:\d{2}(:\d{2})?(\s*(AM|PM))?(\s*Uhr)?\]?$""")
            if (trimmed.matches(timestampRegex)) return true

            // Reine Erwähnungen / Reaktionen: z. B. "<@!12345>", "@everyone", "@here", "👍 5"
            if (trimmed.matches(Regex("""^<@!?[0-9]+>$"""))) return true
            if (trimmed.matches(Regex("""^(?i)@(everyone|here)$"""))) return true
            if (trimmed.matches(Regex("""^[\p{So}\p{Sk}]\s*\d+$"""))) return true

            return false
        }

        fun cleanChatText(text: String): String {
            if (isChatMetadataOrTimestamp(text)) {
                return ""
            }

            var cleaned = text
            cleaned = cleaned.replace(Regex("""(?i)\b(Today|Yesterday|Gestern|Heute)\s+(at|um)\s+\d{1,2}:\d{2}(\s*(AM|PM))?(\s*Uhr)?"""), "")
            cleaned = cleaned.replace(Regex("""(?i)\b(Today|Yesterday|Gestern|Heute)\s+\d{1,2}:\d{2}(\s*(AM|PM))?"""), "")
            cleaned = cleaned.replace(Regex("""\[?\d{1,2}:\d{2}(:\d{2})?(\s*Uhr)?\]?"""), "")
            cleaned = cleaned.replace(Regex("""<@!?[0-9]+>"""), "")
            cleaned = cleaned.replace(Regex("""(?i)@(everyone|here)"""), "")
            cleaned = cleaned.replace(Regex("""[\p{So}\p{Sk}]\s*\d+"""), "")
            cleaned = cleaned.replace(Regex("""^>\s*"""), "")
            cleaned = cleaned.replace("||", "")
            cleaned = cleaned.replace(Regex("""\*{1,3}|_{1,3}|~{1,2}|`{1,3}"""), "")
            cleaned = cleaned.replace(Regex("""\s+"""), " ").trim()
            return cleaned
        }

        fun extractAuthorPrefix(text: String): Pair<String, String>? {
            val colonIndex = text.indexOf(": ")
            if (colonIndex <= 0) return null
            val authorPart = text.substring(0, colonIndex).trim()
            val bodyPart = text.substring(colonIndex + 2).trim()
            if (bodyPart.isBlank()) return null
            val words = authorPart.split(Regex("\\s+")).filter { it.isNotBlank() }
            if (words.size in 1..3 && authorPart.length < 30 && !authorPart.endsWith(".") && !isChatMetadataOrTimestamp(authorPart)) {
                return Pair(authorPart, bodyPart)
            }
            return null
        }

        fun getIdiomTranslation(text: String): String? {
            val lower = text.lowercase().trim().trimEnd('.', '!', '?', ',')
            val idioms = mapOf(
                "i'm down" to "Ich bin dabei",
                "im down" to "Ich bin dabei",
                "i am down" to "Ich bin dabei",
                "sounds good" to "Klingt gut",
                "sound good" to "Klingt gut",
                "no problem" to "Kein Problem",
                "no worries" to "Keine Sorge",
                "dont worry" to "Mach dir keine Sorgen",
                "don't worry" to "Mach dir keine Sorgen",
                "take care" to "Pass auf dich auf",
                "take it easy" to "Mach's gut",
                "hit me up" to "Meld dich bei mir",
                "let me know" to "Sag mir Bescheid",
                "see you soon" to "Bis bald",
                "see you later" to "Bis später",
                "see ya" to "Bis dann",
                "have fun" to "Viel Spaß",
                "good luck" to "Viel Glück",
                "whats up" to "Was geht",
                "what's up" to "Was geht",
                "my bad" to "Mein Fehler",
                "never mind" to "Macht nichts",
                "nvm" to "Macht nichts",
                "you're welcome" to "Gern geschehen",
                "you are welcome" to "Gern geschehen",
                "no big deal" to "Keine große Sache",
                "makes sense" to "Ergibt Sinn",
                "make sense" to "Ergibt Sinn",
                "keep it up" to "Weiter so",
                "way to go" to "Klasse gemacht",
                "good job" to "Gut gemacht",
                "well done" to "Gut gemacht",
                "catch you later" to "Bis später",
                "i feel you" to "Ich verstehe dich",
                "i know right" to "Ja, voll",
                "for real" to "Im Ernst",
                "as far as i know" to "Soweit ich weiß",
                "to be honest" to "Um ehrlich zu sein",
                "by the way" to "Übrigens",
                "at the moment" to "Im Moment",
                "right now" to "Gerade jetzt",
                "as soon as possible" to "So schnell wie möglich"
            )
            return idioms[lower]
        }

        fun expandSlangTerms(text: String): String {
            var res = text
            val slangMap = linkedMapOf(
                "idk" to "I don't know",
                "tbh" to "to be honest",
                "tbf" to "to be fair",
                "afk" to "away from keyboard",
                "brb" to "be right back",
                "omg" to "oh my god",
                "thx" to "thank you",
                "ty" to "thank you",
                "np" to "no problem",
                "gg" to "good game",
                "wp" to "well played",
                "ggwp" to "good game well played",
                "gl" to "good luck",
                "hf" to "have fun",
                "glhf" to "good luck have fun",
                "gj" to "good job",
                "wtf" to "what the heck",
                "wth" to "what the heck",
                "imo" to "in my opinion",
                "imho" to "in my humble opinion",
                "pls" to "please",
                "plz" to "please",
                "btw" to "by the way",
                "rn" to "right now",
                "dm" to "direct message",
                "pm" to "private message",
                "fyi" to "for your information",
                "asap" to "as soon as possible",
                "nvm" to "never mind",
                "idc" to "I don't care",
                "tldr" to "too long didn't read",
                "ngl" to "not gonna lie",
                "fr" to "for real",
                "ong" to "on god",
                "bruh" to "brother",
                "bro" to "brother",
                "lmao" to "laughing so much",
                "lmfao" to "laughing so much",
                "lol" to "laughing out loud",
                "rofl" to "rolling on the floor laughing",
                "wdym" to "what do you mean",
                "hmu" to "hit me up",
                "gtg" to "got to go",
                "g2g" to "got to go",
                "cya" to "see you",
                "gn" to "good night",
                "gm" to "good morning",
                "sup" to "what's up",
                "wbu" to "what about you",
                "hbu" to "how about you",
                "smh" to "shaking my head",
                "ofc" to "of course",
                "dw" to "don't worry",
                "ik" to "I know",
                "ikr" to "I know right",
                "ye" to "yes",
                "yea" to "yes",
                "yep" to "yes",
                "nah" to "no",
                "nope" to "no",
                "bc" to "because",
                "bcoz" to "because",
                "sry" to "sorry",
                "yw" to "you are welcome",
                "ez" to "easy",
                "op" to "overpowered",
                "dps" to "damage per second",
                "lfg" to "looking for group",
                "inv" to "invite",
                "dc" to "disconnected",
                "mb" to "my bad",
                "atm" to "at the moment",
                "eta" to "estimated time of arrival",
                "pov" to "point of view",
                "aka" to "also known as",
                "gonna" to "going to",
                "wanna" to "want to",
                "gotta" to "got to",
                "kinda" to "kind of",
                "dunno" to "don't know",
                "lemme" to "let me",
                "gimme" to "give me",
                "imma" to "I am going to",
                "tryna" to "trying to",
                "aint" to "is not",
                "ain't" to "is not",
                "u" to "you",
                "r" to "are",
                "ur" to "your"
            )
            for ((slang, expansion) in slangMap) {
                val regex = Regex("""(?i)\b$slang\b""")
                res = res.replace(regex, expansion)
            }
            return res
        }

        fun isLikelyGerman(text: String): Boolean {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return true

            // 1. Deutsche Umlaute kommen im Englischen niemals vor
            if (trimmed.any { it == 'ä' || it == 'ö' || it == 'ü' || it == 'ß' || it == 'Ä' || it == 'Ö' || it == 'Ü' }) {
                return true
            }

            val englishWords = setOf(
                "the", "and", "is", "it", "you", "that", "he", "was", "for", "on", "are", 
                "as", "with", "his", "they", "at", "be", "this", "have", "from", "or", 
                "one", "had", "by", "word", "but", "not", "what", "all", "were", "we", 
                "when", "your", "can", "said", "there", "use", "an", "each", "which", 
                "she", "do", "how", "their", "if", "will", "up", "other", "about", 
                "out", "many", "then", "them", "these", "so", "some", "her", "would", 
                "make", "like", "him", "into", "time", "has", "look", "two", "more", 
                "write", "go", "see", "number", "no", "way", "could", "people", "my", 
                "than", "first", "water", "been", "call", "who", "oil", "its", "now", 
                "find", "settings", "cancel", "delete", "edit", "save", "notifications", 
                "tap", "double", "activate", "checked", "switch", "button", "server", 
                "servers", "channel", "channels", "message", "messages", "reply", 
                "replied", "pinned", "mention", "mentioned", "reaction", "reactions", 
                "voice", "stream", "streaming", "status", "rules", "general", "online", 
                "offline", "member", "members", "join", "joined", "leave", "left", 
                "role", "roles", "bot", "bots", "chat", "muted", "deafened", "disconnect", 
                "connect", "invite", "friends", "nitro", "emoji", "emojis", "stickers", 
                "thread", "threads", "stage", "announcements", "welcome", "about", 
                "profile", "user", "users", "hello", "hi", "hey", "guys", "bro", 
                "game", "play", "playing", "tonight", "today", "yesterday", "tomorrow", 
                "thanks", "thank", "please", "yes", "cool", "in", "am", "die", "hat",
                "come", "log", "sign", "check", "drop", "tune", "get", "got", "just",
                "good", "bad", "new", "old", "great", "well", "back", "here", "why",
                "where", "who", "how", "what", "which", "whose", "whom", "never", "nice",
                "ever", "always"
            )

            val unambiguousGermanWords = setOf(
                "der", "das", "nicht", "oder", "aber", "wenn", "dann", "weil", 
                "auch", "schon", "jetzt", "heute", "gestern", "morgen", "hier", "dort", 
                "sehr", "viel", "mehr", "immer", "wieder", "etwas", "nichts", "alles", 
                "dieser", "diese", "dieses", "können", "müssen", "sollen", "wollen", 
                "haben", "hatte", "hatten", "habe", "hast", "hat", "werden", "wurde", "wurden", 
                "machen", "gehen", "geht", "ging", "sehen", "sieht", "sah", "wissen", "weiß",
                "bitte", "danke", "schaltfläche", "einstellungen", "abbrechen", "speichern", 
                "zurück", "nachricht", "benachrichtigung", "konto", "suchen", "einem", "einer", 
                "hallo", "wie", "dir", "mir", "ich", "du", "wir", "ihr", "sind", "wirklich", 
                "startseite", "guten", "tag", "abend", "erhalten", "neue", "neuen", "neues"
            )

            val tokens = trimmed.lowercase().split(Regex("[\\s\\p{Punct}]+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return true

            val englishCount = tokens.count { it in englishWords }
            val germanCount = tokens.count { it in unambiguousGermanWords }

            if (englishCount > 0 && englishCount >= germanCount) {
                return false
            }

            return germanCount >= 2 || (tokens.size <= 2 && germanCount >= 1 && englishCount == 0)
        }
    }
}