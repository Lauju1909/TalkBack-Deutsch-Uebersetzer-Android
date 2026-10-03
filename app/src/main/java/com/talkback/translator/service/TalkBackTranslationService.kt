package com.talkback.translator.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityGestureEvent
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.talkback.translator.TalkBackTranslatorApp
import com.talkback.translator.engine.SpeechManager
import com.talkback.translator.engine.TranslationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TalkBackTranslationService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var speechManager: SpeechManager
    private lateinit var translationManager: TranslationManager
    private var isTranslating = false
    private var lastSpokenOriginalText: String = ""
    var lastSpokenGermanText: String = ""
        private set

    private val SENSITIVE_BANKING_PACKAGES = setOf(
        "de.sparkasse",
        "com.starfinanz",
        "de.dkb",
        "de.postbank",
        "de.commerzbanking",
        "com.ing",
        "de.consorsbank",
        "com.db.pbc",
        "de.vrnetworld",
        "de.fimatex",
        "com.n26",
        "de.santander",
        "com.paypal.android"
    )

    fun isBankingApp(packageName: CharSequence?): Boolean {
        if (packageName == null) return false
        val pkg = packageName.toString().lowercase()
        return SENSITIVE_BANKING_PACKAGES.any { pkg.startsWith(it) || pkg.contains(it) }
    }

    fun disableService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            disableSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val app = application as TalkBackTranslatorApp
        speechManager = app.speechManager
        translationManager = app.translationManager
    }

    private var accessibilityButtonCallback: AccessibilityButtonController.AccessibilityButtonCallback? = null
    private var clipboardManager: ClipboardManager? = null
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null
    private var lastClipText: String = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "TalkBackTranslationService connected")

        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                    AccessibilityServiceInfo.FLAG_REQUEST_MULTI_FINGER_GESTURES or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_ACCESSIBILITY_BUTTON
            }
            serviceInfo = info
        } catch (e: Exception) {
            Log.w(TAG, "Error updating serviceInfo: ${e.message}")
        }

        setupClipboardListener()

        // Barrierefreiheits-Taste in der Navigationsleiste registrieren (falls vorhanden)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val buttonController = accessibilityButtonController
                accessibilityButtonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
                    override fun onClicked(controller: AccessibilityButtonController?) {
                        vibrateStart()
                        speechManager.stop()
                        translateCurrentTalkBackElementOrScreen()
                    }
                }
                buttonController.registerAccessibilityButtonCallback(accessibilityButtonCallback!!)
            } catch (e: Exception) {
                Log.w(TAG, "AccessibilityButtonController register error: ${e.message}")
            }
        }
    }

    fun setupClipboardListener() {
        try {
            val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("pref_clipboard_translator_enabled", false)
            if (clipboardManager == null) {
                clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            }

            if (enabled) {
                if (clipboardListener == null) {
                    clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
                        handleClipboardChanged()
                    }
                    clipboardManager?.addPrimaryClipChangedListener(clipboardListener)
                }
            } else {
                clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
                clipboardListener = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "setupClipboardListener error: ${e.message}")
        }
    }

    private fun handleClipboardChanged() {
        val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pref_clipboard_translator_enabled", false)) return

        try {
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0)?.text?.toString()?.trim() ?: ""
                if (text.isNotBlank() && text != lastClipText && text.length > 2) {
                    lastClipText = text
                    serviceScope.launch {
                        if (!translationManager.isLikelyGerman(text)) {
                            vibrateStart()
                            val useChat = prefs.getBoolean("pref_chat_filter_enabled", true)
                            val useSlang = prefs.getBoolean("pref_slang_translator_enabled", true)
                            val speakOriginal = prefs.getBoolean("pref_speak_original_first", false)
                            val preferNeural = prefs.getString("pref_translation_mode", "neural_online") != "offline_only"

                            val german = translationManager.translateToGerman(text, useChat, useSlang, preferNeural)
                            if (german.isNotBlank() && german != text) {
                                vibrateSuccess()
                                lastSpokenOriginalText = text
                                lastSpokenGermanText = german
                                (application as TalkBackTranslatorApp).historyManager.addTranslation(text, german)

                                val toSpeak = if (speakOriginal) {
                                    "Zwischenablage Original: $text. Auf Deutsch: $german"
                                } else {
                                    "Kopierter Text auf Deutsch: $german"
                                }
                                speechManager.speak(toSpeak, flush = true)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard changed error: ${e.message}")
        }
    }

    fun repeatLastTranslation() {
        val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        val speakOriginalFirst = prefs.getBoolean("pref_speak_original_first", false)

        if (lastSpokenGermanText.isBlank()) {
            vibrateNotFound()
            speechManager.speak("Noch keine Übersetzung zum Wiederholen vorhanden.", flush = true)
            return
        }

        vibrateStart()
        speechManager.stop()

        val textToSpeak = if (speakOriginalFirst && lastSpokenOriginalText.isNotBlank()) {
            "Original: $lastSpokenOriginalText. Auf Deutsch: $lastSpokenGermanText"
        } else {
            lastSpokenGermanText
        }
        speechManager.speak(textToSpeak, flush = true)
    }

    private fun handleGestureAction(gestureId: Int): Boolean {
        Log.d(TAG, "handleGestureAction: id = $gestureId")
        val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        val gestureEnabled = prefs.getBoolean("pref_two_finger_gesture", true)
        if (!gestureEnabled) return false

        val triggerSetting = prefs.getString("pref_trigger_gesture", "2_finger_double_tap") ?: "2_finger_double_tap"
        val repeatEnabled = prefs.getBoolean("pref_repeat_gesture_enabled", true)
        val stopEnabled = prefs.getBoolean("pref_stop_gesture_enabled", true)

        // 1. Sofort-Stopp: 2 Finger Einzeltipp (17)
        if (stopEnabled && (gestureId == GESTURE_2_FINGER_SINGLE_TAP || gestureId == 17)) {
            speechManager.stop()
            vibratePattern(longArrayOf(0, 30), intArrayOf(0, 120))
            return true
        }

        // 2. Wiederholen: 3 Finger Doppeltipp (23) oder 3 Finger Doppeltipp und halten (42)
        if (repeatEnabled && (gestureId == GESTURE_3_FINGER_DOUBLE_TAP || gestureId == 23 || gestureId == 42)) {
            repeatLastTranslation()
            return true
        }

        // 3. Übersetzung auslösen
        val isTrigger = when (triggerSetting) {
            "2_finger_tap_hold" -> gestureId == GESTURE_2_FINGER_DOUBLE_TAP_AND_HOLD || gestureId == 40
            "3_finger_double_tap" -> gestureId == GESTURE_3_FINGER_DOUBLE_TAP || gestureId == 23
            else -> gestureId == GESTURE_2_FINGER_DOUBLE_TAP || gestureId == 19 || gestureId == 40
        }

        if (isTrigger) {
            vibrateStart()
            speechManager.stop()
            translateCurrentTalkBackElementOrScreen()
            return true
        }

        return false
    }

    override fun onGesture(gestureEvent: AccessibilityGestureEvent): Boolean {
        if (handleGestureAction(gestureEvent.gestureId)) {
            return true
        }
        return super.onGesture(gestureEvent)
    }

    @Deprecated("Deprecated in Java")
    override fun onGesture(gestureId: Int): Boolean {
        if (handleGestureAction(gestureId)) {
            return true
        }
        return super.onGesture(gestureId)
    }

    private var lastLiveSpokenText: String = ""
    private var lastLiveSpokenTime: Long = 0L
    private var lastFocusedTalkBackText: String = ""
    private var lastFocusedTalkBackTime: Long = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // Banking-Apps (Sparkasse, S-pushTAN etc.) aus Sicherheitsgründen vollständig ignorieren
        if (isBankingApp(event.packageName)) {
            return
        }

        val eventType = event.eventType
        val isRelevantEvent = when (eventType) {
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_SELECTED,
            AccessibilityEvent.TYPE_ANNOUNCEMENT -> true
            else -> false
        }

        if (!isRelevantEvent) return

        val pkg = event.packageName?.toString()?.lowercase() ?: ""
        val isDiscord = pkg.contains("discord")

        // 1. Text direkt aus dem Barrierefreiheits-Event abrufen
        val eventTexts = event.text?.filter { !it.isNullOrBlank() }?.map { it.toString().trim() }
        val eventTextJoined = if (!eventTexts.isNullOrEmpty()) eventTexts.joinToString(" ") else ""
        val eventDesc = event.contentDescription?.toString()?.trim() ?: ""

        // 2. Aus Quellknoten extrahieren (bei Discord immer mit voller Nachrichten-Auflösung)
        var extractedFromSource = ""
        val source = try { event.source } catch (_: Exception) { null }
        if (source != null) {
            extractedFromSource = extractSpecificNodeText(source)
            source.recycle()
        }

        val bestText = when {
            extractedFromSource.isNotBlank() && (isDiscord || extractedFromSource.length > eventTextJoined.length) -> extractedFromSource
            eventDesc.length > eventTextJoined.length + 5 -> eventDesc
            eventTextJoined.isNotBlank() -> eventTextJoined
            eventDesc.isNotBlank() -> eventDesc
            else -> extractedFromSource
        }.trim()

        if (bestText.isBlank()) return

        // Zuletzt fokussierten Text mit Zeitstempel speichern (verhindert das Vermischen von 2 Nachrichten)
        lastFocusedTalkBackText = bestText
        lastFocusedTalkBackTime = System.currentTimeMillis()
        lastSpokenOriginalText = bestText
        Log.d(TAG, "Captured single focused message in ${event.packageName}: $bestText")

        val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        val autoLive = prefs.getBoolean("pref_auto_live_focus", false)

        if (autoLive) {
            val now = System.currentTimeMillis()
            // Verhindert doppeltes Vorlesen desselben Texts innerhalb von 1.5 Sekunden
            if (bestText == lastLiveSpokenText && (now - lastLiveSpokenTime) < 1500) {
                return
            }
            lastLiveSpokenText = bestText
            lastLiveSpokenTime = now

            serviceScope.launch {
                val isGerman = translationManager.isLikelyGerman(bestText)
                if (!isGerman) {
                    val useChat = prefs.getBoolean("pref_chat_filter_enabled", true)
                    val useSlang = prefs.getBoolean("pref_slang_translator_enabled", true)
                    val speakOriginalFirst = prefs.getBoolean("pref_speak_original_first", false)
                    val preferNeural = prefs.getString("pref_translation_mode", "neural_online") != "offline_only"

                    val german = translationManager.translateToGerman(bestText, useChat, useSlang, preferNeural)
                    if (german.isNotBlank() && german != bestText) {
                        vibrateSuccess()
                        lastSpokenOriginalText = bestText
                        lastSpokenGermanText = german
                        (application as TalkBackTranslatorApp).historyManager.addTranslation(bestText, german)

                        val toSpeak = if (speakOriginalFirst) {
                            "Original: $bestText. Auf Deutsch: $german"
                        } else {
                            german
                        }
                        speechManager.speak(toSpeak, flush = true)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {
        speechManager.stop()
    }

    fun translateCurrentTalkBackElementOrScreen() {
        if (isTranslating) return
        isTranslating = true

        speechManager.stop()

        serviceScope.launch {
            // Aus Sicherheitsgründen in Banking-Apps (Sparkasse etc.) niemals scannen
            val activeRoot = rootInActiveWindow
            if (activeRoot != null && isBankingApp(activeRoot.packageName)) {
                activeRoot.recycle()
                speechManager.speak("In Banking-Apps wird die Übersetzung aus Sicherheitsgründen nicht ausgeführt.")
                isTranslating = false
                return@launch
            }
            activeRoot?.recycle()

            var targetText = ""

            // 1. TalkBack Fokus-Element abfragen (grüner TalkBack Rahmen)
            val focusedNode = findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
            if (focusedNode != null) {
                targetText = extractSpecificNodeText(focusedNode)
                focusedNode.recycle()
            }

            // 2. Eingabe-Fokus abfragen (falls TalkBack-Fokus in React Native/Discord nicht greift)
            if (targetText.isBlank()) {
                val inputNode = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (inputNode != null) {
                    targetText = extractSpecificNodeText(inputNode)
                    inputNode.recycle()
                }
            }

            // 3. Kürzlich durch TalkBack fokussierte Einzelnachricht (verhindert Bündelung in Discord)
            val now = System.currentTimeMillis()
            if (targetText.isBlank() && lastFocusedTalkBackText.isNotBlank() && (now - lastFocusedTalkBackTime) < 15000) {
                targetText = lastFocusedTalkBackText
                Log.d(TAG, "Using single focused message from TalkBack event: $targetText")
            }

            // 4. Fallback: Zuletzt erfasstes TalkBack-Element
            if (targetText.isBlank() && lastSpokenOriginalText.isNotBlank()) {
                targetText = lastSpokenOriginalText
            }

            // 5. Fallback: Gesamten Bildschirm scannen (aktives Fenster und alle interaktiven Fenster)
            if (targetText.isBlank()) {
                val collected = mutableListOf<String>()
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    collectAllVisibleText(rootNode, collected)
                    rootNode.recycle()
                }

                if (collected.isEmpty()) {
                    try {
                        for (w in windows) {
                            val wRoot = w.root ?: continue
                            collectAllVisibleText(wRoot, collected)
                            wRoot.recycle()
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Error inspecting windows: ${e.message}")
                    }
                }

                targetText = collected.filter { it.length > 1 }.joinToString(". ")
            }

            // Text-Puffer zurücksetzen, damit nächste Aktion nicht dieselbe Nachricht wiederholt
            lastFocusedTalkBackText = ""

            if (targetText.isBlank()) {
                vibrateNotFound()
                speechManager.speak("Kein englischer Text zum Übersetzen gefunden.")
                isTranslating = false
                return@launch
            }

            val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
            val useChat = prefs.getBoolean("pref_chat_filter_enabled", true)
            val useSlang = prefs.getBoolean("pref_slang_translator_enabled", true)
            val speakOriginalFirst = prefs.getBoolean("pref_speak_original_first", false)
            val preferNeural = prefs.getString("pref_translation_mode", "neural_online") != "offline_only"

            Log.d(TAG, "Translating text: $targetText")
            Toast.makeText(this@TalkBackTranslationService, "Übersetze auf Deutsch…", Toast.LENGTH_SHORT).show()

            val germanText = translationManager.translateToGerman(
                targetText,
                useChatFilter = useChat,
                useSlangExpansion = useSlang,
                preferNeuralOnline = preferNeural
            )

            if (germanText.isNotBlank()) {
                vibrateSuccess()
                lastSpokenOriginalText = targetText
                lastSpokenGermanText = germanText
                (application as TalkBackTranslatorApp).historyManager.addTranslation(targetText, germanText)

                val toSpeak = if (speakOriginalFirst) {
                    "Original: $targetText. Auf Deutsch: $germanText"
                } else {
                    germanText
                }

                Log.d(TAG, "Speaking in German: $toSpeak")
                speechManager.speak(toSpeak, flush = true)
            } else {
                vibrateNotFound()
                speechManager.speak("Übersetzung fehlgeschlagen.")
            }

            isTranslating = false
        }
    }

    private fun collectAllVisibleText(node: AccessibilityNodeInfo?, list: MutableList<String>, depth: Int = 0) {
        if (node == null || depth > 12) return

        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val isOnScreen = rect.width() > 0 && rect.height() > 0 &&
                rect.bottom > 0 && rect.top < screenHeight &&
                rect.right > 0 && rect.left < screenWidth

        if (isOnScreen || node.isVisibleToUser) {
            val directText = node.text?.toString()?.trim()
            val desc = node.contentDescription?.toString()?.trim()
            val itemText = when {
                !directText.isNullOrBlank() -> directText
                !desc.isNullOrBlank() -> desc
                else -> ""
            }
            if (itemText.isNotBlank() && itemText.length > 1 && !list.contains(itemText)) {
                list.add(itemText)
            }
        }

        // Bei Discord / React Native niemals an Zwischencontainern abbrechen, sondern alle Kindknoten durchsuchen
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChild(i) ?: continue
            collectAllVisibleText(child, list, depth + 1)
            child.recycle()
        }
    }

    private fun extractSpecificNodeText(node: AccessibilityNodeInfo?): String {
        if (node == null) return ""

        val pkg = node.packageName?.toString()?.lowercase() ?: ""
        val isDiscord = pkg.contains("discord")

        // 1. In Discord: Immer die vollständige Einzelnachricht extrahieren (Autor + Nachricht)
        if (isDiscord) {
            val msgText = extractMessageUnit(node)
            if (msgText.isNotBlank()) {
                return msgText
            }
        }

        // 2. Direkter Text des Knotens
        val directText = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!directText.isNullOrBlank()) {
            val words = directText.split(Regex("\\s+")).filter { it.isNotBlank() }
            // Falls es sich um einen kurzen Namen / Header in einem Chat handelt:
            if (words.size <= 2 && directText.length < 25) {
                val msgText = extractMessageUnit(node)
                if (msgText.isNotBlank() && msgText != directText) {
                    return msgText
                }
            }
            return directText
        }

        // 3. Barrierefreiheits-Beschreibung
        if (!desc.isNullOrBlank()) {
            return desc
        }

        // 4. Wenn es ein Container mit mehreren Kindern ist (z. B. Discord Nachrichten-Gruppe):
        // Suche, ob ein Kindknoten selbst barrierefrei fokussiert ist
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            if (child.isAccessibilityFocused || child.isFocused) {
                val childText = extractSpecificNodeText(child)
                child.recycle()
                if (childText.isNotBlank()) return childText
            }
            child.recycle()
        }

        // 5. Falls kürzlich ein TalkBack-Fokus-Text erfasst wurde
        val now = System.currentTimeMillis()
        if (lastFocusedTalkBackText.isNotBlank() && (now - lastFocusedTalkBackTime) < 30000) {
            return lastFocusedTalkBackText
        }

        // 6. Fallback auf Tiefensuche
        return extractDeepNodeText(node)
    }

    private fun extractMessageUnit(node: AccessibilityNodeInfo): String {
        var msgContainer: AccessibilityNodeInfo = node
        var curr: AccessibilityNodeInfo = node
        var depth = 0
        val nodesToRecycle = mutableListOf<AccessibilityNodeInfo>()

        try {
            while (depth < 6) {
                val parent = try { curr.parent } catch (_: Exception) { null } ?: break
                nodesToRecycle.add(parent)
                val parentClass = parent.className?.toString() ?: ""
                val isList = parent.isScrollable ||
                        parentClass.contains("RecyclerView", ignoreCase = true) ||
                        parentClass.contains("ListView", ignoreCase = true) ||
                        parentClass.contains("ScrollView", ignoreCase = true) ||
                        parentClass.contains("ViewPager", ignoreCase = true) ||
                        (parent.collectionInfo != null) ||
                        parent.childCount > 8

                if (isList) {
                    // parent ist die Chat-Liste -> curr ist die exakte Zeile der Einzelnachricht!
                    msgContainer = curr
                    break
                }
                curr = parent
                depth++
            }

            // Prüfen, ob der Container selbst eine vollständige contentDescription besitzt
            val containerDesc = msgContainer.contentDescription?.toString()?.trim() ?: ""
            val cleanedDesc = TranslationManager.cleanChatText(containerDesc)
            if (cleanedDesc.length > 5 && (cleanedDesc.contains(" ") || cleanedDesc.length > 20)) {
                return cleanedDesc
            }

            val pieces = mutableListOf<String>()
            collectContainerTexts(msgContainer, pieces, 0)

            if (pieces.isEmpty()) {
                val direct = node.text?.toString()?.trim() ?: ""
                return if (direct.isNotBlank()) direct else containerDesc
            }

            return assembleChatMessage(pieces)
        } finally {
            for (n in nodesToRecycle) {
                if (n != node && n != msgContainer) {
                    try { n.recycle() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun collectContainerTexts(node: AccessibilityNodeInfo?, list: MutableList<String>, depth: Int = 0) {
        if (node == null || depth > 8) return

        val text = node.text?.toString()?.trim()
        val desc = node.contentDescription?.toString()?.trim()

        if (!text.isNullOrBlank() && !list.contains(text)) {
            list.add(text)
        } else if (!desc.isNullOrBlank() && !list.contains(desc)) {
            list.add(desc)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            collectContainerTexts(child, list, depth + 1)
            child.recycle()
        }
    }

    private fun extractDeepNodeText(node: AccessibilityNodeInfo?, depth: Int = 0): String {
        if (node == null || depth > 8) return ""

        val directText = node.text?.toString()?.trim()
        if (!directText.isNullOrBlank() && node.childCount == 0) {
            return directText
        }

        val pieces = mutableListOf<String>()
        if (!directText.isNullOrBlank()) {
            pieces.add(directText)
        }

        // 2. Barrierefreiheits-Beschreibung
        val desc = node.contentDescription?.toString()?.trim()
        if (!desc.isNullOrBlank() && !pieces.any { it.contains(desc, ignoreCase = true) }) {
            pieces.add(desc)
        }

        // 3. Hinweis-Text (Hint)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val hint = node.hintText?.toString()?.trim()
            if (!hint.isNullOrBlank() && !pieces.any { it.contains(hint, ignoreCase = true) }) {
                pieces.add(hint)
            }
        }

        // 4. Kind-Knoten rekursiv durchsuchen (entscheidend für Discord / React Native Chat-Nachrichten!)
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val childText = extractDeepNodeText(child, depth + 1)
            if (childText.isNotBlank()) {
                if (!pieces.any { it.contains(childText, ignoreCase = true) }) {
                    pieces.add(childText)
                }
            }
            child.recycle()
        }

        return pieces.distinct().joinToString(" ").trim()
    }

    fun vibrateStart() {
        val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pref_haptic_feedback_enabled", true)) return
        vibratePattern(longArrayOf(0, 40, 60, 40), intArrayOf(0, 180, 0, 180))
    }

    fun vibrateSuccess() {
        val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pref_haptic_feedback_enabled", true)) return
        vibratePattern(longArrayOf(0, 80), intArrayOf(0, 200))
    }

    fun vibrateNotFound() {
        val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("pref_haptic_feedback_enabled", true)) return
        vibratePattern(longArrayOf(0, 30, 40, 30, 40, 30), intArrayOf(0, 150, 0, 150, 0, 150))
    }

    private fun vibrateDoubleFeedback() {
        vibrateStart()
    }

    private fun vibratePattern(timings: LongArray, amplitudes: IntArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vibratorManager.defaultVibrator.vibrate(
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                )
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(VibrationEffect.createWaveform(timings, amplitudes, -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(timings, -1)
            }
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && accessibilityButtonCallback != null) {
            try {
                accessibilityButtonController.unregisterAccessibilityButtonCallback(accessibilityButtonCallback!!)
            } catch (_: Exception) {}
        }
        clipboardListener?.let { clipboardManager?.removePrimaryClipChangedListener(it) }
        clipboardListener = null
        serviceScope.cancel()
        instance = null
        super.onDestroy()
    }

    companion object {
        const val TAG = "TalkBackTranslator"
        var instance: TalkBackTranslationService? = null
            private set

        fun isServiceRunning(): Boolean = instance != null

        /**
         * Baut aus extrahierten Textbausteinen einer Chat-Nachricht (Discord etc.)
         * eine saubere, vollständige Nachricht aus Autor und Inhalt zusammen.
         * Verhindert, dass nur der Autorenname ohne Nachricht vorgelesen wird.
         */
        fun assembleChatMessage(rawPieces: List<String>): String {
            if (rawPieces.isEmpty()) return ""

            // 1. Zeitstempel und reine Chat-Metadaten herausfiltern
            val validPieces = mutableListOf<String>()
            for (p in rawPieces) {
                if (TranslationManager.isChatMetadataOrTimestamp(p)) continue
                val cleaned = TranslationManager.cleanChatText(p).trim()
                if (cleaned.isNotBlank()) {
                    validPieces.add(cleaned)
                }
            }

            if (validPieces.isEmpty()) {
                return rawPieces.firstOrNull()?.trim() ?: ""
            }

            if (validPieces.size == 1) {
                return validPieces[0]
            }

            // 2. Ersten Teil auf Autorenschaft prüfen (z. B. "Laurin", "Gamer99")
            val first = validPieces[0]
            val firstClean = first.trimEnd(':').trim()
            val firstWords = firstClean.split(Regex("\\s+")).filter { it.isNotBlank() }
            val isAuthor = firstWords.size <= 3 && firstClean.length < 30 && !firstClean.endsWith(".") && !TranslationManager.isChatMetadataOrTimestamp(firstClean)

            return if (isAuthor && validPieces.size >= 2) {
                val body = validPieces.subList(1, validPieces.size).joinToString(" ").trim()
                if (body.startsWith(firstClean, ignoreCase = true)) {
                    body
                } else {
                    "$firstClean: $body"
                }
            } else {
                validPieces.joinToString(" ")
            }
        }
    }
}