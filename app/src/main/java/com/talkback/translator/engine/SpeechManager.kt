package com.talkback.translator.engine

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.widget.Toast
import java.util.Locale

class SpeechManager(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    var isInitialized = false
        private set
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingTextToSpeak: String? = null

    var isSpeaking: Boolean = false
        private set

    val isSpeakingNow: Boolean
        get() = try {
            isSpeaking || (tts?.isSpeaking == true)
        } catch (_: Exception) {
            isSpeaking
        }

    var currentEnginePackage: String = ""
        private set

    var currentVoiceName: String = ""
        private set

    var speechRate: Float = 1.0f
        private set

    var speechPitch: Float = 1.0f
        private set

    private var onReadyCallback: (() -> Unit)? = null

    init {
        initTtsEngine()
    }

    fun getAvailableTtsEngines(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        try {
            val intent = Intent("android.intent.action.TTS_SERVICE")
            val resolveInfos = context.packageManager.queryIntentServices(intent, 0)
            for (info in resolveInfos) {
                val pkg = info.serviceInfo.packageName
                val label = info.loadLabel(context.packageManager).toString()
                list.add(pkg to label)
            }
        } catch (e: Exception) {
            Log.w("SpeechManager", "Error querying TTS engines: ${e.message}")
        }
        if (list.isEmpty()) {
            list.add("com.google.android.tts" to "Google Sprachausgabe")
        }
        return list
    }

    fun initTtsEngine(preferredEngine: String? = null, onReady: (() -> Unit)? = null) {
        tts?.shutdown()
        isInitialized = false
        this.onReadyCallback = onReady

        val prefs = context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        if (!preferredEngine.isNullOrBlank()) {
            prefs.edit().putString("pref_selected_tts_engine", preferredEngine).apply()
        }
        val savedEngine = preferredEngine ?: prefs.getString("pref_selected_tts_engine", "")

        val engineToUse = if (!savedEngine.isNullOrBlank()) {
            savedEngine
        } else {
            val systemSynth = try {
                Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)
            } catch (_: Exception) { "" }
            if (!systemSynth.isNullOrBlank()) systemSynth else null
        }

        if (engineToUse != null) {
            currentEnginePackage = engineToUse
            Log.i("SpeechManager", "Initializing TTS with engine: $engineToUse")
            tts = TextToSpeech(context, this, engineToUse)
        } else {
            Log.i("SpeechManager", "Initializing TTS with default system engine")
            tts = TextToSpeech(context, this)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val ttsInstance = tts ?: return
            currentEnginePackage = ttsInstance.defaultEngine ?: currentEnginePackage
            Log.i("SpeechManager", "TTS initialized with engine: $currentEnginePackage")

            try {
                var langResult = ttsInstance.setLanguage(Locale.GERMANY)
                if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                    ttsInstance.setLanguage(Locale.GERMAN)
                }
            } catch (_: Exception) {}

            loadSpeedAndPitch()
            applySpeedAndPitch()

            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            try {
                ttsInstance.setAudioAttributes(audioAttributes)
            } catch (e: Exception) {
                Log.w("SpeechManager", "setAudioAttributes failed: ${e.message}")
            }

            isInitialized = true
            applyPreferredVoice()

            onReadyCallback?.invoke()
            onReadyCallback = null

            val pending = pendingTextToSpeak
            if (!pending.isNullOrBlank()) {
                pendingTextToSpeak = null
                speak(pending, flush = true)
            }
        } else {
            Log.e("SpeechManager", "TTS initialization failed: $status")
            if (currentEnginePackage.isNotBlank()) {
                Log.w("SpeechManager", "Engine $currentEnginePackage failed, falling back to default system TTS")
                currentEnginePackage = ""
                tts = TextToSpeech(context, this)
                return
            }
            onReadyCallback?.invoke()
            onReadyCallback = null
        }
    }

    fun loadSpeedAndPitch() {
        val prefs = context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        val savedRate = prefs.getFloat("pref_speech_rate", -1f)
        val savedPitch = prefs.getFloat("pref_speech_pitch", -1f)

        speechRate = if (savedRate > 0f) {
            savedRate.coerceIn(0.5f, 2.5f)
        } else {
            val rateInt = try {
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.TTS_DEFAULT_RATE, 100)
            } catch (_: Exception) { 100 }
            if (rateInt in 50..400) rateInt / 100.0f else 1.0f
        }

        speechPitch = if (savedPitch > 0f) {
            savedPitch.coerceIn(0.5f, 1.5f)
        } else {
            val pitchInt = try {
                Settings.Secure.getInt(context.contentResolver, Settings.Secure.TTS_DEFAULT_PITCH, 100)
            } catch (_: Exception) { 100 }
            if (pitchInt in 50..200) pitchInt / 100.0f else 1.0f
        }
    }

    fun applySpeedAndPitch() {
        val ttsInstance = tts ?: return
        try {
            ttsInstance.setSpeechRate(speechRate.coerceIn(0.5f, 2.5f))
            ttsInstance.setPitch(speechPitch.coerceIn(0.5f, 1.5f))
            Log.i("SpeechManager", "Applied speechRate=$speechRate, pitch=$speechPitch")
        } catch (_: Exception) {}
    }

    fun setSpeedAndPitch(rate: Float, pitch: Float, save: Boolean = true) {
        this.speechRate = rate.coerceIn(0.5f, 2.5f)
        this.speechPitch = pitch.coerceIn(0.5f, 1.5f)
        applySpeedAndPitch()
        if (save) {
            context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
                .edit()
                .putFloat("pref_speech_rate", this.speechRate)
                .putFloat("pref_speech_pitch", this.speechPitch)
                .apply()
        }
    }

    private fun applyPreferredVoice() {
        val ttsInstance = tts ?: return
        val prefs = context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
        val savedVoiceName = prefs.getString("pref_selected_voice_name", "")

        try {
            val germanVoices = getGermanVoices()
            if (germanVoices.isEmpty()) {
                ttsInstance.language = Locale.GERMAN
                return
            }

            val voiceToUse = if (!savedVoiceName.isNullOrBlank()) {
                germanVoices.find { it.name == savedVoiceName }
            } else if (currentVoiceName.isNotBlank()) {
                germanVoices.find { it.name == currentVoiceName }
            } else {
                germanVoices.firstOrNull { !it.isNetworkConnectionRequired } ?: germanVoices.firstOrNull()
            }

            if (voiceToUse != null) {
                ttsInstance.voice = voiceToUse
                currentVoiceName = voiceToUse.name
                Log.i("SpeechManager", "Active TTS voice set to: ${voiceToUse.name}")
            } else {
                ttsInstance.language = Locale.GERMAN
            }
        } catch (e: Exception) {
            Log.w("SpeechManager", "applyPreferredVoice: ${e.message}")
        }
    }

    fun getGermanVoices(): List<Voice> {
        val ttsInstance = tts ?: return emptyList()
        return try {
            val all = ttsInstance.voices?.filter { voice ->
                val lang = voice.locale.language.lowercase()
                lang == "de" || lang.startsWith("de") || voice.locale.country.equals("DE", ignoreCase = true)
            } ?: emptyList()

            all.sortedWith(
                compareByDescending<Voice> { !it.isNetworkConnectionRequired }
                    .thenBy { it.name }
            )
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setVoiceByName(voiceName: String, preview: Boolean = false) {
        val voice = getGermanVoices().find { it.name == voiceName } ?: return
        tts?.voice = voice
        currentVoiceName = voice.name
        context.getSharedPreferences("translator_settings", Context.MODE_PRIVATE)
            .edit()
            .putString("pref_selected_voice_name", voice.name)
            .apply()

        if (preview) {
            speak("Diese Stimme ist jetzt ausgewählt.", flush = true)
        }
    }

    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.d("SpeechManager", "onAudioFocusChange: focusChange=$focusChange")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // TalkBack oder System möchte sprechen / Audio wiederholen -> Deutsche Stimme sofort unterbrechen!
                Log.d("SpeechManager", "AudioFocus lost -> interrupting German speech immediately")
                stop()
            }
        }
    }

    private fun requestTransientAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(audioFocusChangeListener, mainHandler)
                    .build()
                audioFocusRequest = req
                val res = audioManager.requestAudioFocus(req)
                Log.d("SpeechManager", "requestTransientAudioFocus result: $res")
            } else {
                @Suppress("DEPRECATION")
                val res = audioManager.requestAudioFocus(
                    audioFocusChangeListener,
                    AudioManager.STREAM_ACCESSIBILITY,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                )
                Log.d("SpeechManager", "requestTransientAudioFocus (legacy) result: $res")
            }
        } catch (e: Exception) {
            Log.w("SpeechManager", "requestTransientAudioFocus error: ${e.message}")
        }
    }

    private fun releaseAudioFocus() {
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(audioFocusChangeListener)
            }
        } catch (_: Exception) {}
    }

    fun speakWithTalkBackParams(
        text: String,
        speechRate: Float? = null,
        pitch: Float? = null,
        flush: Boolean = true,
        onComplete: (() -> Unit)? = null
    ) {
        if (text.isBlank()) {
            onComplete?.invoke()
            return
        }

        if (!isInitialized) {
            pendingTextToSpeak = text
            return
        }

        // Falls noch alte Sprachausgabe läuft, sofort beenden
        if (isSpeakingNow) {
            try { tts?.stop() } catch (_: Exception) {}
        }

        val rateToUse = (speechRate ?: this.speechRate).coerceIn(0.5f, 2.5f)
        val pitchToUse = (pitch ?: this.speechPitch).coerceIn(0.5f, 1.5f)
        tts?.setSpeechRate(rateToUse)
        tts?.setPitch(pitchToUse)

        // Exklusiven AudioFocus fordern (AUDIOFOCUS_GAIN_TRANSIENT) -> Zwingt TalkBack, sofort mit Englisch aufzuhören!
        requestTransientAudioFocus()

        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        val utteranceId = "tb_trans_${System.currentTimeMillis()}"

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (id == utteranceId) {
                    isSpeaking = true
                }
            }
            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    isSpeaking = false
                    releaseAudioFocus()
                    mainHandler.post { onComplete?.invoke() }
                }
            }
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    isSpeaking = false
                    releaseAudioFocus()
                    mainHandler.post { onComplete?.invoke() }
                }
            }
            override fun onStop(id: String?, interrupted: Boolean) {
                if (id == utteranceId) {
                    isSpeaking = false
                    releaseAudioFocus()
                    mainHandler.post { onComplete?.invoke() }
                }
            }
        })

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ACCESSIBILITY)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
        }

        val result = tts?.speak(text, queueMode, params, utteranceId)
        if (result == TextToSpeech.SUCCESS) {
            isSpeaking = true
        } else {
            // Fallback auf STREAM_MUSIC falls TTS Engine STREAM_ACCESSIBILITY im Bundle ablehnt
            val fallbackParams = Bundle().apply {
                putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
            }
            val fallback = tts?.speak(text, queueMode, fallbackParams, utteranceId)
            if (fallback == TextToSpeech.SUCCESS) {
                isSpeaking = true
            } else {
                isSpeaking = false
                releaseAudioFocus()
                onComplete?.invoke()
            }
        }
    }

    fun speak(text: String, flush: Boolean = true, onComplete: (() -> Unit)? = null) {
        speakWithTalkBackParams(text, null, null, flush, onComplete)
    }

    fun stop() {
        isSpeaking = false
        releaseAudioFocus()
        try {
            tts?.stop()
        } catch (_: Exception) {}
    }

    fun shutdown() {
        isSpeaking = false
        releaseAudioFocus()
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (_: Exception) {}
    }
}