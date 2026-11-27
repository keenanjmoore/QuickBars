package dev.trooped.tvquickbars.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import dev.trooped.tvquickbars.background.BackgroundHaConnectionManager
import dev.trooped.tvquickbars.persistence.AppPrefs
import dev.trooped.tvquickbars.services.HAConnectionService
import dev.trooped.tvquickbars.utils.SecureStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts [HAConnectionService] on boot or after an in-place APK upgrade
 * (`MY_PACKAGE_REPLACED`) off the main thread via `goAsync()`.
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

        val pendingResult = goAsync()
        val appCtx = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val shouldStart = AppPrefs.isPersistentConnectionEnabled(appCtx) ||
                    !SecureStore.getHATokenAsync(appCtx).isNullOrBlank()
                if (!shouldStart) return@launch

                try {
                    val svc = Intent(appCtx, HAConnectionService::class.java)
                    ContextCompat.startForegroundService(appCtx, svc)
                } catch (t: Throwable) {
                    Log.w("BootCompletedReceiver", "startForegroundService failed ($act), falling back: ${t.message}")
                    BackgroundHaConnectionManager.start(appCtx)
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
