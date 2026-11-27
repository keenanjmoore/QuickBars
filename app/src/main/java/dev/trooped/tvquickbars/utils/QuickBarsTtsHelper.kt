package dev.trooped.tvquickbars.utils

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Thread-safe TextToSpeech singleton used by QuickBars to announce doorbell chimes or
 * critical camera alerts aloud on Android TV without blocking the main thread.
 * Includes automatic recovery when the Android TV TTS binder dies (`TextToSpeech.ERROR`).
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
            if (speakNow(instance, clean)) {
                return
            }
            // TTS binder died or returned TextToSpeech.ERROR: reset and lazily re-init once
            Log.w(TAG, "TTS speak failed; resetting engine for lazy re-initialization")
            resetEngineLocked()
        }
        if (pendingTexts.size >= 4) pendingTexts.removeFirst()
        pendingTexts.addLast(clean)
        initEngineIfNeeded(context)
    }

    private fun initEngineIfNeeded(context: Context) {
        if (initializing) return
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
                            if (!speakNow(engine, pendingTexts.removeFirst())) {
                                resetEngineLocked()
                                break
                            }
                        }
                    } else {
                        Log.w(TAG, "TTS init failed with status=$status")
                        pendingTexts.clear()
                        resetEngineLocked()
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TTS construction failed: ${t.message}")
            initializing = false
            pendingTexts.clear()
            resetEngineLocked()
        }
    }

    private fun resetEngineLocked() {
        ready = false
        try { tts?.stop() } catch (_: Throwable) {}
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
    }

    private fun speakNow(engine: TextToSpeech, text: String): Boolean {
        return try {
            val code = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "qb_${System.currentTimeMillis()}")
            code != TextToSpeech.ERROR
        } catch (t: Throwable) {
            Log.w(TAG, "TTS speak failed: ${t.message}")
            false
        }
    }
}
