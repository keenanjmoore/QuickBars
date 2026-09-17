package dev.trooped.tvquickbars.ha.ws.handlers

import dev.trooped.tvquickbars.camera.CameraRequest
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import org.json.JSONObject
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import dev.trooped.tvquickbars.data.AppIdProvider

class QuickBarsOpenHandler : dev.trooped.tvquickbars.ha.ws.WsHandler {
    private val TAG = "QuickBarsOpenHandler"

    override fun canHandle(event: JSONObject): Boolean {
        val eventType = event.optString("event_type")
        return eventType == "quickbars.open" || eventType == "quickbars.close"
    }

    @OptIn(UnstableApi::class)
    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext()
        if (context == null) {
            androidx.media3.common.util.Log.w(TAG, "Cannot handle event: Context is not available.")
            return
        }

        val eventType = event.optString("event_type")
        val data = event.optJSONObject("data") ?: run {
            Log.w("HAClient", "$eventType event had no data payload")
            return
        }

        val targetId = data.optString("id", "")
        if (targetId.isNotEmpty()) {
            val myId = AppIdProvider.get(context) ?: ""
            if (!targetId.equals(myId, ignoreCase = true)) {
                Log.d("HAClient", "$eventType ignored (targetId=$targetId, myId=$myId)")
                return
            }
        }

        // Explicit quickbars.close event support
        if (eventType == "quickbars.close") {
            val closeAll = data.optBoolean("close_all", false)
            if (closeAll || (!data.has("camera_entity") && !data.has("camera_alias") && !data.has("slot"))) {
                ctx.listener.onCameraRequest(CameraRequest(action = "close_all"))
            } else {
                val req = parseCameraRequest(data).copy(action = "close")
                ctx.listener.onCameraRequest(req)
            }
            return
        }

        // Action specified in quickbars.open
        val action = data.optString("action", "").lowercase()
        if (action == "close_all") {
            ctx.listener.onCameraRequest(CameraRequest(action = "close_all"))
            return
        }
        if (action == "close") {
            val req = parseCameraRequest(data).copy(action = "close")
            ctx.listener.onCameraRequest(req)
            return
        }

        // ── Multi-Camera Group Array ─────────────────────────────────────────
        val camerasArray = data.optJSONArray("cameras")
        if (camerasArray != null && camerasArray.length() > 0) {
            val subReqs = mutableListOf<CameraRequest>()
            for (i in 0 until camerasArray.length()) {
                val item = camerasArray.get(i)
                when (item) {
                    is JSONObject -> subReqs.add(parseCameraRequest(item))
                    is String -> {
                        val trimmed = item.trim()
                        when {
                            trimmed.startsWith("camera.") -> subReqs.add(CameraRequest(cameraEntity = trimmed))
                            trimmed.startsWith("rtsp://", ignoreCase = true) -> subReqs.add(CameraRequest(rtspUrl = trimmed))
                            else -> subReqs.add(CameraRequest(cameraAlias = trimmed))
                        }
                    }
                }
            }
            if (subReqs.isNotEmpty()) {
                val groupReq = parseCameraRequest(data).copy(
                    cameras = subReqs,
                    action = "open"
                )
                ctx.listener.onCameraRequest(groupReq)
                return
            }
        }

        // ── Single Camera Request ────────────────────────────────────────────
        val rtspFromEvent = if (data.has("rtsp_url") && !data.isNull("rtsp_url")) {
            data.optString("rtsp_url", "").trim()
                .takeIf { it.startsWith("rtsp://", ignoreCase = true) }
        } else null

        val hasCameraPayload = data.has("camera_alias") || data.has("camera_entity") || (rtspFromEvent != null)
        if (hasCameraPayload) {
            val req = parseCameraRequest(data)
            ctx.listener.onCameraRequest(req)
            return
        }

        // Legacy quickbar alias or legacy camera alias
        val alias = data.optString("alias", "")
        val cameraAliasLegacy = data.optString("camera_alias", "")

        when {
            alias.isNotBlank() -> ctx.listener.onQuickBarAliasTriggered(alias)
            cameraAliasLegacy.isNotBlank() -> ctx.listener.onCameraRequest(CameraRequest(cameraAlias = cameraAliasLegacy))
            else -> Log.w("HAClient", "QuickBar trigger without camera/alias: $data")
        }
    }

    private fun parseCameraRequest(obj: JSONObject): CameraRequest {
        val rtspFromEvent = if (obj.has("rtsp_url") && !obj.isNull("rtsp_url")) {
            obj.optString("rtsp_url", "").trim()
                .takeIf { it.startsWith("rtsp://", ignoreCase = true) }
        } else null

        val sizePx = obj.optJSONObject("size_px")

        return CameraRequest(
            cameraAlias = obj.optString("camera_alias", null)?.takeIf { it.isNotBlank() },
            cameraEntity = obj.optString("camera_entity", null)?.takeIf { it.isNotBlank() },
            position = obj.optString("position", null)?.takeIf { it.isNotBlank() },
            size = obj.optString("size", null)?.takeIf { it.isNotBlank() },
            sizePxW = sizePx?.optInt("w"),
            sizePxH = sizePx?.optInt("h"),
            autoHideSec = if (obj.has("auto_hide")) obj.optInt("auto_hide") else null,
            showTitle = if (obj.has("show_title")) obj.optBoolean("show_title") else null,
            rtspUrl = rtspFromEvent,
            customTitle = obj.optString("camera_title", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) }
                ?: obj.optString("title", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            muteAudio = if (obj.has("mute_audio")) obj.optBoolean("mute_audio") else null,
            showToggleToast = if (obj.has("show_toast")) obj.optBoolean("show_toast") else null,
            rtspTransport = obj.optString("rtsp_transport", null),
            rtspLatency = obj.optString("rtsp_latency", null),
            useSoftwareDecoder = if (obj.has("software_decoder")) obj.optBoolean("software_decoder") else null,
            action = obj.optString("action", null)?.takeIf { it.isNotBlank() },
            slot = obj.optString("slot", null)?.takeIf { it.isNotBlank() },
            layout = obj.optString("layout", null)?.takeIf { it.isNotBlank() },
            allowMultiple = if (obj.has("allow_multiple")) obj.optBoolean("allow_multiple") else true
        )
    }
}
