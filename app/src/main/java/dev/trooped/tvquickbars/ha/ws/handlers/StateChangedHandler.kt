package dev.trooped.tvquickbars.ha.ws.handlers

import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.utils.EntityActionExecutor
import org.json.JSONObject

class StateChangedHandler : dev.trooped.tvquickbars.ha.ws.WsHandler {
    override fun canHandle(event: JSONObject): Boolean =
        event.optString("event_type") == "state_changed"

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        val data = event.optJSONObject("data") ?: return
        val entityId = data.optString("entity_id", null) ?: return

        ctx.knownIds += entityId

        val newStateObj = data.optJSONObject("new_state")
        val newState = newStateObj?.optString("state") ?: return
        val attributes = newStateObj.optJSONObject("attributes") ?: JSONObject()

        EntityActionExecutor.QuickBarDataCache.updateEntityState(entityId, newState)

        // Preserve fan 'turning-on-to-target-speed' interception without duplicate map copies
        if (entityId.startsWith("fan.") && newState == "on") {
            val targetEntity = EntityActionExecutor.QuickBarDataCache.cachedEntities
                .find { it.id == entityId }

            val targetSpeed = targetEntity?.lastKnownState?.get("fan_turning_on_to") as? Number
            val reportedSpeed = attributes.optInt("percentage", -1)

            if (targetSpeed != null && reportedSpeed > 0) {
                if (reportedSpeed == targetSpeed.toInt()) {
                    targetEntity.lastKnownState.remove("fan_turning_on_to")
                } else {
                    val modified = SubscribeEntitiesHandler.shallowCopyJson(attributes)
                    modified.put("percentage", targetSpeed.toInt())
                    EntityActionExecutor.QuickBarDataCache.updateEntityAttributes(entityId, modified)
                    ctx.listener.onEntityStateUpdated(entityId, newState, modified)
                    return
                }
            }
        }

        EntityActionExecutor.QuickBarDataCache.updateEntityAttributes(entityId, attributes)
        ctx.listener.onEntityStateUpdated(entityId, newState, attributes)
    }
}
