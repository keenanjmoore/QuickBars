package dev.trooped.tvquickbars.ha.ws

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetMatcherTest {

    private val myId = "3NNZ59AM"
    private val aliases = setOf("3nnz59am", "dining_room_shield", "shield_android_tv")

    @Test
    fun broadcast_when_no_target_keys_present() {
        val payload = JSONObject().put("rtsp_url", "rtsp://192.168.1.146:8554/front_door")
        assertTrue(TargetMatcher.matchesWithIdentity(payload, myId, aliases))
    }

    @Test
    fun fail_closed_when_explicit_target_ids_empty() {
        val payload = JSONObject().put("target_ids", JSONArray())
        assertFalse(TargetMatcher.matchesWithIdentity(payload, myId, aliases))
    }

    @Test
    fun fail_closed_when_explicit_target_id_blank() {
        val payload = JSONObject().put("target_id", "")
        assertFalse(TargetMatcher.matchesWithIdentity(payload, myId, aliases))
    }

    @Test
    fun fail_closed_when_explicit_id_blank() {
        val payload = JSONObject().put("id", "   ")
        assertFalse(TargetMatcher.matchesWithIdentity(payload, myId, aliases))
    }

    @Test
    fun matches_wildcard_all() {
        val payload = JSONObject().put("target_ids", JSONArray().put("all"))
        assertTrue(TargetMatcher.matchesWithIdentity(payload, myId, aliases))
    }

    @Test
    fun matches_normalized_app_id_case_insensitive() {
        val payload = JSONObject().put("target_ids", JSONArray().put("3nnz-59am"))
        assertTrue(TargetMatcher.matchesWithIdentity(payload, myId, aliases))
    }

    @Test
    fun matches_device_alias_slug() {
        val payload = JSONObject().put("target", "Dining Room Shield")
        assertTrue(TargetMatcher.matchesWithIdentity(payload, myId, aliases))
    }

    @Test
    fun rejects_other_device_id() {
        val payload = JSONObject().put("target_ids", JSONArray().put("PAWN0F35").put("C4C9M9WK"))
        assertFalse(TargetMatcher.matchesWithIdentity(payload, myId, aliases))
    }
}
