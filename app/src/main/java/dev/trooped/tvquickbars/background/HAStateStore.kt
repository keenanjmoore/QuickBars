package dev.trooped.tvquickbars.background

import dev.trooped.tvquickbars.data.CategoryItem
import dev.trooped.tvquickbars.data.EntityItem
import dev.trooped.tvquickbars.ha.ConnectionState
import dev.trooped.tvquickbars.ui.EntityIconMapper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages the state of the Home Assistant connection and entities.
 * Uses synchronized single-copy updates to minimize map cloning overhead across events.
 */
object HAStateStore {
    private val mapLock = Any()

    private val _connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection

    private val _categories = MutableStateFlow<List<CategoryItem>>(emptyList())

    private val _entitiesById = MutableStateFlow<Map<String, EntityItem>>(emptyMap())
    val entitiesById: StateFlow<Map<String, EntityItem>> = _entitiesById

    private val _activePipIds = MutableStateFlow<List<String>>(emptyList())
    val activePipIds: StateFlow<List<String>> = _activePipIds

    private val pipIdsByHost = ConcurrentHashMap<String, List<String>>()

    private val _foregroundApp = MutableStateFlow<String?>(null)
    val foregroundApp: StateFlow<String?> = _foregroundApp

    @Volatile var lastTtffMs: Long = 0L
        private set

    fun setLastTtffMs(ms: Long) {
        if (ms > 0L) lastTtffMs = ms
    }

    private val FULLSCREEN_MEDIA_PACKAGES = setOf(
        "com.plexapp.android",
        "com.netflix.ninja",
        "com.google.android.youtube.tv",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        "com.hbo.hbonow",
        "com.wbd.stream",
        "com.apple.atve.androidtv.appletv",
        "org.xbmc.kodi",
        "tv.twitch.android.app",
        "com.emby.embytv",
        "org.jellyfin.androidtv",
        "com.peacocktv.peacockandroid",
        "com.cbs.app"
    )

    fun setConnection(state: ConnectionState) { _connection.value = state }

    fun setForegroundApp(pkg: String?) {
        if (!pkg.isNullOrBlank() && _foregroundApp.value != pkg) {
            _foregroundApp.value = pkg
        }
    }

    fun isFullscreenMediaApp(): Boolean =
        _foregroundApp.value in FULLSCREEN_MEDIA_PACKAGES

    fun setActivePipIds(ids: List<String>, hostKey: String = "service") {
        pipIdsByHost[hostKey] = ids
        _activePipIds.value = pipIdsByHost.values.flatten().distinct()
    }

    fun setCategories(list: List<CategoryItem>) {
        synchronized(mapLock) {
            _categories.value = list
            _entitiesById.value = list.flatMap { it.entities }.associateBy { it.id }
        }
    }

    /** Seed initial watched entities directly from `subscribe_entities` without needing a full `get_states` dump. */
    fun upsertWatchedEntity(entityId: String, state: String, attributes: JSONObject, isSaved: Boolean = false) {
        synchronized(mapLock) {
            val curMap = _entitiesById.value
            val existing = curMap[entityId]
            val baseItem = if (existing != null) {
                existing.copy(state = state, attributes = attributes)
            } else {
                val friendlyName = attributes.optString("friendly_name", entityId)
                ensureValidEntity(
                    EntityItem(
                        id = entityId,
                        friendlyName = friendlyName,
                        state = state,
                        attributes = attributes,
                        isSaved = isSaved,
                        isAvailable = state != "unavailable"
                    ),
                    entityId
                )
            }
            val next = HashMap<String, EntityItem>(curMap.size + 1).apply {
                putAll(curMap)
                put(entityId, baseItem)
            }
            _entitiesById.value = next
        }
    }

    fun updateEntity(entityId: String, newItem: EntityItem) {
        synchronized(mapLock) {
            val curMap = _entitiesById.value
            val oldItem = curMap[entityId]

            val mergedItem = if (oldItem != null && oldItem.isSaved) {
                val validatedItem = ensureValidEntity(newItem, entityId)
                validatedItem.copy(isSaved = true)
            } else {
                ensureValidEntity(newItem, entityId)
            }

            val next = HashMap<String, EntityItem>(curMap.size + 1).apply {
                putAll(curMap)
                put(entityId, mergedItem)
            }
            _entitiesById.value = next
        }
    }

    private fun ensureValidEntity(entity: EntityItem, entityId: String): EntityItem {
        val safeLastKnownState = entity.lastKnownState ?: mutableMapOf()
        val safeOnIcon = if (entity.customIconOnName.isNullOrEmpty()) {
            EntityIconMapper.getDefaultOnIconForEntityName(entityId)
        } else {
            entity.customIconOnName
        }

        return entity.copy(
            customIconOnName = safeOnIcon,
            lastKnownState = safeLastKnownState,
            isSaved = entity.isSaved
        )
    }
}
