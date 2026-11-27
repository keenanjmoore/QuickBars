package dev.trooped.tvquickbars

import android.app.Application
import android.content.Context
import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.persistence.AppPrefs
import dev.trooped.tvquickbars.persistence.SavedEntitiesManager
import dev.trooped.tvquickbars.utils.DemoModeManager
import dev.trooped.tvquickbars.utils.SecureStore
import dev.trooped.tvquickbars.utils.SharedHttpClients
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Application class that provides global context access and asynchronously warms up
 * credential storage, HTTP pools, and entity cache off the main thread.
 */
class QuickBarsApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        instance = this

        appScope.launch {
            AppPrefs.ensureShowToastOnEntityTriggerDefault(this@QuickBarsApp)
            AppIdProvider.ensure(applicationContext)

            try {
                SecureStore.migrateFromOldPrefsIfNeeded(this@QuickBarsApp)
            } catch (e: Exception) {
                android.util.Log.e("QuickBarsApp", "Credential migration failed", e)
            }

            // Warm up keystore + dual-cache URL/Token off main thread to avoid cold-boot ANR
            SecureStore.getHAUrlAsync(this@QuickBarsApp)
            SecureStore.getHATokenAsync(this@QuickBarsApp)
            SharedHttpClients.base
            SavedEntitiesManager(this@QuickBarsApp).loadEntities()

            if (AppPrefs.isPersistentConnectionEnabled(this@QuickBarsApp)) {
                AppPrefs.setBootReceiverEnabled(this@QuickBarsApp, true)
            }
            DemoModeManager.checkAndEnableDemoMode(this@QuickBarsApp)
        }
    }

    companion object {
        private lateinit var instance: QuickBarsApp

        var showToastOnEntityTrigger: Boolean
            get() = AppPrefs.isShowToastOnEntityTriggerEnabled(instance.applicationContext)
            set(value) = AppPrefs.setShowToastOnEntityTriggerEnabled(instance.applicationContext, value)

        fun getAppContext(): Context = instance.applicationContext
    }
}
