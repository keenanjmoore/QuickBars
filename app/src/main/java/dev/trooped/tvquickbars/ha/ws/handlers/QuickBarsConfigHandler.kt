package dev.trooped.tvquickbars.ha.ws.handlers

import android.util.Log
import dev.trooped.tvquickbars.background.BackgroundHaConnectionManager
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.ha.ws.TargetMatcher
import dev.trooped.tvquickbars.ha.ws.WsHandler
import dev.trooped.tvquickbars.persistence.AppPrefs
import org.json.JSONObject

/**
 * Handles remote management events from Home Assistant:
 * - `quickbars.config`: updates DND, quiet hours, default camera auto-hide, max PiP slots,
 *   RTSP latency mode, auto-dock, telemetry interval, or device alias cleanly over WebSocket.
 * - `quickbars.ping`: immediately emits `quickbars.telemetry` for live HA dashboard/watchdog checks.
 */
class QuickBarsConfigHandler : WsHandler {
    private val TAG = "QuickBarsConfig"

    override fun canHandle(event: JSONObject): Boolean {
        val type = event.optString("event_type")
        return type == "quickbars.config" || type == "quickbars.ping"
    }

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext() ?: return
        val type = event.optString("event_type")
        val data = event.optJSONObject("data") ?: JSONObject()

        if (!TargetMatcher.matches(context, data)) {
            return
        }

        if (type == "quickbars.ping") {
            BackgroundHaConnectionManager.publishTelemetryNow(context, trigger = "ping")
            return
        }

        var changed = false
        if (data.has("dnd")) {
            AppPrefs.setDndEnabled(context, data.optBoolean("dnd", false))
            changed = true
        }
        if (data.has("auto_dock")) {
            AppPrefs.setAutoDockEnabled(context, data.optBoolean("auto_dock", true))
            changed = true
        }
        if (data.has("default_auto_hide")) {
            AppPrefs.setDefaultCameraAutoHideSeconds(context, data.optInt("default_auto_hide", 30))
            changed = true
        }
        if (data.has("pip_max_slots")) {
            AppPrefs.setPipMaxSlots(context, data.optInt("pip_max_slots", 4))
            changed = true
        }
        if (data.has("quiet_hours_enabled") || data.has("quiet_hours_start") || data.has("quiet_hours_end")) {
            val enabled = if (data.has("quiet_hours_enabled")) {
                data.optBoolean("quiet_hours_enabled")
            } else {
                AppPrefs.isQuietHoursEnabled(context)
            }
            val start = if (data.has("quiet_hours_start")) {
                data.optInt("quiet_hours_start")
            } else {
                AppPrefs.getQuietHoursStartHour(context)
            }
            val end = if (data.has("quiet_hours_end")) {
                data.optInt("quiet_hours_end")
            } else {
                AppPrefs.getQuietHoursEndHour(context)
            }
            AppPrefs.setQuietHoursConfig(context, enabled, start, end)
            changed = true
        }
        if (data.has("telemetry_interval")) {
            AppPrefs.setTelemetryIntervalSec(context, data.optInt("telemetry_interval", 60))
            changed = true
        }
        if (data.has("rtsp_latency")) {
            val lat = data.optString("rtsp_latency", "").trim()
            AppPrefs.setRtspLatencyMode(context, lat.takeIf { it.isNotEmpty() })
            changed = true
        }
        if (data.has("device_alias")) {
            val alias = data.optString("device_alias", "").trim()
            AppPrefs.setHaDeviceAlias(context, alias.takeIf { it.isNotEmpty() })
            changed = true
        }
        val rawOpacity = when {
            data.has("default_opacity") -> data.optDouble("default_opacity", Double.NaN)
            data.has("opacity") -> data.optDouble("opacity", Double.NaN)
            else -> Double.NaN
        }
        if (!rawOpacity.isNaN()) {
            val norm = (if (rawOpacity > 1.0) rawOpacity / 100.0 else rawOpacity).coerceIn(0.25, 1.0).toFloat()
            AppPrefs.setDefaultPipOpacity(context, norm)
            BackgroundHaConnectionManager.applyLiveOpacityToActivePips(norm)
            changed = true
        }

        if (changed) {
            Log.i(TAG, "Applied remote config update from HA: $data")
            BackgroundHaConnectionManager.publishTelemetryNow(context, trigger = "config")
        }
    }
}
