package dev.trooped.tvquickbars.persistence

import android.content.ComponentName
import android.content.Context
import android.content.Context.MODE_PRIVATE
import android.content.pm.PackageManager
import androidx.core.content.edit
import dev.trooped.tvquickbars.receivers.BootCompletedReceiver

/**
 * Utility object for managing application preferences.
 *
 * This object provides methods for accessing and modifying shared preferences
 * related to the application's behavior.
 */
object AppPrefs {
    private const val FILE = "app_prefs"
    private const val KEY_PERSISTENT_CONN = "pref_persistent_connection_enabled"
    private const val KEY_FIRST_TIME_SETUP = "pref_first_time_setup_in_progress"
    private const val KEY_LAST_SEEN_VERSION = "pref_last_seen_version"
    private const val KEY_SHOW_TOAST_ON_ENTITY_TRIGGER = "pref_show_toast_on_entity_trigger"
    private const val KEY_DND_ENABLED = "pref_dnd_enabled"
    private const val KEY_AUTO_DOCK_ENABLED = "pref_auto_dock_enabled"
    private const val KEY_DEFAULT_CAMERA_AUTO_HIDE = "pref_default_camera_auto_hide"
    private const val KEY_PIP_MAX_SLOTS = "pref_pip_max_slots"
    private const val KEY_QUIET_HOURS_ENABLED = "pref_quiet_hours_enabled"
    private const val KEY_QUIET_HOURS_START = "pref_quiet_hours_start_hour"
    private const val KEY_QUIET_HOURS_END = "pref_quiet_hours_end_hour"
    private const val KEY_TELEMETRY_INTERVAL_SEC = "pref_telemetry_interval_sec"
    private const val KEY_RT_LATENCY_MODE = "pref_rtsp_latency_mode"
    private const val KEY_HA_DEVICE_ALIAS = "pref_ha_device_alias"
    private const val KEY_DEFAULT_PIP_OPACITY = "pref_default_pip_opacity"

    @Volatile private var cachedVersionCode: Int? = null

