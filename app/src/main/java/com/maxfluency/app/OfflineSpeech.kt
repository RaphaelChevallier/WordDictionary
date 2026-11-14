package com.maxfluency.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import com.maxfluency.core.WordEntry

class OfflineSpeech(context: Context, private val message: (Int, String?) -> Unit) : AutoCloseable {
    private var initialized = false
    private var ready = false
    private val engine = TextToSpeech(context.applicationContext) { status ->
        initialized = true
        ready = status == TextToSpeech.SUCCESS
        if (!ready) Log.w("OfflineSpeech", "No text-to-speech engine is available")
    }

    fun speak(entry: WordEntry) {
        if (!ready) {
            message(if (initialized) R.string.tts_error else R.string.tts_not_ready, null)
            return
        }
        val voices = engine.voices.orEmpty().filter {
            !it.isNetworkConnectionRequired && it.locale.language == entry.language.code
                && !it.features.orEmpty().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }
        val voice = voices.firstOrNull { it.name == engine.defaultVoice?.name }
            ?: voices.sortedBy { it.name }.firstOrNull()
        if (voice == null) {
            message(R.string.tts_no_voice, entry.language.nativeName)
            return
        }
        if (engine.setVoice(voice) != TextToSpeech.SUCCESS
            || engine.speak(entry.word, TextToSpeech.QUEUE_FLUSH, null, entry.id) != TextToSpeech.SUCCESS) {
            Log.e("OfflineSpeech", "The installed offline voice rejected speech")
            message(R.string.tts_error, null)
        }
    }

    override fun close() {
        engine.stop()
        engine.shutdown()
    }
}
