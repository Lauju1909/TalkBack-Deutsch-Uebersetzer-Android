package com.talkback.translator

import android.app.Application
import com.talkback.translator.engine.HistoryManager
import com.talkback.translator.engine.SpeechManager
import com.talkback.translator.engine.TranslationManager

class TalkBackTranslatorApp : Application() {

    lateinit var speechManager: SpeechManager
        private set

    lateinit var translationManager: TranslationManager
        private set

    lateinit var historyManager: HistoryManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        speechManager = SpeechManager(this)
        translationManager = TranslationManager(this)
        historyManager = HistoryManager(this)
    }

    override fun onTerminate() {
        speechManager.shutdown()
        translationManager.close()
        super.onTerminate()
    }

    companion object {
        lateinit var instance: TalkBackTranslatorApp
            private set
    }
}