    fun isPersistentConnectionEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE)
            .getBoolean(KEY_PERSISTENT_CONN, false)

    fun setPersistentConnectionEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            putBoolean(KEY_PERSISTENT_CONN, enabled)
        }
        setBootReceiverEnabled(ctx, enabled)
    }

    fun hasPersistentConnectionFlag(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).contains(KEY_PERSISTENT_CONN)


    fun isFirstTimeSetupInProgress(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE)
            .getBoolean(KEY_FIRST_TIME_SETUP, false)

    fun setFirstTimeSetupInProgress(ctx: Context, inProgress: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE)
            .edit {
                putBoolean(KEY_FIRST_TIME_SETUP, inProgress)
            }
    }

    /**
     * Enables or disables the BOOT_COMPLETED receiver component.
     * Call this ONLY from setPersistentConnectionEnabled or one-time at app start to resync.
     */
    fun setBootReceiverEnabled(ctx: Context, enabled: Boolean) {
        val pm = ctx.packageManager
        val cn = ComponentName(ctx, BootCompletedReceiver::class.java)
        pm.setComponentEnabledSetting(
            cn,
            if (enabled)
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP
        )
    }


    /**
     * Gets the last app version code that showed the "What's New" dialog
     */
    fun getLastSeenVersion(ctx: Context): Int {
        return ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_LAST_SEEN_VERSION, 0)
    }

    /**
     * Sets the last seen version code after showing the "What's New" dialog
     */
    fun setLastSeenVersion(ctx: Context, versionCode: Int) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            putInt(KEY_LAST_SEEN_VERSION, versionCode)
        }
    }

    /**
     * Checks if the current app version is newer than the last version the user saw
     * Returns true if the app has been updated since last launch
     */
    fun isAppUpdated(ctx: Context): Boolean {
        val currentVersion = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
        val lastSeenVersion = getLastSeenVersion(ctx)
        return currentVersion > lastSeenVersion
    }

    /**
     * Gets the current app version code
     */
    fun getCurrentAppVersion(ctx: Context): Int {
        cachedVersionCode?.let { return it }
        val vc = ctx.packageManager.getPackageInfo(ctx.packageName, 0).longVersionCode.toInt()
        cachedVersionCode = vc
        return vc
    }

    fun isDndEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_DND_ENABLED, false)

    fun setDndEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit { putBoolean(KEY_DND_ENABLED, enabled) }
    }

    fun isAutoDockEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_AUTO_DOCK_ENABLED, true)

    fun setAutoDockEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit { putBoolean(KEY_AUTO_DOCK_ENABLED, enabled) }
    }

    fun getDefaultCameraAutoHideSeconds(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_DEFAULT_CAMERA_AUTO_HIDE, 30)

    fun setDefaultCameraAutoHideSeconds(ctx: Context, seconds: Int) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            putInt(KEY_DEFAULT_CAMERA_AUTO_HIDE, seconds.coerceIn(0, 600))
        }
    }

    fun getPipMaxSlots(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_PIP_MAX_SLOTS, 4).coerceIn(1, 4)

    fun setPipMaxSlots(ctx: Context, maxSlots: Int) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            putInt(KEY_PIP_MAX_SLOTS, maxSlots.coerceIn(1, 4))
        }
    }

    fun isQuietHoursEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getBoolean(KEY_QUIET_HOURS_ENABLED, false)

    fun getQuietHoursStartHour(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_QUIET_HOURS_START, 23)

    fun getQuietHoursEndHour(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_QUIET_HOURS_END, 7)

    fun setQuietHoursConfig(ctx: Context, enabled: Boolean, startHour: Int, endHour: Int) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            putBoolean(KEY_QUIET_HOURS_ENABLED, enabled)
            putInt(KEY_QUIET_HOURS_START, startHour.coerceIn(0, 23))
            putInt(KEY_QUIET_HOURS_END, endHour.coerceIn(0, 23))
        }
    }

    /** Returns true if current wall-clock hour falls inside configured quiet hours window. */
    fun isCurrentlyInQuietHours(ctx: Context): Boolean {
        if (!isQuietHoursEnabled(ctx)) return false
        val start = getQuietHoursStartHour(ctx)
        val end = getQuietHoursEndHour(ctx)
        if (start == end) return false
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return if (start < end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    fun getTelemetryIntervalSec(ctx: Context): Int =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getInt(KEY_TELEMETRY_INTERVAL_SEC, 300).coerceIn(15, 3600)

    fun setTelemetryIntervalSec(ctx: Context, seconds: Int) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            putInt(KEY_TELEMETRY_INTERVAL_SEC, seconds.coerceIn(15, 3600))
        }
    }

    fun getRtspLatencyMode(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getString(KEY_RT_LATENCY_MODE, null)

    fun setRtspLatencyMode(ctx: Context, mode: String?) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            if (mode.isNullOrBlank()) remove(KEY_RT_LATENCY_MODE) else putString(KEY_RT_LATENCY_MODE, mode)
        }
    }

    fun getHaDeviceAlias(ctx: Context): String? =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getString(KEY_HA_DEVICE_ALIAS, null)

    fun setHaDeviceAlias(ctx: Context, alias: String?) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            if (alias.isNullOrBlank()) remove(KEY_HA_DEVICE_ALIAS) else putString(KEY_HA_DEVICE_ALIAS, alias.trim())
        }
        dev.trooped.tvquickbars.ha.ws.TargetMatcher.invalidateCache()
    }

    fun getDefaultPipOpacity(ctx: Context): Float =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).getFloat(KEY_DEFAULT_PIP_OPACITY, 0.95f).coerceIn(0.25f, 1.0f)

    fun setDefaultPipOpacity(ctx: Context, opacity: Float) {
        val clamped = opacity.coerceIn(0.25f, 1.0f)
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            putFloat(KEY_DEFAULT_PIP_OPACITY, clamped)
        }
    }

    fun isShowToastOnEntityTriggerEnabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(FILE, MODE_PRIVATE)
            // default = true if not yet written
            .getBoolean(KEY_SHOW_TOAST_ON_ENTITY_TRIGGER, true)

    fun setShowToastOnEntityTriggerEnabled(ctx: Context, enabled: Boolean) {
        ctx.getSharedPreferences(FILE, MODE_PRIVATE).edit {
            putBoolean(KEY_SHOW_TOAST_ON_ENTITY_TRIGGER, enabled)
        }
    }

    /**
     * Ensure the show-toast flag exists and defaults to true.
     * Called once at app startup so both new and existing users start with it enabled.
     */
    fun ensureShowToastOnEntityTriggerDefault(ctx: Context) {
        val prefs = ctx.getSharedPreferences(FILE, MODE_PRIVATE)
        if (!prefs.contains(KEY_SHOW_TOAST_ON_ENTITY_TRIGGER)) {
            prefs.edit {
                putBoolean(KEY_SHOW_TOAST_ON_ENTITY_TRIGGER, true)
            }
        }
    }
}