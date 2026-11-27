package dev.trooped.tvquickbars.ha.ws.handlers

import dev.trooped.tvquickbars.camera.CameraPipController
import dev.trooped.tvquickbars.camera.CameraRequest
import dev.trooped.tvquickbars.data.CategoryItem
import dev.trooped.tvquickbars.ha.ConnectionState
import dev.trooped.tvquickbars.ha.HomeAssistantClient
import dev.trooped.tvquickbars.ha.HomeAssistantListener
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.utils.AppUpdateManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class QuickBarsHandlersTest {

    @Test
    fun close_handler_accepts_custom_slot_and_defaults_to_close_all() {
        val allReq = QuickBarsCloseHandler.buildCloseRequest(JSONObject())
        assertEquals("close_all", allReq.action)

        val customSlotReq = QuickBarsCloseHandler.buildCloseRequest(JSONObject().put("slot", "doorbell_main"))
        assertEquals("close", customSlotReq.action)
        assertEquals("doorbell_main", customSlotReq.slot)
    }

    @Test
    fun config_handler_recognizes_new_v170_events() {
        val h = QuickBarsConfigHandler()
        assertTrue(h.canHandle(JSONObject().put("event_type", "quickbars.diagnostics")))
        assertTrue(h.canHandle(JSONObject().put("event_type", "quickbars.focus")))
        assertTrue(h.canHandle(JSONObject().put("event_type", "quickbars.config")))
        assertTrue(h.canHandle(JSONObject().put("event_type", "quickbars.ping")))
        assertFalse(h.canHandle(JSONObject().put("event_type", "quickbars.open")))
    }

    @Test
    fun config_handler_normalizes_opacity_keys() {
        assertEquals(65.0, QuickBarsConfigHandler.normalizeOpacity(JSONObject().put("default_opacity", 65)), 0.001)
        assertEquals(0.85, QuickBarsConfigHandler.normalizeOpacity(JSONObject().put("opacity", 0.85)), 0.001)
        assertTrue(QuickBarsConfigHandler.normalizeOpacity(JSONObject()).isNaN())
    }

    @Test
    fun subscribe_entities_dispatches_isolated_shallow_snapshot_and_clears_cache() {
        val handler = SubscribeEntitiesHandler()
        val received = mutableListOf<Triple<String, String, JSONObject>>()
        var stateChangedCalls = 0
        val listener = object : HomeAssistantListener {
            override fun onEntitiesFetched(categories: List<CategoryItem>) {}
            override fun onEntityStateChanged(entityId: String, newState: String) { stateChangedCalls++ }
            override fun onEntityStateUpdated(entityId: String, newState: String, attributes: JSONObject) {
                received += Triple(entityId, newState, attributes)
            }
            override fun onCameraRequest(req: CameraRequest) {}
        }
        val bridge = object : HaClientBridge {
            override val listener = listener
            override val knownIds = ConcurrentHashMap.newKeySet<String>()
            override fun getContext() = null
            override fun fireEvent(type: String, data: JSONObject) = true
            override fun send(obj: JSONObject) = true
            override fun nextId() = 1
            override fun updateConnectionState(state: ConnectionState) {}
            override fun getLatestStatesJson(): JSONArray? = null
        }

        val addEvent = JSONObject().put(
            "a",
            JSONObject().put(
                "light.kitchen",
                JSONObject().put("s", "on").put("a", JSONObject().put("brightness", 200))
            )
        )
        handler.handle(addEvent, bridge)
        assertEquals(1, received.size)
        assertEquals(0, stateChangedCalls)
        val firstSnapshot = received[0].third

        val deltaEvent = JSONObject().put(
            "c",
            JSONObject().put(
                "light.kitchen",
                JSONObject().put("+", JSONObject().put("a", JSONObject().put("brightness", 50)))
            )
        )
        handler.handle(deltaEvent, bridge)
        assertEquals(2, received.size)
        val secondSnapshot = received[1].third

        // Mutating/updating delta must NOT mutate earlier snapshot shared with compose/cache
        assertNotSame(firstSnapshot, secondSnapshot)
        assertEquals(200, firstSnapshot.getInt("brightness"))
        assertEquals(50, secondSnapshot.getInt("brightness"))

        handler.clearCache()
        val deltaAfterClear = JSONObject().put(
            "c",
            JSONObject().put(
                "light.kitchen",
                JSONObject().put("+", JSONObject().put("s", "off"))
            )
        )
        handler.handle(deltaAfterClear, bridge)
        assertEquals(3, received.size)
        assertNull(received[2].third.opt("brightness")?.takeIf { it == 50 })
    }

    @Test
    fun sanitize_rtsp_userinfo_does_not_double_encode_percent_escapes() {
        val inUrl = "rtsp://admin:N%40n33k123@192.168.5.199:554/h264Preview_01_sub"
        assertEquals(inUrl, CameraPipController.sanitizeRtspUserInfo(inUrl))

        val rawUrl = "rtsp://admin:N@n33k!@192.168.5.199:554/h264Preview_01_sub"
        assertEquals("rtsp://admin:N%40n33k%21@192.168.5.199:554/h264Preview_01_sub", CameraPipController.sanitizeRtspUserInfo(rawUrl))
    }

    @Test
    fun go2rtc_url_strips_query_to_avoid_rtsp_setup_400() {
        val base = "rtsp://192.168.1.146:8554/front_door?video&audio"
        assertEquals("rtsp://192.168.1.146:8554/front_door", CameraPipController.formatGo2RtcUrl(base, muteAudio = true))
        assertEquals("rtsp://192.168.1.146:8554/front_door", CameraPipController.formatGo2RtcUrl(base, muteAudio = false))
    }

    @Test
    fun fast_reconnect_works_without_saved_entities_and_call_service_uses_target_only() {
        assertTrue(HomeAssistantClient.shouldUseFastReconnect(useEntitySubscription = true, hasDiscoveredWatchIds = true))
        assertFalse(HomeAssistantClient.shouldUseFastReconnect(useEntitySubscription = false, hasDiscoveredWatchIds = true))

        val msg = HomeAssistantClient.buildCallServiceMessage(
            commandId = 9,
            domain = "light",
            service = "turn_on",
            entityId = "light.kitchen",
            data = JSONObject().put("entity_id", "light.kitchen").put("brightness", 128)
        )
        assertEquals("light.kitchen", msg.getJSONObject("target").getString("entity_id"))
        val sData = msg.getJSONObject("service_data")
        assertFalse(sData.has("entity_id"))
        assertEquals(128, sData.getInt("brightness"))
    }

    @Test
    fun semver_comparison_identifies_v180_newer_than_v170() {
        assertTrue(AppUpdateManager.compareSemVer("v1.8.0", "1.7.0") > 0)
        assertEquals(0, AppUpdateManager.compareSemVer("v1.8.0", "1.8.0"))
    }
}
