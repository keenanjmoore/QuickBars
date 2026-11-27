package dev.trooped.tvquickbars.ha.ws.handlers

import android.util.Log
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.ha.ws.TargetMatcher
import dev.trooped.tvquickbars.notification.NotificationSpec
import dev.trooped.tvquickbars.notification.toNotificationSpec
import dev.trooped.tvquickbars.persistence.AppPrefs
import org.json.JSONObject

class QuickBarsNotifyHandler : dev.trooped.tvquickbars.ha.ws.WsHandler {
    private val TAG = "QuickBarsNotifyHandler"

    override fun canHandle(event: JSONObject): Boolean =
        event.optString("event_type") == "quickbars.notify"

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext() ?: run {
            Log.w(TAG, "Cannot handle notification event: Context is not available.")
            return
        }

        val data = event.optJSONObject("data") ?: return

        if (!TargetMatcher.matches(context, data)) {
            return
        }

        val prio = data.optString("priority", "normal").lowercase()
        val bypassAll = data.optBoolean("force", false) ||
            data.optBoolean("bypass_dnd", false) ||
            prio == "critical"
        val bypassQuietHours = bypassAll || prio == "high"

        if (!bypassAll && AppPrefs.isDndEnabled(context)) {
            Log.i(TAG, "Notification suppressed by Cinema DND")
            return
        }
        if (!bypassQuietHours && AppPrefs.isCurrentlyInQuietHours(context)) {
            Log.i(TAG, "Notification suppressed by Quiet Hours")
            return
        }

        val cid = data.optString("cid", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        val spec: NotificationSpec = data.toNotificationSpec(cid)
        ctx.listener.onNotifyReceived(spec)
    }
}
