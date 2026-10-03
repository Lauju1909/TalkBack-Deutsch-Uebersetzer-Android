package com.talkback.translator.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.Voice
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.talkback.translator.R
import com.talkback.translator.TalkBackTranslatorApp
import com.talkback.translator.databinding.ActivityMainBinding
import com.talkback.translator.service.TalkBackTranslationService
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private var _binding: ActivityMainBinding? = null
    private val binding get() = _binding!!
    private val app by lazy { application as TalkBackTranslatorApp }
    private val handler = Handler(Looper.getMainLooper())
    private var isFirstSpinnerSelection = true
    private var isFirstEngineSelection = true

    private val gestureOptions = listOf(
        "2 Finger doppelt tippen (Standard)" to "2_finger_double_tap",
        "2 Finger tippen und halten" to "2_finger_tap_hold",
        "3 Finger doppelt tippen" to "3_finger_double_tap"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            _binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)

            setupToolbar()
            setupTriggerGestureSelection()
            loadPreferences()
            setupListeners()
            setupEngineSelection()
            setupVoiceSelection()
            setupSpeedAndPitchControls()
            checkModelStatus()
            updateHistoryDisplay()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onCreate: ${e.message}", e)
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            updatePermissionStatuses()
            checkModelStatus()
            updateHistoryDisplay()
            if (binding.spinnerVoices.adapter == null || binding.spinnerVoices.adapter.isEmpty) {
                setupEngineSelection()
                setupVoiceSelection()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error in onResume: ${e.message}", e)
        }
    }

    private fun setupToolbar() {
        try {
            setSupportActionBar(binding.toolbar)
            supportActionBar?.title = getString(R.string.app_name)
            supportActionBar?.subtitle = "Version 1.2.1 (Discord-Nachrichten Fix)"
        } catch (e: Exception) {
            Log.w("MainActivity", "Toolbar setup: ${e.message}")
        }
    }

    private fun checkModelStatus() {
        app.translationManager.preloadEnglishGermanModel()
    }

    private fun setupTriggerGestureSelection() {
        try {
            val labels = gestureOptions.map { it.first }
            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
            binding.spinnerTriggerGesture.adapter = adapter

            val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
            val savedValue = prefs.getString("pref_trigger_gesture", "2_finger_double_tap") ?: "2_finger_double_tap"
            val index = gestureOptions.indexOfFirst { it.second == savedValue }.coerceAtLeast(0)
            binding.spinnerTriggerGesture.setSelection(index, false)

            var isInitial = true
            binding.spinnerTriggerGesture.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!isInitial) {
                        val chosen = gestureOptions[position]
                        prefs.edit().putString("pref_trigger_gesture", chosen.second).apply()
                        Toast.makeText(this@MainActivity, "Geste gesetzt: ${chosen.first}", Toast.LENGTH_SHORT).show()
                    }
                    isInitial = false
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "setupTriggerGestureSelection error: ${e.message}")
        }
    }

    private fun setupEngineSelection() {
        try {
            val engines = app.speechManager.getAvailableTtsEngines()
            if (engines.isEmpty()) return

            val displayList = engines.map { (pkg, label) ->
                when {
                    pkg.contains("google") -> "Google Sprachausgabe ($pkg)"
                    pkg.contains("samsung") -> "Samsung Text-zu-Sprache ($pkg)"
                    else -> "$label ($pkg)"
                }
            }

            val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayList)
            binding.spinnerEngines.adapter = adapter

            val currentEngine = app.speechManager.currentEnginePackage
            val selectedIndex = engines.indexOfFirst { it.first == currentEngine }
            if (selectedIndex >= 0) {
                binding.spinnerEngines.setSelection(selectedIndex, false)
            }

            binding.spinnerEngines.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val selectedPkg = engines[position].first
                    if (!isFirstEngineSelection && selectedPkg != app.speechManager.currentEnginePackage) {
                        Toast.makeText(this@MainActivity, "Wechsle zu ${displayList[position]}…", Toast.LENGTH_SHORT).show()
                        isFirstSpinnerSelection = true
                        app.speechManager.initTtsEngine(selectedPkg) {
                            handler.post { setupVoiceSelection() }
                        }
                    }
                    isFirstEngineSelection = false
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "setupEngineSelection error: ${e.message}")
        }
    }

    private fun setupVoiceSelection() {
        try {
            val voices = app.speechManager.getGermanVoices()
            if (voices.isNotEmpty()) {
                val displayList = voices.mapIndexed { index, v -> formatVoiceLabel(v, index) }

                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, displayList)
                binding.spinnerVoices.adapter = adapter

                val activeVoice = app.speechManager.currentVoiceName
                val selectedIndex = voices.indexOfFirst { it.name == activeVoice }
                if (selectedIndex >= 0) {
                    binding.spinnerVoices.setSelection(selectedIndex)
                    binding.tvActiveVoice.text = "Aktive Stimme: ${displayList[selectedIndex]}"
                } else {
                    binding.tvActiveVoice.text = "Aktive Stimme: ${displayList.first()}"
                }

                binding.spinnerVoices.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                        val selectedVoice = voices[position]
                        val isUserChange = !isFirstSpinnerSelection
                        isFirstSpinnerSelection = false

                        app.speechManager.setVoiceByName(selectedVoice.name, preview = isUserChange)
                        binding.tvActiveVoice.text = "Aktive Stimme: ${displayList[position]}"
                    }
                    override fun onNothingSelected(parent: AdapterView<*>?) {}
                }
            } else {
                binding.tvActiveVoice.text = "Aktive Stimme: Standardstimme der gewählten Engine"
                val emptyList = listOf("Standardstimme (Automatisch)")
                val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, emptyList)
                binding.spinnerVoices.adapter = adapter
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "setupVoiceSelection error: ${e.message}")
        }
    }

    private fun formatVoiceLabel(voice: Voice, index: Int): String {
        val name = voice.name.lowercase()
        val country = when (voice.locale.country.uppercase()) {
            "AT" -> "Österreich"
            "CH" -> "Schweiz"
            else -> "Deutschland"
        }

        return when {
            name.contains("deb") -> "Google Stimme 1 (Weiblich, $country)"
            name.contains("deg") -> "Google Stimme 2 (Männlich, $country)"
            name.contains("ded") -> "Google Stimme 3 (Männlich, $country)"
            name.contains("dea") -> "Google Stimme 4 (Weiblich, $country)"
            name.contains("gft") -> "Google Stimme 5 (Natürlich, $country)"
            name.contains("kfl") -> "Google Stimme 6 ($country)"
            name.contains("pae") -> "Google Stimme 7 ($country)"
            name.contains("sfg") || name.contains("female") -> "Samsung Stimme (Weiblich, $country)"
            name.contains("male") -> "Samsung Stimme (Männlich, $country)"
            name.contains("samsung") -> "Samsung Stimme ${index + 1} ($country)"
            name.contains("language") -> "Offline Standard ($country)"
            else -> {
                val short = voice.name.substringAfterLast("-").substringAfterLast("_")
                "Stimme ${index + 1} ($short, $country)"
            }
        }
    }

    private fun setupSpeedAndPitchControls() {
        try {
            val currentRate = app.speechManager.speechRate
            val currentPitch = app.speechManager.speechPitch

            val rateProgress = ((currentRate - 0.5f) * 100f).roundToInt().coerceIn(0, 200)
            binding.seekSpeed.progress = rateProgress
            updateSpeedLabel(currentRate)

            val pitchProgress = ((currentPitch - 0.5f) * 100f).roundToInt().coerceIn(0, 100)
            binding.seekPitch.progress = pitchProgress
            updatePitchLabel(currentPitch)

            binding.seekSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val rate = 0.5f + (progress / 100f)
                    updateSpeedLabel(rate)
                    if (fromUser) {
                        app.speechManager.setSpeedAndPitch(rate, app.speechManager.speechPitch, save = true)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    app.speechManager.speak("Geschwindigkeit angepasst.", flush = true)
                }
            })

            binding.seekPitch.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    val pitch = 0.5f + (progress / 100f)
                    updatePitchLabel(pitch)
                    if (fromUser) {
                        app.speechManager.setSpeedAndPitch(app.speechManager.speechRate, pitch, save = true)
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    app.speechManager.speak("Tonhöhe angepasst.", flush = true)
                }
            })

            binding.btnResetSpeedPitch.setOnClickListener {
                binding.seekSpeed.progress = 50 // 1.00x
                binding.seekPitch.progress = 50 // 1.00x
                updateSpeedLabel(1.0f)
                updatePitchLabel(1.0f)
                app.speechManager.setSpeedAndPitch(1.0f, 1.0f, save = true)
                Toast.makeText(this, "Geschwindigkeit und Tonhöhe auf 1.0x zurückgesetzt", Toast.LENGTH_SHORT).show()
                app.speechManager.speak("Standard wiederhergestellt.", flush = true)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "setupSpeedAndPitchControls error: ${e.message}")
        }
    }

    private fun updateSpeedLabel(rate: Float) {
        val formatted = String.format(Locale.GERMAN, "%.2fx", rate)
        val description = when {
            rate < 0.95f -> "$formatted (Langsamer)"
            rate > 1.05f -> "$formatted (Schneller)"
            else -> "$formatted (Normal)"
        }
        binding.tvSpeedValue.text = description
        binding.seekSpeed.contentDescription = "Sprechgeschwindigkeit $formatted"
    }

    private fun updatePitchLabel(pitch: Float) {
        val formatted = String.format(Locale.GERMAN, "%.2fx", pitch)
        val description = when {
            pitch < 0.95f -> "$formatted (Tiefer)"
            pitch > 1.05f -> "$formatted (Höher)"
            else -> "$formatted (Normal)"
        }
        binding.tvPitchValue.text = description
        binding.seekPitch.contentDescription = "Stimmhöhe $formatted"
    }

    private fun setupListeners() {
        val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)

        // Bedienungshilfen-Dienst aktivieren / deaktivieren
        binding.btnEnableService.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Aktiviere dort 'TalkBack Deutsch-Übersetzer'",
                Toast.LENGTH_LONG
            ).show()
        }

        binding.btnDisableService.setOnClickListener {
            TalkBackTranslationService.instance?.disableService()
            Toast.makeText(this, "Übersetzer deaktiviert. Du kannst jetzt die Sparkasse-App öffnen.", Toast.LENGTH_LONG).show()
            handler.postDelayed({
                updatePermissionStatuses()
            }, 600)
        }

        binding.btnOpenTtsSettings.setOnClickListener {
            try {
                val intent = Intent("com.android.settings.TTS_SETTINGS").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(this, "Öffne Android-Einstellungen > Bedienungshilfen > Text-in-Sprache", Toast.LENGTH_LONG).show()
            }
        }

        // Gesten-Einstellungen
        binding.switchRepeatGesture.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_repeat_gesture_enabled", isChecked).apply()
        }

        binding.switchStopGesture.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_stop_gesture_enabled", isChecked).apply()
        }

        binding.switchAutoLiveFocus.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_auto_live_focus", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "Live-Modus aktiv: Unterbricht TalkBack und liest sofort auf Deutsch vor.", Toast.LENGTH_LONG).show()
            }
        }

        binding.btnOpenTalkBackSettings.setOnClickListener {
            openTalkBackSettings()
        }

        // Übersetzungs-Optionen
        binding.switchSpeakOriginalFirst.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_speak_original_first", isChecked).apply()
            val msg = if (isChecked) "Liest erst das englische Original, dann Deutsch vor." else "Liest direkt nur auf Deutsch vor."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        binding.switchChatFilter.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_chat_filter_enabled", isChecked).apply()
        }

        binding.switchSlangTranslator.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_slang_translator_enabled", isChecked).apply()
        }

        binding.switchHapticFeedback.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_haptic_feedback_enabled", isChecked).apply()
        }

        binding.switchClipboardTranslator.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean("pref_clipboard_translator_enabled", isChecked).apply()
            if (isChecked) {
                Toast.makeText(this, "Kopierter englischer Text wird jetzt automatisch vorgelesen.", Toast.LENGTH_SHORT).show()
            }
        }

        // Test-Buttons
        binding.btnTestVoice.setOnClickListener {
            val testText = getString(R.string.test_speech_text)
            if (!app.speechManager.isInitialized) {
                Toast.makeText(this, "Initialisiere Sprachausgabe…", Toast.LENGTH_SHORT).show()
                app.speechManager.initTtsEngine {
                    handler.post {
                        Toast.makeText(this@MainActivity, "Stimme spricht...", Toast.LENGTH_SHORT).show()
                        app.speechManager.speak(testText, flush = true)
                    }
                }
            } else {
                Toast.makeText(this, "Stimme spricht...", Toast.LENGTH_SHORT).show()
                app.speechManager.speak(testText, flush = true)
            }
        }

        binding.btnSimulateSpeech.setOnClickListener {
            val englishSample = binding.tvSampleEnglish.text.toString()

            binding.btnSimulateSpeech.isEnabled = false
            binding.btnSimulateSpeech.text = "Übersetze & spreche…"

            lifecycleScope.launch {
                val useChat = prefs.getBoolean("pref_chat_filter_enabled", true)
                val useSlang = prefs.getBoolean("pref_slang_translator_enabled", true)
                val speakOriginalFirst = prefs.getBoolean("pref_speak_original_first", false)

                val germanTranslation = app.translationManager.translateToGerman(
                    englishSample,
                    useChatFilter = useChat,
                    useSlangExpansion = useSlang
                )

                binding.tvTestResultHeader.visibility = View.VISIBLE
                binding.tvTestResultContent.visibility = View.VISIBLE
                binding.tvTestResultContent.text = germanTranslation

                binding.btnSimulateSpeech.isEnabled = true
                binding.btnSimulateSpeech.text = getString(R.string.btn_simulate_speech)

                // Add to history
                app.historyManager.addTranslation(englishSample, germanTranslation)
                updateHistoryDisplay()

                val toSpeak = if (speakOriginalFirst) {
                    "Original: $englishSample. Auf Deutsch: $germanTranslation"
                } else {
                    germanTranslation
                }
                app.speechManager.speak(toSpeak, flush = true)
            }
        }

        // Historie leeren
        binding.btnClearHistory.setOnClickListener {
            app.historyManager.clearHistory()
            updateHistoryDisplay()
            Toast.makeText(this, "Verlauf geleert.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openTalkBackSettings() {
        val intents = listOf(
            Intent().apply {
                component = ComponentName(
                    "com.google.android.marvin.talkback",
                    "com.google.android.marvin.talkback.TalkBackPreferencesActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent().apply {
                component = ComponentName(
                    "com.samsung.android.accessibility.talkback",
                    "com.samsung.android.marvin.talkback.TalkBackPreferencesActivity"
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            },
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        )

        var opened = false
        for (intent in intents) {
            try {
                startActivity(intent)
                opened = true
                Toast.makeText(
                    this,
                    "In TalkBack unter 'Gesten anpassen' kannst du die gewünschte Geste festlegen.",
                    Toast.LENGTH_LONG
                ).show()
                break
            } catch (_: Exception) {}
        }

        if (!opened) {
            Toast.makeText(
                this,
                "Öffne Einstellungen > Bedienungshilfen > TalkBack > Einstellungen > Gesten anpassen.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)

        binding.switchRepeatGesture.isChecked = prefs.getBoolean("pref_repeat_gesture_enabled", true)
        binding.switchStopGesture.isChecked = prefs.getBoolean("pref_stop_gesture_enabled", true)
        binding.switchAutoLiveFocus.isChecked = prefs.getBoolean("pref_auto_live_focus", false)

        binding.switchSpeakOriginalFirst.isChecked = prefs.getBoolean("pref_speak_original_first", false)
        binding.switchChatFilter.isChecked = prefs.getBoolean("pref_chat_filter_enabled", true)
        binding.switchSlangTranslator.isChecked = prefs.getBoolean("pref_slang_translator_enabled", true)
        binding.switchHapticFeedback.isChecked = prefs.getBoolean("pref_haptic_feedback_enabled", true)
        binding.switchClipboardTranslator.isChecked = prefs.getBoolean("pref_clipboard_translator_enabled", false)
    }

    private fun updateHistoryDisplay() {
        try {
            val items = app.historyManager.getHistory()
            if (items.isEmpty()) {
                binding.tvHistoryEmpty.visibility = View.VISIBLE
                binding.containerHistoryItems.removeAllViews()
                return
            }

            binding.tvHistoryEmpty.visibility = View.GONE
            binding.containerHistoryItems.removeAllViews()

            val prefs = getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
            val inflater = layoutInflater

            for (item in items) {
                val itemView = inflater.inflate(R.layout.item_history, binding.containerHistoryItems, false)
                val tvOriginal = itemView.findViewById<TextView>(R.id.tvHistoryOriginal)
                val tvGerman = itemView.findViewById<TextView>(R.id.tvHistoryGerman)
                val tvTimestamp = itemView.findViewById<TextView>(R.id.tvHistoryTimestamp)
                val btnSpeak = itemView.findViewById<MaterialButton>(R.id.btnHistorySpeak)

                tvOriginal.text = item.originalText
                tvGerman.text = item.germanText
                tvTimestamp.text = item.formatTimestamp()

                btnSpeak.setOnClickListener {
                    val speakOriginal = prefs.getBoolean("pref_speak_original_first", false)
                    val textToSpeak = if (speakOriginal) {
                        "Original: ${item.originalText}. Auf Deutsch: ${item.germanText}"
                    } else {
                        item.germanText
                    }
                    app.speechManager.speak(textToSpeak, flush = true)
                }

                binding.containerHistoryItems.addView(itemView)
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "updateHistoryDisplay error: ${e.message}")
        }
    }

    private fun updatePermissionStatuses() {
        val isServiceActive = isAccessibilityServiceEnabled()
        if (isServiceActive) {
            binding.tvServiceStatus.text = getString(R.string.status_service_active)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.success))
            binding.btnEnableService.visibility = View.GONE
            binding.btnDisableService.visibility = View.VISIBLE
        } else {
            binding.tvServiceStatus.text = getString(R.string.status_service_inactive)
            binding.tvServiceStatus.setTextColor(ContextCompat.getColor(this, R.color.warning))
            binding.btnEnableService.visibility = View.VISIBLE
            binding.btnDisableService.visibility = View.GONE
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (TalkBackTranslationService.isServiceRunning()) return true

        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
        for (service in enabledServices) {
            if (service.id.contains(packageName)) {
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        _binding = null
        super.onDestroy()
    }
}