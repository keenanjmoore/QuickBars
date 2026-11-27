package dev.trooped.tvquickbars.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.core.content.ContextCompat
import dev.trooped.tvquickbars.background.BackgroundHaConnectionManager

/**
 * Shared singleton screen/dream state monitor so [HAConnectionService] and [QuickBarService]
 * do not register duplicate system broadcast receivers and double-emit screen telemetry.
 */
object ScreenStateMonitor {
    private const val TAG = "ScreenStateMonitor"
    private val lock = Any()
    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val appCtx = context?.applicationContext ?: return
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_DREAMING_STARTED -> {
                    BackgroundHaConnectionManager.onScreenStateChanged(appCtx, isScreenOff = true)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_DREAMING_STOPPED -> {
                    BackgroundHaConnectionManager.onScreenStateChanged(appCtx, isScreenOff = false)
                }
            }
        }
    }

    fun ensureRegistered(context: Context) {
        if (registered) return
        synchronized(lock) {
            if (registered) return
            val appCtx = context.applicationContext
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_DREAMING_STARTED)
                addAction(Intent.ACTION_DREAMING_STOPPED)
            }
            try {
                ContextCompat.registerReceiver(appCtx, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
                registered = true
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to register ScreenStateMonitor: ${t.message}")
            }
        }
    }

    fun unregister(context: Context) {
        synchronized(lock) {
            if (!registered) return
            try {
                context.applicationContext.unregisterReceiver(receiver)
            } catch (_: Throwable) {
            } finally {
                registered = false
            }
        }
    }
}
