package dev.trooped.tvquickbars.persistence

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.reflect.TypeToken
import dev.trooped.tvquickbars.data.QuickBar

/**
 * QuickBarManager Class
 * This class handles saving and loading of QuickBars with process-wide Gson & in-memory caching.
 */
class QuickBarManager(context: Context) {

    private val prefs = context.getSharedPreferences("HA_QuickBars", Context.MODE_PRIVATE)
    private val quickBarsKey = "quickbar_list"
    private val legacyMigratedKey = "legacy_letter_keys_migrated"

    companion object {
        private val sharedGson: Gson by lazy {
            GsonBuilder()
                .setStrictness(Strictness.LENIENT)
                .create()
        }

        private val quickBarListType = object : TypeToken<MutableList<QuickBar>>() {}.type
        private val cacheLock = Any()

        @Volatile
        private var cachedQuickBars: List<QuickBar>? = null

        fun invalidateCache() {
            synchronized(cacheLock) {
                cachedQuickBars = null
            }
        }
    }

    /**
     * Save a list of QuickBars to SharedPreferences asynchronously and refresh cache.
     */
    fun saveQuickBars(bars: List<QuickBar>) {
        val snapshot = bars.map { it.copy(savedEntityIds = it.savedEntityIds.toMutableList()) }
        val jsonString = sharedGson.toJson(snapshot)
        synchronized(cacheLock) {
            cachedQuickBars = snapshot
        }
        prefs.edit {
            putString(quickBarsKey, jsonString)
            putBoolean(legacyMigratedKey, true)
        }
    }

    /**
     * Load a list of QuickBars from SharedPreferences (served from cache after first read).
     */
    fun loadQuickBars(): MutableList<QuickBar> {
        synchronized(cacheLock) {
            cachedQuickBars?.let { cached ->
                return cached.map { it.copy(savedEntityIds = it.savedEntityIds.toMutableList()) }.toMutableList()
            }
        }

        val json = prefs.getString(quickBarsKey, null) ?: return mutableListOf()

        if (!prefs.getBoolean(legacyMigratedKey, false)) {
            val migrated = migrateLegacyLetterKeys(json)
            if (migrated != null) {
                prefs.edit {
                    putString(quickBarsKey, migrated)
                    putBoolean(legacyMigratedKey, true)
                }
                val parsed: MutableList<QuickBar> = try {
                    sharedGson.fromJson(migrated, quickBarListType) ?: mutableListOf()
                } catch (_: Exception) {
                    mutableListOf()
                }
                synchronized(cacheLock) {
                    cachedQuickBars = parsed.map { it.copy(savedEntityIds = it.savedEntityIds.toMutableList()) }
                }
                return parsed
            } else {
                prefs.edit { putBoolean(legacyMigratedKey, true) }
            }
        }

        return try {
            val parsed: MutableList<QuickBar> = sharedGson.fromJson(json, quickBarListType) ?: mutableListOf()
            synchronized(cacheLock) {
                cachedQuickBars = parsed.map { it.copy(savedEntityIds = it.savedEntityIds.toMutableList()) }
            }
            parsed
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    /**
     * Safely migrates legacy QuickBar JSON arrays that used single-letter keys ("a" instead of "id").
     */
    private fun migrateLegacyLetterKeys(json: String): String? {
        val root = try { JsonParser.parseString(json) } catch (_: Exception) { return null }
        if (!root.isJsonArray) return null
        val arr = root.asJsonArray
        if (arr.size() == 0) return null
        val firstEl = arr[0]
        if (!firstEl.isJsonObject) return null
        val firstObj = firstEl.asJsonObject
        val isLegacy = firstObj.has("a") && !firstObj.has("id")
        if (!isLegacy) return null

        fun mapOne(src: JsonObject): JsonObject {
            val dst = JsonObject()
            if (src.has("a")) dst.add("id", src.get("a"))
            if (src.has("b")) dst.add("name", src.get("b"))
            if (src.has("c")) dst.add("backgroundColor", src.get("c"))
            if (src.has("d")) dst.add("backgroundOpacity", src.get("d"))
            if (src.has("e")) dst.add("onStateColor", src.get("e"))
            if (src.has("f")) dst.add("isEnabled", src.get("f"))
            if (src.has("g")) dst.add("showNameInOverlay", src.get("g"))
            if (src.has("h")) dst.add("position", src.get("h"))
            if (src.has("i")) dst.add("useGridLayout", src.get("i"))
            if (src.has("j")) dst.add("savedEntityIds", src.get("j"))
            if (src.has("k")) dst.add("animationStyle", src.get("k"))
            if (src.has("l")) dst.add("animationDuration", src.get("l"))
            if (src.has("m")) dst.add("displayMode", src.get("m"))
            if (src.has("n")) dst.add("closeAfterOneAction", src.get("n"))
            if (src.has("o")) dst.add("autoCloseDelay", src.get("o"))
            if (src.has("p")) dst.add("entityTextSize", src.get("p"))
            if (src.has("q")) dst.add("entityIconSize", src.get("q"))
            return dst
        }

        val migratedArr = JsonArray()
        for (el in arr) {
            if (el.isJsonObject) {
                migratedArr.add(mapOne(el.asJsonObject))
            }
        }
        return migratedArr.toString()
    }

    fun removeEntityFromAllQuickBars(entityId: String): Int {
        val allQuickBars = loadQuickBars().toMutableList()
        var updatedCount = 0

        for (quickBar in allQuickBars) {
            if (quickBar.savedEntityIds.contains(entityId)) {
                quickBar.savedEntityIds.remove(entityId)
                updatedCount++
            }
        }

        if (updatedCount > 0) {
            saveQuickBars(allQuickBars)
        }

        return updatedCount
    }

    fun getValidEntityCount(quickBar: QuickBar, context: Context): Int {
        val savedEntitiesManager = SavedEntitiesManager(context)
        val savedEntityIds = savedEntitiesManager.loadEntities().map { it.id }.toSet()
        return quickBar.savedEntityIds.count { savedEntityIds.contains(it) }
    }

    fun validateAllQuickBars(context: Context): Int {
        val allQuickBars = loadQuickBars().toMutableList()
        val savedEntitiesManager = SavedEntitiesManager(context)
        val savedEntityIds = savedEntitiesManager.loadEntities().map { it.id }.toSet()
        var updatedCount = 0

        for (quickBar in allQuickBars) {
            val originalSize = quickBar.savedEntityIds.size
            val validEntities = quickBar.savedEntityIds.filter { savedEntityIds.contains(it) }

            if (validEntities.size < originalSize) {
                quickBar.savedEntityIds.clear()
                quickBar.savedEntityIds.addAll(validEntities)
                updatedCount++
            }
        }

        if (updatedCount > 0) {
            saveQuickBars(allQuickBars)
        }

        return updatedCount
    }

    fun saveCapturedKey(keyCode: Int, keyName: String) {
        prefs.edit {
            putInt("captured_keycode", keyCode)
                .putString("captured_keyname", keyName)
        }
    }
}
