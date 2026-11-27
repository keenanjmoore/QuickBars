package dev.trooped.tvquickbars.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.trooped.tvquickbars.background.BackgroundHaConnectionManager
import dev.trooped.tvquickbars.persistence.AppPrefs
import dev.trooped.tvquickbars.services.HAConnectionService
import dev.trooped.tvquickbars.persistence.SecurePrefsManager

/**
 * Starts [HAConnectionService] on boot or after an in-place APK upgrade
 * (`MY_PACKAGE_REPLACED`) whenever persistent connection or saved HA credentials exist.
 */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val act = intent.action ?: return
        if (act != Intent.ACTION_BOOT_COMPLETED &&
            act != "android.intent.action.QUICKBOOT_POWERON" &&
            act != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }

        val shouldStart = AppPrefs.isPersistentConnectionEnabled(context) ||
            !SecurePrefsManager.getHAToken(context).isNullOrBlank()
        if (!shouldStart) return

        try {
            val svc = Intent(context, HAConnectionService::class.java)
            ContextCompat.startForegroundService(context, svc)
        } catch (t: Throwable) {
            Log.w("BootCompletedReceiver", "startForegroundService failed ($act), falling back to BackgroundHaConnectionManager: ${t.message}")
            BackgroundHaConnectionManager.start(context.applicationContext)
        }
    }
}