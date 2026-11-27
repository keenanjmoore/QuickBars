package dev.trooped.tvquickbars.utils

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Thread-safe TextToSpeech singleton used by QuickBars v1.6 to announce doorbell chimes or
 * critical camera alerts aloud on Android TV without blocking the main thread.
 */
object QuickBarsTtsHelper {
    private const val TAG = "QuickBarsTts"

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var initializing = false
    private val pendingTexts = ArrayDeque<String>(4)

    @Synchronized
    fun speak(context: Context, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val instance = tts
        if (ready && instance != null) {
            speakNow(instance, clean)
            return
        }
        pendingTexts.addLast(clean)
        if (!initializing) {
            initializing = true
            val appCtx = context.applicationContext
            try {
                tts = TextToSpeech(appCtx) { status ->
                    synchronized(this) {
                        initializing = false
                        val engine = tts
                        if (status == TextToSpeech.SUCCESS && engine != null) {
                            try {
                                engine.setAudioAttributes(
                                    AudioAttributes.Builder()
                                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                        .build()
                                )
                                val res = engine.setLanguage(Locale.US)
                                if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                                    engine.language = Locale.getDefault()
                                }
                            } catch (t: Throwable) {
                                Log.w(TAG, "TTS configuration warning: ${t.message}")
                            }
                            ready = true
                            while (pendingTexts.isNotEmpty()) {
                                speakNow(engine, pendingTexts.removeFirst())
                            }
                        } else {
                            Log.w(TAG, "TTS init failed with status=$status")
                            pendingTexts.clear()
                            ready = false
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "TTS construction failed: ${t.message}")
                initializing = false
                pendingTexts.clear()
            }
        }
    }

    private fun speakNow(engine: TextToSpeech, text: String) {
        try {
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "qb_${System.currentTimeMillis()}")
        } catch (t: Throwable) {
            Log.w(TAG, "TTS speak failed: ${t.message}")
        }
    }
}
