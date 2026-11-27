package dev.trooped.tvquickbars.ha.ws.handlers

import android.os.Handler
import android.os.Looper
import dev.trooped.tvquickbars.camera.CameraRequest
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.ha.ws.TargetMatcher
import org.json.JSONObject

class QuickBarsCloseHandler : dev.trooped.tvquickbars.ha.ws.WsHandler {
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun canHandle(event: JSONObject): Boolean =
        event.optString("event_type") == "quickbars.close"

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext() ?: return
        val data = event.optJSONObject("data") ?: JSONObject()

        if (!TargetMatcher.matches(context, data)) {
            return
        }

        val req = buildCloseRequest(data)
        mainHandler.post {
            ctx.listener.onCameraRequest(req)
        }
    }

    internal companion object {
        fun buildCloseRequest(data: JSONObject): CameraRequest {
            val closeAll = data.optBoolean("close_all", false)
            val entity = data.optString("camera_entity", null)
                ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            val alias = data.optString("camera_alias", null)
                ?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            val slot = data.optString("slot", null)
                ?.takeIf { it.isNotBlank() && !it.equals("null", true) }

            return if (closeAll || (entity == null && alias == null && slot == null)) {
                CameraRequest(action = "close_all")
            } else {
                CameraRequest(
                    cameraEntity = entity,
                    cameraAlias = alias,
                    slot = slot,
                    action = "close"
                )
            }
        }
    }
}
