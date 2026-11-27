package dev.trooped.tvquickbars.ha.ws.handlers

import android.util.Log
import dev.trooped.tvquickbars.camera.CameraRequest
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.ha.ws.TargetMatcher
import org.json.JSONObject

class QuickBarsOpenHandler : dev.trooped.tvquickbars.ha.ws.WsHandler {
    private val TAG = "QuickBarsOpenHandler"

    override fun canHandle(event: JSONObject): Boolean {
        val eventType = event.optString("event_type")
        return eventType == "quickbars.open"
    }

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val context = ctx.getContext() ?: run {
            Log.w(TAG, "Cannot handle event: Context is not available.")
            return
        }

        val eventType = event.optString("event_type")
        val data = event.optJSONObject("data") ?: run {
            Log.w("HAClient", "$eventType event had no data payload")
            return
        }

        if (!TargetMatcher.matches(context, data)) {
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
            cameraAlias = obj.optString("camera_alias", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            cameraEntity = obj.optString("camera_entity", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            position = (obj.optString("position", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) }
                ?: obj.optString("corner", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) }),
            size = obj.optString("size", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            sizePxW = sizePx?.optInt("w"),
            sizePxH = sizePx?.optInt("h"),
            autoHideSec = if (obj.has("auto_hide") && !obj.isNull("auto_hide")) obj.optInt("auto_hide") else null,
            showTitle = if (obj.has("show_title") && !obj.isNull("show_title")) obj.optBoolean("show_title") else null,
            rtspUrl = rtspFromEvent,
            customTitle = obj.optString("camera_title", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) }
                ?: obj.optString("title", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            muteAudio = if (obj.has("mute_audio") && !obj.isNull("mute_audio")) obj.optBoolean("mute_audio") else null,
            showToggleToast = if (obj.has("show_toast") && !obj.isNull("show_toast")) obj.optBoolean("show_toast") else null,
            rtspTransport = obj.optString("rtsp_transport", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            rtspLatency = obj.optString("rtsp_latency", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            useSoftwareDecoder = if (obj.has("software_decoder") && !obj.isNull("software_decoder")) obj.optBoolean("software_decoder") else null,
            action = obj.optString("action", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            slot = obj.optString("slot", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            layout = obj.optString("layout", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            allowMultiple = if (obj.has("allow_multiple") && !obj.isNull("allow_multiple")) obj.optBoolean("allow_multiple") else true,
            onRetrigger = obj.optString("on_retrigger", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            aspectRatio = obj.optString("aspect_ratio", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            autoCloseOnSleep = if (obj.has("auto_close_on_sleep") && !obj.isNull("auto_close_on_sleep")) obj.optBoolean("auto_close_on_sleep") else null,
            priority = obj.optString("priority", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            borderColor = obj.optString("border_color", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            borderWidthDp = if (obj.has("border_width_dp") && !obj.isNull("border_width_dp")) obj.optInt("border_width_dp") else null,
            cornerRadiusDp = if (obj.has("corner_radius_dp") && !obj.isNull("corner_radius_dp")) obj.optInt("corner_radius_dp") else null,
            opacity = if (obj.has("opacity") && !obj.isNull("opacity")) {
                val raw = obj.optDouble("opacity", Double.NaN)
                if (raw.isNaN()) null else (if (raw > 1.0) raw / 100.0 else raw).coerceIn(0.25, 1.0).toFloat()
            } else null,
            marginDp = if (obj.has("margin_dp") && !obj.isNull("margin_dp")) obj.optInt("margin_dp") else null,
            showLiveBadge = if (obj.has("show_live_badge") && !obj.isNull("show_live_badge")) obj.optBoolean("show_live_badge") else null,
            showTimestamp = if (obj.has("show_timestamp") && !obj.isNull("show_timestamp")) obj.optBoolean("show_timestamp") else null,
            showCountdownBar = if (obj.has("show_countdown_bar") && !obj.isNull("show_countdown_bar")) obj.optBoolean("show_countdown_bar") else null,
            snapshotEntity = obj.optString("snapshot_entity", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            speakText = obj.optString("speak_text", null)?.takeIf { it.isNotBlank() && !it.equals("null", true) }
        )
    }
}
