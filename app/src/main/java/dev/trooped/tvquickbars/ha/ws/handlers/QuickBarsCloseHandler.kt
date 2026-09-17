package dev.trooped.tvquickbars.ha.ws.handlers

import dev.trooped.tvquickbars.camera.CameraRequest
import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import org.json.JSONObject

class QuickBarsCloseHandler : dev.trooped.tvquickbars.ha.ws.WsHandler {
    override fun canHandle(event: JSONObject): Boolean =
        event.optString("event_type") == "quickbars.close"

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext() ?: return
        val data = event.optJSONObject("data")

        val targetId = data?.optString("id", "") ?: ""
        if (targetId.isNotEmpty()) {
            val myId = AppIdProvider.get(context) ?: ""
            if (!targetId.equals(myId, ignoreCase = true)) {
                return
            }
        }

        val closeAll = data?.optBoolean("close_all", false) ?: false
        val entity = data?.optString("camera_entity", null)?.takeIf { it.isNotBlank() }
        val alias = data?.optString("camera_alias", null)?.takeIf { it.isNotBlank() }
        val slot = data?.optString("slot", null)?.takeIf { it.isNotBlank() }

        if (closeAll || (entity == null && alias == null && slot == null)) {
            ctx.listener.onCameraRequest(CameraRequest(action = "close_all"))
        } else {
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
