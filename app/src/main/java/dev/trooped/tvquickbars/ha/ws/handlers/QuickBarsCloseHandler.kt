package dev.trooped.tvquickbars.ha.ws.handlers

import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.trooped.tvquickbars.camera.CameraRequest
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.ha.ws.TargetMatcher
import org.json.JSONObject

class QuickBarsCloseHandler : dev.trooped.tvquickbars.ha.ws.WsHandler {
    override fun canHandle(event: JSONObject): Boolean =
        event.optString("event_type") == "quickbars.close"

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext() ?: return
        val data = event.optJSONObject("data") ?: JSONObject()

        if (!TargetMatcher.matches(context, data)) {
            return
        }

        val closeAll = data.optBoolean("close_all", false)
        val entity = data.optString("camera_entity", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        val alias = data.optString("camera_alias", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        val slot = data.optString("slot", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            ?.takeIf { it.uppercase() in ALLOWED_SLOTS || it.lowercase() in ALLOWED_SLOTS }

        val mainHandler = Handler(Looper.getMainLooper())
        if (closeAll || (entity == null && alias == null && slot == null)) {
            mainHandler.post {
                ctx.listener.onCameraRequest(CameraRequest(action = "close_all"))
            }
        } else {
            mainHandler.post {
                ctx.listener.onCameraRequest(
                    CameraRequest(
                        cameraEntity = entity,
                        cameraAlias = alias,
                        slot = slot,
                        action = "close"
                    )
                )
            }
        }
    }

    private companion object {
        val ALLOWED_SLOTS = setOf(
            "top_left", "top_right", "bottom_left", "bottom_right",
            "TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT",
            "default", "slot_0", "slot_1", "slot_2", "slot_3"
        )
    }
}
