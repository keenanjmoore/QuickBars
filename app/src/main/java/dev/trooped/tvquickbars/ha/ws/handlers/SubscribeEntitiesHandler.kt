package dev.trooped.tvquickbars.ha.ws.handlers

import dev.trooped.tvquickbars.background.HAStateStore
import dev.trooped.tvquickbars.ha.ws.HaClientBridge
import dev.trooped.tvquickbars.ha.ws.WsHandler
import dev.trooped.tvquickbars.utils.EntityActionExecutor
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles compact Home Assistant `subscribe_entities` events (`a` added, `c` changed, `r` removed),
 * maintaining a merged local attribute snapshot per entity before dispatching immutable shallow snapshots.
 */
class SubscribeEntitiesHandler : WsHandler {

    private val attributeCache = ConcurrentHashMap<String, JSONObject>()
    private val stateCache = ConcurrentHashMap<String, String>()

    fun clearCache() {
        attributeCache.clear()
        stateCache.clear()
    }

    override fun canHandle(event: JSONObject): Boolean =
        !event.has("event_type") && (event.has("a") || event.has("c") || event.has("r"))

    override fun handle(event: JSONObject, ctx: HaClientBridge) {
        // 1) Initial snapshot / added entities ("a")
        event.optJSONObject("a")?.let { added ->
            val keys = added.keys()
            while (keys.hasNext()) {
                val entityId = keys.next()
                val obj = added.optJSONObject(entityId) ?: continue
                ctx.knownIds += entityId
                val state = obj.optString("s", "")
                val attrs = obj.optJSONObject("a") ?: JSONObject()
                val copyAttrs = shallowCopyJson(attrs)
                attributeCache[entityId] = copyAttrs
                if (state.isNotEmpty()) {
                    stateCache[entityId] = state
                    dispatchUpdate(entityId, state, shallowCopyJson(copyAttrs), ctx)
                }
            }
        }

        // 2) Incremental diffs ("c")
        event.optJSONObject("c")?.let { changed ->
            val keys = changed.keys()
            while (keys.hasNext()) {
                val entityId = keys.next()
                val diff = changed.optJSONObject(entityId) ?: continue
                ctx.knownIds += entityId

                val existingAttrs = attributeCache[entityId]
                    ?: HAStateStore.entitiesById.value[entityId]?.attributes?.let { shallowCopyJson(it) }
                    ?: JSONObject()

                var newState: String? = null

                diff.optJSONObject("+")?.let { plus ->
                    if (plus.has("s")) {
                        newState = plus.optString("s")
                    }
                    plus.optJSONObject("a")?.let { plusAttrs ->
                        val ak = plusAttrs.keys()
                        while (ak.hasNext()) {
                            val k = ak.next()
                            existingAttrs.put(k, plusAttrs.get(k))
                        }
                    }
                }

                diff.optJSONObject("-")?.let { minus ->
                    minus.optJSONArray("a")?.let { minusAttrs ->
                        for (i in 0 until minusAttrs.length()) {
                            existingAttrs.remove(minusAttrs.optString(i))
                        }
                    }
                }

                attributeCache[entityId] = existingAttrs
                val effectiveState = newState
                    ?: stateCache[entityId]
                    ?: HAStateStore.entitiesById.value[entityId]?.state
                    ?: continue
                stateCache[entityId] = effectiveState

                dispatchUpdate(entityId, effectiveState, shallowCopyJson(existingAttrs), ctx)
            }
        }

        // 3) Removed entities ("r")
        event.optJSONArray("r")?.let { removed ->
            for (i in 0 until removed.length()) {
                val entityId = removed.optString(i)
                if (entityId.isNotBlank()) {
                    ctx.knownIds.remove(entityId)
                    attributeCache.remove(entityId)
                    stateCache.remove(entityId)
                    dispatchUpdate(entityId, "unavailable", JSONObject(), ctx)
                }
            }
        }
    }

    private fun dispatchUpdate(
        entityId: String,
        newState: String,
        attributesSnapshot: JSONObject,
        ctx: HaClientBridge
    ) {
        EntityActionExecutor.QuickBarDataCache.updateEntityState(entityId, newState)
        EntityActionExecutor.QuickBarDataCache.updateEntityAttributes(entityId, attributesSnapshot)
        ctx.listener.onEntityStateUpdated(entityId, newState, attributesSnapshot)
    }

    internal companion object {
        fun shallowCopyJson(src: JSONObject): JSONObject {
            val dst = JSONObject()
            val it = src.keys()
            while (it.hasNext()) {
                val k = it.next()
                dst.put(k, src.get(k))
            }
            return dst
        }
    }
}
