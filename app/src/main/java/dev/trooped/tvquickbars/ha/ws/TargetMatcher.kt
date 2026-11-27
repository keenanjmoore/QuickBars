package dev.trooped.tvquickbars.ha.ws

import android.content.Context
import android.os.Build
import android.provider.Settings
import dev.trooped.tvquickbars.data.AppIdProvider
import dev.trooped.tvquickbars.persistence.AppPrefs
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Normalizes target matching across QuickBars events (`quickbars.open`, `quickbars.close`,
 * `quickbars.notify`, `quickbars.config`, `quickbars.ping`, `quickbars.diagnostics`, `quickbars.focus`).
 *
 * Matches when:
 * - Neither `target_id`/`target`/`id`/`id_target` nor `target_ids`/`targets` is present (broadcast)
 * - Or any specified target equals `*` / `all` / `broadcast`
 * - Or any specified target matches the TV's Crockford Base32 App ID (case-insensitive)
 * - Or matches configured `pref_ha_device_alias`, Android device name slug, or Build.MODEL slug
 *
 * Fails CLOSED (`false`) when any explicit target key is present in `payload` yet yields 0 non-blank targets
 * (e.g. `"target_ids": []` when 0 TVs are awake, or `"target_id": ""`).
 */
object TargetMatcher {

    private val SLUG_REGEX = Regex("[^a-z0-9]+")
    private val SINGLE_TARGET_KEYS = listOf("id", "target_id", "target", "id_target")
    private val MULTI_TARGET_KEYS = listOf("target_ids", "targets")
    private val ALL_TARGET_KEYS = SINGLE_TARGET_KEYS + MULTI_TARGET_KEYS

    @Volatile private var cachedDeviceName: String? = null
    @Volatile private var cachedAliases: Set<String>? = null
    @Volatile private var cacheTimestampMs: Long = 0L
    private const val CACHE_TTL_MS = 5 * 60 * 1000L

    fun invalidateCache() {
        cachedDeviceName = null
        cachedAliases = null
        cacheTimestampMs = 0L
    }

    fun matches(ctx: Context, payload: JSONObject): Boolean {
        val myAppId = AppIdProvider.get(ctx).trim().uppercase(Locale.US)
        val myAppIdNorm = myAppId.replace("-", "").replace("_", "").replace(" ", "")
        val localAliases = getLocalAliases(ctx, myAppId, myAppIdNorm)
        return matchesWithIdentity(payload, myAppIdNorm, localAliases)
    }

    /** Pure JVM-testable matcher core without Android framework calls. */
    internal fun matchesWithIdentity(
        payload: JSONObject,
        myAppIdNorm: String,
        localAliases: Set<String>
    ): Boolean {
        val hasExplicitTargetKey = ALL_TARGET_KEYS.any { payload.has(it) && !payload.isNull(it) }
        val targets = extractTargets(payload)

        if (targets.isEmpty()) {
            // Broadcast only when no target field was provided at all; fail closed on explicit empty lists/blanks
            return !hasExplicitTargetKey
        }

        for (raw in targets) {
            val t = raw.trim()
            if (t.isEmpty()) continue
            val low = t.lowercase(Locale.US)
            if (low == "*" || low == "all" || low == "broadcast") return true

            val upperNorm = AppIdProvider.normalizeUserId(t)
            if (upperNorm == myAppIdNorm) return true

            val slug = slugify(t)
            if (slug.isNotEmpty() && slug in localAliases) return true
        }
        return false
    }

    fun getDeviceName(ctx: Context): String {
        val now = System.currentTimeMillis()
        cachedDeviceName?.takeIf { now - cacheTimestampMs < CACHE_TTL_MS }?.let { return it }

        val customAlias = AppPrefs.getHaDeviceAlias(ctx)
        val resolved = if (!customAlias.isNullOrBlank()) {
            customAlias
        } else {
            val sysName = runCatching {
                Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
            }.getOrNull()
            sysName?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Android TV"
        }
        cachedDeviceName = resolved
        if (cacheTimestampMs == 0L) cacheTimestampMs = now
        return resolved
    }

    fun getDeviceSlug(ctx: Context): String = slugify(getDeviceName(ctx))

    private fun getLocalAliases(ctx: Context, myAppId: String, myAppIdNorm: String): Set<String> {
        val now = System.currentTimeMillis()
        cachedAliases?.takeIf { now - cacheTimestampMs < CACHE_TTL_MS }?.let { return it }

        val out = LinkedHashSet<String>()
        out += myAppId.lowercase(Locale.US)
        out += myAppIdNorm.lowercase(Locale.US)
        AppPrefs.getHaDeviceAlias(ctx)?.let {
            val s = slugify(it)
            if (s.isNotEmpty()) out += s
        }
        val sysName = runCatching {
            Settings.Global.getString(ctx.contentResolver, Settings.Global.DEVICE_NAME)
        }.getOrNull()
        if (!sysName.isNullOrBlank()) {
            out += slugify(sysName)
        }
        if (!Build.MODEL.isNullOrBlank()) {
            out += slugify(Build.MODEL)
        }
        cachedAliases = out
        cacheTimestampMs = now
        return out
    }

    private fun extractTargets(payload: JSONObject): List<String> {
        val list = ArrayList<String>()
        for (key in SINGLE_TARGET_KEYS) {
            if (payload.has(key) && !payload.isNull(key)) {
                val obj = payload.opt(key)
                when (obj) {
                    is JSONArray -> collectArray(obj, list)
                    is String -> if (obj.isNotBlank()) list += obj
                }
            }
        }
        for (key in MULTI_TARGET_KEYS) {
            val arr = payload.optJSONArray(key)
            if (arr != null) collectArray(arr, list)
        }
        return list
    }

    private fun collectArray(arr: JSONArray, out: MutableList<String>) {
        for (i in 0 until arr.length()) {
            val s = arr.optString(i, "")
            if (s.isNotBlank()) out += s
        }
    }

    fun slugify(input: String): String =
        input.trim()
            .lowercase(Locale.US)
            .replace(SLUG_REGEX, "_")
            .trim('_')
}
